package app.hoshino.harness.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PipLayoutTest {
    private val l = PipLayout.Limits(minW = 190, areaW = 1080, areaH = 1900, headerH = 58)

    @Test fun defaultIsTopRightAndKeepsAspect() {
        val w = PipLayout.place(Float.NaN, Float.NaN, Float.NaN, 1220, 2712, l)
        assertEquals(1080 - w.w - PipLayout.MARGIN_PX, w.x)
        assertEquals(PipLayout.MARGIN_PX, w.y)
        assertEquals(PreviewGeometry.heightFor(w.w, 1220, 2712), w.pictureH)
    }

    @Test fun alwaysInsideTheArea() {
        for ((x, y) in listOf(-500f to -500f, 5000f to 5000f, 300f to 1800f)) {
            val w = PipLayout.place(400f, x, y, 1220, 2712, l)
            assertTrue(w.x >= 0 && w.y >= 0 && w.x + w.w <= l.areaW && w.y + w.pictureH + l.headerH <= l.areaH)
        }
        // Wider than fits (height bound): clamped so the whole window is visible.
        val big = PipLayout.place(5000f, 0f, 0f, 1220, 2712, l)
        assertTrue(big.pictureH + l.headerH <= l.areaH)
        // Landscape display: width bound by the area width.
        val land = PipLayout.place(5000f, 0f, 0f, 2712, 1220, l)
        assertEquals(1080, land.w)
    }

    @Test fun slowDragsAddUp() {
        var w = 300f
        repeat(100) { w = PipLayout.resizeWidth(w, 0.4f, 0f, 1220, 2712, 0, 0, l) }
        assertEquals(320f, w, 0.01f)
    }

    @Test fun handleGrowsAndShrinksWithinBounds() {
        val grown = PipLayout.resizeWidth(300f, 200f, 200f, 1220, 2712, 100, 100, l)
        assertTrue(grown > 300)
        val shrunk = PipLayout.resizeWidth(300f, -5000f, -5000f, 1220, 2712, 100, 100, l)
        assertEquals(190f, shrunk, 0f)
        val capped = PipLayout.resizeWidth(300f, 9000f, 9000f, 1220, 2712, 100, 100, l)
        assertTrue(capped <= 1080 - 100 && PreviewGeometry.heightFor(capped.toInt(), 1220, 2712) + l.headerH <= l.areaH - 100)
    }
}
