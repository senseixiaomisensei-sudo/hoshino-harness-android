package dev.hoshino.bridge.phone.shell

import dev.hoshino.bridge.phone.adb.AdbConnectionException
import dev.hoshino.bridge.phone.adb.AdbPairingException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingCodeTest {
    @Test fun normalisesSpacesDashesAndFullWidthDigits() {
        assertEquals("123456", PairingCode.normalize("123456"))
        assertEquals("123456", PairingCode.normalize(" 123 456 "))
        assertEquals("123456", PairingCode.normalize("123-456"))
        assertEquals("123456", PairingCode.normalize("１２３４５６"))
        assertEquals("123456", PairingCode.normalize("123—456"))
        assertNull(PairingCode.normalize("12345"))
        assertNull(PairingCode.normalize("1234567"))
        assertNull(PairingCode.normalize("12a456"))
        assertEquals(37001, PairingCode.port("192.168.1.5:37001"))
        assertEquals(41000, PairingCode.port(" 41000 "))
        assertNull(PairingCode.port("70000"))
    }

    @Test fun readsCodeAndPortFromOneWindow() {
        val r = PairingScreen.read(listOf("与设备配对", "WLAN 配对码", "482 913", "IP 地址和端口", "192.168.31.20:39651"))
        assertEquals(PairingScreenReading("482913", 39651), r)
        assertEquals(PairingScreenReading(null, null), PairingScreen.read(listOf("111111", "222222", "10.0.0.1:1", "10.0.0.1:2")))
        assertEquals(39651, PairingScreen.read(listOf("[fe80::1%wlan0]:39651")).port)
        assertNull(PairingScreen.read(listOf("999.1.1.1:5555")).port)
    }

    @Test fun attemptsOnlyWhenWaitingAndNotTried() {
        val reading = PairingScreenReading("482913", 39651)
        assertEquals(PairingAttempt("482913", 39651), PairingScreen.attempt(reading, false, BuiltinShellStatus.NotPaired, emptySet()))
        assertNull(PairingScreen.attempt(reading, false, BuiltinShellStatus.NotPaired, setOf("482913")))
        assertNull(PairingScreen.attempt(reading, false, BuiltinShellStatus.Running, emptySet()))
        assertNull(PairingScreen.attempt(PairingScreenReading("482913", null), false, BuiltinShellStatus.NotPaired, emptySet()))
        assertEquals(PairingAttempt("482913", null), PairingScreen.attempt(PairingScreenReading("482913", null), true, BuiltinShellStatus.Failed(BuiltinShellFailure.WrongCode), emptySet()))
    }
}

class PermissionCommandsTest {
    private val ours = "dev.hoshino.app/app.hoshino.harness.phone.PhoneAccessibilityService"

    @Test fun appendsOurServiceKeepingOthers() {
        assertEquals(ours, PermissionCommands.withService(null, ours))
        assertEquals(ours, PermissionCommands.withService("null", ours))
        assertEquals("com.a/.A:$ours", PermissionCommands.withService("com.a/.A", ours))
        assertEquals("com.a/.A:com.b/.B:$ours", PermissionCommands.withService(" com.a/.A::com.b/.B:com.a/.A ", ours))
        assertNull(PermissionCommands.withService("com.a/.A:$ours", ours))
        assertNull("short form is the same service", PermissionCommands.withService("com.x/.Y:dev.hoshino.app/.Svc", "dev.hoshino.app/dev.hoshino.app.Svc"))
    }

    @Test fun commandsAreQuotedAndExitCodesParsed() {
        val cmds = PermissionCommands.writeAccessibility("a/.B:c/.D")
        assertEquals("settings put secure enabled_accessibility_services 'a/.B:c/.D'", cmds[0])
        assertEquals("settings put secure accessibility_enabled 1", cmds[1])
        assertEquals(0, PermissionCommands.exitCode("Success\n${PermissionCommands.EXIT_MARK}0\n"))
        assertEquals(255, PermissionCommands.exitCode("Error\n${PermissionCommands.EXIT_MARK}255"))
        assertNull(PermissionCommands.exitCode("no marker"))
        assertEquals("pm grant dev.hoshino.app android.permission.WRITE_SECURE_SETTINGS", PermissionCommands.grantWriteSecureSettings("dev.hoshino.app"))
    }
}

class HelperHandoffTest {
    private class Mem : NonceStore { var s = NonceSlots(); override fun load() = s; override fun save(slots: NonceSlots) { s = slots } }

    @Test fun launchCommandKeepsTheNonceOffTheCommandLine() {
        val cmd = HelperHandoff.command("/data/app/x/base.apk", "dev.hoshino.app", 10123, "ab,cd")
        assertTrue(cmd.startsWith("read n; (echo \"\$n\" | CLASSPATH='/data/app/x/base.apk' setsid app_process /system/bin --nice-name='dev.hoshino.app:phonehelper' dev.hoshino.bridge.phone.shell.HelperMain 'dev.hoshino.app' 10123 'ab,cd'"))
        assertTrue(cmd.endsWith("read _"))
        assertEquals("'it'\\''s'", HelperHandoff.quote("it's"))
    }

    @Test fun checksBeforeTouchingTheBinder() {
        assertEquals(HelperHandoff.Reject.NotShell, HelperHandoff.check(10001, 5, 10001, 5, "a", "a", true))
        assertEquals(HelperHandoff.Reject.IdMismatch, HelperHandoff.check(2000, 5, 2000, 6, "a", "a", true))
        assertEquals(HelperHandoff.Reject.StaleApk, HelperHandoff.check(2000, 5, 2000, 5, "old", "new", true))
        assertEquals(HelperHandoff.Reject.NoBinder, HelperHandoff.check(2000, 5, 2000, 5, "a", "a", false))
        assertNull(HelperHandoff.check(2000, 5, 2000, 5, "a", "a", true))
    }

    @Test fun noncesAreOneTimeAndExpire() {
        var now = 1_000L
        var n = 0
        val nonces = HandoffNonces(Mem(), { now }) { "n${++n}" }
        val launch = nonces.issueLaunch()
        assertFalse(nonces.matches("wrong"))
        val resume = nonces.accept(launch)!!
        assertNull("used up", nonces.accept(launch))
        assertTrue(nonces.matches(resume))
        val again = nonces.issueLaunch()
        now += HandoffNonces.LAUNCH_TTL_MS + 1
        assertFalse("launch nonce expired", nonces.matches(again))
        assertTrue("resume nonce does not expire", nonces.matches(resume))
        nonces.clear()
        assertFalse(nonces.matches(resume))
    }

    @Test fun certPinIsOrderIndependent() {
        val a = byteArrayOf(1, 2, 3); val b = byteArrayOf(4, 5)
        assertEquals(HelperHandoff.certPin(listOf(a, b)), HelperHandoff.certPin(listOf(b, a)))
        assertNull(HelperHandoff.certPin(emptyList()))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class BuiltinShellEngineTest {
    private class FakePlatform(val helper: MutableStateFlow<Any?>) : BuiltinShellPlatform {
        var pairedFlag = false
        var wireless: Boolean? = true
        var wifi = true
        var canWrite = false
        var flipsBack = false
        var launches = 0
        var pairFailure: AdbPairingException? = null
        var launchFailure: Exception? = null
        var startsHelper = true
        var allowAuto = true
        override fun supported() = true
        override fun autoWirelessDebuggingAllowed() = allowAuto
        override fun paired() = pairedFlag
        override fun setPaired(paired: Boolean) { pairedFlag = paired }
        override fun wirelessDebugging() = wireless
        override fun wifiConnected() = wifi
        override fun turnOnWirelessDebugging(): Boolean { if (!canWrite) return false; wireless = !flipsBack; return true }
        override suspend fun findPairingPort(timeoutMs: Long): Int? = 37000
        override fun pair(port: Int, code: String) { pairFailure?.let { throw it } }
        override suspend fun launchHelper() { launches++; launchFailure?.let { throw it }; if (startsHelper) helper.value = Any() }
        override fun stopHelper() { helper.value = null }
        override fun forget() {}
    }

    private fun TestScope.engine(p: FakePlatform, h: MutableStateFlow<Any?>) =
        BuiltinShellEngine(p, h, backgroundScope, StandardTestDispatcher(testScheduler), graceMs = 100, handoffTimeoutMs = 1_000, autoOnSettleMs = 100)

    @Test fun pairingThenStartsHelper() = runTest {
        val h = MutableStateFlow<Any?>(null)
        val p = FakePlatform(h)
        val e = engine(p, h)
        assertEquals(BuiltinShellStatus.NotPaired, e.status.value)
        e.startPairing("123456", null)
        assertTrue(p.pairedFlag)
        assertEquals(BuiltinShellStatus.Running, e.status.value)
        assertEquals(1, p.launches)
    }

    @Test fun wrongCodeIsReported() = runTest {
        val h = MutableStateFlow<Any?>(null)
        val p = FakePlatform(h).apply { pairFailure = AdbPairingException(AdbPairingException.Reason.WRONG_CODE, "x") }
        val e = engine(p, h)
        e.startPairing("123456", 1)
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.WrongCode), e.status.value)
        assertFalse(p.pairedFlag)
    }

    @Test fun watchdogRestartsAfterGraceAndBacksOff() = runTest {
        val h = MutableStateFlow<Any?>(null)
        val p = FakePlatform(h).apply { pairedFlag = true; launchFailure = AdbConnectionException(AdbConnectionException.Reason.CONNECT_FAILED, "x") }
        val e = engine(p, h)
        e.start()
        runCurrent()
        advanceTimeBy(150)
        assertEquals(1, p.launches)
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.ConnectFailed), e.status.value)
        p.launchFailure = null
        advanceTimeBy(2_500)
        assertEquals(2, p.launches)
        assertEquals(BuiltinShellStatus.Running, e.status.value)
        e.unpair()
    }

    @Test fun revokedAuthorizationIsNotRetriedInALoop() = runTest {
        val h = MutableStateFlow<Any?>(null)
        val p = FakePlatform(h).apply { pairedFlag = true; launchFailure = AdbConnectionException(AdbConnectionException.Reason.UNAUTHORIZED, "x") }
        val e = engine(p, h)
        e.retry()
        advanceTimeBy(120_000)
        assertEquals(1, p.launches)
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.AuthorizationRevoked), e.status.value)
    }

    @Test fun afterRebootTurnsWirelessDebuggingOnOncePerNetwork() = runTest {
        val h = MutableStateFlow<Any?>(null)
        val p = FakePlatform(h).apply { pairedFlag = true; wireless = false; canWrite = true }
        val e = engine(p, h)
        e.retry()
        assertEquals(true, p.wireless)
        assertEquals(BuiltinShellStatus.Running, e.status.value)

        val h2 = MutableStateFlow<Any?>(null)
        val q = FakePlatform(h2).apply { pairedFlag = true; wireless = false; canWrite = true; flipsBack = true }
        val e2 = engine(q, h2)
        e2.retry()
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.WifiNotAllowed), e2.status.value)
        e2.retry()
        assertEquals("retry by the user may try once more", 0, q.launches)
    }

    @Test fun autoOnNeedsExplicitConsent() = runTest {
        val h = MutableStateFlow<Any?>(null)
        val p = FakePlatform(h).apply { pairedFlag = true; wireless = false; canWrite = true; allowAuto = false }
        val e = engine(p, h)
        e.retry()
        assertEquals("never written without consent", false, p.wireless)
        assertEquals(BuiltinShellStatus.WirelessDebuggingOff, e.status.value)
        assertEquals(0, p.launches)
    }

    @Test fun noWifiWithDebuggingOff() = runTest {
        val h = MutableStateFlow<Any?>(null)
        val p = FakePlatform(h).apply { pairedFlag = true; wireless = false; wifi = false; canWrite = true }
        val e = engine(p, h)
        e.retry()
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.NoWifi), e.status.value)
        assertEquals(0, p.launches)
    }
}
