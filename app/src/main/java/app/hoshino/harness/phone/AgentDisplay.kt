// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package app.hoshino.harness.phone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import dev.hoshino.bridge.phone.IHoshinoPhoneShell
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The agent's own virtual display (PH1). The helper (shell uid) creates it on our [ImageReader] surface, so
 * frames and screenshots never need the accessibility screenshot API, and the user keeps using the main
 * screen. One decoding path feeds every consumer (capsule card, chat picture-in-picture, OCR, screenshot):
 * - [frames]: small preview (width <= 360), at most [PREVIEW_FPS] per second, only while somebody watches.
 * - [latestFrameBitmap]: full-size copy for OCR / screenshot.
 */
class AgentDisplay(private val shell: IHoshinoPhoneShell, val width: Int, val height: Int, dpi: Int) {
    private val thread = HandlerThread("hoshino-agent-display").apply { start() }
    private val handler = Handler(thread.looper)
    private val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
    val displayId: Int

    private val _frames = MutableStateFlow<Bitmap?>(null)
    val frames: StateFlow<Bitmap?> = _frames.asStateFlow()
    /** Number of on-screen consumers of [frames]; previews are only produced while > 0 (saves CPU and memory). */
    @Volatile var previewWatchers = 0

    private val frameLock = Any()
    private var fullFrame: Bitmap? = null
    private var hasFrame = false
    private var lastProcessedAt = 0L
    private var lastPreviewAt = 0L
    private var pending = false
    @Volatile private var released = false
    @Volatile var lastFrameAt = 0L
        private set
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val previewWidth = minOf(PREVIEW_MAX_WIDTH, width)
    private val previewHeight = maxOf(1, height * previewWidth / width)
    private val process = Runnable { pending = false; lastProcessedAt = SystemClock.uptimeMillis(); processLatest() }

    init {
        reader.setOnImageAvailableListener({ schedule() }, handler)
        val id = try { shell.createDisplay(reader.surface, width, height, dpi) } catch (t: Throwable) { closeLocal(); throw t }
        if (id < 0) { closeLocal(); throw IllegalStateException("createDisplay failed") }
        displayId = id
    }

    private fun schedule() {
        if (pending || released) return
        pending = true
        val wait = lastProcessedAt + FRAME_INTERVAL_MS - SystemClock.uptimeMillis()
        if (wait > 0) handler.postDelayed(process, wait) else handler.post(process)
    }

    private fun processLatest() {
        if (released) return
        val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return
        try {
            val plane = image.planes[0]
            val strideWidth = plane.rowStride / plane.pixelStride
            synchronized(frameLock) {
                var bmp = fullFrame
                if (bmp == null || bmp.width != strideWidth) {
                    bmp?.recycle()
                    bmp = Bitmap.createBitmap(strideWidth, height, Bitmap.Config.ARGB_8888)
                    fullFrame = bmp
                }
                bmp.copyPixelsFromBuffer(plane.buffer.rewind())
                hasFrame = true
                lastFrameAt = SystemClock.uptimeMillis()
                if (previewWatchers > 0 && lastFrameAt - lastPreviewAt >= 1000L / PREVIEW_FPS) {
                    lastPreviewAt = lastFrameAt
                    val preview = Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888)
                    Canvas(preview).drawBitmap(bmp, Rect(0, 0, width, height), Rect(0, 0, previewWidth, previewHeight), paint)
                    _frames.value = preview
                }
            }
        } finally {
            image.close()
        }
    }

    /** Full-size copy of the newest frame, or null before the first frame. Caller recycles. */
    fun latestFrameBitmap(): Bitmap? = synchronized(frameLock) {
        val bmp = fullFrame
        if (!hasFrame || bmp == null || bmp.isRecycled) null else Bitmap.createBitmap(bmp, 0, 0, width, height)
    }

    /** Small copy for an immediate preview (capsule opens before the next frame arrives). */
    fun previewNow(): Bitmap? = synchronized(frameLock) {
        val bmp = fullFrame ?: return null
        if (!hasFrame || bmp.isRecycled) return null
        Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawBitmap(bmp, Rect(0, 0, width, height), Rect(0, 0, previewWidth, previewHeight), paint)
        }
    }

    fun release() {
        if (released) return
        released = true
        runCatching { shell.releaseDisplay(displayId) }
        closeLocal()
        _frames.value = null
    }

    private fun closeLocal() {
        released = true
        handler.removeCallbacksAndMessages(null)
        handler.post {
            reader.close()
            synchronized(frameLock) { fullFrame?.recycle(); fullFrame = null; hasFrame = false }
        }
        thread.quitSafely()
    }

    companion object {
        const val PREVIEW_MAX_WIDTH = 360
        /** PH24: the picture-in-picture is capped at 5 fps. */
        const val PREVIEW_FPS = 5
        const val FRAME_INTERVAL_MS = 100L

        /** Same size and density as the main screen, created only when a task first needs it. */
        fun create(context: Context, shell: IHoshinoPhoneShell): AgentDisplay {
            val dm = context.getSystemService(DisplayManager::class.java)
            val display = dm.getDisplay(Display.DEFAULT_DISPLAY)
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION") display.getRealMetrics(metrics)
            val w = minOf(metrics.widthPixels, metrics.heightPixels)
            val h = maxOf(metrics.widthPixels, metrics.heightPixels)
            return AgentDisplay(shell, w, h, metrics.densityDpi)
        }
    }
}
