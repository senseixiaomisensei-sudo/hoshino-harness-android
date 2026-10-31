package app.hoshino.harness.ui.phone

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import app.hoshino.harness.phone.AgentDisplay
import app.hoshino.harness.phone.InAppTakeover
import app.hoshino.harness.phone.PhoneController
import app.hoshino.harness.phone.PreviewGeometry
import app.hoshino.harness.phone.PreviewTransform
import app.hoshino.harness.phone.PreviewZoom
import kotlinx.coroutines.launch
import kotlin.math.hypot

/**
 * m2.9.3: the agent display inside the app (the virtual screen page and the chat picture-in-picture).
 *
 * Picture: the zero-copy GL relay draws straight into a TextureView (several surfaces share the one GPU frame, see
 * PreviewRelay), else the shared Bitmap preview ([AgentDisplay.frames], larger while [hiRes]). Both are drawn into
 * [PreviewTransform.viewport] — the m2.9.2 fit rectangle with the in-app [PreviewZoom] on top — and touches are mapped
 * back through the same transform, so picture and input agree within 1 px at every zoom (PreviewZoomTest).
 *
 * Gestures:
 *  - two fingers: pinch to zoom (1×–8×, around the fingers) and drag to pan; never injected;
 *  - one finger (operate mode): tap / long press / swipe are injected into the agent display; the first touch takes over
 *    and pauses the AI ([InAppTakeover], hands back 4 s after the last input unless 「接管」 locked it);
 *  - double tap: fit ⇄ 2.5× ([doubleTapZoom]; then a single tap is injected only after the double-tap timeout);
 *  - one finger in view mode ([viewMode]): drags pan the zoomed picture, nothing is injected.
 * Rendering stops when this leaves the composition (the page goes to the background or the PiP is closed).
 */
@Composable
fun VirtualScreenSurface(
    display: AgentDisplay,
    zoom: PreviewZoom,
    onZoom: (PreviewZoom) -> Unit,
    modifier: Modifier = Modifier,
    viewMode: Boolean = false,
    doubleTapZoom: Boolean = true,
    hiRes: Boolean = true,
    onNotice: (String) -> Unit = {},
    /**
     * m2.9.4: when set, a two-finger pinch resizes the virtual screen window itself (factor since the last event) instead
     * of magnifying the picture inside it; double tap no longer zooms the content either.
     */
    onResize: ((Float) -> Unit)? = null,
    onResizeEnd: () -> Unit = {},
) {
    val context = LocalContext.current
    val controller = remember { PhoneController.of(context) }
    val takeover = remember { InAppTakeover.of(context) }
    val scope = rememberCoroutineScope()
    val shape by display.shape.collectAsState()
    var size by remember { mutableStateOf(IntSize.Zero) }
    val currentZoom by rememberUpdatedState(zoom)
    val currentView by rememberUpdatedState(viewMode)
    val currentResize by rememberUpdatedState(onResize)
    val currentResizeEnd by rememberUpdatedState(onResizeEnd)
    val currentDoubleTap by rememberUpdatedState(doubleTapZoom && onResize == null)
    // Read by the GL thread for every drawn frame: always the latest shape and zoom.
    val holder = remember(display) { ViewportHolder() }
    holder.srcW = shape.first; holder.srcH = shape.second; holder.zoom = zoom
    var zeroCopy by remember(display) { mutableStateOf(display.zeroCopy) }
    var liveSurface by remember(display) { mutableStateOf<Surface?>(null) }

    fun transform(): PreviewTransform = PreviewTransform(display.width, display.height, size.width, size.height, currentZoom)

    fun inject(block: suspend () -> Boolean) { scope.launch { if (!block()) onNotice("没有送达虚拟屏") } }

    Box(
        modifier
            .clipToBounds()
            .background(Color(0xFF0B0B0E))
            .onSizeChanged { size = it }
            .pointerInput(display) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val slop = viewConfiguration.touchSlop
                    val startT = transform()
                    val hit = startT.toDisplay(down.position.x, down.position.y)
                    val operate = !currentView && hit != null
                    var taken = operate && takeover.touchDown()
                    val xs = ArrayList<Int>(); val ys = ArrayList<Int>(); val ts = ArrayList<Long>()
                    if (hit != null) { xs += hit.first; ys += hit.second; ts += 0L }
                    var moved = 0f
                    var multi = false
                    var upTime = down.uptimeMillis
                    var last = down.position
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) { upTime = event.changes.firstOrNull()?.uptimeMillis ?: upTime; event.changes.forEach { it.consume() }; break }
                        if (pressed.size >= 2) {
                            multi = true
                            val factor = event.calculateZoom()
                            val resize = currentResize
                            if (resize != null) {
                                if (factor.isFinite() && factor > 0f) resize(factor)
                                event.changes.forEach { it.consume() }
                                continue
                            }
                            val pan = event.calculatePan()
                            val c = event.calculateCentroid(useCurrent = true)
                            if (c != Offset.Unspecified && size.width > 0) {
                                val fit = PreviewGeometry.fitViewport(display.width, display.height, size.width, size.height)
                                onZoom(PreviewZoom.transform(currentZoom, c.x, c.y, factor, pan.x, pan.y, fit, size.width, size.height))
                            }
                            event.changes.forEach { it.consume() }
                            if (taken) takeover.activity()
                            continue
                        }
                        val p = pressed.first()
                        if (multi) { last = p.position; p.consume(); continue }
                        moved = maxOf(moved, hypot(p.position.x - down.position.x, p.position.y - down.position.y))
                        if (currentView) {
                            val d = p.positionChange()
                            if (currentZoom.zoomed && size.width > 0) {
                                val fit = PreviewGeometry.fitViewport(display.width, display.height, size.width, size.height)
                                onZoom(PreviewZoom.pan(currentZoom, d.x, d.y, fit, size.width, size.height))
                            }
                        } else if (hit != null) {
                            val (x, y) = transform().toDisplayClamped(p.position.x, p.position.y)
                            xs += x; ys += y; ts += p.uptimeMillis - down.uptimeMillis
                        }
                        last = p.position
                        p.consume()
                    }
                    if (multi && currentResize != null) currentResizeEnd()
                    val duration = upTime - down.uptimeMillis
                    val gesture = PreviewGeometry.classify(moved, duration, slop)
                    // A tap may be the first half of a double tap (zoom): wait for a second press near it.
                    if (!multi && gesture == PreviewGeometry.Gesture.Tap && (currentDoubleTap || currentView)) {
                        val second = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) { awaitFirstDown(requireUnconsumed = false) }
                        if (second != null && hypot(second.position.x - down.position.x, second.position.y - down.position.y) < slop * 4) {
                            // Swallow the second tap and toggle the zoom; nothing is injected.
                            while (true) { val e = awaitPointerEvent(); e.changes.forEach { it.consume() }; if (e.changes.none { it.pressed }) break }
                            if (size.width > 0) {
                                val fit = PreviewGeometry.fitViewport(display.width, display.height, size.width, size.height)
                                onZoom(PreviewZoom.toggle(currentZoom, down.position.x, down.position.y, fit, size.width, size.height))
                            }
                            if (taken) takeover.touchUp()
                            return@awaitEachGesture
                        }
                    }
                    if (operate && taken && !multi && xs.isNotEmpty()) {
                        val x0 = xs.first(); val y0 = ys.first()
                        when (gesture) {
                            PreviewGeometry.Gesture.Tap -> inject { controller.userTap(x0, y0) }
                            PreviewGeometry.Gesture.LongPress -> inject { controller.userLongPress(x0, y0, duration) }
                            PreviewGeometry.Gesture.Swipe -> if (xs.size >= 2) {
                                val (sx, sy, st) = PreviewGeometry.thinStroke(xs.toIntArray(), ys.toIntArray(), ts.toLongArray())
                                inject { controller.userSwipe(sx, sy, st) }
                            }
                        }
                    }
                    if (taken) takeover.touchUp()
                    taken = false
                }
            },
    ) {
        if (zeroCopy) {
            AndroidView(
                factory = { ctx ->
                    TextureView(ctx).apply {
                        isOpaque = true
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                                val s = Surface(st)
                                if (display.attachPreview(s) { vw, vh -> holder.viewport(vw, vh) }) liveSurface = s
                                else { s.release(); zeroCopy = false }
                            }
                            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) { liveSurface?.let { display.redrawPreview(it) } }
                            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                liveSurface?.let { display.detachPreview(it); it.release() }
                                liveSurface = null
                                return true
                            }
                            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            // Zoom / shape changed while the screen itself is static: draw the current frame again.
            LaunchedEffect(zoom, shape) { liveSurface?.let { display.redrawPreview(it) } }
            DisposableEffect(display) { onDispose { liveSurface?.let { display.detachPreview(it); it.release() }; liveSurface = null } }
        } else {
            DisposableEffect(display, hiRes) {
                display.previewWatchers++
                if (hiRes) display.hiResWatchers++
                onDispose {
                    display.previewWatchers = (display.previewWatchers - 1).coerceAtLeast(0)
                    if (hiRes) display.hiResWatchers = (display.hiResWatchers - 1).coerceAtLeast(0)
                }
            }
            val frame by display.frames.collectAsState()
            val first = remember(display) { display.previewNow() }
            Canvas(Modifier.fillMaxSize()) {
                val bmp = frame ?: first
                if (bmp != null && !bmp.isRecycled) {
                    val vp = PreviewTransform(shape.first, shape.second, this.size.width.toInt(), this.size.height.toInt(), zoom).viewport
                    drawImage(
                        bmp.asImageBitmap(),
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(bmp.width, bmp.height),
                        dstOffset = IntOffset(vp.x, vp.y),
                        dstSize = IntSize(vp.w, vp.h),
                    )
                }
            }
        }
    }
}

/** Shape + zoom for the GL thread (written on the main thread, read when a frame is drawn). */
private class ViewportHolder {
    @Volatile var srcW = 0
    @Volatile var srcH = 0
    @Volatile var zoom: PreviewZoom = PreviewZoom.IDENTITY
    fun viewport(viewW: Int, viewH: Int) = PreviewTransform(srcW, srcH, viewW, viewH, zoom).viewport
}
