package app.hoshino.harness.phone

import app.hoshino.harness.core.ApprovalDecision
import app.hoshino.harness.core.ApprovalMode
import app.hoshino.harness.core.ApprovalModeRules
import app.hoshino.harness.core.ApprovalState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * m2.9.2 「彻底无审批」 on the Android side: in a session under no-approval none of the Android entries produces a pending
 * item (approval card / notification / capsule buttons, irreversible-step stop, payment / password / code / captcha stop,
 * full-access risk dialog, repeated no-approval risk dialog). Every lifted stop is reported for the audit.
 */
class NoApprovalStepsTest {
    private class Port(var screen: ScreenSnapshot) : PhonePort {
        var clock = 0L
        val performed = mutableListOf<String>()
        override suspend fun read(): ScreenSnapshot = screen
        override suspend fun perform(step: PhoneStep, target: UiElement?): ActionOutcome { performed += step.kind.name; return ActionOutcome(true) }
        override suspend fun awaitChange(timeoutMs: Long): Boolean { clock += timeoutMs; return false }
        override suspend fun waitQuiet(quietMs: Long, timeoutMs: Long): Boolean { clock += quietMs; return true }
        override fun now() = clock
    }

    private val denyAll = ApprovalGate { false }
    private fun bypass(into: MutableList<String>) = NoApprovalBypass { why, pkg -> into += "$why@${pkg ?: "?"}" }

    @Test fun irreversibleStepRunsWithoutGrantOrPending() = runBlocking {
        val chat = snap(el("e3", "", listOf(0, 900, 800, 990), editable = true, focused = true, role = "EditText"), el("e4", "发送", listOf(800, 900, 999, 990)))
        val port = Port(chat)
        val audit = mutableListOf<String>()
        val r = StepRunner(port, denyAll, noApproval = bypass(audit)).run(listOf(PhoneStep(StepKind.tap, ref = "e4")))
        assertNull(r.pending)
        assertTrue(r.completed)
        assertEquals(listOf("tap"), port.performed)
        assertTrue(audit.single().startsWith("irreversible:"))
    }

    @Test fun paymentPasswordCodeAndCaptchaAreNotHandedOver() = runBlocking {
        val audit = mutableListOf<String>()
        val transfer = Port(snap(el("e1", "张三", listOf(0, 0, 999, 80), clickable = false), el("e2", "转账", listOf(800, 900, 999, 990))))
        val pay = StepRunner(transfer, denyAll, noApproval = bypass(audit)).run(listOf(PhoneStep(StepKind.tap, ref = "e2")))
        assertNull(pay.needsUser); assertNull(pay.pending); assertEquals(listOf("tap"), transfer.performed)

        val pin = Port(snap(el("e1", "请输入支付密码", listOf(0, 0, 999, 100), clickable = false), el("e2", "1", listOf(0, 700, 300, 800))))
        assertNull(StepRunner(pin, denyAll, noApproval = bypass(audit)).run(listOf(PhoneStep(StepKind.tap, ref = "e2"))).needsUser)
        assertEquals(listOf("tap"), pin.performed)

        val password = Port(snap(el("e1", "", listOf(0, 0, 999, 100), editable = true, password = true)))
        assertNull(StepRunner(password, denyAll, noApproval = bypass(audit)).run(listOf(PhoneStep(StepKind.type, ref = "e1", text = "secret"))).needsUser)
        assertEquals(listOf("type"), password.performed)

        val code = Port(snap(el("e1", "输入验证码", listOf(0, 0, 999, 100), clickable = false), el("e2", "", listOf(0, 200, 999, 300), editable = true, role = "EditText")))
        assertNull(StepRunner(code, denyAll, noApproval = bypass(audit)).run(listOf(PhoneStep(StepKind.type, ref = "e2", text = "123456"))).needsUser)
        assertEquals(listOf("type"), code.performed)

        val captcha = Port(snap(el("e1", "拖动滑块完成拼图", listOf(0, 0, 999, 100), clickable = false)))
        assertNull(StepRunner(captcha, denyAll, noApproval = bypass(audit)).run(listOf(PhoneStep(StepKind.swipe, x = 100, y = 500, x2 = 900, y2 = 500))).needsUser)
        assertEquals(listOf("swipe"), captcha.performed)

        // Each lifted stop left one audit reason (no text, only the reason and package).
        assertTrue(audit.any { it.startsWith("irreversible:pay") })
        assertTrue(audit.any { it.startsWith("password") })
        assertTrue(audit.any { it.startsWith("captcha") })
        assertFalse(audit.any { it.contains("secret") || it.contains("123456") })
    }

    @Test fun aScreenThatNeedsTheUserDoesNotStopTheBatch() = runBlocking {
        val port = Port(snap(el("e1", "输入验证码", listOf(0, 0, 999, 100), clickable = false)))
        val r = StepRunner(port, denyAll, noApproval = NoApprovalBypass { _, _ -> }).run(listOf(PhoneStep(StepKind.back), PhoneStep(StepKind.back)))
        assertTrue(r.completed); assertNull(r.needsUser); assertEquals(listOf("back", "back"), port.performed)
    }

    @Test fun withoutNoApprovalNothingChanges() = runBlocking {
        val port = Port(snap(el("e1", "", listOf(0, 0, 999, 100), editable = true, password = true)))
        assertEquals("password", StepRunner(port, ApprovalGate { true }).run(listOf(PhoneStep(StepKind.type, ref = "e1", text = "x"))).needsUser?.reason)
        assertTrue(port.performed.isEmpty())
    }

    @Test fun androidBackstopAndDialogs() {
        val none = ApprovalState(sessionMode = ApprovalMode.None)
        val manual = ApprovalState()
        // Approval card / notification / capsule buttons: auto-allowed without a pending item.
        assertEquals(ApprovalDecision.AllowOnce, ApprovalModeRules.autoAnswer(none))
        for (m in ApprovalMode.entries - ApprovalMode.None) assertNull(ApprovalModeRules.autoAnswer(ApprovalState(sessionMode = m)))
        assertNull(ApprovalModeRules.autoAnswer(null))
        // Full access in a no-approval session: no risk dialog.
        assertFalse(ApprovalModeRules.fullAccessNeedsRiskDialog(none))
        assertTrue(ApprovalModeRules.fullAccessNeedsRiskDialog(manual))
        assertTrue(ApprovalModeRules.fullAccessNeedsRiskDialog(null))
        // The no-approval risk note is shown once: confirming it acknowledges forever.
        assertTrue(ApprovalModeRules.NO_APPROVAL_ACK_FOREVER)
        assertFalse(ApprovalModeRules.needsRiskConfirmation(ApprovalMode.None, acknowledged = ApprovalModeRules.NO_APPROVAL_ACK_FOREVER))
        // Shipped default stays manual.
        assertEquals(ApprovalMode.Manual, ApprovalMode.Default)
    }
}
