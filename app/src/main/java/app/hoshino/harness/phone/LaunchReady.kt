// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package app.hoshino.harness.phone

/**
 * A-5 (WP-1): when an app opened by phone_launch_app counts as usable. Mika's awaitOpened stops as soon as the app's
 * task is in front, which is often still the splash/ad screen; the next step then reads a half-built page. Here the
 * open only returns when, after the app is in front:
 * - the tree shows at least [MIN_CLICKABLE] clickable elements (a real page), or
 * - the top activity changed since the app first came to front (splash activity → main activity) and something is
 *   shown, or
 * - the tree has stayed unchanged for [STABLE_MS] with at least one clickable element (a warm start back onto a
 *   sparse page, e.g. a full-screen player; splash/ad screens usually count down and keep changing), or
 * - a system chooser ("open with", dual apps) is in front: the model must pick, waiting longer would not help.
 * Everything is capped at [CAP_MS]; the caller reports what was true then. Pure and clock-free (JVM-tested).
 */
object LaunchReady {
    const val CAP_MS = 5_000L
    const val POLL_MS = 100L
    const val MIN_CLICKABLE = 3
    const val STABLE_MS = 1_200L

    enum class State { Waiting, Ready, Chooser, Timeout }

    /** One look at the agent display while waiting. */
    data class Look(
        val atMs: Long,
        /** Package of the top task on the agent display (helper), null when unknown. */
        val taskPackage: String?,
        /** Top activity class of that task (helper, AIDL 41), null when unknown or an older helper. */
        val activity: String?,
        /** Package of the top application window read through accessibility. */
        val windowPackage: String?,
        val clickable: Int,
        val named: Int,
        val treeHash: Long,
        val chooser: Boolean,
    )

    data class Verdict(val state: State, val why: String, val inFront: Boolean)

    /** Folds looks one by one; [ready] is the verdict of the newest look. */
    class Tracker(private val pkg: String, private val startedAtMs: Long, private val capMs: Long = CAP_MS) {
        private var firstActivity: String? = null
        private var sawFront = false
        private var lastHash: Long? = null
        private var hashSince = 0L

        fun next(l: Look): Verdict {
            val front = l.taskPackage == pkg || (l.taskPackage == null && l.windowPackage == pkg)
            if (l.chooser && !front) return Verdict(State.Chooser, "chooser", false)
            if (lastHash != l.treeHash) { lastHash = l.treeHash; hashSince = l.atMs }
            if (front) {
                if (!sawFront) { sawFront = true; firstActivity = l.activity }
                // The app's window may still be another package's (the launcher fading out): count only our own content.
                val ownContent = l.windowPackage == null || l.windowPackage == pkg
                if (ownContent && l.clickable >= MIN_CLICKABLE) return Verdict(State.Ready, "clickable", true)
                if (ownContent && l.activity != null && firstActivity != null && l.activity != firstActivity && l.named > 0) return Verdict(State.Ready, "activity", true)
                if (ownContent && l.clickable >= 1 && l.atMs - hashSince >= STABLE_MS) return Verdict(State.Ready, "stable", true)
            }
            if (l.atMs - startedAtMs >= capMs) return Verdict(State.Timeout, if (front) "not_interactive" else "not_in_front", front)
            return Verdict(State.Waiting, "", front)
        }
    }
}
