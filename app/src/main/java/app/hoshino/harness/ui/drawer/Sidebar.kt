package app.hoshino.harness.ui.drawer

import app.hoshino.harness.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.hoshino.harness.core.SessionInfo
import app.hoshino.harness.core.Workspace
import app.hoshino.harness.core.SessionSearchResults
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import app.hoshino.harness.ui.chat.ActionSheet
import app.hoshino.harness.ui.chat.MenuEntry
import app.hoshino.harness.ui.chat.Spinner
import app.hoshino.harness.ui.components.HIcon
import app.hoshino.harness.ui.components.HTextField
import app.hoshino.harness.ui.components.Hairline
import app.hoshino.harness.ui.components.HoshinoWordmark
import app.hoshino.harness.ui.components.IconBtn
import app.hoshino.harness.ui.components.TextInputDialog
import app.hoshino.harness.ui.components.longPressClickable
import app.hoshino.harness.ui.components.relativeTime
import app.hoshino.harness.ui.nav.Routes
import app.hoshino.harness.ui.theme.HIcons
import app.hoshino.harness.ui.theme.HoshinoShapes
import app.hoshino.harness.ui.theme.HoshinoTheme

enum class SessionAction { Rename, Fork, Export, Archive, Unarchive, Delete }

enum class SessionSort(val label: String) { Updated("按更新时间"), Created("按创建时间"), Title("按标题") }

@Composable
fun Sidebar(
    sessions: List<SessionInfo>,
    workspaces: List<Workspace>,
    currentId: String?,
    isDark: Boolean,
    onNewSession: () -> Unit,
    onOpenSession: (String) -> Unit,
    onNavigate: (String) -> Unit,
    onSessionAction: (SessionInfo, SessionAction) -> Unit,
    onAddWorkspace: (String, String) -> Unit,
    onToggleTheme: () -> Unit,
    modifier: Modifier = Modifier,
    onSearchSessions: suspend (String)->SessionSearchResults = { SessionSearchResults(emptyList()) },
) {
    val c = HoshinoTheme.colors
    val t = HoshinoTheme.type
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(SessionSort.Updated) }
    var onlyRunning by rememberSaveable { mutableStateOf(false) }
    var showArchived by rememberSaveable { mutableStateOf(false) }
    var showTools by rememberSaveable { mutableStateOf(false) }
    var collapsed by remember { mutableStateOf(setOf<String>()) }
    var actionFor by remember { mutableStateOf<SessionInfo?>(null) }
    var sortMenu by remember { mutableStateOf(false) }
    var addWs by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf(SessionSearchResults(emptyList())) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    val needle=query.trim()
    LaunchedEffect(needle,sessions.size) {
        results=SessionSearchResults(emptyList());searchError=null
        if(needle.isNotEmpty()) {
            searching=true;delay(250)
            try {results=onSearchSessions(needle)} catch(cancelled:CancellationException){throw cancelled} catch(failure:Exception){searchError=failure.message ?: "历史会话搜索失败"} finally {searching=false}
        } else searching=false
    }
    val hits=results.items.associateBy{it.sessionId}
    val groups=workspaces+sessions.map{it.workspaceId}.distinct().filter{id->workspaces.none{it.id==id}}.map{Workspace(it,"历史工作区","")}

    val filtered = sessions
        .filter { needle.isBlank() || it.title.contains(needle, ignoreCase = true) || it.id in hits }
        .filter { !onlyRunning || it.running }
        .let { list ->
            when (sort) {
                SessionSort.Updated -> list.sortedByDescending { it.updatedAt }
                SessionSort.Created -> list.sortedByDescending { it.createdAt }
                SessionSort.Title -> list.sortedBy { it.title }
            }
        }
    val active = filtered.filter { !it.archived }
    val archived = filtered.filter { it.archived }

    Column(modifier.fillMaxHeight().width(304.dp).background(c.bg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            HoshinoWordmark(24.dp, Modifier.weight(1f))
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            item {
                Row(
                    Modifier.padding(horizontal = 12.dp).fillMaxWidth().heightIn(min = 44.dp).clip(HoshinoShapes.card)
                        .background(c.surface).clickable(onClick = onNewSession).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HIcon(HIcons.Edit2, size = 18.dp, tint = c.text)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.new_session), style = t.bodyMedium, color = c.text)
                }
                Spacer(Modifier.height(8.dp))
                NavEntry(HIcons.Plug, stringResource(R.string.nav_plugins)) { onNavigate(Routes.PLUGINS) }
                NavEntry(HIcons.Sparkle, stringResource(R.string.nav_skills)) { onNavigate(Routes.SKILLS) }
                NavEntry(HIcons.Server, stringResource(R.string.nav_mcp)) { onNavigate(Routes.MCP) }
                NavEntry(HIcons.Clock, stringResource(R.string.nav_schedules)) { onNavigate(Routes.SCHEDULES) }
                NavEntry(HIcons.Target, stringResource(R.string.nav_goals)) { onNavigate(Routes.GOALS) }
                NavEntry(HIcons.Terminal, stringResource(R.string.nav_tools), trailing = if (showTools) HIcons.ChevronUp else HIcons.ChevronDown) { showTools = !showTools }
                if (showTools) {
                    NavEntry(null, stringResource(R.string.nav_terminal), indent = true) { onNavigate(Routes.TERMINAL) }
                    NavEntry(null, "文件", indent = true) { onNavigate(Routes.FILES) }
                    NavEntry(null, "截图预览", indent = true) { onNavigate(Routes.SCREENSHOT) }
                    NavEntry(null, stringResource(R.string.nav_hooks), indent = true) { onNavigate(Routes.HOOKS) }
                    NavEntry(null, stringResource(R.string.nav_inspector), indent = true) { onNavigate(Routes.INSPECTOR) }
                    NavEntry(null, stringResource(R.string.nav_web), indent = true) { onNavigate(Routes.WEB) }
                    NavEntry(null, stringResource(R.string.nav_lsp), indent = true) { onNavigate(Routes.LSP) }
                }
                Spacer(Modifier.height(8.dp))
                HTextField(query, { query = it }, Modifier.padding(horizontal = 12.dp), placeholder = stringResource(R.string.search_sessions), leading = HIcons.Search)
                if(searching)Text("正在搜索历史内容…",style=t.caption,color=c.text3,modifier=Modifier.padding(horizontal=20.dp,vertical=4.dp))
                searchError?.let{Text(it,style=t.caption,color=c.danger,modifier=Modifier.padding(horizontal=20.dp,vertical=4.dp))}
                if(results.hasMore)Text("还有更多结果，请输入更具体的关键词",style=t.caption,color=c.text3,modifier=Modifier.padding(horizontal=20.dp,vertical=4.dp))
                if(onlyRunning)Text("仅看运行中 · 点击清除此筛选",style=t.caption,color=c.accent,modifier=Modifier.clickable{onlyRunning=false}.padding(horizontal=20.dp,vertical=4.dp))
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.workspaces), style = t.caption.copy(fontWeight = FontWeight.Medium), color = c.text3, modifier = Modifier.weight(1f))
                    Box {
                        IconBtn(HIcons.Sort, "排序与筛选", { sortMenu = true }, tint = c.text3, iconSize = 18.dp)
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            SessionSort.entries.forEach { s ->
                                DropdownMenuItem(
                                    text = { Text(s.label, style = t.body, color = c.text) },
                                    trailingIcon = { if (s == sort) HIcon(HIcons.Check, size = 16.dp, tint = c.accent) },
                                    onClick = { sort = s; sortMenu = false },
                                )
                            }
                            Hairline()
                            DropdownMenuItem(
                                text = { Text("仅看运行中", style = t.body, color = c.text) },
                                trailingIcon = { if (onlyRunning) HIcon(HIcons.Check, size = 16.dp, tint = c.accent) },
                                onClick = { onlyRunning = !onlyRunning; sortMenu = false },
                            )
                        }
                    }
                    IconBtn(HIcons.Plus, "添加工作区", { addWs = true }, tint = c.text3, iconSize = 18.dp)
                }
            }
            if (filtered.isEmpty() && !searching) {
                item {
                    Text(
                        stringResource(if (sessions.isEmpty()) R.string.no_sessions else R.string.no_matching_sessions),
                        style = t.caption, color = c.text3,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
            }
            groups.forEach { ws ->
                val list = active.filter { it.workspaceId == ws.id }
                if (list.isNotEmpty()) {
                    item(key = "ws-${ws.id}") {
                        val isCollapsed = ws.id in collapsed
                        Row(
                            Modifier.fillMaxWidth().clickable { collapsed = if (isCollapsed) collapsed - ws.id else collapsed + ws.id }
                                .padding(horizontal = 20.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            HIcon(HIcons.Folder, size = 15.dp, tint = c.text3)
                            Spacer(Modifier.width(8.dp))
                            Text(ws.name, style = t.caption, color = c.text2, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            HIcon(if (isCollapsed) HIcons.ChevronRight else HIcons.ChevronDown, size = 14.dp, tint = c.text3)
                        }
                    }
                    if (ws.id !in collapsed || needle.isNotBlank()) {
                        items(list, key = { it.id }) { s ->
                            SessionRow(s, s.id == currentId, onClick = { onOpenSession(s.id) }, onLongClick = { actionFor = s })
                            hits[s.id]?.snippet?.takeIf{it.isNotBlank()}?.let{Text(it,style=t.caption,color=c.text3,maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(horizontal=20.dp,vertical=4.dp))}
                        }
                    }
                }
            }
            if (archived.isNotEmpty()) {
                item {
                    Row(
                        Modifier.fillMaxWidth().clickable { showArchived = !showArchived }.padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        HIcon(HIcons.Archive, size = 15.dp, tint = c.text3)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.archived_n, archived.size), style = t.caption, color = c.text3, modifier = Modifier.weight(1f))
                        HIcon(if (showArchived) HIcons.ChevronDown else HIcons.ChevronRight, size = 14.dp, tint = c.text3)
                    }
                }
                if (showArchived || needle.isNotBlank()) {
                    items(archived, key = { "a-${it.id}" }) { s ->
                        SessionRow(s, s.id == currentId, onClick = { onOpenSession(s.id) }, onLongClick = { actionFor = s })
                        hits[s.id]?.snippet?.takeIf{it.isNotBlank()}?.let{Text(it,style=t.caption,color=c.text3,maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(horizontal=20.dp,vertical=4.dp))}
                    }
                }
            }
        }
        Hairline()
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).heightIn(min = 44.dp).clip(HoshinoShapes.card).clickable { onNavigate(Routes.SETTINGS) }.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HIcon(HIcons.Settings, size = 20.dp)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.settings), style = t.body, color = c.text)
            }
            IconBtn(if (isDark) HIcons.Sun else HIcons.Moon, stringResource(R.string.toggle_theme), onToggleTheme, tint = c.text2)
        }
    }

    actionFor?.let { s ->
        ActionSheet(
            title = s.title,
            entries = listOf(
                MenuEntry("重命名", HIcons.Edit) { onSessionAction(s, SessionAction.Rename) },
                MenuEntry("分叉会话", HIcons.Fork) { onSessionAction(s, SessionAction.Fork) },
                MenuEntry("导出 ZIP", HIcons.Download) { onSessionAction(s, SessionAction.Export) },
                if (s.archived) MenuEntry("取消归档", HIcons.Archive) { onSessionAction(s, SessionAction.Unarchive) }
                else MenuEntry("归档", HIcons.Archive) { onSessionAction(s, SessionAction.Archive) },
                MenuEntry("删除", HIcons.Trash, danger = true) { onSessionAction(s, SessionAction.Delete) },
            ),
            onDismiss = { actionFor = null },
        )
    }

    if (addWs) {
        var name by remember { mutableStateOf("") }
        TextInputDialog(
            title = "添加工作区",
            initial = "",
            placeholder = "/storage/emulated/0/Projects/my-app",
            mono = true,
            hint = "输入目录路径（实际接入时使用系统目录选择器 OpenDocumentTree）",
            onDismiss = { addWs = false },
            onConfirm = { path ->
                name = path.trimEnd('/').substringAfterLast('/').ifBlank { "工作区" }
                onAddWorkspace(name, path)
                addWs = false
            },
        )
    }
}

@Composable
private fun NavEntry(icon: ImageVector?, label: String, indent: Boolean = false, trailing: ImageVector? = null, onClick: () -> Unit) {
    val c = HoshinoTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(onClick = onClick)
            .padding(start = if (indent) 54.dp else 24.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            HIcon(icon, size = 19.dp, tint = c.text2)
            Spacer(Modifier.width(12.dp))
        }
        Text(label, style = HoshinoTheme.type.body, color = if (indent) c.text2 else c.text, modifier = Modifier.weight(1f))
        if (trailing != null) HIcon(trailing, size = 14.dp, tint = c.text3)
    }
}

@Composable
private fun SessionRow(s: SessionInfo, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = HoshinoTheme.colors
    val t = HoshinoTheme.type
    Row(
        Modifier
            .padding(horizontal = 8.dp)
            .fillMaxWidth()
            .clip(HoshinoShapes.small)
            .background(if (selected) c.surface2 else c.bg)
            .longPressClickable(onClick = onClick, onLongClick = onLongClick)
            .heightIn(min = 44.dp)
            .padding(start = 36.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(s.title, style = t.body, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        if (s.running) {
            Spinner(10.dp)
        } else {
            Text(relativeTime(s.updatedAt), style = t.codeSmall, color = c.text3)
        }
    }
}

@Composable
fun RunningDot() {
    val c = HoshinoTheme.colors
    Box(Modifier.size(6.dp).clip(CircleShape).background(c.accent))
}
