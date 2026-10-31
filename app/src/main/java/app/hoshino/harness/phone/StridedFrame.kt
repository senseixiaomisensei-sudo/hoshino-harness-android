package app.hoshino.harness.phone

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ImageReader RGBA_8888 plane → tightly packed width×height×4 bytes (pure, unit-tested; m2.9.2).
 *
 * A plane's rows are often padded (`rowStride > width * pixelStride`, e.g. 1220 px wide → rowStride 4928 = 1232 px),
 * and many drivers leave the last row unpadded, so the buffer is shorter than `rowStride * height`. Copying the buffer
 * straight into a Bitmap is only right when the Bitmap is exactly rowStride wide and the buffer is complete; any other
 * size shears the picture sideways (the classic slight shift + black band on the right) or throws. This crops every row
 * to `width` pixels first, so the frame Bitmap is exactly the display size and nothing downstream sees the padding.
 */
object StridedFrame {
    const val BYTES_PER_PIXEL = 4

    /** True when [src] can be copied as is (no padding, complete). */
    fun isPacked(width: Int, height: Int, rowStride: Int, pixelStride: Int, available: Int): Boolean =
        pixelStride == BYTES_PER_PIXEL && rowStride == width * BYTES_PER_PIXEL && available >= width * height * BYTES_PER_PIXEL

    /**
     * Packs [src] (position = start of the plane) into [dst] (reused when large enough). Returns the packed buffer,
     * positioned at 0 with limit = width × height × 4. Rows missing at the end of a short buffer are left black.
     */
    fun pack(src: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int, dst: ByteBuffer? = null): ByteBuffer {
        require(width > 0 && height > 0 && pixelStride >= 1 && rowStride >= width * pixelStride) { "bad plane geometry" }
        val size = width * height * BYTES_PER_PIXEL
        val out = (dst?.takeIf { it.capacity() >= size } ?: ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())).also { it.clear() }
        val base = src.position()
        val end = src.limit()
        if (isPacked(width, height, rowStride, pixelStride, end - base)) {
            val view = src.duplicate().also { it.position(base); it.limit(base + size) }
            out.put(view)
        } else if (pixelStride == BYTES_PER_PIXEL) {
            val row = ByteArray(width * BYTES_PER_PIXEL)
            val view = src.duplicate()
            for (y in 0 until height) {
                val at = base + y * rowStride
                val n = minOf(row.size, end - at)
                if (n <= 0) { row.fill(0); out.put(row); continue }
                view.limit(at + n).position(at)
                view.get(row, 0, n)
                if (n < row.size) row.fill(0, n, row.size)
                out.put(row)
            }
        } else {
            // Unusual pixel stride: take the first 4 bytes of each pixel.
            val px = ByteArray(BYTES_PER_PIXEL)
            val view = src.duplicate()
            for (y in 0 until height) for (x in 0 until width) {
                val at = base + y * rowStride + x * pixelStride
                if (at + BYTES_PER_PIXEL <= end) { view.limit(at + BYTES_PER_PIXEL).position(at); view.get(px) } else px.fill(0)
                out.put(px)
            }
        }
        out.position(0).limit(size)
        return out
    }
}
