// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package dev.hoshino.bridge.phone.shell

import dev.hoshino.bridge.phone.adb.AdbConnectionException
import dev.hoshino.bridge.phone.adb.AdbPairingException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Platform side of the engine. Blocking calls run on the IO dispatcher. */
interface BuiltinShellPlatform {
    fun supported(): Boolean
    fun paired(): Boolean
    fun setPaired(paired: Boolean)
    /** Wireless debugging switch; null when unreadable (does not block). */
    fun wirelessDebugging(): Boolean?
    fun wifiConnected(): Boolean
    /** The user ticked "自动打开无线调试" in the setup wizard (default off, mika-gap §2 PL3). */
    fun autoWirelessDebuggingAllowed(): Boolean
    /** Turns wireless debugging on with WRITE_SECURE_SETTINGS (granted only with consent). False when not allowed. */
    fun turnOnWirelessDebugging(): Boolean
    suspend fun findPairingPort(timeoutMs: Long): Int?
    fun pair(port: Int, code: String)
    suspend fun launchHelper()
    fun stopHelper()
    fun forget()
}

data class Backoff(val baseMs: Long = 2_000, val maxMs: Long = 60_000, val maxAttempts: Int = 5) {
    fun delayAfter(failures: Int): Long? {
        if (failures >= maxAttempts) return null
        return (baseMs shl (failures - 1).coerceIn(0, 20)).coerceAtMost(maxMs)
    }
}

object BuiltinShellFailures {
    fun ofPairing(reason: AdbPairingException.Reason) = when (reason) {
        AdbPairingException.Reason.WRONG_CODE -> BuiltinShellFailure.WrongCode
        AdbPairingException.Reason.CONNECT_FAILED -> BuiltinShellFailure.PairingWindowClosed
        AdbPairingException.Reason.TLS_FAILED, AdbPairingException.Reason.PROTOCOL -> BuiltinShellFailure.Unknown
    }
    fun ofConnection(reason: AdbConnectionException.Reason) = when (reason) {
        AdbConnectionException.Reason.UNAUTHORIZED -> BuiltinShellFailure.AuthorizationRevoked
        else -> BuiltinShellFailure.ConnectFailed
    }
    fun retryable(f: BuiltinShellFailure) = f == BuiltinShellFailure.ConnectFailed || f == BuiltinShellFailure.HelperStartFailed || f == BuiltinShellFailure.Unknown
}

/**
 * Helper state and watchdog. [helper] non-null means we hold the helper binder (Running). Otherwise,
 * once paired: wait [graceMs] (a still-alive helper hands itself over again when the app restarts),
 * then start one through adbd, retrying with [backoff]. When wireless debugging is off on Wi-Fi it is
 * turned back on only if the user consented in the wizard ([BuiltinShellPlatform.autoWirelessDebuggingAllowed],
 * default off) and the app holds WRITE_SECURE_SETTINGS: once per network, stopping if the system flips it back.
 */
class BuiltinShellEngine(
    private val platform: BuiltinShellPlatform,
    private val helper: StateFlow<Any?>,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val backoff: Backoff = Backoff(),
    private val graceMs: Long = 3_000,
    private val handoffTimeoutMs: Long = 15_000,
    private val discoveryTimeoutMs: Long = 10 * 60_000L,
    private val pairingPortTimeoutMs: Long = 5_000,
    private val autoOnSettleMs: Long = 3_000,
) : BuiltinShellControl {
    private val _status = MutableStateFlow(initialStatus())
    override val status: StateFlow<BuiltinShellStatus> = _status.asStateFlow()
    private val _discovery = MutableStateFlow<PairingDiscovery>(PairingDiscovery.Idle)
    override val pairingDiscovery: StateFlow<PairingDiscovery> = _discovery.asStateFlow()
    /** Last failure class (diagnostics only; no codes, keys or command output). */
    @Volatile var lastError: String? = null
        private set
    private val launchLock = Mutex()
    private var failures = 0
    private var retryJob: Job? = null
    private var discoveryJob: Job? = null
    private var started = false
    /** Auto-on already tried on this network; reset when Wi-Fi changes or the user retries. */
    @Volatile private var autoOnTried = false
    @Volatile private var autoOnRefused = false

    override val paired: Boolean get() = platform.paired()

    private fun initialStatus(): BuiltinShellStatus = when {
        !platform.supported() -> BuiltinShellStatus.Unsupported
        helper.value != null -> BuiltinShellStatus.Running
        !platform.paired() -> BuiltinShellStatus.NotPaired
        else -> BuiltinShellStatus.Connecting
    }

    @Synchronized
    fun start() {
        if (started || !platform.supported()) return
        started = true
        var first = true
        scope.launch {
            helper.collect { h ->
                if (h != null) {
                    synchronized(this@BuiltinShellEngine) { failures = 0; retryJob?.cancel() }
                    _status.value = BuiltinShellStatus.Running
                } else if (!platform.paired()) {
                    _status.value = BuiltinShellStatus.NotPaired
                } else {
                    if (_status.value == BuiltinShellStatus.Running) _status.value = BuiltinShellStatus.Connecting
                    schedule(if (first) graceMs else backoff.baseMs)
                }
                first = false
            }
        }
    }

    /** Someone needs the helper and it is missing: start one now unless already starting. */
    fun need() {
        if (helper.value != null || !platform.paired()) return
        synchronized(this) { if (retryJob?.isActive == true) return }
        if (launchLock.isLocked) return
        schedule(0)
    }

    fun wirelessDebuggingChanged() {
        if (helper.value != null || !platform.paired()) return
        if (platform.wirelessDebugging() == false) {
            if (_status.value != BuiltinShellStatus.Connecting) _status.value = offStatus()
            return
        }
        synchronized(this) { failures = 0 }
        schedule(0)
    }

    fun wifiAvailable() {
        autoOnTried = false
        autoOnRefused = false
        if (_status.value == BuiltinShellStatus.Running || !platform.paired()) return
        synchronized(this) { failures = 0 }
        schedule(0)
    }

    private fun offStatus() = if (autoOnRefused) BuiltinShellStatus.Failed(BuiltinShellFailure.WifiNotAllowed) else BuiltinShellStatus.WirelessDebuggingOff

    @Synchronized
    private fun schedule(delayMs: Long) {
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(delayMs)
            launchOnce()
        }
    }

    /** Wireless debugging is off: turn it on once per network when allowed; true when it stays on. */
    private suspend fun turnOn(): Boolean {
        if (!platform.autoWirelessDebuggingAllowed() || autoOnTried || !platform.wifiConnected()) return false
        autoOnTried = true
        _status.value = BuiltinShellStatus.Connecting
        val written = withContext(io) { platform.turnOnWirelessDebugging() }
        if (!written) return false
        delay(autoOnSettleMs)
        if (platform.wirelessDebugging() == false) {
            // The system flipped it back: this Wi-Fi was never allowed. Do not keep popping the dialog.
            autoOnRefused = true
            return false
        }
        return true
    }

    private suspend fun launchOnce(): Unit = launchLock.withLock {
        if (helper.value != null) return@withLock
        if (!platform.paired()) {
            _status.value = BuiltinShellStatus.NotPaired
            return@withLock
        }
        if (platform.wirelessDebugging() == false && !turnOn()) {
            _status.value = if (!platform.wifiConnected()) BuiltinShellStatus.Failed(BuiltinShellFailure.NoWifi) else offStatus()
            return@withLock
        }
        _status.value = BuiltinShellStatus.Connecting
        val failure = attempt()
        if (failure == null) {
            lastError = null
            _status.value = BuiltinShellStatus.Running
            return@withLock
        }
        _status.value = BuiltinShellStatus.Failed(failure)
        val next = synchronized(this) { ++failures }
        if (BuiltinShellFailures.retryable(failure)) backoff.delayAfter(next)?.let { d ->
            synchronized(this) { retryJob = scope.launch { delay(d); launchOnce() } }
        }
    }

    private suspend fun attempt(): BuiltinShellFailure? = try {
        withContext(io) { platform.launchHelper() }
        if (withTimeoutOrNull(handoffTimeoutMs) { helper.first { it != null } } != null) null
        else BuiltinShellFailure.HelperStartFailed.also { lastError = "helper handoff timed out" }
    } catch (e: CancellationException) {
        throw e
    } catch (e: AdbConnectionException) {
        lastError = "connect:${e.reason}"
        val f = BuiltinShellFailures.ofConnection(e.reason)
        if (f == BuiltinShellFailure.ConnectFailed && !platform.wifiConnected()) BuiltinShellFailure.NoWifi else f
    } catch (e: Exception) {
        lastError = e.javaClass.simpleName
        BuiltinShellFailure.Unknown
    }

    override fun startDiscovery() {
        synchronized(this) {
            if (discoveryJob?.isActive == true) return
            _discovery.value = PairingDiscovery.Searching
            discoveryJob = scope.launch {
                val port = withContext(io) { platform.findPairingPort(discoveryTimeoutMs) }
                _discovery.value = port?.let { PairingDiscovery.Found(it) } ?: PairingDiscovery.Idle
            }
        }
    }

    override fun stopDiscovery() {
        synchronized(this) { discoveryJob?.cancel(); discoveryJob = null }
        _discovery.value = PairingDiscovery.Idle
    }

    override suspend fun startPairing(code: String, port: Int?) {
        _status.value = BuiltinShellStatus.Connecting
        val target = port ?: (_discovery.value as? PairingDiscovery.Found)?.port ?: withContext(io) { platform.findPairingPort(pairingPortTimeoutMs) }
        if (target == null) {
            lastError = "pairing port not found"
            _status.value = BuiltinShellStatus.Failed(BuiltinShellFailure.PairingWindowClosed)
            return
        }
        val failure = try {
            withContext(io) { platform.pair(target, code) }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: AdbPairingException) {
            lastError = "pair:${e.reason}"
            BuiltinShellFailures.ofPairing(e.reason)
        } catch (e: Exception) {
            lastError = e.javaClass.simpleName
            BuiltinShellFailure.Unknown
        }
        if (failure != null) {
            _status.value = BuiltinShellStatus.Failed(failure)
            return
        }
        platform.setPaired(true)
        stopDiscovery()
        retry()
    }

    override suspend fun retry() {
        synchronized(this) { failures = 0; retryJob?.cancel() }
        autoOnTried = false
        if (helper.value != null) {
            _status.value = BuiltinShellStatus.Running
            return
        }
        launchOnce()
    }

    override suspend fun unpair() {
        synchronized(this) { retryJob?.cancel() }
        launchLock.withLock {
            withContext(io) {
                runCatching { platform.stopHelper() }
                platform.forget()
            }
            platform.setPaired(false)
            _status.value = BuiltinShellStatus.NotPaired
        }
    }
}
