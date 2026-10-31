package app.hoshino.harness.phone

import java.security.MessageDigest

/** What the runner needs from a screen. The Android implementation reads accessibility windows of the agent display. */
interface PhonePort {
    /** Fresh read with stable refs. */
    suspend fun read(): ScreenSnapshot
    /** Executes one action on the already-resolved [target] (null for point/back/key/open_app/scroll without target). */
    suspend fun perform(step: PhoneStep, target: UiElement?): ActionOutcome
    /** Suspends until the UI changes (accessibility event) or [timeoutMs] passes; true when something changed. */
    suspend fun awaitChange(timeoutMs: Long): Boolean
    /** Suspends until no UI change for [quietMs] or [timeoutMs]; true when it became quiet. */
    suspend fun waitQuiet(quietMs: Long, timeoutMs: Long): Boolean
    fun now(): Long

    /**
     * A-1: captures the tree hash and frame signature, subscribes to UI events of this display, and only then runs
     * [block] (the action and the wait after it) while the subscription is active, so the first events caused by
     * the action are never lost and the wait compares with the screen as it was **before** the action.
     *
     * The default is for ports that cannot watch (test fakes): it has no "before" baseline and falls back to
     * waiting for quiet after the action, i.e. the pre-WP-1 behaviour. [AndroidPhoneScreen] overrides it.
     */
    suspend fun <T> watch(block: suspend (ChangeProbe) -> T): T = block(object : ChangeProbe {
        override suspend fun awaitStableChange(): ChangeWait.Result {
            val t = now()
            waitQuiet(StepRunner.QUIET_MS, StepRunner.SETTLE_MAX_MS)
            return ChangeWait.Result(changed = true, treeChanged = true, frameChanged = false, settled = true, waitedMs = now() - t)
        }
        override fun events(): Long = 0
        override suspend fun awaitEventAfter(seen: Long, timeoutMs: Long): Boolean = awaitChange(timeoutMs)
    })
}

/** The "before" baseline and event clock of one action ([PhonePort.watch]). */
interface ChangeProbe {
    /** Waits by the [ChangeWait] rules and says whether the screen changed compared with before the action. */
    suspend fun awaitStableChange(): ChangeWait.Result
    /** Number of UI events seen since the subscription (before the action). */
    fun events(): Long
    /** Suspends until more than [seen] events were seen, or [timeoutMs]; true on a new event. Never misses an event that arrived after [seen] was read. */
    suspend fun awaitEventAfter(seen: Long, timeoutMs: Long): Boolean
}

/** [openedInMs]: open_app only (A-5), from the launch request until the app was usable (or the wait ended). */
data class ActionOutcome(val ok: Boolean, val detail: String? = null, val openedInMs: Long? = null)

/** m2.9.2: receives each stop that the user-enabled no-approval mode let through (reason like "payment", package). */
fun interface NoApprovalBypass { fun record(reason: String, packageName: String?) }

/** Approval for one irreversible step, issued by the policy in the kernel and consumed exactly once. */
fun interface ApprovalGate { fun consume(fingerprint: String): Boolean }

data class PendingApproval(val index: Int, val step: String, val action: IrrevAction, val matched: String, val fingerprint: String, val packageName: String?)

/**
 * [timing]: where the step's time went (A-7); null when the step stopped before acting.
 * [change]: A-1 verdict of the wait after the action compared with the screen before it; null for steps that waited
 * for an expectation instead, for `wait` steps and for steps that did not act.
 */
data class StepResult(
    val index: Int, val step: String, val ok: Boolean, val detail: String? = null, val waitedMs: Long = 0, val met: Boolean? = null,
    val timing: StepTiming? = null, val change: ChangeWait.Result? = null, val openedInMs: Long? = null,
)

data class StepsResult(
    val results: List<StepResult>,
    val stoppedAt: Int?,
    val reason: String?,
    val pending: PendingApproval? = null,
    val chooser: Chooser? = null,
    val needsUser: NeedsUser? = null,
    val screen: ScreenSnapshot? = null,
    /** Milliseconds of the read before the first step (A-7). */
    val initialReadMs: Long = 0,
) {
    val completed: Boolean get() = stoppedAt == null
    /** Some acted step changed only the picture (self-drawn UI): [ActionEffects] must not call that no_effect. */
    val frameChanged: Boolean get() = results.any { it.change?.frameChanged == true }
}

/**
 * Runs 1..20 known steps in a row without asking the model between them. Each step resolves its target on
 * the current screen, passes the irreversible check (stops with [PendingApproval] unless the exact step on
 * the exact screen was approved), executes, then waits by code for its expectation (text appears/gone,
 * app in front, screen quiet). Stops early on a chooser, a screen that needs the user, a missing target,
 * a stop request, or a failed action.
 *
 * Every acted step also reports a [StepTiming] (resolve / act / wait / read / total, A-7). The timing only
 * reads [PhonePort.now] around the existing calls; it never adds a wait, a read or a retry.
 */
class StepRunner(
    private val port: PhonePort,
    private val gate: ApprovalGate,
    /**
     * m2.9.2: set only when the user enabled 无审批 for this session (the kernel says so, `no_approval`). Then nothing stops
     * for the user: irreversible steps need no grant, and password / payment / captcha / code screens are not handed over.
     * Every step that would have stopped is reported to [NoApprovalBypass.record] (audit: reason + package, no content).
     */
    private val noApproval: NoApprovalBypass? = null,
    private val stopRequested: () -> String? = { null },
) {

    // Per-step accumulators for A-7 timing; reset at the start of every step. Only touched by timed* below.
    private var readMs = 0L
    private var waitMs = 0L

    private suspend fun timedRead(): ScreenSnapshot { val t = port.now(); try { return port.read() } finally { readMs += port.now() - t } }
    private suspend fun timedWaitQuiet(quietMs: Long, timeoutMs: Long): Boolean { val t = port.now(); try { return port.waitQuiet(quietMs, timeoutMs) } finally { waitMs += port.now() - t } }
    private suspend fun timedAwaitEvent(probe: ChangeProbe, seen: Long, timeoutMs: Long): Boolean { val t = port.now(); try { return probe.awaitEventAfter(seen, timeoutMs) } finally { waitMs += port.now() - t } }
    private suspend fun timedStableChange(probe: ChangeProbe): ChangeWait.Result { val t = port.now(); try { return probe.awaitStableChange() } finally { waitMs += port.now() - t } }

    private class Acted(val outcome: ActionOutcome, val started: Long, val acted: Long, val met: Boolean?, val screen: ScreenSnapshot?, val change: ChangeWait.Result?)

    suspend fun run(steps: List<PhoneStep>, appLabel: String? = null): StepsResult {
        val results = mutableListOf<StepResult>()
        val firstRead = port.now()
        var screen = port.read()
        val initialReadMs = port.now() - firstRead
        fun result(done: List<StepResult>, stoppedAt: Int?, reason: String?, pending: PendingApproval? = null, chooser: Chooser? = null, needsUser: NeedsUser? = null, screen: ScreenSnapshot? = null) =
            StepsResult(done, stoppedAt, reason, pending, chooser, needsUser, screen, initialReadMs)
        for ((i, step) in steps.withIndex()) {
            val stepStart = port.now()
            readMs = 0L; waitMs = 0L
            stopRequested()?.let { return result(results, i, it, screen = screen) }
            SystemScreens.chooser(screen, appLabel)?.let { c ->
                val picking = step.kind == StepKind.tap && (step.ref != null && c.options.any { it.ref == step.ref } || step.target != null)
                if (!picking) return result(results, i, "chooser", chooser = c, screen = screen)
            }
            val target = when (val r = resolve(step, screen)) {
                is Resolved.Fail -> return result(results + StepResult(i, step.describe(), false, r.why), i, r.why, screen = screen)
                is Resolved.Ok -> r.element
            }
            if (step.kind == StepKind.type && (target?.password == true || (target == null && screen.elements.any { it.focused && it.password }))) {
                if (noApproval == null) return result(results, i, "password", needsUser = NeedsUser("password", "typing into a password field is left to the user"), screen = screen)
                noApproval.record("password", screen.packageName)
            }
            // Hands-only screens and payment do not depend on approval rules: no grant or remembered decision lets the agent
            // act there (docs/decisions.md, M2.8). m2.9.2: only the user-enabled no-approval mode lifts it (audited).
            handsOnly(step, screen)?.let { n ->
                if (noApproval == null) return result(results, i, "needs_user", needsUser = n, screen = screen)
                noApproval.record(n.reason, screen.packageName)
            }
            IrreversibleDetector.classify(step, screen, target)?.let { hit ->
                if (noApproval != null) noApproval.record("irreversible:" + hit.action.name, screen.packageName)
                else {
                    if (hit.action == IrrevAction.pay) return result(results, i, "payment", needsUser = NeedsUser("payment", "payment is always done by the user: ${hit.matched}"), screen = screen)
                    val fp = fingerprint(step, hit, screen, target)
                    if (!gate.consume(fp)) return result(results, i, "approval_required", pending = PendingApproval(i, step.describe(), hit.action, hit.matched, fp, screen.packageName), screen = screen)
                }
            }
            // A-1: subscribe and take the "before" baseline first, then act and wait inside the same subscription.
            val a = port.watch { probe ->
                val started = port.now()
                val outcome = port.perform(step, target)
                val acted = port.now()
                if (!outcome.ok) Acted(outcome, started, acted, null, null, null)
                else { val s = settle(step, probe); Acted(outcome, started, acted, s.met, s.screen, s.change) }
            }
            val (outcome, started, acted) = Triple(a.outcome, a.started, a.acted)
            if (!outcome.ok) {
                val timing = StepTiming(started - stepStart, acted - started, 0, 0, acted - stepStart)
                return result(results + StepResult(i, step.describe(), false, outcome.detail, timing = timing, openedInMs = outcome.openedInMs), i, outcome.detail ?: "action failed", screen = runCatching { port.read() }.getOrDefault(screen))
            }
            val met = a.met
            screen = a.screen!!
            val end = port.now()
            results += StepResult(i, step.describe(), true, outcome.detail, end - started, met, StepTiming(started - stepStart, acted - started, waitMs, readMs, end - stepStart), a.change, outcome.openedInMs)
            if (met == false) return result(results, i, "expectation not met within ${step.expect?.timeoutMs}ms", screen = screen)
            if (noApproval == null) SystemScreens.needsUser(screen)?.let { n -> if (i < steps.lastIndex) return result(results, i + 1, "needs_user", needsUser = n, screen = screen) }
        }
        return result(results, null, null, screen = screen, chooser = SystemScreens.chooser(screen, appLabel), needsUser = if (noApproval == null) SystemScreens.needsUser(screen) else null)
    }

    private class Settled(val met: Boolean?, val screen: ScreenSnapshot, val change: ChangeWait.Result?)

    /**
     * After an action: wait for the expectation, or (no expectation) until the screen settled, judged against the screen
     * before the action ([ChangeWait]). Returns (met?, screen, change verdict).
     */
    private suspend fun settle(step: PhoneStep, probe: ChangeProbe): Settled {
        val e = step.expect ?: step.takeIf { it.kind == StepKind.wait }?.expect
        if (e == null) {
            val change = timedStableChange(probe)
            return Settled(null, timedRead(), change)
        }
        val deadline = port.now() + e.timeoutMs
        while (true) {
            // Read the event count before reading the screen: an event that lands after the read wakes the wait below.
            val seen = probe.events()
            if (e.quiet) {
                val quiet = timedWaitQuiet(QUIET_MS, (deadline - port.now()).coerceAtLeast(0))
                val s = timedRead()
                if (quiet && conditionsMet(e, s)) return Settled(true, s, null)
            } else {
                val s = timedRead()
                if (conditionsMet(e, s)) return Settled(true, s, null)
                if (SystemScreens.chooser(s) != null || SystemScreens.needsUser(s) != null) return Settled(null, s, null)
            }
            val left = deadline - port.now()
            if (left <= 0) return Settled(false, timedRead(), null)
            timedAwaitEvent(probe, seen, left)
        }
    }

    companion object {
        const val QUIET_MS = 400L
        const val SETTLE_MAX_MS = 2_500L

        /**
         * A step the agent must not take on this screen whatever the approval mode: typing or clearing on any screen that
         * needs the user (password, login, verification code, captcha, payment), and tapping/long-pressing/swiping on a
         * captcha or payment screen (sliders, PIN pads). Reading, Back, keys, waiting and opening another app stay allowed
         * so the agent can still hand over or leave.
         */
        fun handsOnly(step: PhoneStep, s: ScreenSnapshot): NeedsUser? {
            val n = SystemScreens.needsUser(s) ?: return null
            val acting = when (step.kind) {
                StepKind.type, StepKind.clear -> true
                StepKind.tap, StepKind.long_press, StepKind.swipe -> n.reason == "captcha" || n.reason == "payment"
                else -> false
            }
            return if (acting) n else null
        }

        fun conditionsMet(e: Expectation, s: ScreenSnapshot): Boolean {
            val text = s.texts.joinToString("\n")
            if (e.text != null && !contains(text, e.text)) return false
            if (e.gone != null && contains(text, e.gone)) return false
            if (e.app != null && s.packageName != e.app) return false
            return true
        }

        private fun contains(haystack: String, needle: String) =
            haystack.filterNot(Char::isWhitespace).lowercase().contains(needle.filterNot(Char::isWhitespace).lowercase())

        fun fingerprint(step: PhoneStep, hit: Irreversible, s: ScreenSnapshot, target: UiElement?): String {
            val text = listOf(step.kind.name, step.ref, step.target, step.x, step.y, step.text?.hashCode(), step.enter, step.key, hit.action, hit.matched, s.packageName, target?.name, target?.cx, target?.cy).joinToString("|")
            return MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).take(16).joinToString("") { "%02x".format(it) }
        }

        sealed interface Resolved { data class Ok(val element: UiElement?) : Resolved; data class Fail(val why: String) : Resolved }

        /** Finds the element a step acts on. Text targets prefer exact, then contained, then clickable; ambiguity fails. */
        fun resolve(step: PhoneStep, s: ScreenSnapshot): Resolved {
            step.ref?.let { r ->
                return s.byRef(r)?.let { Resolved.Ok(it) } ?: Resolved.Fail("$r is not on the current screen; read the screen again")
            }
            step.target?.let { t ->
                val n = t.filterNot(Char::isWhitespace).lowercase()
                fun key(e: UiElement) = e.name.filterNot(Char::isWhitespace).lowercase()
                val exact = s.elements.filter { key(it) == n }
                val pool = exact.ifEmpty { s.elements.filter { key(it).contains(n) && n.isNotEmpty() } }
                if (pool.isEmpty()) return Resolved.Fail("no element shows \"$t\"")
                val preferred = pool.filter { it.clickable || it.editable }.ifEmpty { pool }
                val distinct = preferred.distinctBy { "${it.cx / 20},${it.cy / 20}" }
                if (distinct.size > 1 && exact.size != 1) return Resolved.Fail("\"$t\" matches ${distinct.size} elements: " + distinct.take(5).joinToString { "${it.ref} \"${it.name.take(20)}\"" })
                return Resolved.Ok(distinct.first())
            }
            if (step.x != null && step.y != null && step.kind != StepKind.swipe) {
                val under = s.elements.filter { e -> e.bounds.size == 4 && step.x in e.bounds[0]..e.bounds[2] && step.y in e.bounds[1]..e.bounds[3] }
                return Resolved.Ok(under.filter { it.clickable || it.editable }.minByOrNull { (it.bounds[2] - it.bounds[0]) * (it.bounds[3] - it.bounds[1]) })
            }
            return Resolved.Ok(null)
        }
    }
}
