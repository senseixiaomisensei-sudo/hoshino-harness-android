package app.hoshino.harness.phone

import app.hoshino.harness.phone.ocr.CtcDecoder
import app.hoshino.harness.phone.ocr.DbPostProcess
import app.hoshino.harness.phone.ocr.OcrLine
import app.hoshino.harness.phone.ocr.OcrMerge
import app.hoshino.harness.phone.ocr.Pixels
import app.hoshino.harness.phone.ocr.TreeQuality
import app.hoshino.harness.phone.setup.PhoneSetupFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneGuardsTest {
    private val tap = PhoneStep(StepKind.tap, x = 10, y = 10)

    @Test fun neverOperatesItselfUnlessAllowed() {
        assertNotNull(PhoneGuards.refuse("dev.hoshino.app", "dev.hoshino.app", false, false, tap))
        assertNull(PhoneGuards.refuse("dev.hoshino.app", "dev.hoshino.app", true, false, tap))
        assertNotNull(PhoneGuards.refuse("com.tencent.mm", "dev.hoshino.app", false, false, PhoneStep(StepKind.open_app, app = "dev.hoshino.app")))
        assertNull(PhoneGuards.refuse("com.tencent.mm", "dev.hoshino.app", false, false, tap))
    }

    @Test fun lockedScreenStopsEveryAction() {
        val why = PhoneGuards.refuse("com.tencent.mm", "dev.hoshino.app", true, true, tap)
        assertTrue(why!!.startsWith("locked"))
    }
}

class ActionEffectTest {
    private fun snap(pkg: String, hash: Long, vararg names: String) = ScreenSnapshot("s", pkg, 1080, 2400,
        names.mapIndexed { i, n -> UiElement(ref = "e$i", role = "TextView", name = n, bounds = listOf(0, i * 50, 999, i * 50 + 40)) }, hash)

    @Test fun effectKinds() {
        assertEquals(Effect.no_effect, ActionEffects.of(snap("a", 1, "x", "y"), snap("a", 1, "x", "y")).effect)
        assertEquals(Effect.done, ActionEffects.of(snap("a", 1, "x", "y"), snap("a", 2, "x", "z")).effect)
        assertEquals(Effect.place_changed, ActionEffects.of(snap("a", 1, "x"), snap("b", 2, "x")).effect)
        assertTrue(ActionEffects.of(null, snap("a", 1)).treeBlind)
    }

    @Test fun limitNoticeOnlyWhenNew() {
        val before = snap("a", 1, "发送")
        assertEquals("操作过于频繁，请稍后再试", ActionEffects.of(before, snap("a", 2, "发送", "操作过于频繁，请稍后再试")).limitReached)
        assertNull("already there before the action", LimitNotice.find(snap("a", 1, "已达到每日上限"), snap("a", 2, "已达到每日上限")))
        assertEquals("今日已达到沟通上限", LimitNotice.find(before, snap("a", 3, "今日已达到沟通上限")))
        assertNull("long paragraphs are not notices", LimitNotice.find(before, snap("a", 3, "上限".repeat(80))))
    }
}

class FrameMathTest {
    @Test fun blackFrameMeansSecureWindow() {
        assertTrue(FrameMath.isBlack(100, 200) { _, _ -> 0xff000000.toInt() })
        assertFalse(FrameMath.isBlack(100, 200) { x, _ -> if (x > 50) 0xffffffff.toInt() else 0xff000000.toInt() })
    }
}

class LedgerAndTextTest {
    @Test fun appOpModeParsing() {
        assertEquals("ignore", PhoneLedger.parseMode("PLAY_AUDIO: ignore; time=+1m2s ago"))
        assertEquals("allow", PhoneLedger.parseMode("Uid mode: X\nPLAY_AUDIO: allow"))
        assertNull(PhoneLedger.parseMode("No operations."))
        assertNull("unknown modes are never written back", PhoneLedger.parseMode("PLAY_AUDIO: ; rm -rf /"))
    }

    @Test fun userScreenTextHidesSecureContent() {
        val s = ScreenSnapshot("s", "com.bank", 1080, 2400, listOf(UiElement(ref = "e1", role = "TextView", name = "余额 1234", bounds = listOf(0, 0, 999, 50))), secure = true)
        val text = UserScreenText.build(s)
        assertTrue(text.contains("com.bank"))
        assertFalse(text.contains("1234"))
        assertTrue(text.contains("先问我"))
    }
}

class OcrLogicTest {
    @Test fun treeQualityDecidesWhenToRecognise() {
        assertEquals(TreeQuality.Verdict.Must, TreeQuality.judge(10, 0, rootMissing = true, secure = false))
        assertEquals(TreeQuality.Verdict.Must, TreeQuality.judge(0, 5, false, false))
        assertEquals(TreeQuality.Verdict.Once, TreeQuality.judge(2, 0, false, false))
        assertEquals(TreeQuality.Verdict.Once, TreeQuality.judge(5, 9, false, false))
        assertEquals(TreeQuality.Verdict.Skip, TreeQuality.judge(30, 3, false, false))
        assertEquals("secure screens are never recognised", TreeQuality.Verdict.Skip, TreeQuality.judge(0, 0, true, true))
    }

    @Test fun ocrMergeAddsOnlyWhatTheTreeLacks() {
        val tree = listOf(UiElement(ref = "e1", role = "Button", name = "发送", bounds = listOf(0, 0, 499, 99), clickable = true))
        val lines = listOf(
            OcrLine("发送", 0.99f, 10, 10, 100, 60),      // already in the tree
            OcrLine("群聊名称", 0.95f, 600, 300, 900, 360), // new
            OcrLine("噪声", 0.2f, 600, 500, 700, 560),      // low confidence
        )
        val merged = OcrMerge.merge(tree, lines, 1000, 1000)
        assertEquals(2, merged.size)
        val added = merged.last()
        assertEquals("群聊名称", added.name)
        assertTrue(added.ocr)
        assertFalse(added.clickable)
        assertEquals(750, added.px)
    }

    @Test fun dbPostProcessFindsSeparateLinesAndExpandsThem() {
        val w = 64; val h = 32
        val prob = FloatArray(w * h)
        fun fill(x0: Int, y0: Int, x1: Int, y1: Int) { for (y in y0..y1) for (x in x0..x1) prob[y * w + x] = 0.9f }
        fill(4, 4, 40, 8)
        fill(4, 20, 20, 24)
        prob[30 * w + 60] = 0.95f // single pixel: too small
        val boxes = DbPostProcess.boxes(prob, w, h)
        assertEquals(2, boxes.size)
        val first = boxes[0]
        assertTrue("unclip expands the shrunk text kernel", first.left < 4 && first.right > 41 && first.top < 4 && first.bottom > 9)
        assertTrue(boxes[1].top > first.top)
    }

    @Test fun ctcGreedyDecoding() {
        val table = CtcDecoder.table("a\nb\nc")
        assertEquals(listOf("blank", "a", "b", "c", " "), table)
        // steps: a a blank a b b → "aab"
        val classes = table.size
        val seq = intArrayOf(1, 1, 0, 1, 2, 2)
        val data = FloatArray(seq.size * classes)
        seq.forEachIndexed { t, c -> data[t * classes + c] = 0.9f }
        val (text, conf) = CtcDecoder(table).decode(data, seq.size, classes)
        assertEquals("aab", text)
        assertEquals(0.9f, conf, 1e-4f)
    }

    @Test fun bilinearResizeKeepsFlatColour() {
        val src = IntArray(4 * 4) { 0xff336699.toInt() }
        val out = Pixels.resize(src, 4, 4, 7, 3)
        assertEquals(21, out.size)
        assertTrue(out.all { it == 0xff336699.toInt() })
    }
}

class PhoneSetupFlowTest {
    private val base = PhoneSetupFlow.Input(
        sdk = 35, consentConfirmed = false, shizukuReady = false, developerOptions = false, wirelessDebugging = false,
        paired = false, helperRunning = false, accessibility = false, ocrReady = false,
    )
    private val flow = PhoneSetupFlow

    @Test fun consentComesBeforeAnySystemChange() {
        assertEquals(PhoneSetupFlow.Step.Consent, flow.current(base))
        assertEquals(PhoneSetupFlow.Step.Developer, flow.current(base.copy(consentConfirmed = true)))
    }

    @Test fun unreadableSwitchesNeverBlock() {
        val i = base.copy(consentConfirmed = true, developerOptions = null, wirelessDebugging = null, userSaysDeveloperOn = true)
        assertEquals(PhoneSetupFlow.Step.Pairing, flow.current(i))
    }

    @Test fun shizukuSkipsPairingAndOcrIsOptional() {
        val i = base.copy(consentConfirmed = true, shizukuReady = true, accessibility = true)
        assertEquals(PhoneSetupFlow.Step.Done, flow.current(i))
        assertEquals(PhoneSetupFlow.Mark.Optional, flow.marks(i)[PhoneSetupFlow.Step.Ocr])
        assertNull(flow.likelyProblem(i))
    }

    @Test fun oldAndroidIsUnsupportedAndExplained() {
        val i = base.copy(sdk = 31)
        assertEquals(PhoneSetupFlow.Step.Version, flow.current(i))
        assertTrue(flow.likelyProblem(i)!!.contains("Android 13"))
    }

    @Test fun likelyProblemPointsAtTheHelperWhenPaired() {
        val i = base.copy(consentConfirmed = true, paired = true, wirelessDebugging = false)
        assertTrue(flow.likelyProblem(i)!!.contains("无线调试关着"))
    }
}
