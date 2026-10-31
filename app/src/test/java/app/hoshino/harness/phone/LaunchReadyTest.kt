package app.hoshino.harness.phone

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** WP-1 A-5: phone_launch_app returns only when the app is usable, not on its splash screen. */
class LaunchReadyTest {
    private val pkg = "com.ss.android.ugc.aweme"
    private fun look(at: Long, task: String? = pkg, activity: String? = "SplashActivity", window: String? = task, clickable: Int = 0, named: Int = 1, hash: Long = at, chooser: Boolean = false) =
        LaunchReady.Look(at, task, activity, window, clickable, named, hash, chooser)

    private fun run(looks: List<LaunchReady.Look>): Pair<LaunchReady.Verdict, Long> {
        val t = LaunchReady.Tracker(pkg, 0)
        for (l in looks) { val v = t.next(l); if (v.state != LaunchReady.State.Waiting) return v to l.atMs }
        error("still waiting")
    }

    @Test fun splashThenMainPageIsReadyOnlyAtTheMainPage() {
        // Launcher still in front, then the splash (changing countdown, 1 "跳过" button), then the feed at 1.4 s.
        val looks = (0L..5_000L step 100).map { t -> when {
            t < 300 -> look(t, task = "com.miui.home", activity = "Launcher", clickable = 12)
            t < 1_400 -> look(t, clickable = 1, hash = t / 200) // countdown text changes every 200 ms
            else -> look(t, activity = "MainActivity", clickable = 9, hash = 99)
        } }
        val (v, at) = run(looks)
        assertEquals(LaunchReady.State.Ready, v.state)
        assertEquals(1_400L, at)
    }

    @Test fun launcherContentInFrontDoesNotCount() {
        // The task is already ours but the window still belongs to the launcher animating out.
        val (v, at) = run(listOf(look(0, window = "com.miui.home", clickable = 20), look(100, clickable = 5)))
        assertEquals(LaunchReady.State.Ready, v.state)
        assertEquals(100L, at)
    }

    @Test fun activityChangeCountsWhenTheTreeIsThin() {
        val (v, _) = run(listOf(look(0, clickable = 0), look(100, activity = "PlayerActivity", clickable = 1, named = 2)))
        assertEquals(LaunchReady.State.Ready, v.state)
        assertEquals("activity", v.why)
    }

    @Test fun warmStartOnASparseStablePageIsReadyAfterStability() {
        // Full-screen player, one button, nothing changes; no activity info (older helper).
        val looks = (0L..5_000L step 100).map { look(it, activity = null, clickable = 1, hash = 7) }
        val (v, at) = run(looks)
        assertEquals(LaunchReady.State.Ready, v.state)
        assertEquals("stable", v.why)
        assertEquals(LaunchReady.STABLE_MS, at)
    }

    @Test fun chooserReturnsAtOnce() {
        val (v, at) = run(listOf(look(0, task = null, window = "android", clickable = 4, chooser = true)))
        assertEquals(LaunchReady.State.Chooser, v.state)
        assertEquals(0L, at)
    }

    @Test fun splashThatNeverEndsTimesOutInFront() {
        val looks = (0L..6_000L step 100).map { look(it, clickable = 0, hash = it) }
        val (v, at) = run(looks)
        assertEquals(LaunchReady.State.Timeout, v.state)
        assertTrue(v.inFront)
        assertEquals(LaunchReady.CAP_MS, at)
    }

    @Test fun neverInFrontTimesOutNotInFront() {
        val looks = (0L..6_000L step 100).map { look(it, task = "com.other", clickable = 10) }
        val (v, _) = run(looks)
        assertEquals(LaunchReady.State.Timeout, v.state)
        assertFalse(v.inFront)
        assertEquals("not_in_front", v.why)
    }

    @Test fun coldFlagParsesOnlyForOpenApp() {
        assertTrue(PhoneStep.parse(JSONObject("""{"action":"open_app","app":"抖音","cold":true}""")).cold)
        assertFalse(PhoneStep.parse(JSONObject("""{"action":"open_app","app":"抖音"}""")).cold)
        assertFalse(PhoneStep.parse(JSONObject("""{"action":"tap","ref":"e1","cold":true}""")).cold)
        assertEquals("open_app app=抖音 cold", PhoneStep.parse(JSONObject("""{"action":"open_app","app":"抖音","cold":true}""")).describe())
    }
}
