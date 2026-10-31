// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package app.hoshino.harness.ui.chat

import android.content.Context
import android.content.res.Configuration
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import app.hoshino.harness.phone.InAppTakeover
import app.hoshino.harness.phone.PhoneController
import app.hoshino.harness.phone.PhoneTaskState
import app.hoshino.harness.phone.PipLayout
import app.hoshino.harness.phone.PreviewZoom
import app.hoshino.harness.ui.components.toast
import app.hoshino.harness.ui.phone.VirtualScreenSurface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/** Whether the chat picture-in-picture is open (「虚拟屏 · 小窗」 toggles it), persisted. */
object PhonePipPrefs {
    @Volatile private var flow: MutableStateFlow<Boolean>? = null
    private fun prefs(context: Context) = context.getSharedPreferences("phone_pip", Context.MODE_PRIVATE)
    private fun flow(context: Context): MutableStateFlow<Boolean> =
        flow ?: synchronized(this) { flow ?: MutableStateFlow(prefs(context).getBoolean("open", true)).also { flow = it } }
    fun open(context: Context): StateFlow<Boolean> = flow(context).asStateFlow()
    fun set(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("open", value).apply()
        flow(context).value = value
    }
}

/**
 * Chat-page picture-in-picture of the agent display (PH24; m2.9.3 rewritten): a small window over the chat with the live
 * picture — operable like the full page (one finger operates and takes over, two fingers zoom/pan, double tap toggles
 * the zoom). Drag it by its title strip, resize it with the bottom-right handle (aspect follows the display); position and
 * width are remembered separately for portrait and landscape (of the app window). 「⤢」 opens the full virtual screen page,
 * 「×」 closes the window (the chat bar's 「小窗」 brings it back). Green border while the AI works, breathing orange when you
 * are needed. Shown only while the agent display exists; rendering stops in the background.
 */
@Composable
fun PhonePip(modifier: Modifier = Modifier, onExpand: () -> Unit = {}) {
    val context = LocalContext.current
    val controller = remember { PhoneController.of(context) }
    val takeover = remember { InAppTakeover.of(context) }
    val display by controller.display.collectAsState()
    val state by controller.state.collectAsState()
    val manual by controller.manual.active.collectAsState()
    val open by remember { PhonePipPrefs.open(context) }.collectAsState()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val d = display ?: return
    if (!open || !lifecycle.isAtLeast(Lifecycle.State.STARTED)) return
    DisposableEffect(d) {
        controller.retainViewer()
        onDispose { controller.releaseViewer() }
    }
    val shape by d.shape.collectAsState()
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val prefs = remember { context.getSharedPreferences("phone_pip", Context.MODE_PRIVATE) }
    val key = if (landscape) "land" else "port"
    val density = LocalDensity.current
    var zoom by remember { mutableStateOf(PreviewZoom.IDENTITY) }
    val needsYou = state.status == PhoneTaskState.Status.NeedsUser || state.status == PhoneTaskState.Status.Approval || state.status == PhoneTaskState.Status.TakenOver
    val pulse by rememberInfiniteTransition(label = "pip").animateFloat(1f, if (needsYou) 0.35f else 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse")
    val borderColor = when {
        needsYou -> Color(0xFFFB8C00).copy(alpha = pulse)
        state.status == PhoneTaskState.Status.Working -> Color(0xFF43A047)
        else -> Color(0x668E8E93)
    }
    val notice by takeover.notice.collectAsState()
    LaunchedEffect(notice) { notice?.let { toast(context, it); takeover.consumeNotice() } }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val areaW = constraints.maxWidth
        val areaH = constraints.maxHeight
        val headerPx = with(density) { HEADER_DP.dp.roundToPx() }
        val minW = with(density) { PipLayout.MIN_DP.dp.roundToPx() }
        val limits = PipLayout.Limits(minW, areaW, areaH, headerPx)
        var w by remember(key, areaW, areaH) { mutableFloatStateOf(prefs.getFloat("w_$key", with(density) { PipLayout.DEFAULT_DP.dp.toPx() })) }
        var x by remember(key, areaW, areaH) { mutableFloatStateOf(prefs.getFloat("x_$key", Float.NaN)) }
        var y by remember(key, areaW, areaH) { mutableFloatStateOf(prefs.getFloat("y_$key", Float.NaN)) }
        val win = PipLayout.place(w, x, y, shape.first, shape.second, limits)
        val curWin by rememberUpdatedState(win)
        val curLimits by rememberUpdatedState(limits)
        fun save() { val v = curWin; prefs.edit().putFloat("w_$key", v.w.toFloat()).putFloat("x_$key", v.x.toFloat()).putFloat("y_$key", v.y.toFloat()).apply() }
        Column(
            Modifier
                .offset { IntOffset(win.x, win.y) }
                .size(with(density) { win.w.toDp() }, with(density) { (win.pictureH + headerPx).toDp() })
                .clip(RoundedCornerShape(12.dp))
                .border(2.dp, borderColor, RoundedCornerShape(12.dp))
                .background(Color(0xFF111114)),
        ) {
            // Title strip: drag to move; ⤢ = full page; × = close.
            Row(
                Modifier.fillMaxWidth().height(HEADER_DP.dp).background(Color(0xFF1B1B22))
                    .pointerInput(key) {
                        detectDragGestures(onDragEnd = { save() }) { change, amount ->
                            change.consume()
                            val p = PipLayout.place(w, curWin.x + amount.x, curWin.y + amount.y, shape.first, shape.second, curLimits)
                            x = p.x.toFloat(); y = p.y.toFloat()
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (manual != null) "你在操作" else if (needsYou) "要你" else "虚拟屏", color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(start = 8.dp).weight(1f), maxLines = 1)
                Text("⤢", color = Color.White, fontSize = 14.sp, modifier = Modifier.clickable { onExpand() }.padding(horizontal = 8.dp))
                Text("×", color = Color.White, fontSize = 16.sp, modifier = Modifier.clickable { PhonePipPrefs.set(context, false) }.padding(horizontal = 8.dp))
            }
            Box(Modifier.fillMaxWidth().height(with(density) { win.pictureH.toDp() })) {
                VirtualScreenSurface(display = d, zoom = zoom, onZoom = { zoom = it }, modifier = Modifier.fillMaxSize(), hiRes = false, onNotice = { toast(context, it) })
                // Bottom-right resize handle.
                Box(
                    Modifier.align(Alignment.BottomEnd).size(28.dp)
                        .pointerInput(key) {
                            detectDragGestures(onDragEnd = { save() }) { change, amount ->
                                change.consume()
                                // Accumulate the raw width so slow drags are not lost to rounding; place() clamps it.
                                w = PipLayout.resizeWidth(w, amount.x, amount.y, shape.first, shape.second, curWin.x, curWin.y, curLimits)
                                x = curWin.x.toFloat(); y = curWin.y.toFloat()
                            }
                        },
                    contentAlignment = Alignment.BottomEnd,
                ) { Text("◢", color = Color(0xCCFFFFFF), fontSize = 14.sp, modifier = Modifier.padding(2.dp)) }
            }
        }
    }
}

private const val HEADER_DP = 22
