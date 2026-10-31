package app.hoshino.harness.ui.chat

import app.hoshino.harness.R
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hoshino.harness.core.ConnectionState
import app.hoshino.harness.core.PermissionTier
import app.hoshino.harness.core.SessionInfo
import app.hoshino.harness.core.ShizukuStatus
import app.hoshino.harness.core.SystemAccess
import app.hoshino.harness.core.SlashCommand
import app.hoshino.harness.core.SubTask
import app.hoshino.harness.ui.components.HDialog
import app.hoshino.harness.ui.components.HIcon
import app.hoshino.harness.ui.components.Hairline
import app.hoshino.harness.ui.components.HoshinoMark
import app.hoshino.harness.ui.components.IconBtn
import app.hoshino.harness.ui.components.Pill
import app.hoshino.harness.ui.components.TextInputDialog
import app.hoshino.harness.ui.components.toast
import app.hoshino.harness.ui.components.markdownText
import app.hoshino.harness.ui.drawer.SessionAction
import app.hoshino.harness.ui.drawer.Sidebar
import app.hoshino.harness.ui.nav.Routes
import app.hoshino.harness.ui.permission.FullAccessRiskDialog
import app.hoshino.harness.ui.permission.ShizukuGuideSheet
import app.hoshino.harness.ui.permission.TierSheet
import app.hoshino.harness.ui.theme.HIcons
import app.hoshino.harness.ui.theme.HoshinoShapes
import app.hoshino.harness.ui.theme.HoshinoTheme
import app.hoshino.harness.core.Hoshino
import kotlinx.coroutines.launch
import app.hoshino.harness.ui.diy.DiySlot

@Composable
fun ChatScreen(
    vm: ChatViewModel,
    isDark: Boolean,
    onToggleTheme: () -> Unit,
    onNavigate: (String) -> Unit,
) {
    val c = HoshinoTheme.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pickAttachment=attachmentPicker(vm)
    val drawer = rememberDrawerState(DrawerValue.Closed)

    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val workspaces by vm.workspaces.collectAsStateWithLifecycle()
    val currentId by vm.currentId.collectAsStateWithLifecycle()
    val current by vm.current.collectAsStateWithLifecycle()
    // 会话状态在流式输出时每帧更新：这里只持有 State 对象，不在 ChatScreen 本体里读取它，
    // 否则顶栏、输入框等整屏每帧重组。需要的字段用 derivedStateOf 取出，值不变就不触发重组；消息列表在自己的作用域里读取。
    val sessionState = vm.session.collectAsStateWithLifecycle()
    val running by remember { derivedStateOf { sessionState.value.running } }
    val queued by remember { derivedStateOf { sessionState.value.queued } }
    val contextUsage by remember { derivedStateOf { sessionState.value.contextUsage } }
    val sessionEmpty by remember { derivedStateOf { sessionState.value.turns.isEmpty() } }
    val composer by vm.composer.collectAsStateWithLifecycle()
    val presets by vm.presets.collectAsStateWithLifecycle()
    val providers by vm.providers.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    val shizuku by vm.shizuku.collectAsStateWithLifecycle()
    val systemAccess by vm.systemAccess.collectAsStateWithLifecycle()
    val slash by vm.slashCommands.collectAsStateWithLifecycle()
    val custom by vm.uiCustomization.collectAsStateWithLifecycle()
    val files by vm.fileSuggestions.collectAsStateWithLifecycle()

    var input by remember { mutableStateOf(TextFieldValue("")) }
    val shared by app.hoshino.harness.core.M2Intents.pending.collectAsStateWithLifecycle()
    LaunchedEffect(shared?.id){shared?.let{task->if(task.newTask)vm.newSession();if(task.text.isNotBlank())input=TextFieldValue(if(input.text.isBlank())task.text else input.text+"\n"+task.text);task.attachment?.let{vm.attach(it)};app.hoshino.harness.core.M2Intents.pending.value=null}}
    val voice=androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()){result->if(result.resultCode==android.app.Activity.RESULT_OK){val text=result.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty();if(text.isNotBlank())input=TextFieldValue(if(input.text.isBlank())text else input.text+" "+text)}}
    var showTier by remember { mutableStateOf(false) }
    var showRisk by remember { mutableStateOf(false) }
    var showShizuku by remember { mutableStateOf(false) }
    var showModel by remember { mutableStateOf(false) }
    var showContext by remember { mutableStateOf(false) }
    var showStats by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<SessionInfo?>(null) }
    var deleteTargets by remember { mutableStateOf<List<SessionInfo>>(emptyList()) }
    val approval by vm.approval.collectAsStateWithLifecycle()
    val summarizingSessions by app.hoshino.harness.core.Steering.summarizing.collectAsStateWithLifecycle()
    var showApproval by remember { mutableStateOf(false) }
    var riskFor by remember { mutableStateOf<Pair<app.hoshino.harness.core.ApprovalMode, app.hoshino.harness.core.ApprovalScope>?>(null) }
    LaunchedEffect(Unit) { vm.exports.collect { e -> runCatching { e.shareFile?.let { f -> app.hoshino.harness.core.AppMedia(ctx).shareFile(f, e.format.mime) } } } }
    var openTask by remember { mutableStateOf<SubTask?>(null) }

    // Kernel/long errors: show the full text (a Toast clips it to "session … is …") with 重试.
    var errorText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.messages.collect { if (vm.isFullError(it)) errorText = it else toast(ctx, it) } }
    errorText?.let { text ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { errorText = null },
            title = { Text("请求未完成") },
            text = { androidx.compose.foundation.text.selection.SelectionContainer { Text(text) } },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { errorText = null; vm.retryLast() }, enabled = vm.canRetry()) { Text("重试") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { errorText = null }) { Text("关闭") } },
        )
    }

    val tier = (current?.tier ?: composer.tier).takeIf { it in vm.supportedPermissionTiers } ?: PermissionTier.WorkspaceWrite
    val presetName = presets.firstOrNull { it.id == composer.presetId }?.name ?: "Standard"
    val modelInfo = vm.modelInfo(composer.model)
    val modelLabel = if (modelInfo == null) "选择模型" else if (modelInfo.reasoning || composer.effort!=app.hoshino.harness.core.ReasoningEffort.Off) "${modelInfo.displayName} · ${composer.effort.label}" else modelInfo.displayName

    fun navigate(route: String) {
        scope.launch { drawer.close() }
        onNavigate(route)
    }

    fun pickTier(t: PermissionTier) {
        showTier = false
        // m2.9.2: in a no-approval session switching to full access asks nothing (system access must still be authorized).
        if (t == PermissionTier.FullAccess && tier != PermissionTier.FullAccess) {
            if (!app.hoshino.harness.core.ApprovalModeRules.fullAccessNeedsRiskDialog(approval)) { if (SystemAccess.authorized(systemAccess)) vm.setTier(PermissionTier.FullAccess) else showShizuku = true }
            else showRisk = true
        } else vm.setTier(t)
    }

    fun sessionAction(s: SessionInfo, a: SessionAction) {
        when (a) {
            SessionAction.Rename -> renameTarget = s
            SessionAction.Fork -> vm.fork(s.id)
            SessionAction.Export -> vm.export(s.id, app.hoshino.harness.core.SessionExportFormat.Markdown)
            SessionAction.ExportJson -> vm.export(s.id, app.hoshino.harness.core.SessionExportFormat.Json)
            SessionAction.Pin -> vm.setPinned(s.id, true)
            SessionAction.Unpin -> vm.setPinned(s.id, false)
            SessionAction.Archive -> vm.setArchived(s.id, true)
            SessionAction.Unarchive -> vm.setArchived(s.id, false)
            SessionAction.Delete -> deleteTargets = listOf(s)
        }
    }

    fun runSlash(cmd: SlashCommand) {
        custom.commands.firstOrNull{it.name==cmd.name}?.let{input=TextFieldValue(it.prompt,TextRange(it.prompt.length));return}
        val name = cmd.name
        when (name) {
            "/goal", "/plan", "/schedule" -> input = TextFieldValue("$name ", TextRange(name.length + 1))
            else -> {
                input = TextFieldValue("")
                when (name) {
                    "/permission" -> showTier = true
                    "/export" -> current?.let { vm.export(it.id) } ?: vm.toast("当前没有会话")
                    "/feedback" -> navigate(Routes.FEEDBACK)
                    "/skills" -> navigate(Routes.SKILLS)
                    "/mcp" -> navigate(Routes.MCP)
                    "/compact" -> vm.compact()
                    else -> input = TextFieldValue("$name ", TextRange(name.length + 1))
                }
            }
        }
    }

    fun doSend() {
        val text = input.text.trim()
        val exact = slash.firstOrNull { it.name == text && it.name !in setOf("/goal", "/plan", "/schedule") }
        if (exact != null) {
            runSlash(exact); return
        }
        vm.send(text) { accepted -> if(accepted && input.text.trim()==text)input=TextFieldValue("") }
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet(
                drawerShape = RectangleShape,
                drawerContainerColor = c.bg,
                drawerTonalElevation = 0.dp,
                windowInsets = WindowInsets(0, 0, 0, 0),
            ) {
                DiySlot("sidebar",onNavigate={navigate(it)},onPrompt={input=TextFieldValue(it)}) { Sidebar(
                    customization = custom,
                    onSearchSessions = vm::searchSessions,
                    sessions = sessions,
                    workspaces = workspaces,
                    currentId = currentId,
                    isDark = isDark,
                    onNewSession = { vm.newSession(); scope.launch { drawer.close() } },
                    onOpenSession = { vm.openSession(it); scope.launch { drawer.close() } },
                    onNavigate = { navigate(it) },
                    onSessionAction = { s, a -> sessionAction(s, a) },
                    onDeleteSessions = { deleteTargets = it },
                    onAddWorkspace = { n, p -> vm.addWorkspace(n, p) },
                    onToggleTheme = onToggleTheme,
                )
                }
            }
        },
    ) {
        Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding()) {
            // ---------------- 顶栏
            DiySlot("topbar",onNavigate={navigate(it)},onPrompt={input=TextFieldValue(it)}) { Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBtn(HIcons.Menu, stringResource(R.string.cd_sidebar), { scope.launch { drawer.open() } })
                app.hoshino.harness.ui.components.HButton("语音",{runCatching{voice.launch(android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM))}.onFailure{vm.toast("系统没有可用的语音识别应用")}},compact=true)
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(current?.title ?: stringResource(R.string.new_session), style = HoshinoTheme.type.bodyMedium, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ConnectionDot(connection == ConnectionState.Connected, connection == ConnectionState.Connecting)
                        Spacer(Modifier.width(5.dp))
                        Text(
                            "${connection.label} · $presetName${if (composer.planMode) stringResource(R.string.plan_suffix) else ""} · ",
                            style = HoshinoTheme.type.caption, color = c.text3, maxLines = 1,
                        )
                        Text(
                            tier.label,
                            style = HoshinoTheme.type.caption,
                            color = if (tier == PermissionTier.FullAccess) c.danger else c.text3,
                            maxLines = 1,
                        )
                    }
                }
                IconBtn(HIcons.Edit2, stringResource(R.string.new_session), { vm.newSession(); input = TextFieldValue("") })
                Box {
                    var menu by remember { mutableStateOf(false) }
                    IconBtn(HIcons.More, stringResource(R.string.cd_more), { menu = true })
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        val s = current
                        val entries = listOf(
                            MenuEntry("复制整个会话为 Markdown",HIcons.Copy) {app.hoshino.harness.ui.components.copyToClipboard(ctx,sessionState.value.items.joinToString("\n\n"){it.markdownText()})},
                            MenuEntry("截当前屏幕",HIcons.Image) {scope.launch {runCatching{app.hoshino.harness.core.AppSurface.capture()}.onSuccess{app.hoshino.harness.ui.tools.CaptureRequests.selection.value=app.hoshino.harness.ui.tools.CaptureSelection(screen=it);navigate(Routes.SCREENSHOT)}.onFailure{vm.toast(it.message ?: "截图失败")}}},
                            MenuEntry("会话长图",HIcons.Image) {app.hoshino.harness.ui.tools.CaptureRequests.selection.value=app.hoshino.harness.ui.tools.CaptureSelection(items=sessionState.value.items.toList());navigate(Routes.SCREENSHOT)},
                            MenuEntry(stringResource(R.string.menu_rename), HIcons.Edit) { s?.let { renameTarget = it } },
                            MenuEntry(stringResource(R.string.menu_fork), HIcons.Fork) { s?.let { vm.fork(it.id) } },
                            MenuEntry(stringResource(R.string.menu_export), HIcons.Download) { s?.let { vm.export(it.id) } },
                            MenuEntry(stringResource(R.string.menu_trajectory), HIcons.Layers) { navigate(Routes.TRAJECTORY) },
                            MenuEntry(stringResource(R.string.menu_context), HIcons.Gauge) { showContext = true },
                            MenuEntry(stringResource(R.string.menu_stats), HIcons.Chart) { showStats = true },
                            MenuEntry(stringResource(R.string.menu_archive), HIcons.Archive) { s?.let { vm.setArchived(it.id, true) } },
                            MenuEntry(stringResource(R.string.menu_delete), HIcons.Trash, danger = true) { s?.let { deleteTargets = listOf(it) } },
                        )
                        entries.forEachIndexed { i, e ->
                            if (i == 6) Hairline()
                            val enabled = s != null || i == 4
                            DropdownMenuItem(
                                text = { Text(e.label, style = HoshinoTheme.type.body, color = if (!enabled) c.text3 else if (e.danger) c.danger else c.text) },
                                leadingIcon = { HIcon(e.icon, size = 18.dp, tint = if (e.danger) c.danger else c.text2) },
                                enabled = enabled,
                                onClick = { menu = false; e.onClick() },
                            )
                        }
                    }
                }
            }
            Hairline()

            }
            val composerUi: @Composable (Modifier) -> Unit = { m ->
                DiySlot("input",onNavigate={navigate(it)},onPrompt={input=TextFieldValue(it)}) {
                Composer(
                    value = input,
                    onValueChange = { input = it },
                    state = composer,
                    running = running,
                    queued = queued,
                    presets = presets,
                    tier = tier,
                    modelLabel = modelLabel,
                    contextFraction = contextUsage.fraction,
                    slashCommands = slash,
                    fileSuggestions = files,
                    onFileQuery = { vm.searchFiles(it) },
                    onSend = { doSend() },
                    onStop = { vm.stop() },
                    onSteer = { vm.steer(it) },
                    onRemoveQueued = { vm.removeQueued(it) },
                    onAddAttachment = pickAttachment,
                    onRemoveAttachment = { vm.removeAttachment(it) },
                    onPickPreset = { vm.setPreset(it) },
                    onTogglePlan = { vm.setPlanMode(it) },
                    onManagePresets = { navigate(Routes.PRESETS) },
                    onOpenTier = { showTier = true },
                    onOpenModel = { showModel = true },
                    onOpenContext = { showContext = true },
                    onSlash = { runSlash(it) },
                    modifier = m,
                    approval = approval,
                    onOpenApproval = { showApproval = true },
                    summarizing = currentId != null && currentId in summarizingSessions,
                )
                }
            }

            val isHero = currentId == null && sessionEmpty
            Box(Modifier.fillMaxWidth().weight(1f).imePadding()) {
                if (isHero) {
                    Hero(
                        customization = custom,
                        onCustomPrompt = {input=TextFieldValue(it,TextRange(it.length))},
                        workspaces = workspaces.map { it.id to it.name },
                        selectedWorkspace = composer.workspaceId,
                        onSelectWorkspace = { vm.selectWorkspace(it) },
                        onAddWorkspace = { navigate(Routes.CHAT); scope.launch { drawer.open() } },
                        composer = { composerUi(Modifier.fillMaxWidth()) },
                        onChip = { i ->
                            when (i) {
                                0 -> input = TextFieldValue("/plan ", TextRange(6))
                                1 -> input = TextFieldValue("/goal ", TextRange(6))
                                2 -> navigate(Routes.MCP)
                                3 -> navigate(Routes.MODELS)
                            }
                        },
                    )
                } else {
                    Column(Modifier.fillMaxSize()) {
                        val messageActions = remember(vm) {
                            MessageActions(
                                onApprove = { id, d -> vm.respondApproval(id, d) },
                                onAnswer = { id, a -> vm.answer(id, a) },
                                onDiff = { id, a -> vm.resolveDiff(id, a) },
                                onOpenSubTask = { openTask = it },
                                onOpenGoals = { navigate(Routes.GOALS) },
                                onOpenSchedules = { navigate(Routes.SCHEDULES) },
                                onScreenshot = {app.hoshino.harness.ui.tools.CaptureRequests.selection.value=app.hoshino.harness.ui.tools.CaptureSelection(items=listOf(it));navigate(Routes.SCREENSHOT)},
                            )
                        }
                        app.hoshino.harness.ui.phone.PhoneScreenBar(onOpen = { navigate(Routes.VIRTUAL_SCREEN) })
                        SessionMessages(
                            session = sessionState,
                            sessionId = currentId.orEmpty(),
                            actions = messageActions,
                            modifier = Modifier.fillMaxWidth().weight(1f),
                        )
                        RunSummary(currentId.orEmpty())
                        composerUi(Modifier.padding(horizontal = 12.dp).padding(top = 4.dp, bottom = 10.dp).navigationBarsPadding())
                    }
                }
                PhonePip(onExpand = { navigate(Routes.VIRTUAL_SCREEN) })
            }
        }
    }

    // ---------------- 弹层 / 对话框
    if (showTier) TierSheet(tier, shizuku, onPick = { pickTier(it) }, onDismiss = { showTier = false }, supportedTiers=vm.supportedPermissionTiers, systemAccess = systemAccess)
    if (showRisk) {
        FullAccessRiskDialog(
            onConfirm = {
                showRisk = false
                // m2.8.1: Shizuku 或 内置无线调试助手任一就绪即可；用户仍需在此会话手动确认风险。
                if (SystemAccess.authorized(systemAccess)) vm.setTier(PermissionTier.FullAccess) else showShizuku = true
            },
            onDismiss = { showRisk = false },
        )
    }
    if (showShizuku) {
        ShizukuGuideSheet(
            status = shizuku,
            pendingEnable = true,
            onRefresh = { scope.launch { Hoshino.client.refreshShizuku(); Hoshino.client.refreshSystemAccess() } },
            onRequest = { scope.launch { Hoshino.client.requestShizukuPermission() } },
            onEnable = { vm.setTier(PermissionTier.FullAccess); showShizuku = false },
            onDismiss = { showShizuku = false },
            executionAvailable = PermissionTier.FullAccess in vm.supportedPermissionTiers,
            systemAccess = systemAccess,
            onSetupPhone = { showShizuku = false; navigate(Routes.PHONE_SETUP) },
        )
    }
    if (showModel) {
        ModelPickerSheet(
            providers = providers,
            selected = composer.model,
            effort = composer.effort,
            onPick = { vm.setModel(it) },
            onEffort = { vm.setEffort(it) },
            onAddProvider = { showModel = false; navigate(Routes.MODELS) },
            onDismiss = { showModel = false },
        )
    }
    if (showContext) ContextUsageSheet(contextUsage, onCompact = { vm.compact() }, onDismiss = { showContext = false })
    if (showStats) SessionStatsDialog(sessionState.value.stats, onDismiss = { showStats = false })
    openTask?.let { SubTaskSheet(it, onDismiss = { openTask = null }) }
    renameTarget?.let { s ->
        TextInputDialog(
            title = stringResource(R.string.rename_title),
            initial = s.title,
            onDismiss = { renameTarget = null },
            onConfirm = { vm.rename(s.id, it); renameTarget = null },
        )
    }
    if (deleteTargets.isNotEmpty()) {
        val targets = deleteTargets
        HDialog(
            title = if (targets.size == 1) stringResource(R.string.delete_title) else "删除 ${targets.size} 个会话？",
            onDismiss = { deleteTargets = emptyList() },
            confirmText = "删除",
            danger = true,
            onConfirm = { vm.delete(targets.map { it.id }); deleteTargets = emptyList() },
        ) {
            Text(app.hoshino.harness.ui.drawer.deleteConfirmation(targets, currentId), style = HoshinoTheme.type.body, color = c.text2)
        }
    }
    if (showApproval) {
        ApprovalSheet(
            state = approval,
            hasSession = currentId != null,
            onPick = { mode, scope ->
                showApproval = false
                if (vm.needsRiskConfirmation(mode)) riskFor = mode to scope else vm.setApprovalMode(mode, scope)
            },
            onDismiss = { showApproval = false },
        )
    }
    riskFor?.let { (mode, scope) ->
        NoApprovalRiskDialog(
            scope = scope,
            onConfirm = { vm.acknowledgeNoApproval(app.hoshino.harness.core.ApprovalModeRules.NO_APPROVAL_ACK_FOREVER); vm.setApprovalMode(mode, scope); riskFor = null },
            onDismiss = { riskFor = null },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Hero(
    customization: app.hoshino.harness.core.UiCustomization,
    onCustomPrompt: (String)->Unit,
    workspaces: List<Pair<String, String>>,
    selectedWorkspace: String,
    onSelectWorkspace: (String) -> Unit,
    onAddWorkspace: () -> Unit,
    composer: @Composable () -> Unit,
    onChip: (Int) -> Unit,
) {
    val c = HoshinoTheme.colors
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Spacer(Modifier.height(48.dp))
        HoshinoMark(56.dp)
        Spacer(Modifier.height(20.dp))
        Text(customization.tagline ?: stringResource(R.string.hero_title), style = HoshinoTheme.type.headline, color = c.text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Box {
            var open by remember { mutableStateOf(false) }
            Pill(
                workspaces.firstOrNull { it.first == selectedWorkspace }?.second ?: stringResource(R.string.pick_workspace),
                icon = HIcons.Folder,
                trailing = HIcons.ChevronDown,
                onClick = { open = true },
            )
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                workspaces.forEach { (id, name) ->
                    DropdownMenuItem(
                        text = { Text(name, style = HoshinoTheme.type.body, color = c.text) },
                        leadingIcon = { HIcon(HIcons.Folder, size = 18.dp) },
                        trailingIcon = { if (id == selectedWorkspace) HIcon(HIcons.Check, size = 16.dp, tint = c.accent) },
                        onClick = { open = false; onSelectWorkspace(id) },
                    )
                }
                Hairline()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.add_workspace_ellipsis), style = HoshinoTheme.type.body, color = c.text) },
                    leadingIcon = { HIcon(HIcons.Plus, size = 18.dp) },
                    onClick = { open = false; onAddWorkspace() },
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        Box(Modifier.widthIn(max = 640.dp)) { composer() }
        Spacer(Modifier.height(16.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.widthIn(max = 640.dp),
        ) {
            if(customization.capsules!=null)customization.capsules.forEach{capsule->Pill(capsule.label,icon=HIcons.Sparkle,onClick={onCustomPrompt(capsule.prompt)})}
            else {
                Pill(stringResource(R.string.chip_plan), icon = HIcons.ListIcon, onClick = { onChip(0) })
                Pill(stringResource(R.string.chip_goal), icon = HIcons.Target, onClick = { onChip(1) })
                Pill(stringResource(R.string.chip_mcp), icon = HIcons.Server, onClick = { onChip(2) })
                Pill(stringResource(R.string.chip_provider), icon = HIcons.Cpu, onClick = { onChip(3) })
            }
        }
        Spacer(Modifier.height(48.dp))
    }
}

/** 过程组显示配置（来自 UI 覆盖 DSL）；包成不可变类型，避免 JSONObject/Map 参数让每一行都无法跳过重组。 */
@androidx.compose.runtime.Immutable
data class ProcessDisplay(val display: String = "collapsed", val types: Map<String, Boolean> = emptyMap())

/** 测试钩子：每次某一行实际重组时回调该行 key（生产环境为 null，零开销）。 */
val LocalRecomposeProbe = androidx.compose.runtime.staticCompositionLocalOf<((String) -> Unit)?> { null }

/** 在自己的重组作用域里读取会话状态：流式更新只重组消息列表，不牵动 ChatScreen 其余部分。 */
@Composable
private fun SessionMessages(session: androidx.compose.runtime.State<app.hoshino.harness.core.SessionState>, actions: MessageActions, modifier: Modifier = Modifier, sessionId: String = "") {
    MessageList(state = session.value, actions = actions, modifier = modifier, sessionId = sessionId)
}

/** 兼容入口：只读视图传入扁平条目。 */
@Composable
fun MessageList(chatItems: List<app.hoshino.harness.core.ChatItem>, actions: MessageActions, modifier: Modifier = Modifier, sessionId: String = "") {
    val state = remember(chatItems) { app.hoshino.harness.core.SessionState.of(chatItems) }
    MessageList(state = state, actions = actions, modifier = modifier, sessionId = sessionId)
}

/**
 * 消息流：reverseLayout 保证流式输出时锚定底部；右侧轮次刻度；回到底部浮钮。
 *
 * 每轮拆成多行（用户消息 / 过程组 / 动作卡 / 回答 / 文件卡 / 用量），每行一个 LazyColumn item，带稳定 key 与 contentType。
 * [TurnPresenter] 按轮次实例缓存：流式更新只重算当前轮，已完成轮次的行对象不变，Compose 直接跳过。
 * 交付文件卡渲染在产生它的那一轮的回答下面（不再常驻列表底部）。
 */
@Composable
fun MessageList(state: app.hoshino.harness.core.SessionState, actions: MessageActions, modifier: Modifier = Modifier, sessionId: String = "") {
    val c = HoshinoTheme.colors
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val presenter = remember(sessionId) { TurnPresenter() }
    val collapse by ChatDisplayPrefs.collapseApprovals.collectAsStateWithLifecycle()
    val turns = remember(presenter, state.turns, state.deliveries, state.turnMarks, collapse) { presenter.present(state, collapse) }
    val rows = remember(turns) { flattenRowsReversed(turns) }
    val custom by Hoshino.client.uiCustomization.collectAsStateWithLifecycle()
    val processDisplay = remember(custom) {
        val process = custom.raw.optJSONObject("process")
        val types = process?.optJSONObject("types")
        ProcessDisplay(process?.optString("display", "collapsed") ?: "collapsed", types?.keys()?.asSequence()?.associateWith { types.optBoolean(it, false) }.orEmpty())
    }
    val atBottom by remember { derivedStateOf { listState.firstVisibleItemIndex <= 1 } }

    Box(modifier) {
        androidx.compose.runtime.CompositionLocalProvider(LocalChatSessionId provides sessionId) {
            LazyColumn(state = listState, reverseLayout = true, modifier = Modifier.fillMaxSize()) {
                item(key = "bottom-spacer", contentType = "spacer") { Spacer(Modifier.height(8.dp)) }
                items(rows, key = { it.key }, contentType = { it.contentType }) { row -> ChatRowView(row, actions, processDisplay) }
                item(key = "top-spacer", contentType = "spacer") { Spacer(Modifier.height(12.dp)) }
            }
        }

        // 轮次导航刻度（≥3 轮时显示）；+1 是底部占位行。
        val userIdx = remember(rows) { rows.mapIndexedNotNull { i, r -> if (r is ChatRow.UserRow) i + 1 else null } }
        if (userIdx.size >= 3) {
            Column(
                Modifier.align(Alignment.CenterEnd).padding(end = 2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                userIdx.asReversed().forEach { idx ->
                    Box(
                        Modifier.size(width = 20.dp, height = 12.dp).clickable { scope.launch { listState.animateScrollToItem(idx) } },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.size(width = 10.dp, height = 2.dp).clip(HoshinoShapes.pill).background(c.text3))
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = !atBottom,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
        ) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(c.surface).border(1.dp, c.line, CircleShape)
                    .clickable { scope.launch { listState.animateScrollToItem(0) } },
                contentAlignment = Alignment.Center,
            ) {
                HIcon(HIcons.ArrowDown, size = 18.dp, tint = c.text)
            }
        }
    }
}

@Composable
private fun ChatRowView(row: ChatRow, actions: MessageActions, process: ProcessDisplay) {
    LocalRecomposeProbe.current?.let { probe -> androidx.compose.runtime.SideEffect { probe(row.key) } }
    when (row) {
        is ChatRow.UserRow -> ChatItemView(row.item, actions)
        is ChatRow.ProcessRow -> ProcessTimeline(row, actions, process.display, process.types)
        is ChatRow.ItemRow -> ChatItemView(row.item, actions)
        is ChatRow.FilesRow -> DeliveredFiles(row.files)
    }
}
