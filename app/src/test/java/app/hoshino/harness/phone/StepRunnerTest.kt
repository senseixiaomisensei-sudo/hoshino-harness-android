package app.hoshino.harness.phone

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Scripted screen: each performed action may switch to another screen, optionally after some "loading" reads. */
private class FakePort(var screen: ScreenSnapshot) : PhonePort {
    var clock = 0L
    val performed = mutableListOf<String>()
    var onPerform: (PhoneStep, UiElement?) -> Unit = { _, _ -> }
    val pendingScreens = ArrayDeque<ScreenSnapshot>()
    override suspend fun read(): ScreenSnapshot = screen
    override suspend fun perform(step: PhoneStep, target: UiElement?): ActionOutcome {
        performed += step.kind.name + (target?.ref?.let { ":$it" } ?: "")
        onPerform(step, target)
        return ActionOutcome(true)
    }
    override suspend fun awaitChange(timeoutMs: Long): Boolean {
        val next = pendingScreens.removeFirstOrNull()
        clock += if (next != null) 200 else timeoutMs
        if (next != null) screen = next
        return next != null
    }
    override suspend fun waitQuiet(quietMs: Long, timeoutMs: Long): Boolean { clock += quietMs; return true }
    override fun now() = clock
}

class StepRunnerTest {
    private val home = snap(el("e1", "搜索", listOf(0, 0, 800, 100), editable = true, hint = true, role = "EditText"), el("e2", "张三", listOf(0, 200, 999, 300)))
    private val chat = snap(el("e3", "", listOf(0, 900, 800, 990), editable = true, focused = true, role = "EditText"), el("e4", "发送", listOf(800, 900, 999, 990)), el("e5", "张三", listOf(0, 0, 999, 80), clickable = false))
    private val noApproval = ApprovalGate { false }

    @Test fun runsStepsAndWaitsForExpectationWithoutTheModel() = runBlocking {
        val port = FakePort(home)
        port.onPerform = { step, _ -> if (step.kind == StepKind.tap) { port.pendingScreens += snap(el("x", "加载中", listOf(0, 0, 999, 999), clickable = false)); port.pendingScreens += chat } }
        val steps = PhoneStep.parseAll(JSONArray("""[{"action":"tap","target":"张三","expect":{"text":"发送","timeout_ms":5000}},{"action":"type","text":"晚上好"}]"""))
        val r = StepRunner(port, noApproval).run(steps)
        assertTrue(r.reason ?: "", r.completed)
        assertEquals(listOf("tap:e2", "type"), port.performed)
        assertEquals(true, r.results[0].met)
    }

    @Test fun expectationTimeoutStops() = runBlocking {
        val port = FakePort(home)
        val r = StepRunner(port, noApproval).run(listOf(PhoneStep(StepKind.tap, ref = "e2", expect = Expectation(text = "不存在", timeoutMs = 1000)), PhoneStep(StepKind.back)))
        assertEquals(0, r.stoppedAt)
        assertTrue(r.reason!!.startsWith("expectation not met"))
        assertEquals(listOf("tap:e2"), port.performed)
    }

    @Test fun irreversibleStepStopsUntilThatExactStepIsApproved() = runBlocking {
        val port = FakePort(chat)
        val steps = listOf(PhoneStep(StepKind.type, ref = "e3", text = "你好"), PhoneStep(StepKind.tap, ref = "e4"))
        val first = StepRunner(port, noApproval).run(steps)
        assertEquals(1, first.stoppedAt)
        val pending = first.pending!!
        assertEquals(IrrevAction.send, pending.action)
        assertEquals(listOf("type:e3"), port.performed)
        // The policy approved exactly this fingerprint; a different screen would not match.
        val approved = mutableSetOf(pending.fingerprint)
        val gate = ApprovalGate { approved.remove(it) }
        val second = StepRunner(port, gate).run(listOf(PhoneStep(StepKind.tap, ref = "e4")))
        assertTrue(second.completed)
        assertEquals(listOf("type:e3", "tap:e4"), port.performed)
        // Consumed: running it again asks again.
        assertNotNull(StepRunner(port, gate).run(listOf(PhoneStep(StepKind.tap, ref = "e4"))).pending)
    }

    @Test fun approvalDoesNotTransferToAnotherScreen() = runBlocking {
        val port = FakePort(chat)
        val pending = StepRunner(port, noApproval).run(listOf(PhoneStep(StepKind.tap, ref = "e4"))).pending!!
        port.screen = chat.copy(packageName = "com.other")
        val r = StepRunner(port, ApprovalGate { it == pending.fingerprint }).run(listOf(PhoneStep(StepKind.tap, ref = "e4")))
        assertNotNull(r.pending)
        assertTrue(port.performed.isEmpty())
    }

    @Test fun passwordTypingIsHandedToTheUser() = runBlocking {
        val port = FakePort(snap(el("e1", "", listOf(0, 0, 999, 100), editable = true, password = true)))
        val r = StepRunner(port, noApproval).run(listOf(PhoneStep(StepKind.type, ref = "e1", text = "secret")))
        assertEquals("password", r.needsUser?.reason)
        assertTrue(port.performed.isEmpty())
    }

    // M2.8 approval semantics: these hold whatever the kernel approval mode is (the gate below approves everything,
    // which is what the user-enabled no-approval mode amounts to on this side).
    private val approveAll = ApprovalGate { true }

    @Test fun paymentIsNeverGrantableEvenWhenEverythingIsApproved() = runBlocking {
        val transfer = snap(el("e1", "张三", listOf(0, 0, 999, 80), clickable = false), el("e2", "转账", listOf(800, 900, 999, 990)))
        val port = FakePort(transfer)
        val r = StepRunner(port, approveAll).run(listOf(PhoneStep(StepKind.tap, ref = "e2")))
        assertEquals("payment", r.reason)
        assertEquals("payment", r.needsUser?.reason)
        assertNull(r.pending)
        assertTrue(port.performed.isEmpty())
        // A payment sheet (PIN pad digits are not "pay" buttons) is hands-only too.
        val sheet = snap(el("e1", "请输入支付密码", listOf(0, 0, 999, 100), clickable = false), el("e2", "1", listOf(0, 700, 300, 800)))
        val pin = FakePort(sheet)
        assertEquals("payment", StepRunner(pin, approveAll).run(listOf(PhoneStep(StepKind.tap, ref = "e2"))).needsUser?.reason)
        assertTrue(pin.performed.isEmpty())
        // Leaving stays possible so the agent can hand over.
        assertTrue(StepRunner(pin, approveAll).run(listOf(PhoneStep(StepKind.back))).completed)
        assertEquals(listOf("back"), pin.performed)
    }

    @Test fun verificationCodeAndCaptchaAreHandsOnlyEvenWhenEverythingIsApproved() = runBlocking {
        val code = snap(el("e1", "输入验证码", listOf(0, 0, 999, 100), clickable = false), el("e2", "", listOf(0, 200, 999, 300), editable = true, role = "EditText"))
        val port = FakePort(code)
        val r = StepRunner(port, approveAll).run(listOf(PhoneStep(StepKind.type, ref = "e2", text = "123456")))
        assertEquals("needs_user", r.reason)
        assertEquals("login", r.needsUser?.reason)
        assertTrue(port.performed.isEmpty())
        val captcha = FakePort(snap(el("e1", "拖动滑块完成拼图", listOf(0, 0, 999, 100), clickable = false)))
        val c = StepRunner(captcha, approveAll).run(listOf(PhoneStep(StepKind.swipe, x = 100, y = 500, x2 = 900, y2 = 500)))
        assertEquals("captcha", c.needsUser?.reason)
        assertTrue(captcha.performed.isEmpty())
    }

    @Test fun chooserStopsUnlessThePickIsAnOption() = runBlocking {
        val chooser = ScreenSnapshot("c", "android", 1080, 2400, listOf(el("e9", "打开方式", listOf(0, 500, 999, 550), clickable = false), el("e10", "微信", listOf(0, 600, 500, 700)), el("e11", "微信(双开)", listOf(500, 600, 999, 700))))
        val port = FakePort(chooser)
        val r = StepRunner(port, noApproval).run(listOf(PhoneStep(StepKind.back)))
        assertEquals("chooser", r.reason)
        assertEquals(2, r.chooser!!.options.size)
        val pick = StepRunner(port, noApproval).run(listOf(PhoneStep(StepKind.tap, ref = "e10")))
        assertNull(pick.pending)
        assertEquals(listOf("tap:e10"), port.performed)
    }

    @Test fun missingRefAndAmbiguousTextFailClearly() = runBlocking {
        val port = FakePort(snap(el("e1", "确定", listOf(0, 0, 400, 100)), el("e2", "确定", listOf(500, 500, 900, 600))))
        val r = StepRunner(port, noApproval).run(listOf(PhoneStep(StepKind.tap, ref = "e99")))
        assertTrue(r.reason!!.contains("e99"))
        val amb = StepRunner(port, noApproval).run(listOf(PhoneStep(StepKind.tap, target = "定")))
        assertTrue(amb.reason!!, amb.reason!!.contains("matches 2"))
    }

    @Test fun stopRequestEndsBetweenSteps() = runBlocking {
        val port = FakePort(home)
        var stop: String? = null
        port.onPerform = { _, _ -> stop = "stopped by user" }
        val r = StepRunner(port, noApproval) { stop }.run(listOf(PhoneStep(StepKind.back), PhoneStep(StepKind.back)))
        assertEquals(1, r.stoppedAt)
        assertEquals("stopped by user", r.reason)
    }

    @Test fun stepParsingValidates() {
        val bad = runCatching { PhoneStep.parseAll(JSONArray("""[{"action":"tap"}]""")) }.exceptionOrNull()
        assertTrue(bad?.message.orEmpty(), bad?.message.orEmpty().contains("step 1"))
        assertTrue(runCatching { PhoneStep.parseAll(JSONArray("""[{"action":"key","key":"power"}]""")) }.isFailure)
        assertTrue(runCatching { PhoneStep.parseAll(JSONArray((1..21).joinToString(",", "[", "]") { """{"action":"back"}""" })) }.isFailure)
        val s = PhoneStep.parseAll(JSONArray("""[{"action":"swipe","x":900,"y":500,"x2":100,"y2":500,"duration_ms":300}]""")).single()
        assertEquals(300L, s.durationMs)
    }
}
