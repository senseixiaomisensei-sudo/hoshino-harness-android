package app.hoshino.harness.phone

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Zero-copy shared surface for the agent display (A-2 / floating ball). The helper renders the VirtualDisplay into
 * [input] (a SurfaceTexture on our own GL thread) instead of straight into the ImageReader. Each new frame stays on
 * the GPU and is blitted:
 *  - into the ImageReader surface at the capture rate ([captureIntervalMs]) — OCR, screenshots and WP-1's frame
 *    signature keep reading the same CPU frames as before, nothing changes for them;
 *  - into the floating panel's TextureView surface at the preview rate ([previewIntervalMs]), only while one is
 *    attached. No CPU copy and no Bitmap are involved, the GPU scales to whatever size the user dragged the window to.
 * A frame that arrives before its target is due is not dropped: the newest one is drawn when the interval expires,
 * so a screen that stops changing still ends up captured and shown.
 *
 * If EGL setup fails on a ROM, [create] returns null and [AgentDisplay] falls back to rendering into the ImageReader
 * directly with Bitmap previews (the pre-m2.9 path).
 */
class PreviewRelay private constructor(
    private val width: Int,
    private val height: Int,
    private val captureIntervalMs: () -> Long,
    private val previewIntervalMs: () -> Long,
) {
    private val thread = HandlerThread("hoshino-preview-relay").apply { start() }
    private val handler = Handler(thread.looper)
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var idleSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var captureSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    /**
     * m2.9.3: every attached preview (floating panel, in-app page, in-app picture-in-picture) is drawn from the same GPU
     * frame — one VirtualDisplay, one texture, no second encode. Each has its own [Target.viewport] (its zoom/pan).
     */
    private class Target(val surface: Surface, val egl: EGLSurface, val viewport: ((Int, Int) -> PxRect)?) {
        var dirty = false
        var lastAt = 0L
    }
    private val targets = ArrayList<Target>()
    private var texId = 0
    private var program = 0
    private var aPos = 0
    private var aTex = 0
    private var uTex = 0
    private var texture: SurfaceTexture? = null
    private val matrix = FloatArray(16)
    private val quad: FloatBuffer = floats(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val uv: FloatBuffer = floats(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
    private var hasFrame = false
    private var captureDirty = false
    private var lastCaptureAt = 0L
    private val pumpTask = Runnable { pump() }
    @Volatile private var released = false

    /** Surface handed to the helper's createDisplay. */
    lateinit var input: Surface
        private set

    /** A preview surface is attached and being drawn. */
    @Volatile var previewAttached = false
        private set

    /** Preview frames drawn since creation (for the on-device fps check). */
    @Volatile var previewFrames = 0L
        private set

    private fun setup(captureTarget: Surface) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "no EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "eglInitialize" }
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, count, 0) && count[0] > 0) { "eglChooseConfig" }
        eglConfig = configs[0]
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext" }
        captureSurface = windowSurface(captureTarget)
        check(captureSurface != EGL14.EGL_NO_SURFACE) { "capture surface" }
        idleSurface = captureSurface
        check(EGL14.eglMakeCurrent(eglDisplay, captureSurface, captureSurface, eglContext)) { "eglMakeCurrent" }
        EGL14.eglSwapInterval(eglDisplay, 0)
        program = buildProgram()
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aTex = GLES20.glGetAttribLocation(program, "aTex")
        uTex = GLES20.glGetUniformLocation(program, "uTex")
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        texId = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        check(GLES20.glGetError() == GLES20.GL_NO_ERROR) { "texture setup" }
        val st = SurfaceTexture(texId)
        st.setDefaultBufferSize(width, height)
        st.setOnFrameAvailableListener({ onFrame() }, handler)
        texture = st
        input = Surface(st)
    }

    private fun onFrame() {
        if (released) return
        val st = texture ?: return
        try {
            if (!EGL14.eglMakeCurrent(eglDisplay, idleSurface, idleSurface, eglContext)) return
            st.updateTexImage()
            st.getTransformMatrix(matrix)
        } catch (t: Throwable) {
            Log.w(TAG, "updateTexImage failed: ${t.javaClass.simpleName}")
            return
        }
        hasFrame = true
        captureDirty = true
        for (t in targets) t.dirty = true
        pump()
    }

    private fun pump() {
        handler.removeCallbacks(pumpTask)
        if (released || !hasFrame) return
        val now = SystemClock.uptimeMillis()
        var next = Long.MAX_VALUE
        if (captureDirty) {
            val due = lastCaptureAt + captureIntervalMs()
            if (now >= due) {
                draw(captureSurface, fit = false)
                lastCaptureAt = now
                captureDirty = false
            } else next = minOf(next, due)
        }
        val interval = previewIntervalMs()
        if (interval > 0) for (t in targets) {
            if (!t.dirty) continue
            val due = t.lastAt + interval
            if (now >= due) {
                if (draw(t.egl, fit = true, t.viewport)) previewFrames++
                t.lastAt = now
                t.dirty = false
            } else next = minOf(next, due)
        }
        if (next != Long.MAX_VALUE) handler.postAtTime(pumpTask, next)
    }

    private fun draw(target: EGLSurface, fit: Boolean, viewport: ((Int, Int) -> PxRect)? = null): Boolean {
        if (!EGL14.eglMakeCurrent(eglDisplay, target, target, eglContext)) return false
        val size = IntArray(2)
        EGL14.eglQuerySurface(eglDisplay, target, EGL14.EGL_WIDTH, size, 0)
        EGL14.eglQuerySurface(eglDisplay, target, EGL14.EGL_HEIGHT, size, 1)
        // m2.9.3: a target may supply its own rectangle (in-app zoom/pan through the same PreviewTransform as its touches).
        val vp = if (!fit) PxRect(0, 0, size[0], size[1]) else viewport?.invoke(size[0], size[1]) ?: PreviewGeometry.fitViewport(width, height, size[0], size[1])
        GLES20.glViewport(0, 0, size[0], size[1])
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        // GL's origin is bottom-left; the letterbox is centred, so only its y has to be flipped.
        GLES20.glViewport(vp.x, size[1] - vp.y - vp.h, vp.w, vp.h)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glUniformMatrix4fv(uTex, 1, false, matrix, 0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, uv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        return EGL14.eglSwapBuffers(eglDisplay, target)
    }

    /**
     * Attaches a preview surface (a TextureView's). Several may be attached at once (m2.9.3); attaching the same surface
     * again replaces it. [viewport] (surface width, height) → picture rectangle; null = fit-centre. Blocks until done;
     * false when EGL refused it.
     */
    fun attachPreview(surface: Surface, viewport: ((Int, Int) -> PxRect)? = null): Boolean = onThread {
        if (released) return@onThread false
        dropPreview(surface)
        val s = windowSurface(surface)
        if (s == EGL14.EGL_NO_SURFACE) return@onThread false
        if (EGL14.eglMakeCurrent(eglDisplay, s, s, eglContext)) EGL14.eglSwapInterval(eglDisplay, 0)
        targets += Target(surface, s, viewport).also { it.dirty = hasFrame }
        previewAttached = true
        pump()
        true
    } ?: false

    /** Detaches it again; must complete before the TextureView frees its SurfaceTexture, so it blocks (bounded). */
    fun detachPreview(surface: Surface) {
        onThread { dropPreview(surface) }
    }

    /** m2.9.3: redraw [surface] from the current frame (its zoom changed while the screen itself did not). */
    fun redraw(surface: Surface) {
        if (released) return
        handler.post { targets.firstOrNull { it.surface === surface }?.let { it.dirty = hasFrame; it.lastAt = 0L; pump() } }
    }

    private fun dropPreview(surface: Surface? = null) {
        val gone = targets.filter { surface == null || it.surface === surface }
        if (gone.isNotEmpty()) EGL14.eglMakeCurrent(eglDisplay, idleSurface, idleSurface, eglContext)
        for (t in gone) EGL14.eglDestroySurface(eglDisplay, t.egl)
        targets.removeAll(gone.toSet())
        previewAttached = targets.isNotEmpty()
    }

    fun release() {
        if (released) return
        released = true
        onThread {
            handler.removeCallbacksAndMessages(null)
            runCatching { dropPreview() }
            runCatching { texture?.setOnFrameAvailableListener(null); texture?.release(); texture = null }
            if (::input.isInitialized) runCatching { input.release() }
            runCatching { teardownEgl() }
        }
        thread.quitSafely()
    }

    private fun teardownEgl() {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return
        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (captureSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, captureSurface)
        captureSurface = EGL14.EGL_NO_SURFACE
        idleSurface = EGL14.EGL_NO_SURFACE
        if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
        eglContext = EGL14.EGL_NO_CONTEXT
        // No eglTerminate: the default display is shared with the UI renderer in this process.
        EGL14.eglReleaseThread()
        eglDisplay = EGL14.EGL_NO_DISPLAY
    }

    private fun windowSurface(target: Surface): EGLSurface =
        runCatching { EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, target, intArrayOf(EGL14.EGL_NONE), 0) }.getOrNull() ?: EGL14.EGL_NO_SURFACE

    private fun <T> onThread(timeoutMs: Long = 1_000L, block: () -> T): T? {
        if (Looper.myLooper() == thread.looper) return block()
        val latch = CountDownLatch(1)
        var out: T? = null
        var error: Throwable? = null
        if (!handler.post { try { out = block() } catch (t: Throwable) { error = t } finally { latch.countDown() } }) return null
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) return null
        error?.let { Log.w(TAG, "relay call failed: ${it.javaClass.simpleName}"); return null }
        return out
    }

    private fun buildProgram(): Int {
        fun shader(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "shader compile" }
            return s
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, VERTEX))
        GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "program link" }
        return p
    }

    companion object {
        private const val TAG = "HoshinoRelay"
        private const val EGL_RECORDABLE_ANDROID = 0x3142
        private const val VERTEX = "attribute vec4 aPos;\nattribute vec4 aTex;\nuniform mat4 uTex;\nvarying vec2 vTex;\nvoid main() { gl_Position = aPos; vTex = (uTex * aTex).xy; }\n"
        private const val FRAGMENT = "#extension GL_OES_EGL_image_external : require\nprecision mediump float;\nvarying vec2 vTex;\nuniform samplerExternalOES sTex;\nvoid main() { gl_FragColor = texture2D(sTex, vTex); }\n"

        private fun floats(vararg v: Float): FloatBuffer =
            ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(v); position(0) }

        /** Null when this device's EGL cannot do it; the caller then renders straight into [captureTarget]. */
        fun create(width: Int, height: Int, captureTarget: Surface, captureIntervalMs: () -> Long, previewIntervalMs: () -> Long): PreviewRelay? {
            val relay = PreviewRelay(width, height, captureIntervalMs, previewIntervalMs)
            val ok = relay.onThread(2_000L) { relay.setup(captureTarget); true } == true
            if (!ok) {
                Log.w(TAG, "zero-copy relay unavailable, falling back to ImageReader previews")
                relay.release()
                return null
            }
            return relay
        }
    }
}
