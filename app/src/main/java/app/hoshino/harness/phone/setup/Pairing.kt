// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package app.hoshino.harness.phone.setup

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.Display
import app.hoshino.harness.phone.AccessibilityReader
import app.hoshino.harness.phone.PhoneAccessibilityService
import dev.hoshino.bridge.phone.shell.BuiltinShellControl
import dev.hoshino.bridge.phone.shell.BuiltinShellFailure
import dev.hoshino.bridge.phone.shell.BuiltinShellStatus
import dev.hoshino.bridge.phone.shell.PairingCode
import dev.hoshino.bridge.phone.shell.PairingDiscovery
import dev.hoshino.bridge.phone.shell.PairingScreen
import dev.hoshino.bridge.phone.shell.PhoneServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** What the pairing notification shows now. */
sealed interface PairingNotice {
    data class EnterCode(val port: Int?, val error: String? = null) : PairingNotice
    data object Working : PairingNotice
    data object Done : PairingNotice
    data class Problem(val title: String) : PairingNotice

    companion object {
        /** Only while the user is pairing. The input box appears once the pairing window was found. */
        fun of(status: BuiltinShellStatus, discovery: PairingDiscovery): PairingNotice? {
            val port = (discovery as? PairingDiscovery.Found)?.port
            return when (status) {
                BuiltinShellStatus.Running -> Done
                BuiltinShellStatus.Connecting -> Working
                is BuiltinShellStatus.Failed -> if (status.reason.duringPairing) EnterCode(port, PairingCopy.retry(status.reason)) else Problem(PairingCopy.failure(status.reason))
                BuiltinShellStatus.NotPaired, BuiltinShellStatus.WirelessDebuggingOff -> port?.let { EnterCode(it) }
                BuiltinShellStatus.Unsupported -> Problem("这台手机的系统版本不支持（需要 Android 13+）")
            }
        }

        /** With accessibility on, the code is usually read automatically: show the input box a bit later. */
        const val AUTO_READ_GRACE_MS = 5_000L
        val REALERT_MS = listOf(8_000L, 20_000L, 40_000L)
        fun delayBefore(n: PairingNotice, autoRead: Boolean) = if (n is EnterCode && autoRead) AUTO_READ_GRACE_MS else 0L
        fun realertAfter(n: PairingNotice, sent: Int): Long? = if (n is EnterCode) REALERT_MS.getOrNull(sent) else null
    }
}

/** User-facing copy (Simplified Chinese), incl. the HyperOS/MIUI pitfalls the wizard links to. */
object PairingCopy {
    fun retry(f: BuiltinShellFailure) = when (f) {
        BuiltinShellFailure.WrongCode -> "配对码不对，看一下系统窗口上的 6 位数字再填一次"
        BuiltinShellFailure.PairingWindowClosed -> "配对窗口关了或过期了：在「无线调试」里重新点「使用配对码配对设备」"
        else -> "没配上，再试一次"
    }

    fun failure(f: BuiltinShellFailure) = when (f) {
        BuiltinShellFailure.NoWifi -> "没有连接 Wi-Fi（无线调试需要 Wi-Fi，可以是不能上网的热点）"
        BuiltinShellFailure.WifiNotAllowed -> "这个 Wi-Fi 不允许无线调试：请在系统弹框里点「允许」"
        BuiltinShellFailure.ConnectFailed -> "连不上手机自己的调试服务：确认「无线调试」开着"
        BuiltinShellFailure.AuthorizationRevoked -> "调试授权被撤销了（例如点过「撤销 USB 调试授权」），需要重新配对"
        BuiltinShellFailure.HelperStartFailed -> "助手没能启动：小米/HyperOS 请打开「USB 调试（安全设置）」后重试"
        else -> "没有成功，回 Hoshino 看看怎么处理"
    }
}

/**
 * One pairing run: find the pairing service, read the code from the system dialog with our accessibility
 * service when it is on ([PairingCodeReader]), otherwise post a notification with an input box, hand the
 * code to the helper and show the result. Lives with the process, not the screen.
 */
class PairingCoordinator(context: Context, private val control: BuiltinShellControl) {
    private val app = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watch: Job? = null
    @Volatile private var shown: PairingNotice? = null

    suspend fun pair(code: String, port: Int?) = coroutineScope {
        launch(start = CoroutineStart.UNDISPATCHED) { control.startPairing(code, port) }.join()
    }

    @Synchronized
    fun begin() {
        control.startDiscovery()
        if (watch?.isActive == true) return
        watch = scope.launch {
            val reader = launch { PairingCodeReader.run(app, control, this@PairingCoordinator) }
            withTimeoutOrNull(WINDOW_MS) {
                combine(control.status, control.pairingDiscovery) { s, d -> PairingNotice.of(s, d) to (s == BuiltinShellStatus.Running) }
                    .distinctUntilChanged()
                    .transformWhile { emit(it.first); !it.second }
                    .collectLatest { n -> n?.let { post(it) } }
            } ?: cancelNotice()
            reader.cancel()
            control.stopDiscovery()
        }
    }

    private suspend fun post(n: PairingNotice) {
        delay(PairingNotice.delayBefore(n, PhoneAccessibilityService.instance.value != null))
        show(n)
        var sent = 0
        while (true) {
            delay(PairingNotice.realertAfter(n, sent) ?: return)
            if (shown != n) return
            show(n)
            sent++
        }
    }

    private fun show(n: PairingNotice) { shown = n; PairingNotifier.show(app, n) }
    private fun cancelNotice() { shown = null; PairingNotifier.cancel(app) }

    @Synchronized
    fun end() {
        watch?.cancel(); watch = null
        control.stopDiscovery()
        cancelNotice()
    }

    /** Code typed in the notification or in the wizard. Not six digits: ask again without trying. */
    fun submit(raw: String, port: Int?): Boolean {
        val code = PairingCode.normalize(raw) ?: run { show(PairingNotice.EnterCode(port, "要填 6 位数字配对码")); return false }
        begin()
        show(PairingNotice.Working)
        scope.launch { pair(code, port) }
        return true
    }

    companion object {
        const val WINDOW_MS = 10 * 60_000L
        @Volatile private var instance: PairingCoordinator? = null
        fun of(context: Context): PairingCoordinator = instance ?: synchronized(this) {
            instance ?: PairingCoordinator(context, PhoneServices.of(context).builtin.engine).also { instance = it }
        }
    }
}

/** Notification input box target. */
class PairingCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(PairingNotifier.KEY_CODE)?.toString() ?: return
        val port = intent.getIntExtra(PairingNotifier.EXTRA_PORT, -1).takeIf { it > 0 }
        PairingCoordinator.of(context).submit(code, port)
    }
}

/**
 * Reads the pairing code from the system pairing dialog through our accessibility service while a pairing
 * run is open, and only from the Settings app's windows. The code is never logged.
 */
internal object PairingCodeReader {
    private const val SETTLE_MS = 300L

    fun settingsPackage(context: Context): String? = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.resolveActivity(Intent(Settings.ACTION_SETTINGS), 0)?.activityInfo?.packageName
    }.getOrNull() ?: "com.android.settings"

    suspend fun run(context: Context, control: BuiltinShellControl, coordinator: PairingCoordinator) {
        val settings = settingsPackage(context) ?: return
        val tried = mutableSetOf<String>()
        PhoneAccessibilityService.instance.collectLatest { service ->
            service ?: return@collectLatest
            service.packagesOn(Display.DEFAULT_DISPLAY).filter { it == settings }.map { }.onStart { emit(Unit) }.conflate().collect {
                delay(SETTLE_MS)
                val portFound = control.pairingDiscovery.value is PairingDiscovery.Found
                val attempt = AccessibilityReader.texts(service.windowsOn(Display.DEFAULT_DISPLAY), settings)
                    .firstNotNullOfOrNull { PairingScreen.attempt(PairingScreen.read(it), portFound, control.status.value, tried) } ?: return@collect
                tried += attempt.code
                coordinator.pair(attempt.code, attempt.port)
            }
        }
    }
}

/** High-priority pairing notification with a RemoteInput box, so the user never leaves the pairing dialog. */
object PairingNotifier {
    const val KEY_CODE = "pairing_code"
    const val EXTRA_PORT = "pairing_port"
    private const val CHANNEL = "hoshino-phone-pairing"
    private const val ID = 4501

    fun ensureChannel(context: Context): NotificationChannel {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.getNotificationChannel(CHANNEL)?.let { return it }
        return NotificationChannel(CHANNEL, "手机操作 · 配对码", NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true); setShowBadge(false) }.also(nm::createNotificationChannel)
    }

    /** The user lowered or turned off the channel (the wizard shows a hint and opens its settings). */
    fun channelLowered(context: Context) = ensureChannel(context).importance < NotificationManager.IMPORTANCE_HIGH

    fun show(context: Context, n: PairingNotice) {
        val nm = context.getSystemService(NotificationManager::class.java)
        ensureChannel(context)
        val open = PendingIntent.getActivity(context, 0, Intent(context, app.hoshino.harness.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = Notification.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).setCategory(Notification.CATEGORY_STATUS).setContentIntent(open)
        when (n) {
            is PairingNotice.EnterCode -> {
                val input = RemoteInput.Builder(KEY_CODE).setLabel("6 位配对码").setAllowFreeFormInput(true).build()
                val reply = PendingIntent.getBroadcast(context, 1, Intent(context, PairingCodeReceiver::class.java).apply { n.port?.let { putExtra(EXTRA_PORT, it) } }, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                val action = Notification.Action.Builder(null, "填配对码", reply).addRemoteInput(input).setAllowGeneratedReplies(false).setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) action.setAuthenticationRequired(false)
                val text = n.error ?: "在这里填系统窗口上的 6 位配对码，不用离开配对窗口"
                b.setContentTitle("输入无线调试配对码").setContentText(text).setStyle(Notification.BigTextStyle().bigText(text)).setOngoing(true)
                    .setOnlyAlertOnce(false).setTimeoutAfter(PairingCoordinator.WINDOW_MS).addAction(action.build())
                if (n.port != null) b.setSubText("已找到配对窗口")
            }
            PairingNotice.Working -> b.setContentTitle("正在配对…").setOngoing(true).setOnlyAlertOnce(true).setProgress(0, 0, true)
            PairingNotice.Done -> b.setContentTitle("配对成功").setContentText("回到 Hoshino 继续设置").setAutoCancel(true).setOnlyAlertOnce(true).setTimeoutAfter(8_000)
            is PairingNotice.Problem -> b.setContentTitle(n.title).setContentText("打开 Hoshino 看怎么处理").setAutoCancel(true)
        }
        runCatching { nm.notify(ID, b.build()) }
    }

    fun cancel(context: Context) = context.getSystemService(NotificationManager::class.java).cancel(ID)
}
