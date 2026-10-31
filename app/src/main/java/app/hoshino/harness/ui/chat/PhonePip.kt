// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package app.hoshino.harness.ui.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hoshino.harness.phone.PhoneController
import app.hoshino.harness.phone.PhoneTaskState
import kotlin.math.roundToInt

/**
 * Chat-page picture-in-picture of the agent display (PH24): draggable, snaps to the nearest side, green
 * border while Hoshino works, breathing orange when you are needed. Frames come from the same decoder as the
 * floating ball (at the user's frame rate from 设置 → 设置手机操作, only while this is on screen). Tap to collapse to a small badge. Shown only while the
 * agent display exists. Mounted by one line in ChatScreen.
 */
@Composable
fun PhonePip(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val controller = remember { PhoneController.of(context) }
    val display by controller.display.collectAsState()
    val state by controller.state.collectAsState()
    val d = display ?: return
    DisposableEffect(d) {
        d.previewWatchers++
        onDispose { d.previewWatchers = (d.previewWatchers - 1).coerceAtLeast(0) }
    }
    val frame by d.frames.collectAsState()
    var collapsed by remember { mutableStateOf(false) }
    var dx by remember { mutableFloatStateOf(0f) }
    var dy by remember { mutableFloatStateOf(0f) }
    var right by remember { mutableStateOf(true) }
    val needsYou = state.status == PhoneTaskState.Status.NeedsUser || state.status == PhoneTaskState.Status.Approval || state.status == PhoneTaskState.Status.TakenOver
    val pulse by rememberInfiniteTransition(label = "pip").animateFloat(1f, if (needsYou) 0.35f else 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse")
    val borderColor = when {
        needsYou -> Color(0xFFFB8C00).copy(alpha = pulse)
        state.status == PhoneTaskState.Status.Working -> Color(0xFF43A047)
        else -> Color(0x668E8E93)
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val w = if (collapsed) 56.dp else 112.dp
        val maxX = with(density) { (maxWidth - w - 24.dp).toPx() }
        Box(
            Modifier
                .align(if (right) Alignment.TopEnd else Alignment.TopStart)
                .padding(12.dp)
                .offset { IntOffset(dx.roundToInt(), dy.roundToInt()) }
                .width(w)
                .clip(RoundedCornerShape(12.dp))
                .border(2.dp, borderColor, RoundedCornerShape(12.dp))
                .background(Color(0xFF111114))
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragEnd = {
                            // Snap: crossing half the width flips the side; vertical position is kept.
                            if (right && dx < -maxX / 2) right = false else if (!right && dx > maxX / 2) right = true
                            dx = 0f
                        },
                    ) { change, amount -> change.consume(); dx += amount.x; dy = (dy + amount.y).coerceAtLeast(0f) }
                }
                .clickable { collapsed = !collapsed },
        ) {
            val bmp = frame
            if (!collapsed && bmp != null && !bmp.isRecycled) {
                Image(bmp.asImageBitmap(), contentDescription = "Hoshino 正在操作的后台屏", contentScale = ContentScale.FillWidth, modifier = Modifier.alpha(0.98f))
            } else {
                Text(if (needsYou) "要你" else "手机", color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(10.dp).align(Alignment.Center))
            }
        }
    }
}
