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
import android.view.Surface
import dev.hoshino.bridge.phone.IHoshinoPhoneShell
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The agent's own virtual display (PH1). The helper (shell uid) creates it on our [ImageReader] surface, so
 * frames and screenshots never need the accessibility screenshot API, and the user keeps using the main
 * screen. One decoding path feeds every consumer (capsule card, chat picture-in-picture, OCR, screenshot):
 * - [frames]: small Bitmap preview (width <= 360) for the chat picture-in-picture and the panel fallback, only while
 *   somebody watches, at the user's frame rate ([userFps], 设置 → 设置手机操作).
 * - [attachPreview]: zero-copy live picture for the floating panel through [PreviewRelay] (GPU only, no Bitmap).
 * - [latestFrameBitmap]: full-size copy for OCR / screenshot.
 * Capture is throttled by [FpsMath]: the user's rate while watched, at most [FpsMath.IDLE_CAPTURE_FPS] otherwise.
 */
class AgentDisplay(private val shell: IHoshinoPhoneShell, width: Int, height: Int, val dpi: Int, zeroCopyPreview: Boolean = false) {
    /** Current size in pixels; swaps on a portrait ⇄ landscape switch ([resize], m2.9.2). */
    @Volatile var width: Int = width
        private set
    @Volatile var height: Int = height
        private set
    private val _shape = MutableStateFlow(width to height)
    /** (width, height) — the preview and its touch mapping follow this ([PreviewTransform]). */
    val shape: StateFlow<Pair<Int, Int>> = _shape.asStateFlow()
    val orientation: DisplayOrientation get() = OrientationPolicy.of(width, height)
    val shortSide: Int = minOf(width, height)
    val longSide: Int = maxOf(width, height)
    private val thread = HandlerThread("hoshino-agent-display").apply { start() }
    private val handler = Handler(thread.looper)
    @Volatile private var reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
    /** Reused buffer for [StridedFrame.pack] (rows cropped to the display width). */
    private var packed: java.nio.ByteBuffer? = null
    val displayId: Int

    private val _frames = MutableStateFlow<Bitmap?>(null)
    val frames: StateFlow<Bitmap?> = _frames.asStateFlow()
    /** Number of on-screen consumers of [frames]; previews are only produced while > 0 (saves CPU and memory). */
    @Volatile var previewWatchers = 0
    /**
     * m2.9.3: consumers among [previewWatchers] that show the picture large (the in-app virtual screen page, zoomable):
     * while > 0 the Bitmap preview is made up to [HIRES_PREVIEW_WIDTH] wide instead of [PREVIEW_MAX_WIDTH]. Still one
     * decode for everybody (the floating ball, the in-app page and its picture-in-picture share [frames]).
     */
    @Volatile var hiResWatchers = 0
    /** The user's frame-rate choice ([PhoneDisplayFps]); set by [PhoneController]. */
    @Volatile var userFps = FpsMath.DEFAULT_FPS

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
    private var previewWidth = minOf(PREVIEW_MAX_WIDTH, width)
    private var previewHeight = maxOf(1, (height * previewWidth + width / 2) / width)
    private val process = Runnable { pending = false; lastProcessedAt = SystemClock.uptimeMillis(); processLatest() }
    /** GPU relay between the VirtualDisplay and [reader]; null when not enabled by the user or EGL setup failed (old direct path). */
    @Volatile private var relay: PreviewRelay? = if (!zeroCopyPreview) null else runCatching {
        PreviewRelay.create(width, height, reader.surface, ::captureIntervalMs) { FpsMath.intervalMs(FpsMath.previewFps(userFps, true)) }
    }.getOrNull()

    /** Somebody looks at the picture (Bitmap previews or the zero-copy panel). */
    val watching: Boolean get() = previewWatchers > 0 || relay?.previewAttached == true
    /** True when [attachPreview] can be used. */
    val zeroCopy: Boolean get() = relay != null
    /** Zero-copy preview frames drawn so far (on-device fps check). */
    val previewFrames: Long get() = relay?.previewFrames ?: 0L

    private fun captureIntervalMs(): Long = FpsMath.intervalMs(FpsMath.captureFps(userFps, watching))

    init {
        reader.setOnImageAvailableListener({ schedule() }, handler)
        val id = try { shell.createDisplay(relay?.input ?: reader.surface, width, height, dpi) } catch (t: Throwable) { relay?.release(); closeLocal(); throw t }
        if (id < 0) { relay?.release(); closeLocal(); throw IllegalStateException("createDisplay failed") }
        displayId = id
    }

    private fun schedule() {
        if (pending || released) return
        pending = true
        val wait = lastProcessedAt + captureIntervalMs() - SystemClock.uptimeMillis()
        if (wait > 0) handler.postDelayed(process, wait) else handler.post(process)
    }

    private fun processLatest() {
        if (released) return
        val r = reader
        val image = runCatching { r.acquireLatestImage() }.getOrNull() ?: return
        try {
            val w = image.width
            val h = image.height
            if (w != width || h != height) return // a frame of the previous shape, right after a resize
            val plane = image.planes[0]
            synchronized(frameLock) {
                var bmp = fullFrame
                if (bmp == null || bmp.width != w || bmp.height != h) {
                    bmp?.recycle()
                    bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    fullFrame = bmp
                }
                // m2.9.2: rows are cropped to the display width first; copying a padded plane (rowStride > width * 4)
                // straight into a Bitmap sheared the picture and left a black band on the right.
                val buf = StridedFrame.pack(plane.buffer, w, h, plane.rowStride, plane.pixelStride, packed)
                packed = buf
                bmp.copyPixelsFromBuffer(buf)
                hasFrame = true
                lastFrameAt = SystemClock.uptimeMillis()
                if (previewWatchers > 0 && lastFrameAt - lastPreviewAt >= FpsMath.intervalMs(FpsMath.previewFps(userFps, true))) {
                    lastPreviewAt = lastFrameAt
                    val (pw, ph) = previewSize(w, h)
                    val preview = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
                    Canvas(preview).drawBitmap(bmp, Rect(0, 0, w, h), Rect(0, 0, pw, ph), paint)
                    _frames.value = preview
                }
            }
        } finally {
            image.close()
        }
    }

    /**
     * m2.9.2: portrait ⇄ landscape. Swaps to a new ImageReader of the new size and asks the helper to resize the display
     * onto it (same dpi). The zero-copy relay is sized for the old shape, so after a resize the panel uses the Bitmap
     * preview. False (nothing changed) when the helper is too old for resizeDisplay or it failed.
     */
    fun resize(newW: Int, newH: Int): Boolean {
        if (released || newW <= 0 || newH <= 0) return false
        if (newW == width && newH == height) return true
        val next = ImageReader.newInstance(newW, newH, PixelFormat.RGBA_8888, 2)
        val code = runCatching { shell.resizeDisplay(displayId, next.surface, newW, newH, dpi) }.getOrDefault(0)
        if (code != 1) { next.close(); return false }
        val old = reader
        val oldRelay = relay
        relay = null
        synchronized(frameLock) {
            reader = next
            width = newW; height = newH
            previewWidth = minOf(PREVIEW_MAX_WIDTH, newW)
            previewHeight = maxOf(1, (newH * previewWidth + newW / 2) / newW)
            hasFrame = false
        }
        next.setOnImageAvailableListener({ schedule() }, handler)
        handler.post { runCatching { old.close() }; oldRelay?.release() }
        _shape.value = newW to newH
        return true
    }

    /** Full-size copy of the newest frame, or null before the first frame. Caller recycles. */
    fun latestFrameBitmap(): Bitmap? = synchronized(frameLock) {
        val bmp = fullFrame
        if (!hasFrame || bmp == null || bmp.isRecycled) null else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height)
    }

    /**
     * A-1 (WP-1): coarse luma grid ([FrameMath.lumaGrid]) of the newest frame, read in place under the frame lock
     * (no full-size copy), or null before the first frame. Cheap enough to poll every 100 ms while waiting.
     */
    fun frameSignature(): IntArray? = synchronized(frameLock) {
        val bmp = fullFrame
        if (!hasFrame || bmp == null || bmp.isRecycled) null
        else runCatching { FrameMath.lumaGrid(bmp.width, bmp.height) { x, y -> bmp.getPixel(x, y) } }.getOrNull()
    }

    /** Small copy for an immediate preview (capsule opens before the next frame arrives). */
    fun previewNow(): Bitmap? = synchronized(frameLock) {
        val bmp = fullFrame ?: return null
        if (!hasFrame || bmp.isRecycled) return null
        Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawBitmap(bmp, Rect(0, 0, bmp.width, bmp.height), Rect(0, 0, previewWidth, previewHeight), paint)
        }
    }

    /** Bitmap preview size: small for the ball/PiP, larger while an in-app page shows it big ([hiResWatchers]). */
    private fun previewSize(w: Int, h: Int): Pair<Int, Int> {
        if (hiResWatchers <= 0) return previewWidth to previewHeight
        val pw = minOf(HIRES_PREVIEW_WIDTH, w)
        return pw to maxOf(1, (h * pw + w / 2) / w)
    }

    /**
     * Draw this display straight into [surface] (a TextureView's), GPU only. False = use [frames]. m2.9.3: several
     * surfaces at once (floating panel + in-app page), each with its own [viewport] (zoom); one frame source.
     */
    fun attachPreview(surface: Surface, viewport: ((Int, Int) -> PxRect)? = null): Boolean = !released && relay?.attachPreview(surface, viewport) == true

    /** m2.9.3: redraw [surface] after its zoom changed (zero-copy path only). */
    fun redrawPreview(surface: Surface) { relay?.redraw(surface) }

    fun detachPreview(surface: Surface) { relay?.detachPreview(surface) }

    fun release() {
        if (released) return
        released = true
        runCatching { shell.releaseDisplay(displayId) }
        relay?.release()
        closeLocal()
        _frames.value = null
    }

    private fun closeLocal() {
        released = true
        handler.removeCallbacksAndMessages(null)
        handler.post {
            runCatching { reader.close() }
            synchronized(frameLock) { fullFrame?.recycle(); fullFrame = null; hasFrame = false }
        }
        thread.quitSafely()
    }

    companion object {
        const val PREVIEW_MAX_WIDTH = 360
        /** m2.9.3: Bitmap preview width while the in-app page shows the picture large. */
        const val HIRES_PREVIEW_WIDTH = 1080
        /** PH24 (m2.8): the picture-in-picture was capped at 5 fps; m2.9 follows [userFps]. Kept for reference. */
        const val PREVIEW_FPS = 5
        /** Pre-m2.9 fixed capture interval; now [FpsMath.intervalMs] of [FpsMath.captureFps]. Kept for reference. */
        const val FRAME_INTERVAL_MS = 100L

        /** Same size and density as the main screen, created only when a task first needs it. */
        fun create(context: Context, shell: IHoshinoPhoneShell): AgentDisplay {
            val dm = context.getSystemService(DisplayManager::class.java)
            val display = dm.getDisplay(Display.DEFAULT_DISPLAY)
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION") display.getRealMetrics(metrics)
            val w = minOf(metrics.widthPixels, metrics.heightPixels)
            val h = maxOf(metrics.widthPixels, metrics.heightPixels)
            // m2.9: the GL relay is opt-in (设置 → 设置手机操作 → 实验：零拷贝预览) until it has been verified on devices.
            return AgentDisplay(shell, w, h, metrics.densityDpi, PhoneDisplayFps.of(context).zeroCopy.value)
        }
    }
}
