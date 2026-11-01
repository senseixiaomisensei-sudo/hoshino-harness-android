// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
// (ui/VoiceHold.kt：按住说话的手势、浮在输入栏上的听写卡片、权限申请；改写为本项目的 Compose 组件。)
package app.hoshino.harness.ui.chat

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.hoshino.harness.ui.components.HDialog
import app.hoshino.harness.ui.components.HIcon
import app.hoshino.harness.ui.components.hairlineCard
import app.hoshino.harness.ui.theme.HIcons
import app.hoshino.harness.ui.theme.HoshinoTheme
import app.hoshino.harness.voice.VoiceCopy
import app.hoshino.harness.voice.VoiceInput
import dev.hoshino.bridge.voice.HoldToTalk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 输入栏按住说话的控制器：一个 [HoldToTalk] + 会话事件收集 + 计时。只在主线程用。 */
@Stable
class VoiceHoldController(private val voice: VoiceInput, private val scope: CoroutineScope) {
    val hold = HoldToTalk(clock = { android.os.SystemClock.elapsedRealtime() })
    private var collect: Job? = null
    private var ticker: Job? = null

    /** 按下：开会话。返回 false 时不进入按住状态（例如还没有权限）。 */
    fun press(): Boolean {
        val session = voice.start(this)
        hold.press(session)
        collect?.cancel()
        collect = scope.launch { session.events.collect { hold.onEvent(it) } }
        ticker?.cancel()
        ticker = scope.launch { while (true) { delay(100); hold.tick(); if (hold.state.value !is HoldToTalk.State.Listening) break } }
        return true
    }

    fun drag(upPx: Float, thresholdPx: Float) = hold.drag(upPx, thresholdPx)
    fun release() = hold.release()

    fun dispose() {
        collect?.cancel(); ticker?.cancel()
        hold.dispose()
    }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/**
 * 输入栏的麦克风按钮：按住说话，上滑取消，松开交给 [onDone]（文字、是否拖到了改一改）。
 * 第一次按时才申请录音权限；被拒且不再询问时弹窗指去系统设置。离开页面或 App 到后台时丢掉这一段。
 */
@Composable
fun rememberVoiceHold(onDone: (text: String, edit: Boolean) -> Unit, onProblem: (String) -> Unit): VoiceHoldController {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember { VoiceHoldController(VoiceInput.of(ctx), scope) }
    val done by rememberUpdatedState(onDone)
    val problem by rememberUpdatedState(onProblem)
    val state by controller.hold.state.collectAsState()
    LaunchedEffect(state) {
        when (val s = state) {
            is HoldToTalk.State.Done -> { done(s.text, s.edit); controller.hold.reset() }
            is HoldToTalk.State.Problem -> { problem(VoiceCopy.failure(s.why)); controller.hold.reset() }
            else -> Unit
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) controller.dispose() }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs); controller.dispose() }
    }
    return controller
}

@Composable
fun VoiceMicButton(controller: VoiceHoldController, onProblem: (String) -> Unit) {
    val ctx = LocalContext.current
    val c = HoshinoTheme.colors
    val voice = remember { VoiceInput.of(ctx) }
    var askSettings by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onProblem("已允许麦克风，按住说话吧")
        else {
            val act = ctx.activity()
            // 拒绝且勾了不再询问：系统不会再弹，只能去设置里开
            if (act != null && !act.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) askSettings = true
            else onProblem(VoiceCopy.PERMISSION_DENIED)
        }
    }
    val state by controller.hold.state.collectAsState()
    val active = state is HoldToTalk.State.Listening || state is HoldToTalk.State.Converting
    val threshold = with(LocalDensity.current) { 72.dp.toPx() }
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (active) c.accentSoft else c.surface)
            .semantics { contentDescription = VoiceCopy.HOLD_TO_TALK }
            .pointerInput(controller) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    if (!voice.hasPermission()) {
                        permission.launch(Manifest.permission.RECORD_AUDIO)
                        return@awaitEachGesture
                    }
                    controller.press()
                    var released = false
                    try {
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) { controller.release(); released = true; break }
                            controller.drag(down.position.y - ch.position.y, threshold)
                            ch.consume()
                        }
                    } catch (e: CancellationException) {
                        if (!released) controller.dispose()
                        throw e
                    }
                    if (!released) controller.release()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        HIcon(HIcons.Mic, size = 20.dp, tint = if (active) c.accent else c.text2)
    }
    if (askSettings) {
        HDialog(
            title = "需要麦克风权限",
            onDismiss = { askSettings = false },
            confirmText = "去设置",
            onConfirm = {
                askSettings = false
                runCatching { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            },
        ) { Text(VoiceCopy.PERMISSION_DENIED, style = HoshinoTheme.type.body, color = HoshinoTheme.colors.text2) }
    }
}

/** 按着时浮在输入栏上的听写卡片：边说边出字（末尾几个字淡一些）、音量、计时、松手去向、提示。 */
@Composable
fun VoiceFloat(controller: VoiceHoldController, running: Boolean) {
    val c = HoshinoTheme.colors
    val t = HoshinoTheme.type
    val state by controller.hold.state.collectAsState()
    val s = state
    if (s !is HoldToTalk.State.Listening && s !is HoldToTalk.State.Converting) return
    val cancel = s is HoldToTalk.State.Listening && s.cancelArmed
    Column(
        Modifier.fillMaxWidth().padding(bottom = 8.dp)
            .hairlineCard(if (cancel) c.danger.copy(alpha = 0.08f) else c.surface, if (cancel) c.danger else c.line)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    s is HoldToTalk.State.Converting -> if (s.slow) VoiceCopy.CONVERTING_SLOW else VoiceCopy.CONVERTING
                    running -> "正在听 · 插话给当前任务"
                    else -> "正在听"
                },
                style = t.caption, color = if (cancel) c.danger else c.text2, modifier = Modifier.weight(1f),
            )
            if (s is HoldToTalk.State.Listening) {
                Levels(s.level, if (cancel) c.danger else c.accent)
                Spacer(Modifier.width(8.dp))
                Text(VoiceCopy.elapsed(s.elapsedMs), style = t.caption, color = c.text3)
            }
        }
        Spacer(Modifier.height(6.dp))
        val text = when (s) {
            is HoldToTalk.State.Listening -> buildAnnotatedString {
                append(s.stable)
                withStyle(SpanStyle(color = c.text3)) { append(s.tail) }
            }
            is HoldToTalk.State.Converting -> buildAnnotatedString { append(s.soFar) }
            else -> buildAnnotatedString {}
        }
        Text(if (text.isEmpty()) buildAnnotatedString { withStyle(SpanStyle(color = c.text3)) { append("请说…") } } else text, style = t.body, color = c.text, modifier = Modifier.heightIn(min = 22.dp))
        if (s is HoldToTalk.State.Listening) {
            s.hint?.let { Text(VoiceCopy.hint(it), style = t.caption, color = c.danger, modifier = Modifier.padding(top = 4.dp)) }
            Text(
                if (cancel) VoiceCopy.RELEASE_TO_CANCEL else if (running) VoiceCopy.RELEASE_TO_INTERJECT else VoiceCopy.RELEASE_TO_FILL,
                style = t.caption, color = if (cancel) c.danger else c.text3, modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun Levels(level: Float, color: androidx.compose.ui.graphics.Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        val weights = listOf(0.5f, 0.8f, 1f, 0.8f, 0.5f)
        for (w in weights) {
            val h = (4f + 14f * (level * w).coerceIn(0f, 1f)).dp
            Box(Modifier.width(3.dp).height(h).clip(RoundedCornerShape(2.dp)).background(color))
        }
    }
}

