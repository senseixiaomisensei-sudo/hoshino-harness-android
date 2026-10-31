// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package dev.hoshino.bridge.phone.shell

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import dev.hoshino.bridge.phone.adb.AdbConnectPort
import dev.hoshino.bridge.phone.adb.AdbConnection
import dev.hoshino.bridge.phone.adb.AdbPairing
import dev.hoshino.bridge.phone.adb.AdbServiceTypes
import dev.hoshino.bridge.phone.adb.AdbTls
import dev.hoshino.bridge.phone.adb.LocalPortScan
import dev.hoshino.bridge.phone.adb.NioPortScanner
import dev.hoshino.bridge.phone.adb.PortCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.Inet4Address
import java.util.concurrent.atomic.AtomicReference

/**
 * Real implementation of the built-in helper: pairing, starting the helper through the local adbd, watchdog
 * and the setup commands after pairing. Unlike Mika, none of the setup commands (enable our accessibility
 * service, grant WRITE_SECURE_SETTINGS, battery exemption) and no automatic "turn wireless debugging back
 * on" runs unless the user ticked that item in the setup wizard ([SetupConsent], mika-gap §2 PL3).
 */
@SuppressLint("NewApi")
class BuiltinShellManager(
    context: Context,
    private val hub: HelperHub,
    private val accessibilityService: () -> String,
    private val consent: () -> SetupConsent = { SetupConsent.NONE },
) {
    private val app = context.applicationContext
    private var started = false
    private val platform by lazy { AndroidPlatform(app, hub, consent) }

    val engine: BuiltinShellEngine by lazy { BuiltinShellEngine(platform, hub.shell, CoroutineScope(SupervisorJob() + Dispatchers.Default), Dispatchers.IO) }

    /** Runs a setup command through the helper when present, else through one adbd exec stream. Returns exit code + output. */
    suspend fun run(command: String): Pair<Int, String> = withContext(Dispatchers.IO) {
        val shell = hub.shell.value
        if (shell != null) {
            val b = shell.runCommand(command)
            (b?.getInt("exitCode") ?: -1) to b?.getString("output").orEmpty()
        } else {
            val out = platform.connect().use { it.exec(PermissionCommands.checked(command)).readText() }
            (PermissionCommands.exitCode(out) ?: -1) to out.substringBeforeLast(PermissionCommands.EXIT_MARK)
        }
    }

    fun need() = engine.need()

    suspend fun connectWithPort(port: Int) {
        platform.manualPort.set(port)
        engine.retry()
    }

    @Synchronized
    fun start() {
        if (started || Build.VERSION.SDK_INT < MIN_SDK) return
        started = true
        val e = engine
        app.contentResolver.registerContentObserver(Settings.Global.getUriFor(ADB_WIFI_ENABLED), false, object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = e.wirelessDebuggingChanged()
        })
        runCatching {
            app.getSystemService(ConnectivityManager::class.java).registerNetworkCallback(
                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                object : ConnectivityManager.NetworkCallback() { override fun onAvailable(network: Network) = e.wifiAvailable() },
            )
        }
        e.start()
    }

    /**
     * After the helper is up: run only the setup commands the user consented to. Accessibility: allow the
     * restricted-settings app-op and add our service to enabled_accessibility_services (other services stay).
     * Each command is independent; failures are reported, never retried in a loop. Returns failed items.
     */
    suspend fun applySetupPermissions(): List<String> {
        val c = consent()
        val shell = hub.shell.value ?: return listOf("helper not running")
        val pkg = app.packageName
        val failed = mutableListOf<String>()
        fun exec(cmd: String): Pair<Int, String> {
            val b = shell.runCommand(cmd)
            return (b?.getInt("exitCode") ?: -1) to (b?.getString("output").orEmpty())
        }
        withContext(Dispatchers.IO) {
            val commands = buildList {
                if (c.accessibility()) add(PermissionCommands.allowRestrictedSettings(pkg))
                if (c.writeSecureSettings() && app.checkSelfPermission(PermissionCommands.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) add(PermissionCommands.grantWriteSecureSettings(pkg))
                if (c.batteryExemption()) add(PermissionCommands.whitelist(pkg))
            }
            for (cmd in commands) {
                if (exec(cmd).first != 0) failed += cmd.substringBefore(' ') else c.audit("setup " + cmd.substringBefore(' ') + " " + cmd.split(' ').getOrNull(1).orEmpty())
            }
            if (c.accessibility()) {
                val current = exec(PermissionCommands.READ_ACCESSIBILITY).second.trim()
                PermissionCommands.withService(current, accessibilityService())?.let { list ->
                    var ok = true
                    for (cmd in PermissionCommands.writeAccessibility(list)) if (exec(cmd).first != 0) { failed += "accessibility"; ok = false }
                    if (ok) c.audit("setup accessibility enabled")
                }
            }
        }
        return failed
    }

    private class AndroidPlatform(private val app: Context, private val hub: HelperHub, private val consent: () -> SetupConsent) : BuiltinShellPlatform {
        val manualPort = AtomicReference<Int?>(null)
        private val scan = LocalPortScan(NioPortScanner(), ::localHosts, { LocalPortScan.parseRange(runCatching { File(PORT_RANGE_FILE).readText() }.getOrNull()) })
        private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        private val finder = NsdAdbServiceFinder(app)
        private val portCache = object : PortCache {
            override fun load() = prefs.getInt("port", 0).takeIf { it > 0 }
            override fun save(port: Int) { prefs.edit().putInt("port", port).apply() }
        }

        override fun supported() = Build.VERSION.SDK_INT >= MIN_SDK
        override fun paired() = supported() && prefs.getBoolean("paired", false)
        override fun setPaired(paired: Boolean) { prefs.edit().putBoolean("paired", paired).apply() }
        override fun wirelessDebugging(): Boolean? = Settings.Global.getString(app.contentResolver, ADB_WIFI_ENABLED)?.let { it == "1" }
        override fun wifiConnected(): Boolean = runCatching {
            val cm = app.getSystemService(ConnectivityManager::class.java)
            @Suppress("DEPRECATION")
            cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        }.getOrDefault(true)

        override fun autoWirelessDebuggingAllowed() = consent().autoWirelessDebugging()

        override fun turnOnWirelessDebugging(): Boolean {
            val c = consent()
            // PL3: only when the user ticked "自动打开无线调试" (default off); every write is audited and announced once.
            if (!c.autoWirelessDebugging()) return false
            if (app.checkSelfPermission(PermissionCommands.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return false
            val ok = runCatching { Settings.Global.putInt(app.contentResolver, ADB_WIFI_ENABLED, 1) }.getOrDefault(false)
            if (ok) { c.audit("auto-on wireless debugging"); c.wirelessDebuggingTurnedOn() }
            return ok
        }

        override suspend fun findPairingPort(timeoutMs: Long): Int? = finder.findPorts(AdbServiceTypes.PAIRING, timeoutMs).firstOrNull()

        override fun pair(port: Int, code: String) {
            AdbPairing(AndroidAdb.tls(AndroidAdb.identity(app))).pair(LOCALHOST, port, code)
        }

        override suspend fun launchHelper() {
            val tls = AndroidAdb.tls(AndroidAdb.identity(app))
            val command = HelperHandoff.command(app.applicationInfo.sourceDir, app.packageName, Process.myUid(), ownCertPin())
            val before = hub.shell.value
            connect(tls).use {
                val stream = it.exec(command)
                stream.write(HelperHandoff.stdinLine(hub.issueLaunchNonce()))
                withTimeoutOrNull(HANDOFF_WAIT_MS) { hub.shell.first { s -> s != null && s !== before } }
            }
        }

        suspend fun connect(tls: AdbTls = AndroidAdb.tls(AndroidAdb.identity(app))): AdbConnection =
            AdbConnectPort(::tlsPort, finder, portCache, scan = scan, manual = { manualPort.getAndSet(null) }).connect { host, port -> AdbConnection.open(host, port, tls) }

        private fun ownCertPin(): String {
            val info = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            return HelperHandoff.certPin(info.signingInfo?.apkContentsSigners?.map { it.toByteArray() }) ?: error("unsigned package")
        }

        private fun localHosts(): List<String> = listOf(LOCALHOST) + runCatching {
            val cm = app.getSystemService(ConnectivityManager::class.java)
            @Suppress("DEPRECATION")
            cm.allNetworks.filter { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
                .flatMap { cm.getLinkProperties(it)?.linkAddresses.orEmpty() }.mapNotNull { (it.address as? Inet4Address)?.hostAddress }
        }.getOrDefault(emptyList())

        override fun stopHelper() { runCatching { hub.shell.value?.destroy() } }

        override fun forget() {
            hub.forget()
            AndroidAdb.forget(app)
            prefs.edit().remove("port").apply()
        }

        @SuppressLint("PrivateApi")
        private fun tlsPort(): Int? = runCatching {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, TLS_PORT_PROPERTY) as String?
        }.getOrNull()?.trim()?.toIntOrNull()
    }

    companion object {
        /** Virtual displays with own focus and wireless-debugging pairing are only reliable from Android 13. */
        const val MIN_SDK = Build.VERSION_CODES.TIRAMISU
        const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
        val wirelessDebuggingUri: Uri get() = Settings.Global.getUriFor(ADB_WIFI_ENABLED)
        private const val PREFS = "phone_builtin_shell"
        private const val TLS_PORT_PROPERTY = "service.adb.tls.port"
        private const val LOCALHOST = AdbConnectPort.LOCALHOST
        private const val PORT_RANGE_FILE = "/proc/sys/net/ipv4/ip_local_port_range"
        private const val HANDOFF_WAIT_MS = 15_000L
    }
}
