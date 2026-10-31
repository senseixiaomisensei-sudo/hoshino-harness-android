package app.hoshino.harness.phone

import kotlin.math.roundToInt

/**
 * m2.9.3: in-app zoom / pan of the agent display picture (pure, unit-tested). It is one more step on top of the m2.9.2
 * fit rectangle ([PreviewGeometry.fitViewport]): view point p ↦ p · [scale] + ([tx], [ty]). [PreviewTransform] applies it
 * to its viewport, so the picture (GL viewport or Bitmap matrix) and the touch mapping keep using the very same rectangle
 * and stay within 1 px of each other at every zoom.
 *
 * Rules: scale 1 = fit (no pan), up to [MAX_SCALE]. While the zoomed picture is wider/taller than the view it must cover
 * it on that axis (no empty band can be dragged in); while it is smaller it is centred on that axis.
 */
data class PreviewZoom(val scale: Float = 1f, val tx: Float = 0f, val ty: Float = 0f) {
    val zoomed: Boolean get() = scale > 1.001f

    /** [fit] (the unzoomed picture rectangle inside a viewW×viewH view) after this zoom, rounded to whole pixels. */
    fun apply(fit: PxRect): PxRect {
        if (!zoomed) return fit
        val x = (fit.x * scale + tx).roundToInt()
        val y = (fit.y * scale + ty).roundToInt()
        return PxRect(x, y, (fit.w * scale).roundToInt(), (fit.h * scale).roundToInt())
    }

    companion object {
        val IDENTITY = PreviewZoom()
        const val MAX_SCALE = 8f
        const val DOUBLE_TAP_SCALE = 2.5f

        /** Keeps the zoomed picture covering / centred in the view (see the class rules). */
        fun clamp(z: PreviewZoom, fit: PxRect, viewW: Int, viewH: Int): PreviewZoom {
            val s = z.scale.coerceIn(1f, MAX_SCALE)
            if (s <= 1.001f || viewW <= 0 || viewH <= 0) return IDENTITY
            fun axis(t: Float, start: Int, span: Int, view: Int): Float {
                val left = start * s + t           // picture start after zoom
                val size = span * s
                return if (size <= view) t + ((view - size) / 2f - left)      // centre
                else t + when {
                    left > 0f -> -left                                          // gap on the start side
                    left + size < view -> view - (left + size)                  // gap on the end side
                    else -> 0f
                }
            }
            return PreviewZoom(s, axis(z.tx, fit.x, fit.w, viewW), axis(z.ty, fit.y, fit.h, viewH))
        }

        /** Two-finger pinch around (fx, fy) by [factor] (span ratio of this event) plus the centroid's movement (dx, dy). */
        fun transform(z: PreviewZoom, fx: Float, fy: Float, factor: Float, dx: Float, dy: Float, fit: PxRect, viewW: Int, viewH: Int): PreviewZoom {
            val s = (z.scale * factor).coerceIn(1f, MAX_SCALE)
            val k = s / z.scale
            // Keep the content point under the focus where it is: p' = f + (p − f)·k, then follow the fingers.
            val tx = fx + (z.tx - fx) * k + dx
            val ty = fy + (z.ty - fy) * k + dy
            return clamp(PreviewZoom(s, tx, ty), fit, viewW, viewH)
        }

        /** One-finger drag in view mode (only meaningful while zoomed). */
        fun pan(z: PreviewZoom, dx: Float, dy: Float, fit: PxRect, viewW: Int, viewH: Int): PreviewZoom =
            if (!z.zoomed) z else clamp(z.copy(tx = z.tx + dx, ty = z.ty + dy), fit, viewW, viewH)

        /** Double tap: zoomed → fit; fit → [DOUBLE_TAP_SCALE] centred on the tapped point. */
        fun toggle(z: PreviewZoom, fx: Float, fy: Float, fit: PxRect, viewW: Int, viewH: Int): PreviewZoom =
            if (z.zoomed) IDENTITY else transform(IDENTITY, fx, fy, DOUBLE_TAP_SCALE, 0f, 0f, fit, viewW, viewH)
    }
}
