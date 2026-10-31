package app.hoshino.harness.ui.settings

import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hoshino.harness.core.ModelInfo
import app.hoshino.harness.core.ModelRef
import app.hoshino.harness.core.ModelRouting
import app.hoshino.harness.core.Provider
import app.hoshino.harness.core.ProviderProtocol
import app.hoshino.harness.core.ReasoningEffort
import app.hoshino.harness.ui.ConfigViewModel
import app.hoshino.harness.ui.chat.ModelPickerSheet
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
import app.hoshino.harness.ui.components.PageScaffold
import app.hoshino.harness.ui.components.SectionLabel
import app.hoshino.harness.ui.components.Segmented
import app.hoshino.harness.ui.components.Tag
import app.hoshino.harness.ui.components.formatTokens
import app.hoshino.harness.ui.nav.Routes
import app.hoshino.harness.ui.theme.HIcons
import app.hoshino.harness.ui.theme.HoshinoTheme
import kotlinx.coroutines.launch

/** 设置 → 模型：供应商列表 + 多模型路由 */
@Composable
fun ModelsScreen(vm: ConfigViewModel, onBack: () -> Unit, onNavigate: (String) -> Unit) {
    val providers by vm.providers.collectAsStateWithLifecycle()
    val routing by vm.routing.collectAsStateWithLifecycle()
    val c = HoshinoTheme.colors
    var catalog by remember { mutableStateOf(false) }
    var routeSlot by remember { mutableStateOf<Int?>(null) }
    CollectToasts(vm)

    fun label(ref: ModelRef?): String {
        if (ref == null) return "未选择"
        val p = providers.firstOrNull { it.id == ref.providerId } ?: return "未选择"
        val m = p.models.firstOrNull { it.id == ref.modelId } ?: return "未选择"
        return "${m.displayName} · ${p.name}"
    }

    PageScaffold("模型", onBack, actions = { IconBtn(HIcons.Plus, "添加供应商", { catalog = true }) }) {
        ChatGptSubscription()
        SectionLabel("供应商")
        if (providers.isEmpty()) {
            EmptyState("还没有模型供应商", subtitle = "添加一个供应商，再为它配置一个或多个模型", icon = HIcons.Cpu)
            HButton("添加模型供应商", { catalog = true }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), style = BtnStyle.Primary, icon = HIcons.Plus)
        } else {
            GroupCard {
                providers.forEachIndexed { i, p ->
                    if (i > 0) Hairline()
                    ListRow(
                        p.name,
                        subtitle = "${p.protocol.label} · ${p.models.size} 个模型",
                        icon = HIcons.Cpu,
                        onClick = { onNavigate(Routes.provider(p.id)) },
                        trailing = { HSwitch(p.enabled, { on -> vm.run { setProviderEnabled(p.id, on) } }) },
                    )
                }
            }
        }
        SectionLabel("多模型路由")
        GroupCard {
            listOf("主对话模型", "子代理模型", "压缩 / 标题模型", "视觉模型").forEachIndexed { i, name ->
                if (i > 0) Hairline()
                val ref = when (i) {
                    0 -> routing.main
                    1 -> routing.subagent
                    2 -> routing.compact
                    else -> routing.vision
                }
                ListRow(name, value = label(ref), showChevron = true, onClick = { routeSlot = i })
            }
        }
        Text("每个模型的默认推理等级在供应商详情中设置；输入框中可临时切换。", style = HoshinoTheme.type.caption, color = c.text3, modifier = Modifier.padding(20.dp))
    }

    if (catalog) {
        HSheet(onDismiss = { catalog = false }, title = "添加供应商") {
            vm.client.catalog.forEach { cp ->
                ListRow(
                    cp.name,
                    subtitle = if (cp.baseUrl.isBlank()) "自定义 Base URL" else "${cp.protocol.label} · ${cp.baseUrl}",
                    showChevron = true,
                    onClick = { catalog = false; onNavigate(Routes.newProvider(cp.id)) },
                )
            }
        }
    }
    routeSlot?.let { slot ->
        val cur = when (slot) {
            0 -> routing.main
            1 -> routing.subagent
            2 -> routing.compact
            else -> routing.vision
        }
        ModelPickerSheet(
            providers = providers,
            selected = cur,
            effort = ReasoningEffort.Off,
            onPick = { ref ->
                val r: ModelRouting = when (slot) {
                    0 -> routing.copy(main = ref)
                    1 -> routing.copy(subagent = ref)
                    2 -> routing.copy(compact = ref)
                    else -> routing.copy(vision = ref)
                }
                vm.run { setRouting(r) }
                routeSlot = null
            },
            onEffort = {},
            onAddProvider = { routeSlot = null; catalog = true },
            onDismiss = { routeSlot = null },
            title = "选择模型",
            showEffort = false,
        )
    }
}

/** 供应商详情：字段 + 模型列表（拉取 / 手动添加 / 每模型默认推理等级）+ 测试连接 */
@Composable
fun ProviderEditScreen(vm: ConfigViewModel, providerId: String, onBack: () -> Unit) {
    app.hoshino.harness.ui.components.SecureKeyScope()
    val c = HoshinoTheme.colors
    val t = HoshinoTheme.type
    val scope = rememberCoroutineScope()
    CollectToasts(vm)

    val isNew = providerId.startsWith("new:")
    val initial: Provider = remember(providerId) {
        if (isNew) {
            val cp = vm.client.catalog.firstOrNull { it.id == providerId.removePrefix("new:") } ?: vm.client.catalog.last()
            Provider("pv${System.currentTimeMillis()}", cp.name, cp.id, cp.protocol, cp.baseUrl, "", emptyList(), true, emptyList())
        } else {
            vm.providers.value.firstOrNull { it.id == providerId }
                ?: Provider(providerId, "未知供应商", "custom", ProviderProtocol.OpenAICompatible, "", "", emptyList(), true, emptyList())
        }
    }
    var name by remember { mutableStateOf(initial.name) }
    var protocol by remember { mutableStateOf(initial.protocol) }
    var baseUrl by remember { mutableStateOf(initial.baseUrl) }
    var apiKey by remember { mutableStateOf(if (initial.apiKey == "••••••••") "" else initial.apiKey) }
    var headers by remember { mutableStateOf(initial.headers.joinToString("\n") { "${it.first}: ${it.second}" }) }
    var enabled by remember { mutableStateOf(initial.enabled) }
    var models by remember { mutableStateOf(initial.models) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var fetching by remember { mutableStateOf(false) }
    var editModel by remember { mutableStateOf<ModelInfo?>(null) }
    var editIsNew by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun draft() = initial.copy(
        name = name.trim().ifBlank { "未命名" },
        protocol = protocol,
        baseUrl = dev.hoshino.bridge.normalizeProviderInput(baseUrl),
        apiKey = apiKey.trim().ifBlank { initial.apiKey },
        headers = headers.lines().filter { it.contains(':') }.map { it.substringBefore(':').trim() to it.substringAfter(':').trim() },
        enabled = enabled,
        models = models,
    )

    PageScaffold(
        if (isNew) "添加供应商" else name,
        onBack,
        actions = {
            HButton("保存", { vm.run("已保存") { upsertProvider(draft()) }; onBack() }, style = BtnStyle.Primary, compact = true)
            Spacer(Modifier.width(8.dp))
        },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            HTextField(name, { name = it }, label = "名称")
            Column {
                Text("协议", style = t.caption, color = c.text2)
                Spacer(Modifier.height(6.dp))
                Segmented(ProviderProtocol.entries, protocol, { it.label }, { protocol = it }, Modifier.fillMaxWidth())
            }
            HTextField(baseUrl, { baseUrl = it }, label = "Base URL", placeholder = "https://api.example.com/v1", mono = true, keyboardType = KeyboardType.Uri)
            HTextField(apiKey, { apiKey = it }, label = "API Key", placeholder = if (initial.apiKey.isNotBlank()) "留空保留已保存的密钥" else "sk-…", mono = true, password = true, hint = "保存在 Android Keystore，仅本机")
            HTextField(headers, { headers = it }, label = "自定义 Header", placeholder = "X-Org-Id: my-org", mono = true, singleLine = false, minLines = 2, hint = "每行一个，格式 Name: Value")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("启用", style = t.body, color = c.text, modifier = Modifier.weight(1f))
                HSwitch(enabled, { enabled = it })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                HButton(if (testing) "测试中…" else "测试连接", {
                    testing = true
                    testResult = null
                    scope.launch {
                        val r = try { vm.client.testProvider(draft()) } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (e: Throwable) { Result.failure(e) }
                        testResult = r.fold({ true to it }, { false to (it.message ?: "失败") })
                        testing = false
                    }
                }, style = BtnStyle.Secondary, compact = true, enabled = !testing, icon = HIcons.Link)
                Spacer(Modifier.width(12.dp))
                testResult?.let { (ok, msg) ->
                    Text(msg, style = t.caption, color = if (ok) c.success else c.danger, modifier = Modifier.weight(1f))
                }
            }
        }

        SectionLabel("模型 · ${models.size}") {
            HButton(if (fetching) "拉取中…" else "从接口拉取", {
                fetching = true
                scope.launch {
                    // m2.9.5: a failed fetch used to throw out of this coroutine and crash the app.
                    try {
                        val fetched = vm.client.fetchModels(draft())
                        val existing = models.map { it.id }.toSet()
                        models = models + fetched.filter { it.id !in existing }
                        vm.toast("拉取到 ${fetched.size} 个模型")
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (e: Throwable) {
                        testResult = false to (e.message ?: "拉取模型失败")
                        vm.toast(e.message?.take(80) ?: "拉取模型失败")
                    } finally {
                        fetching = false
                    }
                }
            }, style = BtnStyle.Ghost, compact = true, enabled = !fetching, icon = HIcons.Refresh)
            HButton("手动添加", {
                editIsNew = true
                editModel = ModelInfo("", "", 128_000, 8_000, false, false, ReasoningEffort.Off)
            }, style = BtnStyle.Ghost, compact = true, icon = HIcons.Plus)
        }
        if (models.isEmpty()) {
            Text("还没有模型。可从接口拉取，或手动添加。", style = t.caption, color = c.text3, modifier = Modifier.padding(horizontal = 20.dp))
        } else {
            GroupCard {
                models.forEachIndexed { i, m ->
                    if (i > 0) Hairline()
                    Row(
                        Modifier.fillMaxWidth().clickable { editIsNew = false; editModel = m }.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(m.displayName.ifBlank { m.id }, style = t.body, color = c.text)
                            Text("${m.id} · ${formatTokens(m.contextLength)} 上下文 · 最大输出 ${formatTokens(m.maxOutput)}", style = t.codeSmall, color = c.text3)
                        }
                        if (m.vision) Tag("图片")
                        if (m.reasoning) {
                            Spacer(Modifier.width(4.dp)); Tag("推理 · 默认${m.defaultEffort.label}")
                        }
                        if (m.webSearchMode != "off" && m.webSearchMethod.isNotEmpty() && m.webSearchMethod != "none") {
                            Spacer(Modifier.width(4.dp)); Tag("自带联网")
                        }
                        Spacer(Modifier.width(4.dp))
                        HIcon(HIcons.ChevronRight, size = 16.dp, tint = c.text3)
                    }
                }
            }
        }
        if (!isNew) {
            Spacer(Modifier.height(24.dp))
            HButton("删除供应商", { confirmDelete = true }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), style = BtnStyle.Secondary, icon = HIcons.Trash)
        }
    }

    editModel?.let { m ->
        ModelEditSheet(
            model = m,
            isNew = editIsNew,
            onProbe = { id -> vm.client.probeWebSearch(draft(), id) },
            onSave = { nm ->
                models = if (editIsNew) models + nm else models.map { if (it.id == m.id) nm else it }
                editModel = null
            },
            onDelete = { models = models.filterNot { it.id == m.id }; editModel = null },
            onDismiss = { editModel = null },
        )
    }
    if (confirmDelete) {
        HDialog("删除供应商？", onDismiss = { confirmDelete = false }, confirmText = "删除", danger = true, onConfirm = {
            confirmDelete = false
            vm.run("已删除") { deleteProvider(initial.id) }
            onBack()
        }) {
            Text("「$name」及其 ${models.size} 个模型配置将被删除。", style = t.body, color = c.text2)
        }
    }
}

@Composable
private fun ModelEditSheet(
    model: ModelInfo,
    isNew: Boolean,
    onProbe: suspend (String) -> String,
    onSave: (ModelInfo) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = HoshinoTheme.colors
    val t = HoshinoTheme.type
    var id by remember { mutableStateOf(model.id) }
    var display by remember { mutableStateOf(model.displayName) }
    var ctx by remember { mutableStateOf(model.contextLength.toString()) }
    var maxOut by remember { mutableStateOf(model.maxOutput.toString()) }
    var vision by remember { mutableStateOf(model.vision) }
    var reasoning by remember { mutableStateOf(model.reasoning) }
    var effort by remember { mutableStateOf(model.defaultEffort) }
    var searchMode by remember { mutableStateOf(dev.hoshino.bridge.WebSearchMode.of(model.webSearchMode)) }
    var searchMethod by remember { mutableStateOf(model.webSearchMethod) }
    var probing by remember { mutableStateOf(false) }
    var probedId by remember { mutableStateOf(model.id) }
    var probeError by remember { mutableStateOf<String?>(null) }
    val probeScope = rememberCoroutineScope()
    HSheet(onDismiss = onDismiss, title = if (isNew) "添加模型" else "编辑模型") {
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            HTextField(id, { id = it }, label = "模型 ID", placeholder = "claude-sonnet-4-5", mono = true)
            HTextField(display, { display = it }, label = "显示名", placeholder = "Claude Sonnet 4.5")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HTextField(ctx, { ctx = it.filter(Char::isDigit) }, Modifier.weight(1f), label = "上下文长度", mono = true, keyboardType = KeyboardType.Number)
                HTextField(maxOut, { maxOut = it.filter(Char::isDigit) }, Modifier.weight(1f), label = "最大输出", mono = true, keyboardType = KeyboardType.Number)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("支持图片", style = t.body, color = c.text, modifier = Modifier.weight(1f))
                HSwitch(vision, { vision = it })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("支持推理", style = t.body, color = c.text, modifier = Modifier.weight(1f))
                HSwitch(reasoning, { reasoning = it; if (!it) effort = ReasoningEffort.Off })
            }
            Column {
                Text("默认推理等级", style = t.caption, color = c.text2)
                Spacer(Modifier.height(6.dp))
                Segmented(ReasoningEffort.entries, effort, { it.label }, { effort = it }, Modifier.fillMaxWidth(), enabled = reasoning)
            }
            Column {
                // Model-hosted web search: preferred over Hoshino's own search tool, which stays as the fallback.
                Text("模型自带联网搜索", style = t.caption, color = c.text2)
                Spacer(Modifier.height(6.dp))
                Segmented(dev.hoshino.bridge.WebSearchMode.entries, searchMode, { it.label }, { searchMode = it }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("检测结果：" + dev.hoshino.bridge.WebSearchMethods.label(searchMethod), style = t.caption, color = c.text3, modifier = Modifier.weight(1f))
                    HButton(if (probing) "检测中…" else "检测", {
                        probing = true; probeError = null
                        probeScope.launch {
                            val target = id.trim()
                            runCatching { onProbe(target) }.onSuccess { searchMethod = it; probedId = target }.onFailure { probeError = it.message ?: "检测失败" }
                            probing = false
                        }
                    }, style = BtnStyle.Ghost, compact = true, enabled = !probing && id.isNotBlank())
                }
                probeError?.let { Text(it, style = t.caption, color = c.danger) }
                Text("自动：使用检测到的方式，不可用时用 Hoshino 内置搜索；强制开：总是尝试模型自带搜索；关：只用内置搜索。检测会向该模型发送几次很小的请求。", style = t.caption, color = c.text3)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!isNew) HButton("删除", onDelete, Modifier.weight(1f), style = BtnStyle.Secondary)
                HButton(
                    "保存",
                    {
                        onSave(
                            ModelInfo(
                                id.trim(), display.trim().ifBlank { id.trim() },
                                ctx.toIntOrNull() ?: 0, maxOut.toIntOrNull() ?: 0, vision, reasoning, effort,
                                searchMode.key, if (id.trim() == probedId) searchMethod else "",
                            ),
                        )
                    },
                    Modifier.weight(1f),
                    style = BtnStyle.Primary,
                    enabled = id.isNotBlank(),
                )
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

