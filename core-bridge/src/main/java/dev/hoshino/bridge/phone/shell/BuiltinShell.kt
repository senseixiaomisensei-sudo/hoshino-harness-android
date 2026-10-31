// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package dev.hoshino.bridge.phone.shell

import kotlinx.coroutines.flow.StateFlow

/** State of the built-in phone helper (privileged process started through the phone's own wireless debugging). */
sealed interface BuiltinShellStatus {
    data object NotPaired : BuiltinShellStatus
    data object WirelessDebuggingOff : BuiltinShellStatus
    data object Connecting : BuiltinShellStatus
    data object Running : BuiltinShellStatus
    data class Failed(val reason: BuiltinShellFailure) : BuiltinShellStatus
    data object Unsupported : BuiltinShellStatus
}

/** Failure classes; UI copy lives in the app. Only cases that need a different user action are separated. */
enum class BuiltinShellFailure(val rePair: Boolean, val duringPairing: Boolean) {
    WrongCode(true, true),
    PairingWindowClosed(true, true),
    NoWifi(false, false),
    WifiNotAllowed(false, false),
    ConnectFailed(false, false),
    AuthorizationRevoked(true, false),
    HelperStartFailed(false, false),
    Unknown(false, false),
}

sealed interface PairingDiscovery {
    data object Idle : PairingDiscovery
    data object Searching : PairingDiscovery
    data class Found(val port: Int) : PairingDiscovery
}

interface BuiltinShellControl {
    val status: StateFlow<BuiltinShellStatus>
    val pairingDiscovery: StateFlow<PairingDiscovery>
    val paired: Boolean
    fun startDiscovery()
    fun stopDiscovery()
    suspend fun startPairing(code: String, port: Int? = null)
    suspend fun retry()
    suspend fun unpair()
}

/** Pairing code: six digits. Spaces, dashes and full-width digits are normalised; anything else is rejected. */
object PairingCode {
    const val LENGTH = 6
    private const val DASHES = "-－‐‑–—"
    fun normalize(input: String): String? {
        val digits = input.filterNot { it.isWhitespace() || it in DASHES }
            .map { c -> if (c in '０'..'９') '0' + (c - '０') else c }.joinToString("")
        return digits.takeIf { it.length == LENGTH && it.all { c -> c in '0'..'9' } }
    }
    fun port(input: String): Int? = input.trim().substringAfterLast(':').trim().toIntOrNull()?.takeIf { it in 1..65535 }
}

data class PairingScreenReading(val code: String?, val port: Int?)
data class PairingAttempt(val code: String, val port: Int?)

/**
 * Reads the pairing code and "IP:port" from all texts of one window of the system pairing dialog, by
 * shape only (no ids, language or layout). Two different codes or ports in one window count as unread.
 */
object PairingScreen {
    private val CODE = Regex("""\d{6}""")
    private val IPV4_PORT = Regex("""(?<![\w.:])(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3}):(\d{1,5})(?![\w.:])""")
    private val IPV6_PORT = Regex("""\[[0-9A-Fa-f]*:[0-9A-Fa-f:.]*(?:%[\w.]+)?]:(\d{1,5})(?![\w.:])""")

    fun read(texts: List<String>): PairingScreenReading {
        val codes = texts.mapNotNull { t -> t.filterNot { it.isWhitespace() }.takeIf { CODE.matches(it) } }.toSet()
        val ports = texts.flatMap(::ports).toSet()
        return PairingScreenReading(codes.singleOrNull(), ports.singleOrNull())
    }

    private fun ports(text: String): List<Int> {
        val v4 = IPV4_PORT.findAll(text).filter { m -> (1..4).all { m.groupValues[it].toInt() <= 255 } }.map { it.groupValues[5] }
        val v6 = IPV6_PORT.findAll(text).map { it.groupValues[1] }
        return (v4 + v6).mapNotNull { p -> p.toIntOrNull()?.takeIf { it in 1..65535 } }.toList()
    }

    /** Try a read code only when the helper is waiting for pairing and this code was not tried yet. */
    fun attempt(reading: PairingScreenReading, portFound: Boolean, status: BuiltinShellStatus, tried: Set<String>): PairingAttempt? {
        val code = reading.code ?: return null
        if (code in tried) return null
        if (reading.port == null && !portFound) return null
        val waiting = when (status) {
            BuiltinShellStatus.NotPaired, BuiltinShellStatus.WirelessDebuggingOff -> true
            is BuiltinShellStatus.Failed -> status.reason.duringPairing || status.reason.rePair
            else -> false
        }
        return if (waiting) PairingAttempt(code, reading.port) else null
    }
}

/**
 * The user's choices from the setup wizard's consent page. Implemented by the app; the default allows nothing.
 * The helper never changes a system setting the user did not tick (mika-gap §2 PL3).
 */
interface SetupConsent {
    fun accessibility(): Boolean
    fun writeSecureSettings(): Boolean
    fun batteryExemption(): Boolean
    fun autoWirelessDebugging(): Boolean
    /** Local audit line (no content). */
    fun audit(event: String) {}
    /** Wireless debugging was switched on automatically: tell the user once (capsule/notification). */
    fun wirelessDebuggingTurnedOn() {}

    companion object {
        val NONE = object : SetupConsent {
            override fun accessibility() = false
            override fun writeSecureSettings() = false
            override fun batteryExemption() = false
            override fun autoWirelessDebugging() = false
        }
    }
}
