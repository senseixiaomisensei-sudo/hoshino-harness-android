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
}

data class ActionOutcome(val ok: Boolean, val detail: String? = null)

/** Approval for one irreversible step, issued by the policy in the kernel and consumed exactly once. */
fun interface ApprovalGate { fun consume(fingerprint: String): Boolean }

data class PendingApproval(val index: Int, val step: String, val action: IrrevAction, val matched: String, val fingerprint: String, val packageName: String?)

data class StepResult(val index: Int, val step: String, val ok: Boolean, val detail: String? = null, val waitedMs: Long = 0, val met: Boolean? = null)

data class StepsResult(
    val results: List<StepResult>,
    val stoppedAt: Int?,
    val reason: String?,
    val pending: PendingApproval? = null,
    val chooser: Chooser? = null,
    val needsUser: NeedsUser? = null,
    val screen: ScreenSnapshot? = null,
) {
    val completed: Boolean get() = stoppedAt == null
}

/**
 * Runs 1..20 known steps in a row without asking the model between them. Each step resolves its target on
 * the current screen, passes the irreversible check (stops with [PendingApproval] unless the exact step on
 * the exact screen was approved), executes, then waits by code for its expectation (text appears/gone,
 * app in front, screen quiet). Stops early on a chooser, a screen that needs the user, a missing target,
 * a stop request, or a failed action.
 */
class StepRunner(private val port: PhonePort, private val gate: ApprovalGate, private val stopRequested: () -> String? = { null }) {

    suspend fun run(steps: List<PhoneStep>, appLabel: String? = null): StepsResult {
        val results = mutableListOf<StepResult>()
        var screen = port.read()
        for ((i, step) in steps.withIndex()) {
            stopRequested()?.let { return StepsResult(results, i, it, screen = screen) }
            SystemScreens.chooser(screen, appLabel)?.let { c ->
                val picking = step.kind == StepKind.tap && (step.ref != null && c.options.any { it.ref == step.ref } || step.target != null)
                if (!picking) return StepsResult(results, i, "chooser", chooser = c, screen = screen)
            }
            val target = when (val r = resolve(step, screen)) {
                is Resolved.Fail -> return StepsResult(results + StepResult(i, step.describe(), false, r.why), i, r.why, screen = screen)
                is Resolved.Ok -> r.element
            }
            if (step.kind == StepKind.type && (target?.password == true || (target == null && screen.elements.any { it.focused && it.password }))) {
                return StepsResult(results, i, "password", needsUser = NeedsUser("password", "typing into a password field is left to the user"), screen = screen)
            }
            // Hands-only screens and payment never depend on approval: no grant, approval mode (including the user-enabled
            // no-approval mode) or remembered decision can let the agent act there (docs/decisions.md, 2026-10-31 M2.8).
            handsOnly(step, screen)?.let { n -> return StepsResult(results, i, "needs_user", needsUser = n, screen = screen) }
            IrreversibleDetector.classify(step, screen, target)?.let { hit ->
                if (hit.action == IrrevAction.pay) return StepsResult(results, i, "payment", needsUser = NeedsUser("payment", "payment is always done by the user: ${hit.matched}"), screen = screen)
                val fp = fingerprint(step, hit, screen, target)
                if (!gate.consume(fp)) return StepsResult(results, i, "approval_required", pending = PendingApproval(i, step.describe(), hit.action, hit.matched, fp, screen.packageName), screen = screen)
            }
            val started = port.now()
            val outcome = port.perform(step, target)
            if (!outcome.ok) return StepsResult(results + StepResult(i, step.describe(), false, outcome.detail), i, outcome.detail ?: "action failed", screen = runCatching { port.read() }.getOrDefault(screen))
            val (met, after) = settle(step)
            screen = after
            results += StepResult(i, step.describe(), true, outcome.detail, port.now() - started, met)
            if (met == false) return StepsResult(results, i, "expectation not met within ${step.expect?.timeoutMs}ms", screen = screen)
            SystemScreens.needsUser(screen)?.let { n -> if (i < steps.lastIndex) return StepsResult(results, i + 1, "needs_user", needsUser = n, screen = screen) }
        }
        return StepsResult(results, null, null, screen = screen, chooser = SystemScreens.chooser(screen, appLabel), needsUser = SystemScreens.needsUser(screen))
    }

    /** After an action: wait for the expectation, or briefly for the screen to settle. Returns (met?, screen). */
    private suspend fun settle(step: PhoneStep): Pair<Boolean?, ScreenSnapshot> {
        val e = step.expect ?: step.takeIf { it.kind == StepKind.wait }?.expect
        if (e == null) {
            port.waitQuiet(QUIET_MS, SETTLE_MAX_MS)
            return null to port.read()
        }
        val deadline = port.now() + e.timeoutMs
        while (true) {
            if (e.quiet) {
                val quiet = port.waitQuiet(QUIET_MS, (deadline - port.now()).coerceAtLeast(0))
                val s = port.read()
                if (quiet && conditionsMet(e, s)) return true to s
            } else {
                val s = port.read()
                if (conditionsMet(e, s)) return true to s
                if (SystemScreens.chooser(s) != null || SystemScreens.needsUser(s) != null) return null to s
            }
            val left = deadline - port.now()
            if (left <= 0) return false to port.read()
            port.awaitChange(left)
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
