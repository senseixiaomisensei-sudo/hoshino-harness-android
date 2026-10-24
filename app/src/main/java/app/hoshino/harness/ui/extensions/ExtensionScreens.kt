package app.hoshino.harness.ui.extensions

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hoshino.harness.core.Hook
import app.hoshino.harness.core.McpServer
import app.hoshino.harness.core.McpStatus
import app.hoshino.harness.core.McpTransport
import app.hoshino.harness.core.Plugin
import app.hoshino.harness.core.PluginCategory
import app.hoshino.harness.core.PluginSource
import app.hoshino.harness.core.Skill
import app.hoshino.harness.core.WebhookRule
import app.hoshino.harness.ui.ConfigViewModel
import app.hoshino.harness.ui.components.BtnStyle
import app.hoshino.harness.ui.components.EmptyState
import app.hoshino.harness.ui.components.GroupCard
import app.hoshino.harness.ui.components.HButton
import app.hoshino.harness.ui.components.HDialog
import app.hoshino.harness.ui.components.HIcon
import app.hoshino.harness.ui.components.HSheet
import app.hoshino.harness.ui.components.HSwitch
import app.hoshino.harness.ui.components.HTextField
import app.hoshino.harness.ui.components.Hairline
import app.hoshino.harness.ui.components.IconBtn
import app.hoshino.harness.ui.components.ListRow
import app.hoshino.harness.ui.components.MarkdownText
import app.hoshino.harness.ui.components.PageScaffold
import app.hoshino.harness.ui.components.Pill
import app.hoshino.harness.ui.components.SectionLabel
import app.hoshino.harness.ui.components.Segmented
import app.hoshino.harness.ui.components.StatusDot
import app.hoshino.harness.ui.components.Tag
import app.hoshino.harness.ui.components.TextInputDialog
import app.hoshino.harness.ui.components.hairlineCard
import app.hoshino.harness.ui.settings.CollectToasts
import app.hoshino.harness.ui.theme.HIcons
import app.hoshino.harness.ui.theme.HoshinoShapes
import app.hoshino.harness.ui.theme.HoshinoTheme
import kotlinx.coroutines.launch

// ================================================================ 插件

@Composable
fun PluginsScreen(vm: ConfigViewModel, onBack: () -> Unit) {
    val plugins by vm.plugins.collectAsStateWithLifecycle()
    val restart by vm.restartRequired.collectAsStateWithLifecycle()
    val c = HoshinoTheme.colors
    val t = HoshinoTheme.type
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<PluginCategory?>(null) }
    var gitDialog by remember { mutableStateOf(false) }
    var localDialog by remember { mutableStateOf(false) }
    var installing by remember { mutableStateOf(false) }
    var configFor by remember { mutableStateOf<Plugin?>(null) }
    var removeFor by remember { mutableStateOf<Plugin?>(null) }
    CollectToasts(vm)

    val shown = plugins.filter {
        (category == null || it.category == category) &&
            (query.isBlank() || it.name.contains(query, true) || it.pkg.contains(query, true))
    }

    PageScaffold("插件", onBack, actions = {
        Box {
            var menu by remember { mutableStateOf(false) }
            IconBtn(HIcons.Plus, "安装插件", { menu = true })
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("从 Git 安装", style = t.body, color = c.text) }, leadingIcon = { HIcon(HIcons.Fork, size = 18.dp) }, onClick = { menu = false; gitDialog = true })
                DropdownMenuItem(text = { Text("本地链接", style = t.body, color = c.text) }, leadingIcon = { HIcon(HIcons.Folder, size = 18.dp) }, onClick = { menu = false; localDialog = true })
            }
        }
    }) {
        AnimatedVisibility(restart) {
            Row(
                Modifier.padding(16.dp).fillMaxWidth().hairlineCard(c.warning.copy(alpha = 0.08f), c.warning.copy(alpha = 0.4f)).padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HIcon(HIcons.Warning, size = 18.dp, tint = c.warning)
                Spacer(Modifier.width(10.dp))
                Text("插件变更需要重启内核", style = t.caption, color = c.text, modifier = Modifier.weight(1f))
                HButton("重启内核", { vm.run("内核已重启") { restartKernel() } }, style = BtnStyle.Primary, compact = true)
            }
        }
        HTextField(query, { query = it }, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), placeholder = "搜索插件名或包名", leading = HIcons.Search)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("全部", selected = category == null, onClick = { category = null })
            PluginCategory.entries.forEach { cat -> Pill(cat.label, selected = category == cat, onClick = { category = cat }) }
        }
        if (installing) Text("正在安装…", style = t.caption, color = c.accent, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        Spacer(Modifier.height(8.dp))
        if (shown.isEmpty()) {
            EmptyState("没有匹配的插件", icon = HIcons.Plug)
        } else GroupCard {
            shown.forEachIndexed { i, p ->
                if (i > 0) Hairline()
                PluginRow(p, onToggle = { on -> vm.run { setPluginEnabled(p.id, on) } }, onConfig = { configFor = p }, onRemove = { removeFor = p })
            }
        }
    }

    if (gitDialog) {
        TextInputDialog(
            title = "从 Git 安装",
            initial = "github:",
            placeholder = "github:owner/repo#ref",
            mono = true,
            hint = "例如 github:hoshino-harness/plugin-browser#v0.2.0",
            onDismiss = { gitDialog = false },
            onConfirm = { spec ->
                gitDialog = false
                installing = true
                scope.launch {
                    val r = vm.client.installPluginFromGit(spec)
                    installing = false
                    vm.toast(r.fold({ "已安装 ${it.name}" }, { it.message ?: "安装失败" }))
                }
            },
        )
    }
    if (localDialog) {
        TextInputDialog(
            title = "本地链接",
            initial = "",
            placeholder = "/storage/emulated/0/dev/my-plugin",
            mono = true,
            hint = "链接本地目录中的插件（开发模式）",
            onDismiss = { localDialog = false },
            onConfirm = { path ->
                localDialog = false
                scope.launch {
                    val r = vm.client.linkLocalPlugin(path)
                    vm.toast(r.fold({ "已链接 ${it.name}" }, { it.message ?: "链接失败" }))
                }
            },
        )
    }
    configFor?.let { p -> PluginConfigSheet(vm, p, onDismiss = { configFor = null }) }
    removeFor?.let { p ->
        HDialog("移除插件？", onDismiss = { removeFor = null }, confirmText = "移除", danger = true, onConfirm = {
            removeFor = null
            vm.run("已移除 ${p.name}") { removePlugin(p.id) }
        }) {
            Text("${p.name}（${p.pkg}）将被卸载，需重启内核生效。", style = t.body, color = c.text2)
        }
    }
}

@Composable
private fun PluginRow(p: Plugin, onToggle: (Boolean) -> Unit, onConfig: () -> Unit, onRemove: () -> Unit) {
    val c = HoshinoTheme.colors
    val t = HoshinoTheme.type
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.name, style = t.body, color = c.text, maxLines = 1)
                Spacer(Modifier.width(6.dp))
                Tag(p.source.label, color = if (p.source == PluginSource.BuiltIn) c.text2 else c.accent, bg = if (p.source == PluginSource.BuiltIn) c.surface2 else c.accentSoft)
                Spacer(Modifier.width(4.dp))
                Tag(p.category.label)
            }
            Text("${p.pkg} · ${p.version}", style = t.codeSmall, color = c.text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(p.description, style = t.caption, color = c.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        HSwitch(p.enabled, onToggle)
        Box {
            IconBtn(HIcons.More, "更多", { menu = true }, tint = c.text3)
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("打开配置文件", style = t.body, color = c.text) }, leadingIcon = { HIcon(HIcons.File, size = 18.dp) }, onClick = { menu = false; onConfig() })
                DropdownMenuItem(
                    text = { Text("移除", style = t.body, color = if (p.source == PluginSource.BuiltIn) c.text3 else c.danger) },
                    leadingIcon = { HIcon(HIcons.Trash, size = 18.dp, tint = c.danger) },
                    enabled = p.source != PluginSource.BuiltIn,
                    onClick = { menu = false; onRemove() },
                )
            }
        }
    }
}

@Composable
private fun PluginConfigSheet(vm: ConfigViewModel, p: Plugin, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    if (text == null) {
        androidx.compose.runtime.LaunchedEffect(p.id) { text = vm.client.readPluginConfig(p.id) }
    }
    HSheet(onDismiss = onDismiss, title = "${p.name} · 配置") {
        Text("~/.hoshino/plugins/${p.pkg.substringAfterLast('/')}/config.json", style = HoshinoTheme.type.codeSmall, color = HoshinoTheme.colors.text3, modifier = Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(8.dp))
        HTextField(text ?: "", { text = it }, Modifier.padding(horizontal = 16.dp), mono = true, singleLine = false, minLines = 8)
        Spacer(Modifier.height(12.dp))
        HButton("保存", {
            val v = text ?: ""
            scope.launch { vm.client.writePluginConfig(p.id, v); vm.toast("已保存，重启内核后生效") }
            onDismiss()
        }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), style = BtnStyle.Primary)
        Spacer(Modifier.height(8.dp))
    }
}

// ================================================================ Skills

@Composable
fun SkillsScreen(vm: ConfigViewModel, onBack: () -> Unit) {
    val skills by vm.skills.collectAsStateWithLifecycle()
    val c = HoshinoTheme.colors
    var viewing by remember { mutableStateOf<Skill?>(null) }
    var creating by remember { mutableStateOf(false) }
    CollectToasts(vm)
    PageScaffold("Skills", onBack, actions = { IconBtn(HIcons.Plus, "新建 Skill", { creating = true }) }) {
        Text("Skill 是一份 SKILL.md：Agent 在相关任务中按需加载其中的说明与脚本。", style = HoshinoTheme.type.caption, color = c.text2, modifier = Modifier.padding(20.dp))
        if (skills.isEmpty()) EmptyState("暂无 Skills", icon = HIcons.Sparkle)
        else GroupCard {
            skills.forEachIndexed { i, s ->
                if (i > 0) Hairline()
                ListRow(
                    s.name, subtitle = s.description, onClick = { viewing = s },
                    trailing = {
                        Tag(s.source)
                        Spacer(Modifier.width(8.dp))
                        HSwitch(s.enabled, { on -> vm.run { setSkillEnabled(s.id, on) } })
                    },
                )
            }
        }
    }
    viewing?.let { s ->
        HSheet(onDismiss = { viewing = null }, title = "${s.name}/SKILL.md") {
            Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(HoshinoShapes.card).background(c.surface2).padding(14.dp)) {
                MarkdownText(s.content)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
    if (creating) {
        var name by remember { mutableStateOf("") }
        var desc by remember { mutableStateOf("") }
        var body by remember { mutableStateOf("# 说明\n\n1. ") }
        HSheet(onDismiss = { creating = false }, title = "新建 Skill") {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                HTextField(name, { name = it }, label = "名称", placeholder = "release-notes", mono = true)
                HTextField(desc, { desc = it }, label = "描述", placeholder = "何时使用这个 Skill")
                HTextField(body, { body = it }, label = "SKILL.md 正文", singleLine = false, minLines = 6, mono = true)
                HButton("创建", {
                    val content = "---\nname: ${name.trim()}\ndescription: ${desc.trim()}\n---\n\n$body"
                    vm.run("已创建") { createSkill(name.trim(), desc.trim(), content) }
                    creating = false
                }, Modifier.fillMaxWidth(), style = BtnStyle.Primary, enabled = name.isNotBlank())
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

// ================================================================ MCP

@Composable
fun McpScreen(vm: ConfigViewModel, onBack: () -> Unit) {
    val servers by vm.mcpServers.collectAsStateWithLifecycle()
    val c = HoshinoTheme.colors
    val t = HoshinoTheme.type
    var adding by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(setOf<String>()) }
    var removeFor by remember { mutableStateOf<McpServer?>(null) }
    CollectToasts(vm)
    PageScaffold("MCP", onBack, actions = { IconBtn(HIcons.Plus, "添加服务器", { adding = true }) }) {
        SectionLabel("服务器")
        if (servers.isEmpty()) {
            EmptyState("还没有 MCP 服务器", icon = HIcons.Server)
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            servers.forEach { s ->
                val open = s.id in expanded
                Column(Modifier.fillMaxWidth().hairlineCard(c.surface, c.line)) {
                    Row(
                        Modifier.fillMaxWidth().clickable { expanded = if (open) expanded - s.id else expanded + s.id }.padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(s.name, style = t.bodyMedium, color = c.text)
                                Spacer(Modifier.width(6.dp))
                                Tag(s.transport.label)
                                if (s.oauth) {
                                    Spacer(Modifier.width(4.dp)); Tag("OAuth")
                                }
                            }
                            Text(s.target, style = t.codeSmall, color = c.text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                StatusDot(
                                    when (s.status) {
                                        McpStatus.Connected -> c.success
                                        McpStatus.Connecting -> c.warning
                                        McpStatus.Error -> c.danger
                                        McpStatus.Disabled -> c.text3
                                    },
                                    size = 6.dp,
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("${s.status.label} · ${s.tools.count { it.enabled }}/${s.tools.size} 个工具", style = t.caption, color = c.text2)
                            }
                        }
                        IconBtn(HIcons.Trash, "移除", { removeFor = s }, tint = c.text3, iconSize = 18.dp)
                        HIcon(if (open) HIcons.ChevronUp else HIcons.ChevronDown, size = 16.dp, tint = c.text3, modifier = Modifier.padding(end = 8.dp))
                    }
                    AnimatedVisibility(open) {
                        Column {
                            s.tools.forEach { tool ->
                                Hairline()
                                Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(tool.name, style = t.code, color = c.text)
                                        Text(tool.description, style = t.caption, color = c.text3)
                                    }
                                    HSwitch(tool.enabled, { on -> vm.run { setMcpToolEnabled(s.id, tool.name, on) } })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (adding) AddMcpSheet(onAdd = { server -> vm.run("已添加 ${server.name}") { upsertMcpServer(server) }; adding = false }, onDismiss = { adding = false })
    removeFor?.let { s ->
        HDialog("移除 MCP 服务器？", onDismiss = { removeFor = null }, confirmText = "移除", danger = true, onConfirm = {
            removeFor = null; vm.run { removeMcpServer(s.id) }
        }) { Text(s.name, style = t.body, color = c.text2) }
    }
}

@Composable
private fun AddMcpSheet(onAdd: (McpServer) -> Unit, onDismiss: () -> Unit) {
    val c = HoshinoTheme.colors
    var name by remember { mutableStateOf("") }
    var transport by remember { mutableStateOf(McpTransport.Stdio) }
    var target by remember { mutableStateOf("") }
    var env by remember { mutableStateOf("") }
    var oauth by remember { mutableStateOf(false) }
    HSheet(onDismiss = onDismiss, title = "添加 MCP 服务器") {
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            HTextField(name, { name = it }, label = "名称", placeholder = "filesystem")
            Segmented(McpTransport.entries, transport, { it.label }, { transport = it }, Modifier.fillMaxWidth())
            HTextField(
                target, { target = it },
                label = if (transport == McpTransport.Stdio) "命令" else "URL",
                placeholder = if (transport == McpTransport.Stdio) "npx -y @modelcontextprotocol/server-filesystem ~/workspace" else "https://example.com/mcp",
                mono = true,
            )
            HTextField(
                env, { env = it },
                label = if (transport == McpTransport.Stdio) "环境变量" else "Token / Header",
                placeholder = if (transport == McpTransport.Stdio) "KEY=value（每行一个）" else "Authorization: Bearer …",
                mono = true, singleLine = false, minLines = 2,
            )
            if (transport == McpTransport.Http) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("使用 OAuth", style = HoshinoTheme.type.body, color = c.text)
                        Text("连接时在浏览器中完成授权", style = HoshinoTheme.type.caption, color = c.text3)
                    }
                    HSwitch(oauth, { oauth = it })
                }
            }
            HButton(
                "添加",
                { onAdd(McpServer("mcp${System.currentTimeMillis()}", name.trim(), transport, target.trim(), env.trim(), oauth, McpStatus.Connecting, emptyList())) },
                Modifier.fillMaxWidth(), style = BtnStyle.Primary, enabled = name.isNotBlank() && target.isNotBlank(),
            )
            Spacer(Modifier.height(4.dp))
        }
    }
}

// ================================================================ Hooks / Webhook

@Composable
fun HooksScreen(vm: ConfigViewModel, onBack: () -> Unit) {
    val hooks by vm.hooks.collectAsStateWithLifecycle()
    val webhooks by vm.webhooks.collectAsStateWithLifecycle()
    val c = HoshinoTheme.colors
    val t = HoshinoTheme.type
    var tab by remember { mutableStateOf("Hooks") }
    var addHook by remember { mutableStateOf(false) }
    var addWebhook by remember { mutableStateOf(false) }
    CollectToasts(vm)
    PageScaffold("Hooks / Webhook", onBack, actions = {
        IconBtn(HIcons.Plus, "添加", { if (tab == "Hooks") addHook = true else addWebhook = true })
    }) {
        Segmented(listOf("Hooks", "Webhook"), tab, { it }, { tab = it }, Modifier.fillMaxWidth().padding(16.dp))
        if (tab == "Hooks") {
            Text("兼容 Claude Code / Codex hooks：在工具调用前后、会话开始/结束时运行脚本。", style = t.caption, color = c.text2, modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(12.dp))
            if (hooks.isEmpty()) EmptyState("暂无 Hooks", icon = HIcons.Link)
            else GroupCard {
                hooks.forEachIndexed { i, h ->
                    if (i > 0) Hairline()
                    ListRow(
                        "${h.event} · ${h.matcher}", subtitle = h.command,
                        trailing = {
                            Tag(h.compat)
                            Spacer(Modifier.width(8.dp))
                            HSwitch(h.enabled, { on -> vm.run { upsertHook(h.copy(enabled = on)) } })
                        },
                        onLongClick = { vm.run("已删除") { removeHook(h.id) } },
                    )
                }
            }
        } else {
            Text("事件发生时向 URL 发送 POST（运行完成、需要审批、定时任务结束等）。", style = t.caption, color = c.text2, modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(12.dp))
            if (webhooks.isEmpty()) EmptyState("暂无 Webhook 规则", icon = HIcons.Link)
            else GroupCard {
                webhooks.forEachIndexed { i, w ->
                    if (i > 0) Hairline()
                    ListRow(
                        w.name, subtitle = "${w.events} → ${w.url}",
                        trailing = { HSwitch(w.enabled, { on -> vm.run { upsertWebhook(w.copy(enabled = on)) } }) },
                        onLongClick = { vm.run("已删除") { removeWebhook(w.id) } },
                    )
                }
            }
        }
        Text("长按条目可删除。", style = t.caption, color = c.text3, modifier = Modifier.padding(20.dp))
    }
    if (addHook) {
        var event by remember { mutableStateOf("PreToolUse") }
        var matcher by remember { mutableStateOf("bash") }
        var command by remember { mutableStateOf("") }
        var compat by remember { mutableStateOf("Claude") }
        HSheet(onDismiss = { addHook = false }, title = "添加 Hook") {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Segmented(listOf("PreToolUse", "PostToolUse", "SessionStart", "Stop"), event, { it }, { event = it }, Modifier.fillMaxWidth())
                HTextField(matcher, { matcher = it }, label = "匹配（工具名 / 正则）", mono = true)
                HTextField(command, { command = it }, label = "命令", placeholder = "~/.hoshino/hooks/guard.sh", mono = true)
                Segmented(listOf("Claude", "Codex"), compat, { "$it 兼容" }, { compat = it }, Modifier.fillMaxWidth())
                HButton("添加", {
                    vm.run("已添加") { upsertHook(Hook("h${System.currentTimeMillis()}", event, matcher, command, compat, true)) }
                    addHook = false
                }, Modifier.fillMaxWidth(), style = BtnStyle.Primary, enabled = command.isNotBlank())
                Spacer(Modifier.height(4.dp))
            }
        }
    }
    if (addWebhook) {
        var name by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("https://") }
        var events by remember { mutableStateOf("turn.finished, approval.requested") }
        HSheet(onDismiss = { addWebhook = false }, title = "添加 Webhook 规则") {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                HTextField(name, { name = it }, label = "名称", placeholder = "飞书通知")
                HTextField(url, { url = it }, label = "URL", mono = true)
                HTextField(events, { events = it }, label = "事件（逗号分隔）", mono = true)
                HButton("添加", {
                    vm.run("已添加") { upsertWebhook(WebhookRule("w${System.currentTimeMillis()}", name.trim(), url.trim(), events.trim(), true)) }
                    addWebhook = false
                }, Modifier.fillMaxWidth(), style = BtnStyle.Primary, enabled = name.isNotBlank() && url.length > 8)
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}
