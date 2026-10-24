package app.hoshino.harness.ui.chat

import app.hoshino.harness.R
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.hoshino.harness.core.AgentPreset
import app.hoshino.harness.core.AttachmentKind
import app.hoshino.harness.core.PermissionTier
import app.hoshino.harness.core.QueuedMessage
import app.hoshino.harness.core.SlashCommand
import app.hoshino.harness.ui.components.HIcon
import app.hoshino.harness.ui.components.Hairline
import app.hoshino.harness.ui.components.Pill
import app.hoshino.harness.ui.components.Tag
import app.hoshino.harness.ui.components.UsageRing
import app.hoshino.harness.ui.components.hairlineCard
import app.hoshino.harness.ui.theme.HIcons
import app.hoshino.harness.ui.theme.HoshinoShapes
import app.hoshino.harness.ui.theme.HoshinoTheme

/** 光标前的 @ 查询（无则 null） */
fun atQuery(v: TextFieldValue): String? {
    val before = v.text.substring(0, v.selection.start.coerceIn(0, v.text.length))
    val at = before.lastIndexOf('@')
    if (at < 0) return null
    if (at > 0 && !before[at - 1].isWhitespace()) return null
    val q = before.substring(at + 1)
    return if (q.any { it.isWhitespace() }) null else q
}

/** 行首 / 命令查询（无则 null） */
fun slashQuery(v: TextFieldValue): String? {
    val t = v.text
    if (!t.startsWith("/")) return null
    return if (t.any { it.isWhitespace() }) null else t
}

fun insertFile(v: TextFieldValue, path: String): TextFieldValue {
    val cursor = v.selection.start.coerceIn(0, v.text.length)
    val before = v.text.substring(0, cursor)
    val at = before.lastIndexOf('@')
    if (at < 0) return v
    val newText = v.text.substring(0, at) + "@$path " + v.text.substring(cursor)
    val pos = at + path.length + 2
    return TextFieldValue(newText, TextRange(pos))
}

@Composable
fun Composer(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    state: ComposerState,
    running: Boolean,
    queued: List<QueuedMessage>,
    presets: List<AgentPreset>,
    tier: PermissionTier,
    modelLabel: String,
    contextFraction: Float,
    slashCommands: List<SlashCommand>,
    fileSuggestions: List<String>,
    onFileQuery: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onSteer: (String) -> Unit,
    onRemoveQueued: (String) -> Unit,
    onAddAttachment: (AttachmentKind) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onPickPreset: (String) -> Unit,
    onTogglePlan: (Boolean) -> Unit,
    onManagePresets: () -> Unit,
    onOpenTier: () -> Unit,
    onOpenModel: () -> Unit,
    onOpenContext: () -> Unit,
    onSlash: (SlashCommand) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = HoshinoTheme.colors
    val t = HoshinoTheme.type

    val sq = slashQuery(value)
    val aq = if (sq == null) atQuery(value) else null
    val filteredSlash = remember(sq, slashCommands) {
        if (sq == null) emptyList() else slashCommands.filter { it.name.startsWith(sq, ignoreCase = true) }
    }
    LaunchedEffect(aq) { if (aq != null) onFileQuery(aq) }
    var highlight by remember { mutableIntStateOf(0) }
    LaunchedEffect(sq, aq) { highlight = 0 }
    val paletteSize = if (sq != null) filteredSlash.size else if (aq != null) fileSuggestions.size else 0

    fun pick(index: Int) {
        if (sq != null) filteredSlash.getOrNull(index)?.let(onSlash)
        else if (aq != null) fileSuggestions.getOrNull(index)?.let { onValueChange(insertFile(value, it)) }
    }

    Column(modifier.fillMaxWidth()) {
        // ---- 排队消息条
        AnimatedVisibility(queued.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().padding(bottom = 8.dp).hairlineCard(c.surface, c.line)) {
                queued.forEachIndexed { i, q ->
                    if (i > 0) Hairline()
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Tag(stringResource(R.string.queued_n, i + 1))
                        Spacer(Modifier.width(8.dp))
                        Text(q.text, style = t.caption, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(
                            stringResource(R.string.steer),
                            style = t.caption,
                            color = c.accent,
                            modifier = Modifier.clip(HoshinoShapes.small).clickable { onSteer(q.id) }.padding(horizontal = 10.dp, vertical = 10.dp),
                        )
                        Box(Modifier.size(36.dp).clip(CircleShape).clickable { onRemoveQueued(q.id) }, contentAlignment = Alignment.Center) {
                            HIcon(HIcons.Close, size = 14.dp, tint = c.text3)
                        }
                    }
                }
            }
        }

        // ---- 斜杠命令 / @ 文件面板
        if (paletteSize > 0) {
            Column(Modifier.fillMaxWidth().padding(bottom = 8.dp).hairlineCard(c.surface, c.line)) {
                Text(
                    stringResource(if (sq != null) R.string.composer_commands else R.string.composer_files),
                    style = t.caption, color = c.text3,
                    modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 4.dp),
                )
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
                    if (sq != null) {
                        itemsIndexed(filteredSlash, key = { _, it -> it.name }) { i, cmd ->
                            Row(
                                Modifier.fillMaxWidth().background(if (i == highlight) c.surface2 else c.surface)
                                    .clickable { onSlash(cmd) }.heightIn(min = 44.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(cmd.name, style = t.code, color = c.text, modifier = Modifier.widthIn(min = 96.dp))
                                Text(cmd.description, style = t.caption, color = c.text2, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (cmd.source != "内置") Tag(cmd.source)
                            }
                        }
                    } else {
                        itemsIndexed(fileSuggestions, key = { _, it -> it }) { i, f ->
                            Row(
                                Modifier.fillMaxWidth().background(if (i == highlight) c.surface2 else c.surface)
                                    .clickable { onValueChange(insertFile(value, f)) }.heightIn(min = 44.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                HIcon(HIcons.File, size = 16.dp, tint = c.text3)
                                Spacer(Modifier.width(8.dp))
                                Text(f, style = t.codeSmall, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }

        // ---- 输入卡
        Column(Modifier.fillMaxWidth().hairlineCard(c.surface, c.line)) {
            if (state.attachments.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 10.dp, end = 10.dp, top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    state.attachments.forEach { a -> AttachmentThumb(a.name, a.kind, a.sizeLabel, onRemove = { onRemoveAttachment(a.id) }) }
                }
            }
            Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
                if (value.text.isEmpty()) {
                    Text(
                        stringResource(if (running) R.string.composer_placeholder_running else R.string.composer_placeholder),
                        style = t.body, color = c.text3,
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    textStyle = t.body.copy(color = c.text),
                    cursorBrush = SolidColor(c.accent),
                    maxLines = 8,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 24.dp)
                        .onPreviewKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when {
                                paletteSize > 0 && e.key == Key.DirectionDown -> { highlight = (highlight + 1) % paletteSize; true }
                                paletteSize > 0 && e.key == Key.DirectionUp -> { highlight = (highlight - 1 + paletteSize) % paletteSize; true }
                                paletteSize > 0 && (e.key == Key.Enter || e.key == Key.Tab) -> { pick(highlight); true }
                                e.key == Key.Enter && e.isCtrlPressed -> { onSend(); true }
                                else -> false
                            }
                        },
                )
            }
            // ---- 工具行
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AttachMenuButton(onAddAttachment = { kind ->
                    if (kind == AttachmentKind.WorkspaceFile) {
                        val cur = value.selection.start.coerceIn(0, value.text.length)
                        val prefix = if (cur > 0 && !value.text[cur - 1].isWhitespace()) " @" else "@"
                        val nt = value.text.substring(0, cur) + prefix + value.text.substring(cur)
                        onValueChange(TextFieldValue(nt, TextRange(cur + prefix.length)))
                    } else onAddAttachment(kind)
                })
                Row(
                    Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ModeChip(presets, state.presetId, state.planMode, onPickPreset, onTogglePlan, onManagePresets)
                    Pill(
                        tier.label,
                        icon = if (tier == PermissionTier.FullAccess) HIcons.Warning else if (tier == PermissionTier.ReadOnly) HIcons.Lock else HIcons.Shield,
                        color = if (tier == PermissionTier.FullAccess) c.danger else null,
                        trailing = HIcons.ChevronDown,
                        onClick = onOpenTier,
                    )
                    Pill(modelLabel, icon = HIcons.Cpu, trailing = HIcons.ChevronDown, onClick = onOpenModel)
                }
                Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onOpenContext), contentAlignment = Alignment.Center) {
                    UsageRing(contextFraction, size = 18.dp)
                }
                Spacer(Modifier.width(4.dp))
                val canSend = value.text.isNotBlank() || state.attachments.isNotEmpty()
                val showStop = running && !canSend
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                showStop -> c.text
                                canSend -> c.text
                                else -> c.surface2
                            },
                        )
                        .clickable(enabled = showStop || canSend) { if (showStop) onStop() else onSend() }
                        .semantics { contentDescription = if (showStop) "停止" else "发送" },
                    contentAlignment = Alignment.Center,
                ) {
                    HIcon(
                        if (showStop) HIcons.Stop else HIcons.ArrowUp,
                        size = 18.dp,
                        tint = if (showStop || canSend) c.bg else c.text3,
                    )
                }
            }
        }
    }
}

@Composable
private fun AttachMenuButton(onAddAttachment: (AttachmentKind) -> Unit) {
    val c = HoshinoTheme.colors
    var open by remember { mutableStateOf(false) }
    Box {
        Box(Modifier.size(40.dp).clip(CircleShape).clickable { open = true }, contentAlignment = Alignment.Center) {
            HIcon(HIcons.Plus, size = 20.dp, tint = c.text2)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(
                Triple(AttachmentKind.File, stringResource(R.string.attach_file), HIcons.File),
                Triple(AttachmentKind.Image, stringResource(R.string.attach_image), HIcons.Image),
                Triple(AttachmentKind.Camera, stringResource(R.string.attach_camera), HIcons.Camera),
                Triple(AttachmentKind.WorkspaceFile, stringResource(R.string.attach_workspace), HIcons.At),
            ).forEach { (kind, label, icon) ->
                DropdownMenuItem(
                    text = { Text(label, style = HoshinoTheme.type.body, color = c.text) },
                    leadingIcon = { HIcon(icon, size = 18.dp) },
                    onClick = { open = false; onAddAttachment(kind) },
                )
            }
        }
    }
}

@Composable
private fun ModeChip(
    presets: List<AgentPreset>,
    selectedId: String,
    planMode: Boolean,
    onPick: (String) -> Unit,
    onTogglePlan: (Boolean) -> Unit,
    onManage: () -> Unit,
) {
    val c = HoshinoTheme.colors
    var open by remember { mutableStateOf(false) }
    val name = presets.firstOrNull { it.id == selectedId }?.name ?: "Standard"
    Box {
        Pill(if (planMode) "$name · 规划" else name, icon = HIcons.Sparkle, trailing = HIcons.ChevronDown, onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            presets.forEach { p ->
                DropdownMenuItem(
                    text = {
                        Column(Modifier.widthIn(max = 260.dp)) {
                            Text(p.name, style = HoshinoTheme.type.body, color = c.text)
                            Text(p.description, style = HoshinoTheme.type.caption, color = c.text3, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    trailingIcon = { if (p.id == selectedId) HIcon(HIcons.Check, size = 16.dp, tint = c.accent) },
                    onClick = { open = false; onPick(p.id) },
                )
            }
            Hairline()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.plan_mode), style = HoshinoTheme.type.body, color = c.text) },
                leadingIcon = { HIcon(HIcons.ListIcon, size = 18.dp) },
                trailingIcon = {
                    Box(
                        Modifier.size(18.dp).clip(HoshinoShapes.small)
                            .background(if (planMode) c.accent else c.surface)
                            .border(1.5.dp, if (planMode) c.accent else c.text3, HoshinoShapes.small),
                        contentAlignment = Alignment.Center,
                    ) { if (planMode) HIcon(HIcons.Check, size = 12.dp, tint = c.onAccent) }
                },
                onClick = { onTogglePlan(!planMode) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.manage_presets), style = HoshinoTheme.type.body, color = c.text) },
                leadingIcon = { HIcon(HIcons.Settings, size = 18.dp) },
                onClick = { open = false; onManage() },
            )
        }
    }
}
