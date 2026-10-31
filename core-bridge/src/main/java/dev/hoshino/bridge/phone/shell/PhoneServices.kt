package dev.hoshino.bridge.phone.shell

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import dev.hoshino.bridge.phone.IHoshinoPhoneShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/** Which privileged channel currently serves phone control. */
enum class PhoneChannel { None, Shizuku, Builtin }

/**
 * Process-wide phone services. Routing rule (the user's choice in the task): when Shizuku is running and
 * authorised we use a Shizuku UserService hosting the same [PhoneShellService]; otherwise the built-in
 * helper (paired once through wireless debugging). Both expose the same [IHoshinoPhoneShell].
 */
class PhoneServices private constructor(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val hub = HelperHub(app)
    /** Set by the app before [start]: what the user allowed in the setup wizard. Allows nothing by default. */
    @Volatile var consent: SetupConsent = SetupConsent.NONE
    val builtin = BuiltinShellManager(app, hub, { accessibilityComponent(app) }, { consent })
    val shizuku = ShizukuPhoneChannel(app)

    /** Shizuku first, then the built-in helper. */
    val shell: StateFlow<IHoshinoPhoneShell?> = combine(shizuku.shell, hub.shell) { s, b -> s ?: b }
        .distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, null)
    val channel: StateFlow<PhoneChannel> = combine(shizuku.shell, hub.shell) { s, b ->
        when { s != null -> PhoneChannel.Shizuku; b != null -> PhoneChannel.Builtin; else -> PhoneChannel.None }
    }.stateIn(scope, SharingStarted.Eagerly, PhoneChannel.None)

    private val _setupIssues = MutableStateFlow<List<String>>(emptyList())
    /** Setup commands that failed after the last helper start (shown in the setup page). */
    val setupIssues: StateFlow<List<String>> = _setupIssues.asStateFlow()
    @Volatile private var started = false

    @Synchronized
    fun start() {
        if (started) return
        started = true
        builtin.start()
        shizuku.refresh()
        // Each time the built-in helper comes up: run the setup commands the user consented to (no dialogs, no loops).
        scope.launch {
            hub.shell.collect { s -> if (s != null) applyConsent() }
        }
    }

    /** Runs the consented setup commands now (after the wizard's consent page, or when the helper starts). */
    suspend fun applyConsent(): List<String> {
        if (hub.shell.value == null) return emptyList()
        return runCatching { builtin.applySetupPermissions() }.getOrElse { listOf("setup") }.also { _setupIssues.value = it }
    }

    /** Somebody needs phone control now: start or bind the best available channel. */
    suspend fun ensure(): IHoshinoPhoneShell? {
        shell.value?.let { return it }
        if (shizuku.available()) shizuku.bind()?.let { return it }
        builtin.need()
        return null
    }

    fun supported() = Build.VERSION.SDK_INT >= BuiltinShellManager.MIN_SDK

    companion object {
        const val ACCESSIBILITY_CLASS = "app.hoshino.harness.phone.PhoneAccessibilityService"
        fun accessibilityComponent(context: Context) = "${context.packageName}/$ACCESSIBILITY_CLASS"
        @Volatile private var instance: PhoneServices? = null
        fun of(context: Context): PhoneServices = instance ?: synchronized(this) { instance ?: PhoneServices(context).also { instance = it } }
    }
}

/** Shizuku-hosted [PhoneShellService] (non-daemon UserService: it stops with the app). */
class ShizukuPhoneChannel(private val context: Context) {
    private val _shell = MutableStateFlow<IHoshinoPhoneShell?>(null)
    val shell: StateFlow<IHoshinoPhoneShell?> = _shell.asStateFlow()
    @Volatile private var bound = false
    private val args by lazy {
        @Suppress("DEPRECATION")
        Shizuku.UserServiceArgs(ComponentName(context, PhoneShellService::class.java)).daemon(false).tag("hoshino-phone").processNameSuffix("phone")
            .version(context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt())
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val s = IHoshinoPhoneShell.Stub.asInterface(binder)
            runCatching { binder.linkToDeath({ _shell.compareAndSet(s, null); bound = false }, 0) }
            _shell.value = s
        }
        override fun onServiceDisconnected(name: ComponentName) { _shell.value = null; bound = false }
    }

    fun available(): Boolean = runCatching { Shizuku.pingBinder() && !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)

    fun refresh() { if (!available()) _shell.value = null }

    suspend fun bind(): IHoshinoPhoneShell? {
        if (!available()) return null
        if (!bound) withContext(Dispatchers.Main) { runCatching { Shizuku.bindUserService(args, connection); bound = true } }
        return kotlinx.coroutines.withTimeoutOrNull(10_000) {
            while (_shell.value == null) kotlinx.coroutines.delay(100)
            _shell.value
        }
    }

    fun unbind() {
        if (bound) runCatching { Shizuku.unbindUserService(args, connection, true) }
        bound = false
        _shell.value = null
    }
}
