package app.hoshino.harness.ui.settings

import app.hoshino.harness.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hoshino.harness.core.ApprovalPolicy
import app.hoshino.harness.core.PermissionTier
import app.hoshino.harness.ui.AppViewModel
import app.hoshino.harness.ui.ConfigViewModel
import app.hoshino.harness.ui.components.BtnStyle
import app.hoshino.harness.ui.components.GroupCard
import app.hoshino.harness.ui.components.HButton
import app.hoshino.harness.ui.components.HDialog
import app.hoshino.harness.ui.components.HIcon
import app.hoshino.harness.ui.components.HSheet
import app.hoshino.harness.ui.components.HSlider
import app.hoshino.harness.ui.components.HSwitch
import app.hoshino.harness.ui.components.HTextField
import app.hoshino.harness.ui.components.Hairline
import app.hoshino.harness.ui.components.HoshinoMark
import app.hoshino.harness.ui.components.ListRow
import app.hoshino.harness.ui.components.PageScaffold
import app.hoshino.harness.ui.components.SectionLabel
import app.hoshino.harness.ui.components.Segmented
import app.hoshino.harness.ui.components.StatusDot
import app.hoshino.harness.ui.components.Tag
import app.hoshino.harness.ui.components.hairlineCard
import app.hoshino.harness.ui.components.toast
import app.hoshino.harness.ui.nav.Routes
import app.hoshino.harness.ui.permission.FullAccessRiskDialog
import app.hoshino.harness.ui.permission.ShizukuGuide
import app.hoshino.harness.ui.permission.ShizukuStatusPill
import app.hoshino.harness.ui.theme.HIcons
import app.hoshino.harness.ui.theme.HoshinoShapes
import app.hoshino.harness.ui.theme.HoshinoTheme
import app.hoshino.harness.ui.theme.ThemeMode

/** 统一收集 ConfigViewModel 的 Toast */
@Composable
fun CollectToasts(vm: ConfigViewModel) {
    val ctx = LocalContext.current
    LaunchedEffect(vm) { vm.messages.collect { toast(ctx, it) } }
}

@Composable
fun SettingsHomeScreen(vm: ConfigViewModel, onBack: () -> Unit, onNavigate: (String) -> Unit) {
    val shizuku by vm.shizuku.collectAsStateWithLifecycle()
    val providers by vm.providers.collectAsStateWithLifecycle()
    val kernel by vm.kernel.collectAsStateWithLifecycle()
    PageScaffold(stringResource(R.string.settings), onBack) {
        SectionLabel("通用")
        GroupCard {
            ListRow(stringResource(R.string.settings_appearance), icon = HIcons.Palette, subtitle = "主题、字号、语言", showChevron = true, onClick = { onNavigate(Routes.APPEARANCE) })
            Hairline()
            ListRow(stringResource(R.string.settings_models), icon = HIcons.Cpu, value = "${providers.size} 个供应商", showChevron = true, onClick = { onNavigate(Routes.MODELS) })
            Hairline()
            ListRow(stringResource(R.string.settings_presets), icon = HIcons.Sparkle, subtitle = "Standard / PTC / Minimal / Creator", showChevron = true, onClick = { onNavigate(Routes.PRESETS) })
        }
        SectionLabel("权限与系统")
        GroupCard {
            ListRow(stringResource(R.string.settings_security), icon = HIcons.Shield, value = "Shizuku · ${shizuku.label}", showChevron = true, onClick = { onNavigate(Routes.SECURITY) })
            Hairline()
            ListRow(stringResource(R.string.settings_phone), icon = HIcons.Phone, subtitle = "文件、通知、无障碍、截图、悬浮窗", showChevron = true, onClick = { onNavigate(Routes.PHONE) })
            Hairline()
            ListRow(stringResource(R.string.settings_kernel), icon = HIcons.Bolt, value = kernel.dshVersion, showChevron = true, onClick = { onNavigate(Routes.KERNEL) })
        }
        SectionLabel("数据")
        GroupCard {
            ListRow(stringResource(R.string.settings_data), icon = HIcons.Database, showChevron = true, onClick = { onNavigate(Routes.DATA) })
            Hairline()
            ListRow(stringResource(R.string.settings_credentials), icon = HIcons.Key, showChevron = true, onClick = { onNavigate(Routes.CREDENTIALS) })
        }
        SectionLabel("其他")
        GroupCard {
            ListRow(stringResource(R.string.settings_feedback), icon = HIcons.Message, value = "/feedback", showChevron = true, onClick = { onNavigate(Routes.FEEDBACK) })
            Hairline()
            ListRow(stringResource(R.string.settings_about), icon = HIcons.Info, value = "v0.1.0", showChevron = true, onClick = { onNavigate(Routes.ABOUT) })
        }
    }
}

@Composable
fun AppearanceScreen(app: AppViewModel, onBack: () -> Unit) {
    val prefs by app.prefs.collectAsStateWithLifecycle()
    val c = HoshinoTheme.colors
    val t = HoshinoTheme.type
    PageScaffold("外观", onBack) {
        SectionLabel("主题")
        Segmented(
            options = ThemeMode.entries,
            selected = prefs.themeMode,
            label = { it.label },
            onSelect = { app.setTheme(it) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        SectionLabel("正文字号 · ${(15 * prefs.bodyScale).toInt()}")
        Column(Modifier.padding(horizontal = 20.dp)) {
            HSlider(prefs.bodyScale, { app.setBodyScale(it) }, 0.87f..1.2f, steps = 3)
            Text("今天想完成什么？这是正文预览。", style = t.body, color = c.text)
        }
        SectionLabel("代码字号 · ${(13 * prefs.codeScale).toInt()}")
        Column(Modifier.padding(horizontal = 20.dp)) {
            HSlider(prefs.codeScale, { app.setCodeScale(it) }, 0.85f..1.3f, steps = 3)
            Text("val client = FakeHarnessClient()", style = t.code, color = c.text)
        }
        SectionLabel("语言")
        Segmented(
            options = listOf("简体中文", "English"),
            selected = prefs.language,
            label = { it },
            onSelect = { app.setLanguage(it) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        Text("English 文案需在 res/values-en/strings.xml 中补充。", style = t.caption, color = c.text3, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
    }
}

@Composable
fun SecurityScreen(vm: ConfigViewModel, app: AppViewModel, onBack: () -> Unit, onNavigate: (String) -> Unit) {
    val prefs by app.prefs.collectAsStateWithLifecycle()
    val shizuku by vm.shizuku.collectAsStateWithLifecycle()
    val kernel by vm.kernel.collectAsStateWithLifecycle()
    val c = HoshinoTheme.colors
    val t = HoshinoTheme.type
    var risk by remember { mutableStateOf(false) }
    PageScaffold("权限与安全", onBack) {
        SectionLabel("新会话默认权限档")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PermissionTier.entries.forEach { tier ->
                val available=tier in vm.client.supportedPermissionTiers
                val selected=prefs.defaultTier.takeIf { it in vm.client.supportedPermissionTiers } ?: PermissionTier.WorkspaceWrite
                val sel = selected == tier
                Row(
                    Modifier.fillMaxWidth().hairlineCard(if (sel) c.accentSoft else c.surface, if (sel) c.accent else c.line)
                        .clickable(enabled=available) { if (tier == PermissionTier.FullAccess && !sel) risk = true else app.setDefaultTier(tier) }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(tier.label, style = t.bodyMedium, color = if (tier == PermissionTier.FullAccess) c.danger else c.text)
                        Text(tier.description, style = t.caption, color = c.text2)
                        if(!available)Tag("即将支持")
                    }
                    if (sel) HIcon(HIcons.Check, size = 18.dp, tint = c.accent)
                }
            }
        }
        SectionLabel("审批策略（工作区内修改档）")
        GroupCard {
            ApprovalPolicy.entries.forEachIndexed { i, p ->
                if (i > 0) Hairline()
                ListRow(
                    p.label, subtitle = p.description, onClick = { app.setApprovalPolicy(p) },
                    trailing = { if (prefs.approvalPolicy == p) HIcon(HIcons.Check, size = 18.dp, tint = c.accent) },
                )
            }
        }
        SectionLabel("Shizuku（完全访问所需）")
        GroupCard {
            ListRow("Shizuku 状态与引导", icon = HIcons.Bolt, showChevron = true, onClick = { onNavigate(Routes.SHIZUKU) }, trailing = { ShizukuStatusPill(shizuku) })
        }
        SectionLabel("沙箱后端")
        GroupCard {
            ListRow(
                kernel.sandbox, icon = HIcons.Terminal, subtitle = "bash 在 proot 中运行；system_shell 经 Shizuku 使用真实 ADB/Root 权限，仍需审批",
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(if (kernel.sandboxReady) c.success else c.warning, size = 6.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(if (kernel.sandboxReady) "就绪" else "未就绪", style = t.caption, color = c.text2)
                    }
                },
            )
        }
    }
    if (risk) {
        FullAccessRiskDialog(onConfirm = { risk = false; app.setDefaultTier(PermissionTier.FullAccess) }, onDismiss = { risk = false })
    }
}

@Composable
fun ShizukuScreen(vm: ConfigViewModel, onBack: () -> Unit) {
    val shizuku by vm.shizuku.collectAsStateWithLifecycle()
    val c = HoshinoTheme.colors
    PageScaffold("Shizuku", onBack) {
        Text(
            "完全访问会话可通过 system_shell 使用 Shizuku 的真实 ADB/Root 权限。普通 bash 和文件工具仍在私有工作区中，系统命令继续走审批。",
            style = HoshinoTheme.type.caption, color = c.text2, modifier = Modifier.padding(20.dp),
        )
        Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().hairlineCard(c.surface, c.line).padding(16.dp)) {
            ShizukuGuide(
                shizuku,
                onRefresh = { vm.run { refreshShizuku() } },
                onRequest = { vm.run { requestShizukuPermission() } },
            )
        }
    }
}

@Composable
fun PhoneCapabilitiesScreen(vm: ConfigViewModel, onBack: () -> Unit) {
    val caps by vm.phoneCapabilities.collectAsStateWithLifecycle()
    val c = HoshinoTheme.colors
    CollectToasts(vm)
    PageScaffold("手机能力", onBack) {
        Text("让 Agent 使用手机的系统能力。每项都需要你在系统设置中单独授权。", style = HoshinoTheme.type.caption, color = c.text2, modifier = Modifier.padding(20.dp))
        GroupCard {
            caps.forEachIndexed { i, cap ->
                if (i > 0) Hairline()
                ListRow(
                    cap.name, subtitle = cap.description,
                    trailing = {
                        if (cap.granted) Tag("已授权", color = c.success)
                        else HButton("去授权", { vm.run("已授权（模拟）") { requestCapability(cap.id) } }, style = BtnStyle.Secondary, compact = true)
                    },
                )
            }
        }
    }
}

@Composable
fun KernelScreen(vm: ConfigViewModel, onBack: () -> Unit) {
    val kernel by vm.kernel.collectAsStateWithLifecycle()
    val restart by vm.restartRequired.collectAsStateWithLifecycle()
    val c = HoshinoTheme.colors
    var logs by remember { mutableStateOf<List<String>?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var snapshots by remember { mutableStateOf<List<String>?>(null) }
    var restore by remember { mutableStateOf<String?>(null) }
    CollectToasts(vm)
    PageScaffold("内核", onBack) {
        SectionLabel("dsh 内核 · headless")
        GroupCard {
            ListRow("dsh 版本", value = kernel.dshVersion, trailing = { Tag("已锁定") })
            Hairline()
            ListRow("Node 运行时", value = kernel.nodeVersion)
            Hairline()
            ListRow("状态", trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(if (kernel.running) c.success else c.warning, size = 6.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(if (kernel.running) "运行中" else "重启中…", style = HoshinoTheme.type.caption, color = c.text2)
                }
            })
            Hairline()
            ListRow("沙箱", value = kernel.sandbox)
        }
        if (restart) {
            Text("插件或配置已变更，需要重启内核才能生效。", style = HoshinoTheme.type.caption, color = c.warning, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
        }
        Spacer(Modifier.height(16.dp))
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HButton("重启内核", { confirm = true }, Modifier.fillMaxWidth(), style = BtnStyle.Primary, icon = HIcons.Refresh)
            HButton("查看日志", { vm.run { logs = kernelLogs() } }, Modifier.fillMaxWidth(), style = BtnStyle.Secondary, icon = HIcons.ListIcon)
            HButton("配置快照与回滚", {vm.run{snapshots=configurationSnapshots()}},Modifier.fillMaxWidth(),style=BtnStyle.Secondary,icon=HIcons.Refresh,enabled=vm.client.configurationRollbackSupported)
        }
    }
    if (confirm) {
        HDialog("重启内核？", onDismiss = { confirm = false }, confirmText = "重启", onConfirm = { confirm = false; vm.run("内核已重启") { restartKernel() } }) {
            Text("正在运行的会话会被中断。", style = HoshinoTheme.type.body, color = c.text2)
        }
    }
    logs?.let { l ->
        HSheet(onDismiss = { logs = null }, title = "内核日志") {
            Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(HoshinoShapes.card).background(c.surface2).padding(12.dp)) {
                l.forEach { Text(it, style = HoshinoTheme.type.codeSmall, color = c.text) }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
    snapshots?.let{items->
        HSheet(onDismiss={snapshots=null},title="配置快照（最近 20 份）") {
            Text("恢复公开配置、用户插件、技能与 UI 覆盖。会话和凭据独立保存。",style=HoshinoTheme.type.caption,color=c.text3,modifier=Modifier.padding(16.dp))
            Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState())) {
                if(items.isEmpty())Text("尚无配置快照",style=HoshinoTheme.type.body,color=c.text2,modifier=Modifier.padding(20.dp))
                items.forEach{id->ListRow("恢复此快照",subtitle=id,onClick={restore=id;snapshots=null});Hairline()}
            }
        }
    }
    restore?.let{id->
        HDialog("恢复配置快照？",onDismiss={restore=null},confirmText="恢复",danger=true,onConfirm={restore=null;vm.run("配置快照已恢复"){rollbackConfiguration(id)}}) {
            Text("将以所选快照恢复配置及用户扩展：$id",style=HoshinoTheme.type.body,color=c.text2)
        }
    }
}

@Composable
fun DataPrivacyScreen(vm: ConfigViewModel, onBack: () -> Unit) {
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val c = HoshinoTheme.colors
    var confirmClear by remember { mutableStateOf(false) }
    CollectToasts(vm)
    PageScaffold("数据与隐私", onBack) {
        SectionLabel("存储")
        GroupCard {
            ListRow("会话存储位置", subtitle = "/data/data/app.hoshino.harness/files/dsh/sessions.db")
            Hairline()
            ListRow("会话数", value = "${sessions.size}")
        }
        SectionLabel("导出与清除")
        GroupCard {
            ListRow("导出全部会话", icon = HIcons.Download, onClick = { vm.toast("已导出到 /storage/emulated/0/Download/Hoshino/all-sessions.zip（模拟）") })
            Hairline()
            ListRow("清除全部数据", icon = HIcons.Trash, titleColor = c.danger, onClick = { confirmClear = true })
        }
        Text(
            "Hoshino Harness 不收集任何遥测。模型请求只发往你配置的供应商，API Key 保存在 Android Keystore，仅存于本机。",
            style = HoshinoTheme.type.caption, color = c.text3, modifier = Modifier.padding(20.dp),
        )
    }
    if (confirmClear) {
        HDialog("清除全部数据？", onDismiss = { confirmClear = false }, confirmText = "清除", danger = true, onConfirm = {
            confirmClear = false
            vm.run("已清除（模拟）") { this.sessions.value.forEach { deleteSession(it.id) } }
        }) {
            Text("将删除所有会话、轨迹、供应商与凭据，且无法恢复。", style = HoshinoTheme.type.body, color = c.text2)
        }
    }
}

@Composable
fun CredentialsScreen(vm: ConfigViewModel, onBack: () -> Unit) {
    val providers by vm.providers.collectAsStateWithLifecycle()
    val mcp by vm.mcpServers.collectAsStateWithLifecycle()
    val c = HoshinoTheme.colors
    CollectToasts(vm)
    PageScaffold("凭据管理", onBack) {
        Text("所有凭据经 Android Keystore 加密，仅保存在本机。", style = HoshinoTheme.type.caption, color = c.text2, modifier = Modifier.padding(20.dp))
        SectionLabel("模型供应商 API Key")
        if (providers.isEmpty()) {
            Text("暂无", style = HoshinoTheme.type.caption, color = c.text3, modifier = Modifier.padding(horizontal = 20.dp))
        } else GroupCard {
            providers.forEachIndexed { i, p ->
                if (i > 0) Hairline()
                ListRow(
                    p.name, subtitle = if (p.apiKey.isBlank()) "未设置" else "••••" + p.apiKey.takeLast(4), icon = HIcons.Key,
                    trailing = {
                        HButton("清除", { vm.run("已清除 ${p.name} 的 Key") { upsertProvider(p.copy(apiKey = "")) } }, style = BtnStyle.Ghost, compact = true)
                    },
                )
            }
        }
        SectionLabel("MCP Token / 环境变量")
        GroupCard {
            mcp.forEachIndexed { i, m ->
                if (i > 0) Hairline()
                ListRow(m.name, subtitle = m.env.ifBlank { "无" }, icon = HIcons.Server)
            }
        }
    }
}

@Composable
fun FeedbackScreen(vm: ConfigViewModel, onBack: () -> Unit) {
    val c = HoshinoTheme.colors
    var text by remember { mutableStateOf("") }
    var logs by remember { mutableStateOf(true) }
    var kind by remember { mutableStateOf("问题") }
    CollectToasts(vm)
    PageScaffold("反馈", onBack, subtitle = "/feedback") {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Segmented(listOf("问题", "建议", "其他"), kind, { it }, { kind = it }, Modifier.fillMaxWidth())
            HTextField(text, { text = it }, label = "描述", placeholder = "发生了什么？期望是什么？", singleLine = false, minLines = 6)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("附带内核日志", style = HoshinoTheme.type.body, color = c.text)
                    Text("不包含 API Key 与会话内容", style = HoshinoTheme.type.caption, color = c.text3)
                }
                HSwitch(logs, { logs = it })
            }
            HButton("提交", { vm.toast("感谢反馈（模拟提交）"); text = ""; onBack() }, Modifier.fillMaxWidth(), style = BtnStyle.Primary, enabled = text.isNotBlank())
        }
    }
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val c = HoshinoTheme.colors
    val t = HoshinoTheme.type
    val uri = LocalUriHandler.current
    val githubUrl = stringResource(R.string.github_url)
    PageScaffold("关于", onBack) {
        Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            HoshinoMark(64.dp)
            Spacer(Modifier.height(16.dp))
            Text("Hoshino Harness", style = t.headline, color = c.text)
            Text("版本 0.1.0 (1)", style = t.caption, color = c.text3)
        }
        GroupCard {
            ListRow("开源许可", value = "MIT")
            Hairline()
            ListRow("GitHub", icon = HIcons.Link, showChevron = true, onClick = { uri.openUri(githubUrl) })
            Hairline()
            ListRow("第三方许可", subtitle = "Jetpack Compose、Kotlin Coroutines、Shizuku API 等", showChevron = false)
        }
        Spacer(Modifier.heightIn(min = 24.dp))
    }
}
