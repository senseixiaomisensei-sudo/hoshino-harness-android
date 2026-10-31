package app.hoshino.harness.phone

import org.junit.Assert.*
import org.junit.Test

/** m2.9: apps opened by the agent stay on the background agent display; the task's foreground service follows the display. */
class VirtualDisplayGuardTest {
    private val pkg = "com.ss.android.ugc.aweme"

    @Test fun appInFrontOfAgentDisplayIsPlaced() {
        assertEquals(Placement.OnAgent, LaunchPlacement.verdict(pkg, mainBefore = "com.android.launcher", agentTop = pkg, mainTop = "com.android.launcher"))
    }

    @Test fun appThatAppearsOnTheMainScreenIsALeak() {
        // The user was on the launcher; after the start the app is in front of the main screen: pulled the user's screen away.
        assertEquals(Placement.LeakedToMain, LaunchPlacement.verdict(pkg, mainBefore = "com.android.launcher", agentTop = null, mainTop = pkg))
        // Even if it is also reported on the agent display, a new main-screen appearance wins (must be moved back).
        assertEquals(Placement.LeakedToMain, LaunchPlacement.verdict(pkg, mainBefore = null, agentTop = pkg, mainTop = pkg))
    }

    @Test fun notVisibleYetIsPendingAndAnAlreadyFrontMainAppIsNotOurLeak() {
        assertEquals(Placement.Pending, LaunchPlacement.verdict(pkg, mainBefore = "com.android.launcher", agentTop = null, mainTop = "com.android.launcher"))
        assertEquals(Placement.Pending, LaunchPlacement.verdict(pkg, mainBefore = pkg, agentTop = null, mainTop = pkg))
    }

    @Test fun foregroundServiceRunsExactlyWhileTheAgentDisplayExists() {
        assertTrue(PhoneTaskForeground.shouldRun(true))
        assertFalse(PhoneTaskForeground.shouldRun(false))
    }

    @Test fun notificationTitleNamesOnlyStatusAndPackage() {
        assertEquals("Hoshino 正在后台操作手机", PhoneTaskForeground.title(PhoneTaskState()))
        assertEquals("Hoshino 正在后台操作手机 · $pkg", PhoneTaskForeground.title(PhoneTaskState(status = PhoneTaskState.Status.Working, app = pkg, recent = listOf("type e3 text(5 chars)"))))
        assertEquals("Hoshino 手机操作暂停 · 需要你", PhoneTaskForeground.title(PhoneTaskState(status = PhoneTaskState.Status.NeedsUser, reason = "captcha")))
        assertFalse(PhoneTaskForeground.title(PhoneTaskState(status = PhoneTaskState.Status.Working, recent = listOf("secret"))).contains("secret"))
    }
}
