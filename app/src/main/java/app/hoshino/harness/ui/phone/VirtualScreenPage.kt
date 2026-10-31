package app.hoshino.harness.ui.phone

import android.view.KeyEvent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import app.hoshino.harness.core.Hoshino
import app.hoshino.harness.phone.AutoTakeoverModel
import app.hoshino.harness.phone.BallPanelModel
import app.hoshino.harness.phone.FpsMath
import app.hoshino.harness.phone.InAppTakeover
import app.hoshino.harness.phone.OrientationMode
import app.hoshino.harness.phone.PhoneApps
import app.hoshino.harness.phone.PhoneCapsule
import app.hoshino.harness.phone.PhoneController
import app.hoshino.harness.phone.PhoneDisplayFps
import app.hoshino.harness.phone.PhoneTaskState
import app.hoshino.harness.phone.PhoneTakeover
import app.hoshino.harness.phone.PreviewZoom
import app.hoshino.harness.ui.chat.PhonePipPrefs
import app.hoshino.harness.ui.components.BtnStyle
import app.hoshino.harness.ui.components.EmptyState
import app.hoshino.harness.ui.components.HButton
import app.hoshino.harness.ui.components.HDialog
import app.hoshino.harness.ui.components.HSheet
import app.hoshino.harness.ui.components.HSlider
import app.hoshino.harness.ui.components.HSwitch
import app.hoshino.harness.ui.components.HTextField
import app.hoshino.harness.ui.components.Hairline
import app.hoshino.harness.ui.components.PageScaffold
import app.hoshino.harness.ui.components.Segmented
import app.hoshino.harness.ui.components.toast
import app.hoshino.harness.ui.theme.HIcons
import app.hoshino.harness.ui.theme.HoshinoTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Text for the status line (same wording as the floating ball). */
internal fun phoneStatusLabel(s: PhoneTaskState, manual: Boolean): String = when (s.status) {
    PhoneTaskState.Status.Working -> "正在操作 " + (s.app ?: "")
    PhoneTaskState.Status.NeedsUser -> "需要你处理"
    PhoneTaskState.Status.Approval -> "等你同意"
    PhoneTaskState.Status.TakenOver -> if (manual) "你在操作 · AI 已暂停" else "交给你了"
    PhoneTaskState.Status.Idle -> "空闲"
}

/**
 * m2.9.3: 「虚拟屏」 page — the agent display inside the app, full size: zoom (pinch, double tap, reset), pan, free
 * operation (tap / long press / swipe / text / back, touch = takeover, 4 s idle = hand back, 「接管」 locks), 横屏 / 竖屏 /
 * 跟随 App, frame rate 5–60, stop the task, current step / recent actions / elapsed time. Without a task the display can
 * be started by hand and an app opened on it. Rendering stops while the app is in the background; the floating ball
 * (single tap = panel, double tap = minimize) keeps working and shares the same frame source.
 */
@Composable
fun VirtualScreenPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val c = HoshinoTheme.colors
    val t = HoshinoTheme.type
    val controller = remember { PhoneController.of(context) }
    val takeover = remember { InAppTakeover.of(context) }
    val scope = rememberCoroutineScope()
    val display by controller.display.collectAsState()
    val state by controller.state.collectAsState()
    val manual by controller.manual.active.collectAsState()
    val mode by takeover.mode.collectAsState()
    val notice by takeover.notice.collectAsState()
    val orientation by controller.orientationMode.collectAsState()
    val fps by PhoneDisplayFps.of(context).fps.collectAsState()
    val since by controller.taskSince.collectAsState()
    val handoff by PhoneTakeover.of(context).pending.collectAsState()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val foreground = lifecycle.isAtLeast(Lifecycle.State.STARTED)
    var zoom by remember { mutableStateOf(PreviewZoom.IDENTITY) }
    // m2.9.4: pinch enlarges / shrinks the virtual screen itself (fraction of the space that fits it), not its content.
    val screenPrefs = remember { context.getSharedPreferences("phone_inapp_screen", android.content.Context.MODE_PRIVATE) }
    var screenScale by remember { mutableFloatStateOf(screenPrefs.getFloat("scale", 1f).coerceIn(MIN_SCREEN_SCALE, 1f)) }
    var viewMode by remember { mutableStateOf(false) }
    val doubleTapZoom = false // m2.9.4: double tap goes to the app; pinch resizes the screen
    var showInput by remember { mutableStateOf(false) }
    var showApps by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    var starting by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // While this page is shown the display is not released for idleness; leaving hands back an automatic takeover.
    DisposableEffect(Unit) {
        controller.retainViewer()
        PhoneCapsule.inAppShown()
        onDispose { takeover.leaving(); controller.releaseViewer(); PhoneCapsule.inAppHidden() }
    }
    LaunchedEffect(foreground) { if (!foreground) takeover.leaving() }
    LaunchedEffect(notice) { notice?.let { toast(context, it); takeover.consumeNotice() } }
    LaunchedEffect(since) { while (since != null) { now = System.currentTimeMillis(); delay(1000) } }
    LaunchedEffect(display) { zoom = PreviewZoom.IDENTITY }

    val title = "虚拟屏"
    val subtitle = phoneStatusLabel(state, manual != null) + (since?.let { " · " + BallPanelModel.formatElapsed((now - it).coerceAtLeast(0)) } ?: "")
    PageScaffold(title = title, onBack = onBack, subtitle = subtitle, scrollable = false, actions = {
        if (display != null) HButton(if (screenScale < 0.995f) "复位 ${(screenScale * 100).toInt()}%" else "最大", {
            screenScale = 1f; zoom = PreviewZoom.IDENTITY; screenPrefs.edit().putFloat("scale", 1f).apply()
        }, style = BtnStyle.Ghost, compact = true)
    }) {
        val d = display
        if (d == null) {
            Column(Modifier.fillMaxWidth().weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                EmptyState("虚拟屏没有在运行", subtitle = "AI 操作手机时会自动打开；也可以现在手动启动，在里面打开 App 自己看、自己点。", icon = HIcons.Phone)
                HButton(if (starting) "正在启动…" else "启动虚拟屏", {
                    if (starting) return@HButton
                    starting = true
                    scope.launch {
                        val err = withContext(Dispatchers.IO) { controller.openViewer() }
                        starting = false
                        if (err != null) toast(context, err)
                    }
                }, style = BtnStyle.Primary, enabled = !starting)
            }
            return@PageScaffold
        }
        // Status: current step, last actions.
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            Text("当前：" + (state.recent.lastOrNull() ?: if (state.status == PhoneTaskState.Status.Idle) "无任务" else "等待下一步"), style = t.caption, color = c.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (state.recent.size > 1) Text("最近：" + state.recent.dropLast(1).reversed().joinToString(" · "), style = t.caption, color = c.text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            state.reason?.takeIf { it.isNotBlank() }?.let { Text(it, style = t.caption, color = c.accent, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            handoff?.let { Text("AI 请你处理：${it.reason.ifBlank { "这一步需要你亲手完成" }}（在对话或通知里点「我来处理 / 处理好了」）", style = t.caption, color = c.danger, maxLines = 2) }
        }
        val shape by d.shape.collectAsState()
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
            if (foreground) {
                // The largest size with the display's aspect that fits, times the user's scale.
                val sw = shape.first.coerceAtLeast(1).toFloat(); val sh = shape.second.coerceAtLeast(1).toFloat()
                val fitW = minOf(maxWidth.value, maxHeight.value * sw / sh)
                val fitH = fitW * sh / sw
                VirtualScreenSurface(
                    display = d, zoom = zoom, onZoom = { zoom = it },
                    modifier = Modifier.size((fitW * screenScale).dp, (fitH * screenScale).dp),
                    viewMode = viewMode, doubleTapZoom = doubleTapZoom, hiRes = true,
                    onNotice = { toast(context, it) },
                    onResize = { f -> screenScale = (screenScale * f).coerceIn(MIN_SCREEN_SCALE, 1f) },
                    onResizeEnd = { screenPrefs.edit().putFloat("scale", screenScale).apply() },
                )
            }
        }
        Hairline()
        // Primary actions.
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            HButton(
                when { manual == null -> "接管"; mode == AutoTakeoverModel.Mode.Auto -> "保持接管"; else -> "交还" },
                { takeover.button() },
                style = if (manual != null) BtnStyle.Accent else BtnStyle.Secondary, compact = true,
                enabled = manual != null || handoff == null,
            )
            HButton("输入", { showInput = true }, compact = true)
            HButton("返回键", { if (takeover.ensure()) scope.launch { if (!controller.userKey(KeyEvent.KEYCODE_BACK)) toast(context, "没有送达虚拟屏") } }, compact = true)
            HButton("打开 App", { showApps = true }, compact = true)
            HButton("停止任务", {
                if (controller.manual.active.value != null) controller.endManualTakeover()
                val sid = controller.state.value.sessionId
                controller.stop(sid, "用户在应用内虚拟屏停止")
                if (sid != null) scope.launch(Dispatchers.IO) { runCatching { Hoshino.client.stop(sid) } }
                toast(context, "已停止")
            }, style = BtnStyle.Danger, compact = true, enabled = state.status != PhoneTaskState.Status.Idle || state.sessionId != null)
            HButton(if (showMore) "收起设置" else "画面设置", { showMore = !showMore }, style = BtnStyle.Ghost, compact = true)
        }
        if (showMore) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Segmented(OrientationMode.entries, orientation, { it.label }, { controller.setOrientationMode(it) }, Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("帧率 $fps", style = t.caption, color = c.text2, modifier = Modifier.width(72.dp))
                    Box(Modifier.weight(1f)) { HSlider(fps.toFloat(), { PhoneDisplayFps.of(context).set(it.toInt()) }, FpsMath.MIN_FPS.toFloat()..FpsMath.MAX_FPS.toFloat(), FpsMath.SLIDER_STEPS) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("查看模式（单指拖动画面，不操作手机）", style = t.caption, color = c.text2, modifier = Modifier.weight(1f))
                    HSwitch(viewMode, { viewMode = it })
                }
                Text("双指捏合放大/缩小虚拟屏本身（不是放大画面内容），右上角「复位」回到最大；点「接管」后 AI 暂停，单指点按/长按/滑动才会操作虚拟屏；点「交还」AI 继续。", style = t.caption, color = c.text3)
            }
        }
        Spacer(Modifier.navigationBarsPadding())
    }

    if (showInput) {
        var text by remember { mutableStateOf("") }
        HDialog("输入到虚拟屏", onDismiss = { showInput = false }, confirmText = "发送", confirmEnabled = text.isNotEmpty(), onConfirm = {
            val value = text
            showInput = false
            if (takeover.ensure()) scope.launch { if (!controller.userType(value)) toast(context, "没有送达：先点一下要输入的框") }
        }) {
            Text("先在画面里点中输入框。中文等经剪贴板粘贴，发完立刻还原你的剪贴板；内容不会记录到日志。", style = t.caption, color = c.text3)
            HTextField(text, { text = it }, singleLine = false, minLines = 2, placeholder = "要输入的文字")
        }
    }
    if (showApps) {
        var apps by remember { mutableStateOf<List<PhoneApps.App>>(emptyList()) }
        var query by remember { mutableStateOf("") }
        LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { controller.launcherApps() } }
        HSheet(onDismiss = { showApps = false }, title = "在虚拟屏打开 App") {
            HTextField(query, { query = it }, Modifier.padding(horizontal = 16.dp), placeholder = "搜索", leading = HIcons.Search)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                items(apps.filter { query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true) }, key = { it.packageName }) { app ->
                    Text(app.label, style = t.body, color = c.text, modifier = Modifier.fillMaxWidth().clickable {
                        showApps = false
                        if (takeover.ensure()) scope.launch { if (!controller.userLaunch(app.packageName)) toast(context, "没能在虚拟屏打开 ${app.label}（它可能正开在主屏上）") }
                    }.padding(horizontal = 20.dp, vertical = 12.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * m2.9.3: the chat page's entry while a phone task runs (or the virtual screen exists): status + 「全屏」 (the virtual screen
 * page) + 「小窗」 (toggles the picture-in-picture over the chat).
 */
@Composable
fun PhoneScreenBar(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val c = HoshinoTheme.colors
    val t = HoshinoTheme.type
    val controller = remember { PhoneController.of(context) }
    val display by controller.display.collectAsState()
    val state by controller.state.collectAsState()
    val manual by controller.manual.active.collectAsState()
    val pip by remember { PhonePipPrefs.open(context) }.collectAsState()
    if (display == null && state.status == PhoneTaskState.Status.Idle) return
    Row(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("虚拟屏 · " + phoneStatusLabel(state, manual != null) + (state.recent.lastOrNull()?.let { " · $it" } ?: ""), style = t.caption, color = c.text2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        HButton("全屏", onOpen, compact = true)
        if (display != null) HButton(if (pip) "收起小窗" else "小窗", { PhonePipPrefs.set(context, !pip) }, style = BtnStyle.Ghost, compact = true)
    }
}

private const val MIN_SCREEN_SCALE = 0.25f
