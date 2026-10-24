package app.hoshino.harness.ui.tools

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hoshino.harness.core.InspectorNode
import app.hoshino.harness.core.TerminalStream
import app.hoshino.harness.core.TrajSource
import app.hoshino.harness.ui.ConfigViewModel
import app.hoshino.harness.ui.chat.ChatViewModel
import app.hoshino.harness.ui.chat.MessageActions
import app.hoshino.harness.ui.chat.MessageList
import app.hoshino.harness.ui.components.EmptyState
import app.hoshino.harness.ui.components.GroupCard
import app.hoshino.harness.ui.components.HIcon
import app.hoshino.harness.ui.components.Hairline
import app.hoshino.harness.ui.components.IconBtn
import app.hoshino.harness.ui.components.ListRow
import app.hoshino.harness.ui.components.PageScaffold
import app.hoshino.harness.ui.components.Pill
import app.hoshino.harness.ui.components.Segmented
import app.hoshino.harness.ui.components.StatusDot
import app.hoshino.harness.ui.components.Tag
import app.hoshino.harness.ui.components.hairlineCard
import app.hoshino.harness.ui.settings.CollectToasts
import app.hoshino.harness.ui.theme.HIcons
import app.hoshino.harness.ui.theme.HoshinoShapes
import app.hoshino.harness.ui.theme.HoshinoTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

// ================================================================ 终端

@Composable
fun TerminalScreen(vm: ConfigViewModel, onBack: () -> Unit) {
    val terminals by vm.terminals.collectAsStateWithLifecycle()
    val c = HoshinoTheme.colors
    val t = HoshinoTheme.type
    val scope = rememberCoroutineScope()
    var activeId by remember { mutableStateOf<String?>(null) }
    var input by remember { mutableStateOf("") }
    var ctrl by remember { mutableStateOf(false) }
    val history = remember { mutableListOf<String>() }
    var historyIndex by remember { mutableStateOf(-1) }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        if (vm.terminals.value.isEmpty()) activeId = vm.client.openTerminal()
        else if (activeId == null) activeId = vm.terminals.value.first().id
    }
    val active = terminals.firstOrNull { it.id == activeId } ?: terminals.firstOrNull()
    LaunchedEffect(active?.lines?.size) {
        val n = active?.lines?.size ?: 0
        if (n > 0) listState.scrollToItem(n - 1)
    }

    fun submit() {
        val id = active?.id ?: return
        val cmd = input
        if (cmd.isNotBlank()) history.add(0, cmd)
        historyIndex = -1
        input = ""
        scope.launch { vm.client.terminalInput(id, cmd) }
    }

    PageScaffold("终端", onBack, subtitle = "proot · Alpine", scrollable = false, actions = {
        IconBtn(HIcons.Plus, "新标签", { scope.launch { activeId = vm.client.openTerminal() } })
    }) {
        // 标签
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            terminals.forEach { term ->
                val sel = term.id == active?.id
                Row(
                    Modifier.clip(HoshinoShapes.pill).background(if (sel) c.surface2 else c.bg).clickable { activeId = term.id }.padding(start = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(term.title, style = t.codeSmall, color = if (sel) c.text else c.text2)
                    Box(Modifier.size(28.dp).clip(CircleShape).clickable { scope.launch { vm.client.closeTerminal(term.id) } }, contentAlignment = Alignment.Center) {
                        HIcon(HIcons.Close, size = 12.dp, tint = c.text3)
                    }
                }
            }
        }
        Hairline()
        Box(Modifier.fillMaxWidth().weight(1f).background(c.surface2)) {
            if (active == null) {
                EmptyState("没有打开的终端", icon = HIcons.Terminal)
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    items(active.lines) { line ->
                        Text(
                            line.text,
                            style = t.code,
                            color = when (line.stream) {
                                TerminalStream.Input -> c.text
                                TerminalStream.Output -> c.text2
                                TerminalStream.Error -> c.danger
                            },
                        )
                    }
                }
            }
        }
        Hairline()
        Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("$", style = t.code, color = c.accent)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (input.isEmpty()) Text("输入命令", style = t.code, color = c.text3)
                    BasicTextField(
                        value = input,
                        onValueChange = {
                            if (ctrl && it.length == input.length + 1) {
                                val ch = it.last().lowercaseChar()
                                ctrl = false
                                if (ch == 'c') {
                                    active?.let { a -> scope.launch { vm.client.terminalInput(a.id, "^C") } }
                                    input = ""
                                } else if (ch == 'l') {
                                    active?.let { a -> scope.launch { vm.client.terminalInput(a.id, "clear") } }
                                    input = ""
                                } else input = it
                            } else input = it
                        },
                        singleLine = true,
                        textStyle = t.code.copy(color = c.text),
                        cursorBrush = SolidColor(c.accent),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Send, autoCorrectEnabled = false),
                        keyboardActions = KeyboardActions(onSend = { submit() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                IconBtn(HIcons.ArrowUp, "执行", { submit() }, tint = c.text)
            }
            // 快捷键条
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                KeyChip("Tab") { input += "\t" }
                KeyChip("Ctrl", active = ctrl) { ctrl = !ctrl }
                KeyChip("Esc") { input = "" }
                KeyChip("↑") {
                    if (history.isNotEmpty()) {
                        historyIndex = (historyIndex + 1).coerceAtMost(history.size - 1)
                        input = history[historyIndex]
                    }
                }
                KeyChip("↓") {
                    if (historyIndex > 0) {
                        historyIndex -= 1; input = history[historyIndex]
                    } else {
                        historyIndex = -1; input = ""
                    }
                }
                KeyChip("/") { input += "/" }
                KeyChip("-") { input += "-" }
                KeyChip("|") { input += "|" }
                KeyChip("~") { input += "~" }
            }
        }
    }
}

@Composable
private fun KeyChip(label: String, active: Boolean = false, onClick: () -> Unit) {
    val c = HoshinoTheme.colors
    Box(
        Modifier.heightIn(min = 36.dp).clip(HoshinoShapes.small)
            .background(if (active) c.accent else c.surface)
            .hairlineCard(if (active) c.accent else c.surface, c.line, HoshinoShapes.small)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = HoshinoTheme.type.code, color = if (active) c.onAccent else c.text)
    }
}

// ================================================================ Trajectory

@Composable
fun TrajectoryScreen(chat: ChatViewModel, onBack: () -> Unit) {
    val state by chat.session.collectAsStateWithLifecycle()
    val current by chat.current.collectAsStateWithLifecycle()
    val c = HoshinoTheme.colors
    val t = HoshinoTheme.type
    var tab by remember { mutableStateOf("Trajectory") }
    var filter by remember { mutableStateOf<Set<TrajSource>>(emptySet()) }
    var expanded by remember { mutableStateOf(setOf<String>()) }
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.CHINA) }

    PageScaffold("Trajectory", onBack, subtitle = current?.title ?: "未选择会话", scrollable = false) {
        Segmented(listOf("Chat", "Trajectory"), tab, { it }, { tab = it }, Modifier.fillMaxWidth().padding(16.dp))
        if (tab == "Chat") {
            if (state.items.isEmpty()) EmptyState("暂无消息", icon = HIcons.Message)
            else MessageList(
                chatItems = state.items,
                actions = MessageActions(
                    onApprove = { id, d -> chat.respondApproval(id, d) },
                    onAnswer = { id, a -> chat.answer(id, a) },
                    onDiff = { id, a -> chat.resolveDiff(id, a) },
                ),
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        } else {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill("全部", selected = filter.isEmpty(), onClick = { filter = emptySet() })
                TrajSource.entries.forEach { s ->
                    Pill(s.key, selected = s in filter, onClick = { filter = if (s in filter) filter - s else filter + s })
                }
            }
            Spacer(Modifier.height(8.dp))
            val entries = state.trajectory.filter { filter.isEmpty() || it.source in filter }
            if (entries.isEmpty()) {
                EmptyState("暂无事件", subtitle = "发送一条消息后，这里会按来源列出完整事件流", icon = HIcons.Layers)
            } else {
                LazyColumn(Modifier.fillMaxWidth().weight(1f).navigationBarsPadding(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
                    itemsIndexed(entries, key = { _, e -> e.id }) { i, e ->
                        val open = e.id in expanded
                        Column(Modifier.fillMaxWidth().clickable { expanded = if (open) expanded - e.id else expanded + e.id }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("#${i + 1}", style = t.codeSmall, color = c.text3, modifier = Modifier.width(36.dp))
                                Tag(
                                    e.source.key,
                                    color = when (e.source) {
                                        TrajSource.User -> c.accent
                                        TrajSource.ToolCall, TrajSource.ToolResult -> c.text
                                        TrajSource.Subagent -> c.success
                                        TrajSource.Context -> c.warning
                                        else -> c.text2
                                    },
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(e.title, style = t.caption, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Text(fmt.format(Date(e.at)), style = t.codeSmall, color = c.text3)
                            }
                            AnimatedVisibility(open) {
                                Box(Modifier.padding(top = 8.dp).fillMaxWidth().clip(HoshinoShapes.small).background(c.surface2).horizontalScroll(rememberScrollState()).padding(10.dp)) {
                                    Text(e.payload.ifBlank { "(空)" }, style = t.codeSmall, color = c.text, softWrap = false)
                                }
                            }
                        }
                        Hairline(Modifier.padding(start = 16.dp))
                    }
                }
            }
        }
    }
}

// ================================================================ Inspector

@Composable
fun InspectorScreen(vm: ConfigViewModel, onBack: () -> Unit) {
    val nodes by vm.inspector.collectAsStateWithLifecycle()
    val c = HoshinoTheme.colors
    PageScaffold("Inspector", onBack, subtitle = "实验性 · 运行时服务 / 插件状态树") {
        Spacer(Modifier.height(8.dp))
        Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().hairlineCard(c.surface, c.line).padding(vertical = 6.dp)) {
            nodes.forEach { InspectorTree(it, 0) }
        }
    }
}

@Composable
private fun InspectorTree(node: InspectorNode, depth: Int) {
    val c = HoshinoTheme.colors
    val t = HoshinoTheme.type
    var open by remember { mutableStateOf(depth < 1) }
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(enabled = node.children.isNotEmpty()) { open = !open }
                .heightIn(min = 40.dp).padding(start = (12 + depth * 18).dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (node.children.isNotEmpty()) HIcon(if (open) HIcons.ChevronDown else HIcons.ChevronRight, size = 14.dp, tint = c.text3)
            else Spacer(Modifier.width(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(node.name, style = t.code, color = c.text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(node.kind, style = t.codeSmall, color = c.text3)
            Spacer(Modifier.width(8.dp))
            val ok = node.status in setOf("运行中", "就绪", "已连接", "已挂载", "正常", "空闲")
            StatusDot(if (ok) c.success else if (node.status == "错误") c.danger else c.warning, size = 6.dp)
            Spacer(Modifier.width(4.dp))
            Text(node.status, style = t.caption, color = c.text2)
        }
        if (open) node.children.forEach { InspectorTree(it, depth + 1) }
    }
}

// ================================================================ Web 预览

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebPreviewScreen(onBack: () -> Unit) {
    val c = HoshinoTheme.colors
    val t = HoshinoTheme.type
    var address by remember { mutableStateOf("https://example.com") }
    var loaded by remember { mutableStateOf("https://example.com") }
    var reloadTick by remember { mutableStateOf(0) }
    PageScaffold("Web 预览", onBack, subtitle = "实验性", scrollable = false) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).heightIn(min = 40.dp).hairlineCard(c.surface, c.line).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HIcon(HIcons.Globe, size = 16.dp, tint = c.text3)
                Spacer(Modifier.width(8.dp))
                BasicTextField(
                    value = address,
                    onValueChange = { address = it },
                    singleLine = true,
                    textStyle = t.codeSmall.copy(color = c.text),
                    cursorBrush = SolidColor(c.accent),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = {
                        loaded = if (address.startsWith("http")) address else "https://$address"
                    }),
                    modifier = Modifier.weight(1f),
                )
            }
            IconBtn(HIcons.Refresh, "刷新", { reloadTick++ })
        }
        Hairline()
        Box(Modifier.fillMaxWidth().weight(1f).navigationBarsPadding()) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = WebViewClient()
                    }
                },
                update = { wv ->
                    val tag = "$loaded#$reloadTick"
                    if (wv.tag != tag) {
                        if (wv.url == loaded) wv.reload() else wv.loadUrl(loaded)
                        wv.tag = tag
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

// ================================================================ LSP

@Composable
fun LspScreen(vm: ConfigViewModel, onBack: () -> Unit) {
    val servers by vm.lspServers.collectAsStateWithLifecycle()
    val c = HoshinoTheme.colors
    CollectToasts(vm)
    PageScaffold("LSP 状态", onBack, subtitle = "语言服务器") {
        Spacer(Modifier.height(8.dp))
        GroupCard {
            servers.forEachIndexed { i, s ->
                if (i > 0) Hairline()
                ListRow(
                    s.name, subtitle = "${s.language} · ${s.version}", icon = HIcons.Braces,
                    trailing = {
                        StatusDot(
                            when (s.status) {
                                "运行中" -> c.success
                                "未安装" -> c.text3
                                else -> c.warning
                            },
                            size = 6.dp,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(s.status, style = HoshinoTheme.type.caption, color = c.text2)
                    },
                )
            }
        }
    }
}
