package app.hoshino.harness.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hoshino.harness.core.AgentPreset
import app.hoshino.harness.core.ModelRef
import app.hoshino.harness.core.PermissionTier
import app.hoshino.harness.core.ReasoningEffort
import app.hoshino.harness.ui.ConfigViewModel
import app.hoshino.harness.ui.chat.ModelPickerSheet
import app.hoshino.harness.ui.components.BtnStyle
import app.hoshino.harness.ui.components.GroupCard
import app.hoshino.harness.ui.components.HButton
import app.hoshino.harness.ui.components.HDialog
import app.hoshino.harness.ui.components.HTextField
import app.hoshino.harness.ui.components.Hairline
import app.hoshino.harness.ui.components.IconBtn
import app.hoshino.harness.ui.components.ListRow
import app.hoshino.harness.ui.components.PageScaffold
import app.hoshino.harness.ui.components.Pill
import app.hoshino.harness.ui.components.SectionLabel
import app.hoshino.harness.ui.components.Segmented
import app.hoshino.harness.ui.components.Tag
import app.hoshino.harness.ui.nav.Routes
import app.hoshino.harness.ui.theme.HIcons
import app.hoshino.harness.ui.theme.HoshinoTheme

val ALL_TOOLS = listOf(
    "文件编辑", "shell", "文件搜索", "网页搜索", "skills", "规划", "goals", "子代理", "workflow",
    "ts_program", "bash", "str_replace_editor", "runtime_inspect", "memory_lab", "preset_author", "mcp", "手机操作",
)

@Composable
fun PresetsScreen(vm: ConfigViewModel, onBack: () -> Unit, onNavigate: (String) -> Unit) {
    val presets by vm.presets.collectAsStateWithLifecycle()
    PageScaffold("Agent 预设", onBack, actions = { IconBtn(HIcons.Plus, "新建预设", { onNavigate(Routes.preset(Routes.NEW_PRESET)) }) }) {
        SectionLabel("内置")
        GroupCard {
            presets.filter { it.builtIn }.forEachIndexed { i, p ->
                if (i > 0) Hairline()
                ListRow(p.name, subtitle = p.description, showChevron = true, onClick = { onNavigate(Routes.preset(p.id)) })
            }
        }
        val custom = presets.filter { !it.builtIn }
        SectionLabel("自定义")
        if (custom.isEmpty()) {
            Text(
                "还没有自定义预设。可新建，或打开内置预设后「复制为自定义」。",
                style = HoshinoTheme.type.caption, color = HoshinoTheme.colors.text3, modifier = Modifier.padding(horizontal = 20.dp),
            )
        } else {
            GroupCard {
                custom.forEachIndexed { i, p ->
                    if (i > 0) Hairline()
                    ListRow(p.name, subtitle = p.description.ifBlank { "${p.tools.size} 个工具 · ${p.defaultTier.label}" }, showChevron = true, onClick = { onNavigate(Routes.preset(p.id)) })
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        HButton("新建预设", { onNavigate(Routes.preset(Routes.NEW_PRESET)) }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), style = BtnStyle.Secondary, icon = HIcons.Plus)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PresetEditScreen(vm: ConfigViewModel, presetId: String, onBack: () -> Unit) {
    val c = HoshinoTheme.colors
    val t = HoshinoTheme.type
    val providers by vm.providers.collectAsStateWithLifecycle()
    CollectToasts(vm)
    val initial = remember(presetId) {
        vm.presets.value.firstOrNull { it.id == presetId }
            ?: AgentPreset("custom-${System.currentTimeMillis()}", "", "", false, "", listOf("文件编辑", "shell", "文件搜索"), null, PermissionTier.WorkspaceWrite)
    }
    val readOnly = initial.builtIn
    var name by remember { mutableStateOf(initial.name) }
    var desc by remember { mutableStateOf(initial.description) }
    var prompt by remember { mutableStateOf(initial.systemPrompt) }
    var tools by remember { mutableStateOf(initial.tools.toSet()) }
    var model by remember { mutableStateOf<ModelRef?>(initial.defaultModel) }
    var tier by remember { mutableStateOf(initial.defaultTier) }
    var pickModel by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val modelLabel = model?.let { ref ->
        providers.firstOrNull { it.id == ref.providerId }?.models?.firstOrNull { it.id == ref.modelId }?.displayName
    } ?: "跟随主对话模型"

    PageScaffold(
        if (readOnly) initial.name else if (name.isBlank()) "新建预设" else name,
        onBack,
        subtitle = if (readOnly) "内置预设（只读）" else null,
        actions = {
            if (readOnly) {
                HButton("复制为自定义", {
                    val copy = initial.copy(id = "custom-${System.currentTimeMillis()}", name = "${initial.name} 副本", builtIn = false)
                    vm.run("已复制") { upsertPreset(copy) }
                    onBack()
                }, style = BtnStyle.Secondary, compact = true)
            } else {
                HButton("保存", {
                    vm.run("已保存") {
                        upsertPreset(initial.copy(name = name.trim(), description = desc.trim(), systemPrompt = prompt, tools = tools.toList(), defaultModel = model, defaultTier = tier))
                    }
                    onBack()
                }, style = BtnStyle.Primary, compact = true, enabled = name.isNotBlank())
            }
            Spacer(Modifier.width(8.dp))
        },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (readOnly) {
                Text(initial.description, style = t.body, color = c.text2)
            } else {
                HTextField(name, { name = it }, label = "名称", placeholder = "例如：前端重构")
                HTextField(desc, { desc = it }, label = "描述", placeholder = "一句话说明用途")
                HTextField(prompt, { prompt = it }, label = "系统提示", placeholder = "你是……", singleLine = false, minLines = 5, mono = true)
            }
        }
        SectionLabel("可用工具 · ${tools.size}")
        FlowRow(
            Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ALL_TOOLS.forEach { tool ->
                val sel = tool in tools
                Pill(
                    tool,
                    selected = sel,
                    icon = if (sel) HIcons.Check else null,
                    onClick = if (readOnly) null else ({ tools = if (sel) tools - tool else tools + tool }),
                )
            }
        }
        SectionLabel("默认模型")
        GroupCard {
            ListRow("模型", value = modelLabel, showChevron = !readOnly, onClick = if (readOnly) null else ({ pickModel = true }))
        }
        SectionLabel("默认权限档")
        Segmented(
            PermissionTier.entries, tier, { it.label }, { if (it != PermissionTier.FullAccess) tier = it },
            Modifier.fillMaxWidth().padding(horizontal = 16.dp), enabled = !readOnly,
        )
        Text("完全访问不能设为预设默认值，需要在会话中手动开启。", style = t.caption, color = c.text3, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        if (!readOnly && vm.presets.value.any { it.id == initial.id }) {
            Spacer(Modifier.height(16.dp))
            HButton("删除预设", { confirmDelete = true }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), style = BtnStyle.Secondary, icon = HIcons.Trash)
        }
        if (readOnly) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Tag("内置")
                Spacer(Modifier.width(8.dp))
                Text("内置预设随 dsh 版本更新。", style = t.caption, color = c.text3)
            }
        }
    }
    if (pickModel) {
        ModelPickerSheet(
            providers = providers, selected = model, effort = ReasoningEffort.Off,
            onPick = { model = it; pickModel = false }, onEffort = {}, onAddProvider = { pickModel = false },
            onDismiss = { pickModel = false }, title = "默认模型", showEffort = false,
        )
    }
    if (confirmDelete) {
        HDialog("删除预设？", onDismiss = { confirmDelete = false }, confirmText = "删除", danger = true, onConfirm = {
            confirmDelete = false
            vm.run("已删除") { deletePreset(initial.id) }
            onBack()
        })
    }
}
