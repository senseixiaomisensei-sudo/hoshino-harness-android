package app.hoshino.harness.phone

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** m2.9.3 in-app virtual screen: zoom/pan on top of the m2.9.2 transform keeps picture and touch within 1 px. */
class PreviewZoomTest {
    private val src = 1220 to 2712

    /** Every display pixel centre that is visible in the view maps there and back within 1 px, at this zoom. */
    private fun roundTrip(viewW: Int, viewH: Int, z: PreviewZoom, srcW: Int = src.first, srcH: Int = src.second) {
        val t = PreviewTransform(srcW, srcH, viewW, viewH, z)
        var checked = 0
        // Display pixels that are visible: sampled through a grid of view points (zoomed-out pixels are skipped).
        val pixels = buildSet {
            for (i in 0..12) for (j in 0..12) t.toDisplay(viewW * i / 12f, viewH * j / 12f)?.let { add(it) }
            add(0 to 0); add(srcW - 1 to srcH - 1); add(srcW / 2 to srcH / 2)
        }
        for ((x, y) in pixels) {
            val (vx, vy) = t.toView(x, y)
            if (vx < 0 || vy < 0 || vx > viewW || vy > viewH) continue // scrolled out of the view
            val back = t.toDisplay(vx, vy)
            assertNotNull("($x,$y) zoom $z", back)
            assertTrue("x $x → ${back!!.first} at $z", abs(back.first - x) <= 1)
            assertTrue("y $y → ${back.second} at $z", abs(back.second - y) <= 1)
            checked++
        }
        assertTrue("some pixels visible at $z", checked > 0)
    }

    @Test fun identityIsExactlyTheM292Transform() {
        val a = PreviewTransform(1220, 2712, 540, 1100)
        val b = PreviewTransform(1220, 2712, 540, 1100, PreviewZoom.IDENTITY)
        assertEquals(a.viewport, b.viewport)
        assertEquals(a.fit, a.viewport)
        assertEquals(a.toDisplay(100f, 300f), b.toDisplay(100f, 300f))
    }

    @Test fun displayAndTouchAgreeAtEveryZoomAndPan() {
        val view = 540 to 1100
        val fit = PreviewGeometry.fitViewport(src.first, src.second, view.first, view.second)
        for (s in listOf(1f, 1.3f, 2f, 2.5f, 3.7f, 8f))
            for ((fx, fy) in listOf(0f to 0f, 270f to 550f, 539f to 1099f, 100f to 900f)) {
                val z = PreviewZoom.transform(PreviewZoom.IDENTITY, fx, fy, s, 0f, 0f, fit, view.first, view.second)
                roundTrip(view.first, view.second, z)
                roundTrip(view.first, view.second, PreviewZoom.pan(z, 37f, -81f, fit, view.first, view.second))
            }
        // Landscape display in a portrait page (letterboxed), zoomed.
        val lf = PreviewGeometry.fitViewport(2712, 1220, 540, 1100)
        roundTrip(540, 1100, PreviewZoom.transform(PreviewZoom.IDENTITY, 270f, 550f, 3f, 0f, 0f, lf, 540, 1100), 2712, 1220)
    }

    @Test fun zoomIsTheSameMatrixOnTopOfTheFit() {
        // toView with zoom = zoom applied to the unzoomed toView (≤ 1 px: the zoomed viewport is rounded once).
        val view = 600 to 1000
        val base = PreviewTransform(src.first, src.second, view.first, view.second)
        val z = PreviewZoom.clamp(PreviewZoom(2.4f, -500f, -700f), base.fit, view.first, view.second)
        val zt = PreviewTransform(src.first, src.second, view.first, view.second, z)
        for ((x, y) in listOf(300 to 900, 610 to 1356, 900 to 2000)) {
            val (ux, uy) = base.toView(x, y)
            val (zx, zy) = zt.toView(x, y)
            assertTrue(abs(zx - (ux * z.scale + z.tx)) <= 1.5f)
            assertTrue(abs(zy - (uy * z.scale + z.ty)) <= 1.5f)
        }
        // The bitmap matrix draws into the same zoomed viewport the touches use.
        val m = zt.bitmapMatrix(360, 800)
        assertEquals(zt.viewport.x.toFloat(), m[2], 0f)
        assertEquals(zt.viewport.y.toFloat(), m[5], 0f)
        assertEquals(zt.viewport.w.toFloat() / 360, m[0], 1e-6f)
    }

    @Test fun pinchKeepsTheFocusPointStill() {
        val view = 540 to 1100
        val fit = PreviewGeometry.fitViewport(src.first, src.second, view.first, view.second)
        val before = PreviewTransform(src.first, src.second, view.first, view.second)
        val focus = 300f to 600f
        val under = before.toDisplay(focus.first, focus.second)!!
        val z = PreviewZoom.transform(PreviewZoom.IDENTITY, focus.first, focus.second, 2f, 0f, 0f, fit, view.first, view.second)
        val after = PreviewTransform(src.first, src.second, view.first, view.second, z).toDisplay(focus.first, focus.second)!!
        assertTrue(abs(after.first - under.first) <= 2 && abs(after.second - under.second) <= 2)
    }

    @Test fun clampCoversTheViewAndCentresSmallAxes() {
        val view = 540 to 1100
        val fit = PreviewGeometry.fitViewport(src.first, src.second, view.first, view.second)
        // Dragged far right/down: the picture's left/top edge snaps back to the view's edge.
        val far = PreviewZoom.clamp(PreviewZoom(3f, 5000f, 5000f), fit, view.first, view.second)
        val r = far.apply(fit)
        assertTrue(r.x <= 0 && r.y <= 0 && r.x + r.w >= view.first && r.y + r.h >= view.second)
        // Dragged far left/up likewise.
        val r2 = PreviewZoom.clamp(PreviewZoom(3f, -9000f, -9000f), fit, view.first, view.second).apply(fit)
        assertTrue(r2.x <= 0 && r2.y <= 0 && r2.x + r2.w >= view.first && r2.y + r2.h >= view.second)
        // Scale is bounded; below 1 means fit.
        assertEquals(PreviewZoom.MAX_SCALE, PreviewZoom.clamp(PreviewZoom(50f), fit, view.first, view.second).scale, 0f)
        assertEquals(PreviewZoom.IDENTITY, PreviewZoom.clamp(PreviewZoom(0.5f, 10f, 10f), fit, view.first, view.second))
        // A landscape picture in a portrait view at 1.5×: still narrower in height → centred vertically.
        val lf = PreviewGeometry.fitViewport(2712, 1220, 540, 1100)
        val lz = PreviewZoom.clamp(PreviewZoom(1.5f, 0f, 400f), lf, 540, 1100).apply(lf)
        assertTrue(abs((lz.y + lz.h / 2) - 550) <= 1)
    }

    @Test fun doubleTapTogglesAndPanOnlyWhenZoomed() {
        val view = 540 to 1100
        val fit = PreviewGeometry.fitViewport(src.first, src.second, view.first, view.second)
        val z = PreviewZoom.toggle(PreviewZoom.IDENTITY, 200f, 300f, fit, view.first, view.second)
        assertEquals(PreviewZoom.DOUBLE_TAP_SCALE, z.scale, 1e-4f)
        assertEquals(PreviewZoom.IDENTITY, PreviewZoom.toggle(z, 10f, 10f, fit, view.first, view.second))
        assertEquals(PreviewZoom.IDENTITY, PreviewZoom.pan(PreviewZoom.IDENTITY, 50f, 50f, fit, view.first, view.second))
        assertFalse(PreviewZoom.IDENTITY.zoomed)
    }

    @Test fun barsOutsideTheZoomedPictureAreNotTouches() {
        // Unzoomed landscape picture in a portrait view: the bars above/below are null, as in m2.9.2.
        val t = PreviewTransform(2712, 1220, 540, 1100)
        assertNull(t.toDisplay(270f, 5f))
        assertNotNull(t.toDisplay(270f, 550f))
    }
}
