package app.hoshino.harness.phone

import java.nio.ByteBuffer
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** m2.9.2 virtual-screen preview: one transform for picture and touch, stride cropping, resize range, orientation. */
class PreviewTransformTest {
    private fun roundTrip(srcW: Int, srcH: Int, viewW: Int, viewH: Int) {
        val t = PreviewTransform(srcW, srcH, viewW, viewH)
        val xs = listOf(0, 1, srcW / 3, srcW / 2, srcW - 2, srcW - 1)
        val ys = listOf(0, 1, srcH / 3, srcH / 2, srcH - 2, srcH - 1)
        for (x in xs) for (y in ys) {
            val (vx, vy) = t.toView(x, y)
            val back = t.toDisplay(vx, vy)
            assertNotNull("($x,$y) in $viewW×$viewH", back)
            assertTrue("x $x → ${back!!.first}", abs(back.first - x) <= 1)
            assertTrue("y $y → ${back.second}", abs(back.second - y) <= 1)
        }
    }

    @Test fun roundTripWithinOnePixelIncludingLetterbox() {
        // Exact fit, letterbox (bars top/bottom), pillarbox (bars left/right), odd sizes, tiny and huge previews.
        roundTrip(1220, 2712, 360, 800)
        roundTrip(1220, 2712, 500, 800)
        roundTrip(1220, 2712, 400, 1300)
        roundTrip(1440, 3200, 217, 483)
        roundTrip(1200, 2670, 1111, 2471)
        roundTrip(1220, 2712, 64, 142)
        // After a portrait → landscape switch the same rules hold for the swapped display.
        roundTrip(2712, 1220, 800, 360)
        roundTrip(2712, 1220, 800, 500)
        roundTrip(2712, 1220, 1000, 300)
    }

    @Test fun barsAreOutsideThePicture() {
        val t = PreviewTransform(1080, 2400, 500, 800)
        assertEquals(PxRect(70, 0, 360, 800), t.viewport)
        assertNull(t.toDisplay(10f, 400f))
        assertNull(t.toDisplay(495f, 400f))
        assertEquals(0 to 1200, t.toDisplayClamped(10f, 400f))
        assertEquals(1079 to 1200, t.toDisplayClamped(495f, 400f))
    }

    @Test fun bitmapMatrixDrawsIntoTheTouchRectangleEvenForARoundedPreview() {
        // 1220×2712 → preview Bitmap 360×801 (801.3 rounded): each axis is scaled on its own onto the same viewport.
        val t = PreviewTransform(1220, 2712, 540, 1300)
        val m = t.bitmapMatrix(360, 801)
        val left = m[2]; val top = m[5]
        val right = m[0] * 360 + m[2]; val bottom = m[4] * 801 + m[5]
        assertEquals(t.viewport.x.toFloat(), left, 0.01f)
        assertEquals(t.viewport.y.toFloat(), top, 0.01f)
        assertEquals((t.viewport.x + t.viewport.w).toFloat(), right, 0.01f)
        assertEquals((t.viewport.y + t.viewport.h).toFloat(), bottom, 0.01f)
        // A picture point drawn at view (vx, vy) maps back to the display pixel it came from (≤ 1 px).
        val bx = 180; val by = 400
        val vx = m[0] * (bx + 0.5f) + m[2]; val vy = m[4] * (by + 0.5f) + m[5]
        val (dx, dy) = t.toDisplay(vx, vy)!!
        assertTrue(abs(dx - (bx + 0.5f) * 1220 / 360) <= 1.5f)
        assertTrue(abs(dy - (by + 0.5f) * 2712 / 801) <= 1.5f)
    }

    @Test fun legacyHelpersUseTheSameTransform() {
        assertEquals(PreviewTransform(1220, 2712, 333, 777).toDisplay(100f, 200f), PreviewGeometry.mapToDisplay(100f, 200f, 333, 777, 1220, 2712))
        assertEquals(PreviewTransform(1220, 2712, 333, 777).toDisplayClamped(-5f, 900f), PreviewGeometry.mapClamped(-5f, 900f, 333, 777, 1220, 2712))
    }
}

class StridedFrameTest {
    /** A plane with [pad] bytes of padding per row, pixel (x, y) = bytes (x, y, 7, 255). */
    private fun plane(w: Int, h: Int, pad: Int, shortLastRow: Boolean): Pair<ByteBuffer, Int> {
        val stride = w * 4 + pad
        val size = stride * h - if (shortLastRow) pad else 0
        val b = ByteBuffer.allocate(size)
        for (y in 0 until h) for (x in 0 until w) { val at = y * stride + x * 4; b.put(at, x.toByte()); b.put(at + 1, y.toByte()); b.put(at + 2, 7); b.put(at + 3, -1) }
        for (y in 0 until h) for (p in 0 until pad) { val at = y * stride + w * 4 + p; if (at < size) b.put(at, 99) }
        return b to stride
    }

    private fun check(out: ByteBuffer, w: Int, h: Int) {
        assertEquals(w * h * 4, out.remaining())
        for (y in 0 until h) for (x in 0 until w) {
            val at = (y * w + x) * 4
            assertEquals("x at $x,$y", x.toByte(), out.get(at))
            assertEquals("y at $x,$y", y.toByte(), out.get(at + 1))
            assertEquals(7.toByte(), out.get(at + 2))
        }
    }

    @Test fun cropsRowPaddingSoThePictureIsNotSheared() {
        val (b, stride) = plane(30, 12, 24, shortLastRow = false)
        assertFalse(StridedFrame.isPacked(30, 12, stride, 4, b.remaining()))
        check(StridedFrame.pack(b, 30, 12, stride, 4), 30, 12)
    }

    @Test fun handlesAShortLastRow() {
        val (b, stride) = plane(30, 12, 24, shortLastRow = true)
        check(StridedFrame.pack(b, 30, 12, stride, 4), 30, 12)
    }

    @Test fun packedPlaneIsCopiedAsIsAndBuffersAreReused() {
        val (b, stride) = plane(16, 5, 0, shortLastRow = false)
        assertTrue(StridedFrame.isPacked(16, 5, stride, 4, b.remaining()))
        val first = StridedFrame.pack(b, 16, 5, stride, 4)
        check(first, 16, 5)
        val again = StridedFrame.pack(b, 16, 5, stride, 4, first)
        assertTrue(first === again)
        check(again, 16, 5)
    }

    @Test fun unusualPixelStride() {
        val w = 4; val h = 3; val ps = 8; val stride = w * ps + 4
        val b = ByteBuffer.allocate(stride * h)
        for (y in 0 until h) for (x in 0 until w) { val at = y * stride + x * ps; b.put(at, x.toByte()); b.put(at + 1, y.toByte()); b.put(at + 2, 7); b.put(at + 3, -1) }
        check(StridedFrame.pack(b, w, h, stride, ps), w, h)
    }
}

class PanelResizeTest {
    @Test fun rangeGoesFromAboutTheBallToNearlyTheWholeScreen() {
        // Xiaomi 15S Pro class screen (1220×2712 px at density 3.5 → 64 dp ≈ 224 px minimum).
        val l = PreviewGeometry.limitsFor(1220, 2712, chromeW = 98, chromeH = 230, minW = 224)
        val small = PreviewGeometry.pinch(600, 0.01f, 1220, 2712, l)
        val big = PreviewGeometry.pinch(600, 50f, 1220, 2712, l)
        assertEquals(224, small)
        assertTrue("big $big", big >= 1100)            // close to the screen width
        assertTrue(PreviewGeometry.heightFor(big, 1220, 2712) <= 2712 - 230)
        // The old fixed 300 dp of chrome allowed only ~795 px; the corner drag reaches the same maximum.
        assertEquals(big, PreviewGeometry.resizeByCorner(600, 5000f, 5000f, 1220, 2712, l))
        // Landscape display on a portrait phone: limited by the width.
        val wide = PreviewGeometry.pinch(600, 50f, 2712, 1220, l)
        assertEquals(1220 - 98, wide)
    }
}

class TapClassifierTest {
    private fun c() = TapClassifier(touchSlop = 10f, doubleTapTimeoutMs = 300)

    @Test fun singleTapOnlyAfterTheDoubleTapTimeout() {
        val t = c()
        t.down(0, 50f, 50f); assertNull(t.up(80))
        assertEquals(380L, t.pendingUntil)
        assertNull(t.flush(200))                     // still inside the double-tap window
        assertEquals(TapClassifier.Click.Single, t.flush(380))
        assertNull(t.flush(500))                     // once only
    }

    @Test fun doubleTapNeverAlsoFiresASingleTap() {
        val t = c()
        t.down(0, 50f, 50f); t.up(60)
        t.down(200, 55f, 52f)
        assertNull(t.flush(400))                     // second press in progress
        assertEquals(TapClassifier.Click.Double, t.up(260))
        assertNull(t.flush(1_000))
    }

    @Test fun secondPressTooLateOrTooFarIsANewSingleTap() {
        val t = c()
        t.down(0, 50f, 50f); t.up(60)
        assertEquals(TapClassifier.Click.Single, t.flush(360))
        t.down(400, 50f, 50f); assertNull(t.up(450))
        assertEquals(TapClassifier.Click.Single, t.flush(750))
        val far = c()
        far.down(0, 50f, 50f); far.up(60)
        far.down(150, 400f, 400f); assertNull(far.up(200))
    }

    @Test fun dragIsNeverAClick() {
        val t = c()
        t.down(0, 50f, 50f)
        assertFalse(t.move(55f, 55f))
        assertTrue(t.move(80f, 50f))
        assertNull(t.up(100))
        assertNull(t.pendingUntil)
        assertNull(t.flush(10_000))
        // A drag right after a tap cancels that pending tap too.
        t.down(0, 0f, 0f); t.up(50)
        t.down(100, 0f, 0f); t.move(100f, 0f); assertNull(t.up(200))
        assertNull(t.flush(10_000))
    }

    @Test fun longHoldIsNotAClick() {
        val t = c()
        t.down(0, 50f, 50f); assertNull(t.up(900))
        assertNull(t.flush(5_000))
    }
}

class AutoTakeoverModelTest {
    @Test fun touchTakesOverAndIdleHandsBack() {
        val m = AutoTakeoverModel(idleMs = 4_000)
        assertEquals(AutoTakeoverModel.Action.Begin, m.touchDown(1_000))
        assertEquals(AutoTakeoverModel.Mode.Auto, m.mode)
        assertNull(m.nextCheckAt())                                  // finger still down
        assertEquals(AutoTakeoverModel.Action.None, m.tick(60_000))  // never while touching
        m.touchUp(2_000)
        assertEquals(6_000L, m.nextCheckAt())
        assertEquals(AutoTakeoverModel.Action.None, m.touchDown(3_000)) // already taken over
        m.touchUp(3_500)
        assertEquals(AutoTakeoverModel.Action.None, m.tick(6_000))   // the second touch restarted the idle time
        m.activity(7_000)                                            // typing keeps it
        assertEquals(AutoTakeoverModel.Action.None, m.tick(10_000))
        assertEquals(AutoTakeoverModel.Action.End, m.tick(11_000))
        assertEquals(AutoTakeoverModel.Mode.Off, m.mode)
        assertNull(m.nextCheckAt())
    }

    @Test fun buttonMakesItStickyUntilHandBack() {
        val m = AutoTakeoverModel(idleMs = 4_000)
        m.touchDown(0); m.touchUp(100)
        assertEquals(AutoTakeoverModel.Action.None, m.button(200))  // auto → sticky, no second begin
        assertEquals(AutoTakeoverModel.Mode.Sticky, m.mode)
        assertEquals(AutoTakeoverModel.Action.None, m.tick(1_000_000))
        assertEquals(AutoTakeoverModel.Action.End, m.button(1_000_001))
        assertEquals(AutoTakeoverModel.Action.Begin, m.button(1_000_002))
        assertEquals(AutoTakeoverModel.Action.None, m.touchDown(1_000_003))
    }

    @Test fun failuresAndOutsideEndsReset() {
        val m = AutoTakeoverModel()
        assertEquals(AutoTakeoverModel.Action.Begin, m.touchDown(0))
        m.beginFailed()
        assertEquals(AutoTakeoverModel.Mode.Off, m.mode)
        assertEquals(AutoTakeoverModel.Action.Begin, m.touchDown(10))
        m.ended()
        assertEquals(AutoTakeoverModel.Mode.Off, m.mode)
        m.startedElsewhere(20)
        assertEquals(AutoTakeoverModel.Mode.Sticky, m.mode)
    }
}

class OrientationTest {
    @Test fun fixedModesAndSizes() {
        assertEquals(DisplayOrientation.Landscape, OrientationPolicy.target(OrientationMode.Landscape, DisplayOrientation.Portrait, null, null))
        assertEquals(DisplayOrientation.Portrait, OrientationPolicy.target(OrientationMode.Portrait, DisplayOrientation.Landscape, DisplayOrientation.Landscape, DisplayOrientation.Landscape))
        assertEquals(2712 to 1220, OrientationPolicy.sizeFor(DisplayOrientation.Landscape, 1220, 2712))
        assertEquals(1220 to 2712, OrientationPolicy.sizeFor(DisplayOrientation.Portrait, 1220, 2712))
        assertEquals(OrientationMode.FollowApp, OrientationMode.of(null))
        assertEquals(OrientationMode.Landscape, OrientationMode.of("landscape"))
    }

    @Test fun followAppReadsManifestAndLetterboxedWindow() {
        assertEquals(DisplayOrientation.Landscape, OrientationPolicy.fromManifest(0))
        assertEquals(DisplayOrientation.Landscape, OrientationPolicy.fromManifest(6))
        assertEquals(DisplayOrientation.Portrait, OrientationPolicy.fromManifest(1))
        assertNull(OrientationPolicy.fromManifest(-1))   // unspecified
        assertNull(OrientationPolicy.fromManifest(4))    // sensor
        // A video gone full screen at runtime: letterboxed 1220×549 window on a portrait 1220×2712 display.
        assertEquals(DisplayOrientation.Landscape, OrientationPolicy.fromWindow(1220, 549, 1220, 2712))
        // A portrait-only app on a landscape display: pillarboxed.
        assertEquals(DisplayOrientation.Portrait, OrientationPolicy.fromWindow(549, 1220, 2712, 1220))
        assertNull(OrientationPolicy.fromWindow(1220, 2712, 1220, 2712)) // fills the display
        assertNull(OrientationPolicy.fromWindow(500, 300, 1220, 2712))   // a small dialog
        // The window (runtime request) wins over the manifest; nothing known keeps the current shape.
        assertEquals(DisplayOrientation.Landscape, OrientationPolicy.target(OrientationMode.FollowApp, DisplayOrientation.Portrait, DisplayOrientation.Portrait, DisplayOrientation.Landscape))
        assertEquals(DisplayOrientation.Portrait, OrientationPolicy.target(OrientationMode.FollowApp, DisplayOrientation.Portrait, null, null))
    }

    @Test fun followerNeedsTwoAgreeingObservations() {
        val f = OrientationFollower()
        assertNull(f.observe(DisplayOrientation.Portrait, DisplayOrientation.Landscape))
        assertNull(f.observe(DisplayOrientation.Portrait, DisplayOrientation.Portrait)) // flicker resets
        assertNull(f.observe(DisplayOrientation.Portrait, DisplayOrientation.Landscape))
        assertEquals(DisplayOrientation.Landscape, f.observe(DisplayOrientation.Portrait, DisplayOrientation.Landscape))
    }

    @Test fun touchMappingFollowsTheOrientationSwitch() {
        // The same preview spot maps into the current display shape; 0..999 model coordinates follow too.
        val portrait = PreviewTransform(1220, 2712, 360, 800)
        val landscape = PreviewTransform(2712, 1220, 800, 360)
        assertEquals(610 to 1356, portrait.toDisplay(180f, 400f))
        assertEquals(1356 to 610, landscape.toDisplay(400f, 180f))
        // Right edge of the landscape picture is the long side.
        assertEquals(2711, landscape.toDisplayClamped(10_000f, 180f).first)
    }
}
