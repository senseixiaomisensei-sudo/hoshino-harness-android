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
    val state by vm.session.collectAsStateWithLifecycle()
    val composer by vm.composer.collectAsStateWithLifecycle()
    val presets by vm.presets.collectAsStateWithLifecycle()
    val providers by vm.providers.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    val shizuku by vm.shizuku.collectAsStateWithLifecycle()
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
    var deleteTarget by remember { mutableStateOf<SessionInfo?>(null) }
    var openTask by remember { mutableStateOf<SubTask?>(null) }

    LaunchedEffect(Unit) { vm.messages.collect { toast(ctx, it) } }

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
        if (t == PermissionTier.FullAccess && tier != PermissionTier.FullAccess) showRisk = true else vm.setTier(t)
    }

    fun sessionAction(s: SessionInfo, a: SessionAction) {
        when (a) {
            SessionAction.Rename -> renameTarget = s
            SessionAction.Fork -> vm.fork(s.id)
            SessionAction.Export -> vm.export(s.id)
            SessionAction.Archive -> vm.setArchived(s.id, true)
            SessionAction.Unarchive -> vm.setArchived(s.id, false)
            SessionAction.Delete -> deleteTarget = s
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
                ApprovalQuickSwitch()
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
                            MenuEntry("复制整个会话为 Markdown",HIcons.Copy) {app.hoshino.harness.ui.components.copyToClipboard(ctx,state.items.joinToString("\n\n"){it.markdownText()})},
                            MenuEntry("截当前屏幕",HIcons.Image) {scope.launch {runCatching{app.hoshino.harness.core.AppSurface.capture()}.onSuccess{app.hoshino.harness.ui.tools.CaptureRequests.selection.value=app.hoshino.harness.ui.tools.CaptureSelection(screen=it);navigate(Routes.SCREENSHOT)}.onFailure{vm.toast(it.message ?: "截图失败")}}},
                            MenuEntry("会话长图",HIcons.Image) {app.hoshino.harness.ui.tools.CaptureRequests.selection.value=app.hoshino.harness.ui.tools.CaptureSelection(items=state.items.toList());navigate(Routes.SCREENSHOT)},
                            MenuEntry(stringResource(R.string.menu_rename), HIcons.Edit) { s?.let { renameTarget = it } },
                            MenuEntry(stringResource(R.string.menu_fork), HIcons.Fork) { s?.let { vm.fork(it.id) } },
                            MenuEntry(stringResource(R.string.menu_export), HIcons.Download) { s?.let { vm.export(it.id) } },
                            MenuEntry(stringResource(R.string.menu_trajectory), HIcons.Layers) { navigate(Routes.TRAJECTORY) },
                            MenuEntry(stringResource(R.string.menu_context), HIcons.Gauge) { showContext = true },
                            MenuEntry(stringResource(R.string.menu_stats), HIcons.Chart) { showStats = true },
                            MenuEntry(stringResource(R.string.menu_archive), HIcons.Archive) { s?.let { vm.setArchived(it.id, true) } },
                            MenuEntry(stringResource(R.string.menu_delete), HIcons.Trash, danger = true) { s?.let { deleteTarget = it } },
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
                    running = state.running,
                    queued = state.queued,
                    presets = presets,
                    tier = tier,
                    modelLabel = modelLabel,
                    contextFraction = state.contextUsage.fraction,
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
                )
                }
            }

            val isHero = currentId == null && state.items.isEmpty()
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
                        MessageList(
                            chatItems = state.items,
                            sessionId = currentId.orEmpty(),
                            actions = MessageActions(
                                onApprove = { id, d -> vm.respondApproval(id, d) },
                                onAnswer = { id, a -> vm.answer(id, a) },
                                onDiff = { id, a -> vm.resolveDiff(id, a) },
                                onOpenSubTask = { openTask = it },
                                onOpenGoals = { navigate(Routes.GOALS) },
                                onOpenSchedules = { navigate(Routes.SCHEDULES) },
                                onScreenshot = {app.hoshino.harness.ui.tools.CaptureRequests.selection.value=app.hoshino.harness.ui.tools.CaptureSelection(items=listOf(it));navigate(Routes.SCREENSHOT)},
                            ),
                            modifier = Modifier.fillMaxWidth().weight(1f),
                        )
                        composerUi(Modifier.padding(horizontal = 12.dp).padding(top = 4.dp, bottom = 10.dp).navigationBarsPadding())
                    }
                }
            }
        }
    }

    // ---------------- 弹层 / 对话框
    if (showTier) TierSheet(tier, shizuku, onPick = { pickTier(it) }, onDismiss = { showTier = false }, supportedTiers=vm.supportedPermissionTiers)
    if (showRisk) {
        FullAccessRiskDialog(
            onConfirm = {
                showRisk = false
                if (shizuku == ShizukuStatus.Ready) vm.setTier(PermissionTier.FullAccess) else showShizuku = true
            },
            onDismiss = { showRisk = false },
        )
    }
    if (showShizuku) {
        ShizukuGuideSheet(
            status = shizuku,
            pendingEnable = true,
            onRefresh = { scope.launch { Hoshino.client.refreshShizuku() } },
            onRequest = { scope.launch { Hoshino.client.requestShizukuPermission() } },
            onEnable = { vm.setTier(PermissionTier.FullAccess); showShizuku = false },
            onDismiss = { showShizuku = false },
            executionAvailable = PermissionTier.FullAccess in vm.supportedPermissionTiers,
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
    if (showContext) ContextUsageSheet(state.contextUsage, onCompact = { vm.compact() }, onDismiss = { showContext = false })
    if (showStats) SessionStatsDialog(state.stats, onDismiss = { showStats = false })
    openTask?.let { SubTaskSheet(it, onDismiss = { openTask = null }) }
    renameTarget?.let { s ->
        TextInputDialog(
            title = stringResource(R.string.rename_title),
            initial = s.title,
            onDismiss = { renameTarget = null },
            onConfirm = { vm.rename(s.id, it); renameTarget = null },
        )
    }
    deleteTarget?.let { s ->
        HDialog(
            title = stringResource(R.string.delete_title),
            onDismiss = { deleteTarget = null },
            confirmText = "删除",
            danger = true,
            onConfirm = { vm.delete(s.id); deleteTarget = null },
        ) {
            Text(stringResource(R.string.delete_message, s.title), style = HoshinoTheme.type.body, color = c.text2)
        }
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

/** 消息流：reverseLayout 保证流式输出时锚定底部；右侧轮次刻度；回到底部浮钮。 */
@Composable
fun MessageList(chatItems: List<app.hoshino.harness.core.ChatItem>, actions: MessageActions, modifier: Modifier = Modifier,sessionId:String="") {
    val c = HoshinoTheme.colors
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val reversed = remember(chatItems) { presentTurns(chatItems).asReversed() }
    val custom by Hoshino.client.uiCustomization.collectAsStateWithLifecycle()
    val atBottom by remember { derivedStateOf { listState.firstVisibleItemIndex <= 1 } }

    Box(modifier) {
        LazyColumn(state = listState, reverseLayout = true, modifier = Modifier.fillMaxSize()) {
            item(key = "bottom-spacer") { Spacer(Modifier.height(8.dp)) }
            if(sessionId.isNotBlank())item(key="delivered-files"){DeliveredFiles(sessionId)}
            items(reversed, key = { it.id }) { turn ->
                Column {
                    turn.user?.let{ChatItemView(it,actions)}
                    ProcessTimeline(turn.process,actions,custom.raw.optJSONObject("process")?.optString("display","collapsed")?:"collapsed",custom.raw.optJSONObject("process")?.optJSONObject("types"),turn.actions.any{it is app.hoshino.harness.core.ChatItem.Approval && it.decision==null})
                    turn.actions.forEach{ChatItemView(it,actions)}
                    turn.finalItems.forEach{ChatItemView(it,actions)}
                }
            }
            item(key = "top-spacer") { Spacer(Modifier.height(12.dp)) }
        }

        // 轮次导航刻度（≥3 轮时显示）
        val userIdx = reversed.mapIndexedNotNull { i, it -> if (it.user!=null) i + if(sessionId.isBlank())1 else 2 else null }
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
