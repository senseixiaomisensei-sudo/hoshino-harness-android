package app.hoshino.harness.phone

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** m2.9 floating ball: frame rate, preview geometry / touch mapping, manual takeover and panel state machines. */
class FpsMathTest {
    @Test fun intervalIsFlooredSoTheChosenRateIsReached() {
        assertEquals(16L, FpsMath.intervalMs(60))
        assertEquals(33L, FpsMath.intervalMs(30))
        assertEquals(50L, FpsMath.intervalMs(20))
        assertEquals(200L, FpsMath.intervalMs(5))
        for (fps in FpsMath.MIN_FPS..FpsMath.MAX_FPS) assertTrue("$fps", 1000.0 / FpsMath.intervalMs(fps) >= fps)
    }

    @Test fun outOfRangeIsClamped() {
        assertEquals(5, FpsMath.clamp(0)); assertEquals(5, FpsMath.clamp(-10)); assertEquals(60, FpsMath.clamp(240)); assertEquals(30, FpsMath.clamp(30))
        assertEquals(16L, FpsMath.intervalMs(1000)); assertEquals(200L, FpsMath.intervalMs(0)); assertEquals(200L, FpsMath.intervalMs(-1))
    }

    @Test fun nobodyWatchingDropsToTheFloor() {
        assertEquals(60, FpsMath.captureFps(60, watching = true))
        assertEquals(FpsMath.IDLE_CAPTURE_FPS, FpsMath.captureFps(60, watching = false))
        assertEquals(5, FpsMath.captureFps(5, watching = false))
        assertEquals(0, FpsMath.previewFps(60, watching = false))
        assertEquals(45, FpsMath.previewFps(45, watching = true))
        assertEquals(60, FpsMath.previewFps(99, watching = true))
    }

    @Test fun sliderSnapsToStepsOfFive() {
        assertEquals(30, FpsMath.snap(31.9f)); assertEquals(35, FpsMath.snap(33f)); assertEquals(5, FpsMath.snap(1f)); assertEquals(60, FpsMath.snap(70f))
        assertEquals(10, FpsMath.SLIDER_STEPS)
        assertEquals(30, FpsMath.DEFAULT_FPS)
    }
}

class PreviewGeometryTest {
    private val l = PreviewGeometry.Limits(minW = 120, maxW = 1000, maxH = 1800)

    @Test fun keepsAspectRatio() {
        assertEquals(800, PreviewGeometry.heightFor(360, 1080, 2400))
        val w = PreviewGeometry.pinch(300, 1.5f, 1080, 2400, l)
        assertEquals(450, w)
        assertEquals(1000, PreviewGeometry.heightFor(w, 1080, 2400))
    }

    @Test fun resizeIsContinuousAndClamped() {
        val a = PreviewGeometry.resizeByCorner(300, 10f, 0f, 1080, 2400, l)
        val b = PreviewGeometry.resizeByCorner(300, 11f, 0f, 1080, 2400, l)
        assertTrue(b - a in 0..1)
        assertEquals(120, PreviewGeometry.resizeByCorner(300, -900f, -900f, 1080, 2400, l))
        // Height bound: 1800 px tall at 1080x2400 → 810 px wide, below maxW.
        assertEquals(810, PreviewGeometry.resizeByCorner(300, 5000f, 5000f, 1080, 2400, l))
        assertEquals(810, PreviewGeometry.pinch(300, 100f, 1080, 2400, l))
        // Dragging down only also grows it (projected on the aspect diagonal).
        assertTrue(PreviewGeometry.resizeByCorner(300, 0f, 200f, 1080, 2400, l) > 300)
    }

    @Test fun positionStaysOnScreen() {
        assertEquals(0 to 0, PreviewGeometry.clampPosition(-50, -20, 300, 600, 1080, 2400))
        assertEquals(780 to 1800, PreviewGeometry.clampPosition(2000, 3000, 300, 600, 1080, 2400))
        assertEquals(100 to 200, PreviewGeometry.clampPosition(100, 200, 300, 600, 1080, 2400))
    }

    @Test fun touchMapsProportionally() {
        // Exact-aspect view 360x800 of a 1080x2400 display: x3 scale.
        assertEquals(540 to 1200, PreviewGeometry.mapToDisplay(180f, 400f, 360, 800, 1080, 2400))
        assertEquals(0 to 0, PreviewGeometry.mapToDisplay(0f, 0f, 360, 800, 1080, 2400))
        assertEquals(1079 to 2399, PreviewGeometry.mapToDisplay(360f, 800f, 360, 800, 1080, 2400))
        // Pillarboxed: 500x800 view → picture 360 wide starting at x=70.
        val vp = PreviewGeometry.fitViewport(1080, 2400, 500, 800)
        assertEquals(PxRect(70, 0, 360, 800), vp)
        assertNull(PreviewGeometry.mapToDisplay(10f, 400f, 500, 800, 1080, 2400))
        assertEquals(540 to 1200, PreviewGeometry.mapToDisplay(250f, 400f, 500, 800, 1080, 2400))
        assertEquals(0 to 1200, PreviewGeometry.mapClamped(10f, 400f, 500, 800, 1080, 2400))
        // Letterboxed: wide view.
        assertEquals(PxRect(0, 100, 360, 800), PreviewGeometry.fitViewport(1080, 2400, 360, 1000))
    }

    @Test fun gesturesAndStrokes() {
        assertEquals(PreviewGeometry.Gesture.Tap, PreviewGeometry.classify(2f, 120, 8f))
        assertEquals(PreviewGeometry.Gesture.LongPress, PreviewGeometry.classify(2f, 700, 8f))
        assertEquals(PreviewGeometry.Gesture.Swipe, PreviewGeometry.classify(40f, 700, 8f))
        val n = 200
        val (xs, ys, ts) = PreviewGeometry.thinStroke(IntArray(n) { it }, IntArray(n) { it * 2 }, LongArray(n) { 1000L + it / 3 })
        assertEquals(PreviewGeometry.MAX_STROKE_POINTS, xs.size)
        assertEquals(0, xs.first()); assertEquals(n - 1, xs.last()); assertEquals(2 * (n - 1), ys.last())
        assertEquals(0L, ts.first())
        for (i in 1 until ts.size) assertTrue(ts[i] > ts[i - 1])
    }
}

class ManualTakeoverTest {
    @Test fun inputsOnlyCountWhileActive() {
        var now = 1_000L
        val m = ManualTakeover { now }
        assertTrue(m.aiMayAct)
        assertFalse(m.record(ManualTakeover.Input.Tap))
        assertTrue(m.begin("s1", "com.a"))
        assertFalse(m.begin("s1", "com.a"))
        assertFalse(m.aiMayAct)
        assertTrue(m.record(ManualTakeover.Input.Tap)); assertTrue(m.record(ManualTakeover.Input.Tap)); assertTrue(m.record(ManualTakeover.Input.Swipe)); assertTrue(m.record(ManualTakeover.Input.Text))
        now += 12_400
        val note = m.end("com.b")!!
        assertTrue(note, note.contains("12 秒")); assertTrue(note.contains("点按 2 次")); assertTrue(note.contains("滑动 1 次")); assertTrue(note.contains("输入文字 1 次"))
        assertTrue(note.contains("com.a") && note.contains("com.b"))
        assertTrue(m.aiMayAct)
        assertNull(m.end("com.b"))
        assertEquals(note, m.takeNote()); assertNull(m.takeNote())
    }

    @Test fun summaryNeverContainsTypedTextAndHandlesNoAction() {
        val s = ManualTakeover.summary(400, emptyMap(), null, "com.x")
        assertTrue(s.contains("没有在预览里操作")); assertTrue(s.contains("仍是 com.x")); assertTrue(s.contains("0 秒"))
    }

    @Test fun aiWaitsUntilHandBack() = runTest {
        val m = ManualTakeover { 0L }
        assertEquals(ManualTakeover.Wait(allowed = true, waited = false), m.awaitAiAllowed(1_000))
        m.begin(null, null)
        val waiting = async { m.awaitAiAllowed(60_000) }
        delay(5_000)
        assertFalse(waiting.isCompleted)
        m.end(null)
        assertEquals(ManualTakeover.Wait(allowed = true, waited = true), waiting.await())
    }

    @Test fun aiGivesUpWithoutActingOnTimeout() = runTest {
        val m = ManualTakeover { 0L }
        m.begin(null, null)
        assertEquals(ManualTakeover.Wait(allowed = false, waited = true), m.awaitAiAllowed(1_000))
        assertFalse(m.aiMayAct)
    }
}

class BallPanelModelTest {
    @Test fun ballExpandsAndCollapses() {
        val m = BallPanelModel()
        assertEquals(BallPanelModel.Mode.Hidden, m.tapBall())
        assertEquals(BallPanelModel.Mode.Ball, m.visibility(true))
        assertFalse(m.rendering)
        assertEquals(BallPanelModel.Mode.Panel, m.tapBall())
        assertTrue(m.rendering)
        assertEquals(BallPanelModel.Mode.Panel, m.visibility(true))
        assertTrue(m.collapse())
        assertEquals(BallPanelModel.Mode.Ball, m.mode)
        assertFalse(m.rendering)
        m.tapBall()
        assertEquals(BallPanelModel.Mode.Hidden, m.visibility(false))
        assertFalse(m.rendering)
    }

    @Test fun takeoverKeepsThePanelOpen() {
        val m = BallPanelModel()
        m.visibility(true)
        assertEquals(BallPanelModel.Mode.Panel, m.takeoverChanged(true))
        assertFalse(m.collapse())
        assertEquals(BallPanelModel.Mode.Panel, m.tapBall())
        assertEquals(BallPanelModel.Mode.Panel, m.visibility(false))
        assertEquals(BallPanelModel.Mode.Hidden, m.screenOff())
        m.takeoverChanged(false)
        m.visibility(true); m.tapBall()
        assertTrue(m.collapse())
    }

    @Test fun taskClock() {
        val m = BallPanelModel()
        assertNull(m.elapsedMs(0))
        m.taskActivity(true, 1_000)
        m.taskActivity(true, 5_000)
        assertEquals(64_000L, m.elapsedMs(65_000))
        assertEquals("1:04", BallPanelModel.formatElapsed(m.elapsedMs(65_000)!!))
        assertEquals("1:01:01", BallPanelModel.formatElapsed(3_661_000))
        m.taskActivity(false, 70_000)
        assertNull(m.elapsedMs(70_000))
        m.taskActivity(true, 80_000)
        assertNotNull(m.elapsedMs(80_000))
    }
}
