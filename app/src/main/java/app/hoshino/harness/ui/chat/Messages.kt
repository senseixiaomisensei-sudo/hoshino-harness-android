package app.hoshino.harness.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.hoshino.harness.core.ApprovalDecision
import app.hoshino.harness.core.AttachmentKind
import app.hoshino.harness.core.ChatItem
import app.hoshino.harness.ui.components.IconBtn
import app.hoshino.harness.core.DiffLineType
import app.hoshino.harness.core.DiffState
import app.hoshino.harness.core.GoalStatus
import app.hoshino.harness.core.SubTask
import app.hoshino.harness.core.TaskStatus
import app.hoshino.harness.core.ToolStatus
import app.hoshino.harness.ui.components.BtnStyle
import app.hoshino.harness.ui.components.HButton
import app.hoshino.harness.ui.components.HIcon
import app.hoshino.harness.ui.components.HSheet
import app.hoshino.harness.ui.components.HTextField
import app.hoshino.harness.ui.components.Hairline
import app.hoshino.harness.ui.components.MarkdownText
import app.hoshino.harness.ui.components.ShimmerText
import app.hoshino.harness.ui.components.StatusDot
import app.hoshino.harness.ui.components.Tag
import app.hoshino.harness.ui.components.ThinProgress
import app.hoshino.harness.ui.components.VHairline
import app.hoshino.harness.ui.components.copyToClipboard
import app.hoshino.harness.ui.components.markdownText
import app.hoshino.harness.ui.components.plainText
import app.hoshino.harness.ui.components.shareText
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import app.hoshino.harness.ui.components.formatDuration
import app.hoshino.harness.ui.components.formatTokens
import app.hoshino.harness.ui.components.futureTime
import app.hoshino.harness.ui.components.hairlineCard
import app.hoshino.harness.ui.theme.HIcons
import app.hoshino.harness.ui.theme.HoshinoShapes
import app.hoshino.harness.ui.theme.HoshinoTheme
import kotlinx.coroutines.delay

/** 消息项回调集合 */
data class MessageActions(
    val onApprove: (String, ApprovalDecision) -> Unit = { _, _ -> },
    val onAnswer: (String, String) -> Unit = { _, _ -> },
    val onDiff: (String, Boolean) -> Unit = { _, _ -> },
    val onOpenSubTask: (SubTask) -> Unit = {},
    val onOpenGoals: () -> Unit = {},
    val onOpenSchedules: () -> Unit = {},
    val onScreenshot: ((ChatItem) -> Unit)? = null,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatItemView(item: ChatItem, actions: MessageActions, modifier: Modifier = Modifier) {
    var menu by remember(item.id) { mutableStateOf(false) }
    var frozen by remember(item.id) { mutableStateOf<ChatItem?>(null) }
    val context=LocalContext.current
    val rendered=frozen ?: item
    Box(modifier.fillMaxWidth().combinedClickable(onClick={},onLongClick={menu=true}).padding(horizontal = 16.dp, vertical = 6.dp)) {
        @Composable fun MessageContent() { when (val item=rendered) {
            is ChatItem.User -> UserBubble(item)
            is ChatItem.Thinking -> ThinkingBlock(item)
            is ChatItem.Assistant -> AssistantMessage(item)
            is ChatItem.Tool -> ToolCallCard(item)
            is ChatItem.Terminal -> TerminalCard(item)
            is ChatItem.Diff -> DiffCard(item, actions.onDiff)
            is ChatItem.Subagents -> SubagentCard(item, actions.onOpenSubTask)
            is ChatItem.Approval -> ApprovalCard(item, actions.onApprove)
            is ChatItem.Question -> QuestionCard(item, actions.onAnswer)
            is ChatItem.Todos -> TodoCard(item)
            is ChatItem.GoalCard -> GoalCardView(item, actions.onOpenGoals)
            is ChatItem.ScheduleCard -> ScheduleCardView(item, actions.onOpenSchedules)
            is ChatItem.Notice -> NoticeRow(item.text)
            is ChatItem.UsageFooter -> UsageFooterRow(item)
        } }
        Column {
            if(frozen!=null) {
                Text("完成选择",color=HoshinoTheme.colors.accent,style=HoshinoTheme.type.caption,modifier=Modifier.clickable { frozen=null }.padding(bottom=8.dp))
                // Freeze this item's display, while the real event stream keeps accumulating.
                SelectionContainer { MessageContent() }
            } else app.hoshino.harness.ui.diy.DiySlot(when(item){is ChatItem.Tool->"tool";is ChatItem.Terminal->"terminal";is ChatItem.Thinking->"thinking";else->"message"}){MessageContent()}
        }
        DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
            DropdownMenuItem(text={Text("复制全文")},onClick={menu=false;copyToClipboard(context,item.plainText())})
            DropdownMenuItem(text={Text("复制为 Markdown")},onClick={menu=false;copyToClipboard(context,item.markdownText())})
            DropdownMenuItem(text={Text("选择文本")},onClick={menu=false;frozen=item})
            DropdownMenuItem(text={Text("分享")},onClick={menu=false;shareText(context,item.markdownText())})
            DropdownMenuItem(text={Text(if(actions.onScreenshot==null)"截图 · 即将支持" else "截图")},enabled=actions.onScreenshot!=null,onClick={menu=false;actions.onScreenshot?.invoke(item)})
        }
    }
}

// ------------------------------------------------------------ 用户

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UserBubble(item: ChatItem.User) {
    val c = HoshinoTheme.colors
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        if (item.steer) {
            Text("插话", style = HoshinoTheme.type.caption, color = c.accent, modifier = Modifier.padding(bottom = 4.dp, end = 4.dp))
        }
        if (item.attachments.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item.attachments.forEach { a -> AttachmentThumb(a.name, a.kind, a.sizeLabel) }
            }
            Spacer(Modifier.height(6.dp))
        }
        if (item.text.isNotEmpty()) {
            Text(
                item.text,
                style = HoshinoTheme.type.body,
                color = c.text,
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 6.dp))
                    .background(c.surface2)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
        if (item.steer) SteerStatusLine(item.id)
    }
}

@Composable
fun AttachmentThumb(name: String, kind: AttachmentKind, size: String, onRemove: (() -> Unit)? = null) {
    val c = HoshinoTheme.colors
    Row(
        Modifier.hairlineCard(c.surface, c.line, HoshinoShapes.small).padding(start = 6.dp, end = if (onRemove != null) 2.dp else 10.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(28.dp).clip(HoshinoShapes.small).background(c.surface2), contentAlignment = Alignment.Center) {
            HIcon(
                when (kind) {
                    AttachmentKind.Image, AttachmentKind.Camera -> HIcons.Image
                    AttachmentKind.WorkspaceFile -> HIcons.At
                    AttachmentKind.File -> HIcons.File
                },
                size = 16.dp,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column {
            Text(name, style = HoshinoTheme.type.caption, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 140.dp))
            if (size.isNotEmpty()) Text(size, style = HoshinoTheme.type.codeSmall, color = c.text3)
        }
        if (onRemove != null) {
            Box(Modifier.size(28.dp).clip(CircleShape).clickable(onClick = onRemove), contentAlignment = Alignment.Center) {
                HIcon(HIcons.Close, size = 14.dp, tint = c.text3)
            }
        }
    }
}

// ------------------------------------------------------------ 思考

@Composable
private fun ThinkingBlock(item: ChatItem.Thinking) {
    val c = HoshinoTheme.colors
    val t = HoshinoTheme.type
    var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(item.running) {
        while (item.running) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        Row(
            Modifier.clip(HoshinoShapes.small).clickable { expanded = !expanded }.padding(vertical = 6.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HIcon(HIcons.Bulb, size = 16.dp, tint = c.text3)
            Spacer(Modifier.width(6.dp))
            if (item.running) {
                val secs = ((now - item.startedAt) / 1000).coerceAtLeast(0)
                ShimmerText("思考过程 · ${secs}s", t.caption)
            } else {
                Text("思考过程 · ${(item.durationMs / 1000).coerceAtLeast(1)}s", style = t.caption, color = c.text3)
            }
            Spacer(Modifier.width(2.dp))
            HIcon(if (expanded) HIcons.ChevronUp else HIcons.ChevronDown, size = 14.dp, tint = c.text3)
        }
        AnimatedVisibility(expanded) {
            Row(Modifier.padding(start = 8.dp, top = 2.dp, bottom = 4.dp).height(IntrinsicSize.Min)) {
                VHairline()
                Spacer(Modifier.width(12.dp))
                SelectionContainer {Text(item.text, style = t.caption, color = c.text2)}
            }
        }
    }
}

// ------------------------------------------------------------ 助手

@Composable
private fun AssistantMessage(item: ChatItem.Assistant) {
    if (item.markdown.isBlank() && item.streaming) {
        ShimmerText("正在生成…", HoshinoTheme.type.body)
    } else {
        MarkdownText(item.markdown, Modifier.fillMaxWidth())
    }
}

// ------------------------------------------------------------ 工具调用

@Composable
fun StatusChip(status: ToolStatus) {
    val c = HoshinoTheme.colors
    val color = when (status) {
        ToolStatus.Running -> c.accent
        ToolStatus.Success -> c.success
        ToolStatus.Failed -> c.danger
        ToolStatus.Denied -> c.warning
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (status == ToolStatus.Running) Spinner(10.dp, color) else StatusDot(color, size = 6.dp)
        Spacer(Modifier.width(5.dp))
        Text(status.label, style = HoshinoTheme.type.caption, color = color)
    }
}

@Composable
fun Spinner(size: Dp, color: Color = HoshinoTheme.colors.accent) {
    val tr = rememberInfiniteTransition(label = "spin")
    val angle by tr.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "angle")
    Canvas(Modifier.size(size).rotate(angle)) {
        val w = this.size.width * 0.18f
        drawArc(color.copy(alpha = 0.2f), 0f, 360f, false, style = Stroke(w))
        drawArc(color, 0f, 100f, false, style = Stroke(w, cap = StrokeCap.Round))
    }
}

@Composable
private fun ToolCallCard(item: ChatItem.Tool) {
    val c = HoshinoTheme.colors
    val t = HoshinoTheme.type
    var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().hairlineCard(c.surface, c.line).animateContentSize()) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.heightIn(min = 44.dp).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HIcon(HIcons.Wrench, size = 16.dp, tint = c.text2)
            Spacer(Modifier.width(8.dp))
            Text(item.name, style = t.code, color = c.text, maxLines = 1)
            Spacer(Modifier.width(8.dp))
            Text(item.summary, style = t.caption, color = c.text3, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            StatusChip(item.status)
            HIcon(if (expanded) HIcons.ChevronUp else HIcons.ChevronDown, size = 14.dp, tint = c.text3, modifier = Modifier.padding(start = 4.dp))
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.fillMaxWidth()) {
                Hairline()
                IoBlock("IN", item.input)
                if (item.output.isNotEmpty()) IoBlock("OUT", item.output)
            }
        }
    }
}

@Composable
private fun IoBlock(label: String, body: String) {
    val c = HoshinoTheme.colors
    val context=LocalContext.current
    var full by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Text(label, style = HoshinoTheme.type.codeSmall.copy(fontWeight = FontWeight.SemiBold), color = c.text3,modifier=Modifier.weight(1f))
            Text("复制",color=c.text3,style=HoshinoTheme.type.caption,modifier=Modifier.clickable { copyToClipboard(context,body) }.padding(4.dp))
            if(body.length>4000)Text(if(full)"收起" else "展开",color=c.text3,style=HoshinoTheme.type.caption,modifier=Modifier.clickable { full=!full }.padding(4.dp))
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().clip(HoshinoShapes.small).background(c.surface2).horizontalScroll(rememberScrollState()).padding(10.dp)) {
            SelectionContainer { Text(if(full)body else body.take(4000), style = HoshinoTheme.type.codeSmall, color = c.text, softWrap = false) }
        }
    }
}

// ------------------------------------------------------------ 终端

@Composable
private fun TerminalCard(item: ChatItem.Terminal) {
    val c = HoshinoTheme.colors
    val t = HoshinoTheme.type
    val ctx = LocalContext.current
    var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
    val shown = item.output
    Column(Modifier.fillMaxWidth().hairlineCard(c.surface, c.line).animateContentSize()) {
        Row(Modifier.fillMaxWidth().clickable{expanded=!expanded}.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            HIcon(HIcons.Terminal, size = 16.dp, tint = c.text2)
            Spacer(Modifier.width(8.dp))
            Text("$ ${item.command}", style = t.code, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            StatusChip(item.status)
            IconBtn(if(expanded)HIcons.ChevronUp else HIcons.ChevronDown,if(expanded)"收起终端输出" else "展开终端输出",{expanded=!expanded},iconSize=16.dp)
            Box(Modifier.size(40.dp).clip(CircleShape).clickable { copyToClipboard(ctx, "$ ${item.command}\n${item.output}") }, contentAlignment = Alignment.Center) {
                HIcon(HIcons.Copy, size = 16.dp, tint = c.text3)
            }
        }
        if (item.autoApproved || item.exitCode != null) {
            Row(Modifier.padding(start = 12.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (item.autoApproved) Tag("完全访问 · 自动执行", color = c.danger)
                if (item.exitCode != null) Tag("exit ${item.exitCode}", color = if (item.exitCode == 0) c.success else c.danger)
            }
        }
        AnimatedVisibility(expanded && (item.output.isNotEmpty() || item.status == ToolStatus.Denied)) {
          Column {
            Hairline()
            Column(
                Modifier.fillMaxWidth().heightIn(max=280.dp).background(c.surface2).verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState()).padding(12.dp),
            ) {
                if (item.status == ToolStatus.Denied) {
                    Text("命令已被拒绝，未执行。", style = t.codeSmall, color = c.warning)
                }
                SelectionContainer{Text(shown,style=t.codeSmall,color=c.text2,softWrap=false)}
            }
          }
        }
    }
}

// ------------------------------------------------------------ Diff

@Composable
private fun DiffCard(item: ChatItem.Diff, onResolve: (String, Boolean) -> Unit) {
    val c = HoshinoTheme.colors
    val t = HoshinoTheme.type
    Column(Modifier.fillMaxWidth().hairlineCard(c.surface, c.line)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            HIcon(HIcons.File, size = 16.dp, tint = c.text2)
            Spacer(Modifier.width(8.dp))
            Text(item.path, style = t.codeSmall, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("+${item.added}", style = t.codeSmall, color = c.success)
            Spacer(Modifier.width(6.dp))
            Text("−${item.removed}", style = t.codeSmall, color = c.danger)
        }
        Hairline()
        Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            item.lines.forEach { l ->
                val (bg, fg, sign) = when (l.type) {
                    DiffLineType.Add -> Triple(c.success.copy(alpha = 0.10f), c.text, "+")
                    DiffLineType.Remove -> Triple(c.danger.copy(alpha = 0.10f), c.text, "−")
                    DiffLineType.Hunk -> Triple(c.accentSoft, c.accent, "")
                    DiffLineType.Context -> Triple(Color.Transparent, c.text2, " ")
                }
                Row(Modifier.background(bg).padding(horizontal = 8.dp, vertical = 1.dp)) {
                    Text((l.oldNo?.toString() ?: "").padStart(3), style = t.codeSmall, color = c.text3)
                    Spacer(Modifier.width(4.dp))
                    Text((l.newNo?.toString() ?: "").padStart(3), style = t.codeSmall, color = c.text3)
                    Spacer(Modifier.width(8.dp))
                    Text("$sign ${l.text}", style = t.codeSmall, color = fg, softWrap = false)
                }
            }
        }
        Hairline()
        Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            when (item.state) {
                DiffState.Pending -> {
                    HButton("撤销", { onResolve(item.id, false) }, style = BtnStyle.Secondary, compact = true)
                    HButton("接受", { onResolve(item.id, true) }, style = BtnStyle.Primary, compact = true, icon = HIcons.Check)
                }
                DiffState.Accepted -> Text("已接受", style = t.caption, color = c.success)
                DiffState.Reverted -> Text("已撤销", style = t.caption, color = c.text3, textDecoration = TextDecoration.LineThrough)
            }
        }
    }
}

// ------------------------------------------------------------ 子代理 / Workflow

@Composable
private fun SubagentCard(item: ChatItem.Subagents, onOpen: (SubTask) -> Unit) {
    val c = HoshinoTheme.colors
    val t = HoshinoTheme.type
    val done = item.tasks.count { it.status == TaskStatus.Done }
    Column(Modifier.fillMaxWidth().hairlineCard(c.surface, c.line)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            HIcon(HIcons.Agents, size = 16.dp, tint = c.text2)
            Spacer(Modifier.width(8.dp))
            Text(item.title, style = t.bodyMedium, color = c.text, modifier = Modifier.weight(1f))
            Text("$done/${item.tasks.size}", style = t.codeSmall, color = c.text3)
        }
        item.tasks.forEach { task ->
            Hairline()
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(task) }.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TaskStatusIcon(task.status)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Tag(task.agent)
                        Spacer(Modifier.width(6.dp))
                        Text(task.title, style = t.caption, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(6.dp))
                    ThinProgress(task.progress, color = if (task.status == TaskStatus.Failed) c.danger else if (task.status == TaskStatus.Done) c.success else c.accent)
                }
                Spacer(Modifier.width(8.dp))
                HIcon(HIcons.ChevronRight, size = 16.dp, tint = c.text3)
            }
        }
    }
}

@Composable
fun TaskStatusIcon(status: TaskStatus) {
    val c = HoshinoTheme.colors
    when (status) {
        TaskStatus.Running -> Spinner(14.dp)
        TaskStatus.Done -> Box(Modifier.size(14.dp).clip(CircleShape).background(c.success), contentAlignment = Alignment.Center) {
            HIcon(HIcons.Check, size = 10.dp, tint = c.onAccent)
        }
        TaskStatus.Failed -> StatusDot(c.danger, size = 10.dp)
        TaskStatus.Pending -> Box(Modifier.size(14.dp).clip(CircleShape).border(1.5.dp, c.text3, CircleShape))
    }
}

@Composable
fun SubTaskSheet(task: SubTask, onDismiss: () -> Unit) {
    val c = HoshinoTheme.colors
    HSheet(onDismiss = onDismiss, title = "${task.agent} · ${task.title}") {
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            TaskStatusIcon(task.status)
            Spacer(Modifier.width(8.dp))
            Text(task.status.label, style = HoshinoTheme.type.caption, color = c.text2)
            Spacer(Modifier.width(12.dp))
            Text("${(task.progress * 100).toInt()}%", style = HoshinoTheme.type.codeSmall, color = c.text3)
        }
        Spacer(Modifier.height(12.dp))
        Text("轨迹", style = HoshinoTheme.type.caption, color = c.text3, modifier = Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(6.dp))
        Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().hairlineCard(c.surface2, c.line).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            task.log.forEachIndexed { i, l ->
                Row {
                    Text("${i + 1}".padStart(2), style = HoshinoTheme.type.codeSmall, color = c.text3)
                    Spacer(Modifier.width(10.dp))
                    Text(l, style = HoshinoTheme.type.codeSmall, color = c.text)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

// ------------------------------------------------------------ 审批

@Composable
private fun ApprovalCard(item: ChatItem.Approval, onDecide: (String, ApprovalDecision) -> Unit) {
    val c = HoshinoTheme.colors
    val t = HoshinoTheme.type
    Column(Modifier.fillMaxWidth().hairlineCard(c.surface, if (item.decision == null) c.warning.copy(alpha = 0.55f) else c.line).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HIcon(HIcons.Shield, size = 18.dp, tint = c.warning)
            Spacer(Modifier.width(8.dp))
            Text("需要审批 · ${item.title}", style = t.bodyMedium, color = c.text)
        }
        Spacer(Modifier.height(6.dp))
        Text(item.detail, style = t.caption, color = c.text2)
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().clip(HoshinoShapes.small).background(c.surface2).horizontalScroll(rememberScrollState()).padding(10.dp)) {
            Text("$ ${item.command}", style = t.codeSmall, color = c.text, softWrap = false)
        }
        Spacer(Modifier.height(12.dp))
        val d = item.decision
        if (d == null) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HButton("允许一次", { onDecide(item.id, ApprovalDecision.AllowOnce) }, Modifier.fillMaxWidth(), style = BtnStyle.Primary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HButton("本会话总是允许", { onDecide(item.id, ApprovalDecision.AllowSession) }, Modifier.weight(1f), style = BtnStyle.Secondary)
                    HButton("拒绝", { onDecide(item.id, ApprovalDecision.Deny) }, Modifier.weight(1f), style = BtnStyle.Secondary)
                }
            }
        } else {
            Text(
                "已处理：${d.label}",
                style = t.caption,
                color = if (d == ApprovalDecision.Deny) c.danger else c.success,
            )
        }
    }
}

// ------------------------------------------------------------ ask_user_question

@Composable
private fun QuestionCard(item: ChatItem.Question, onAnswer: (String, String) -> Unit) {
    val c = HoshinoTheme.colors
    val t = HoshinoTheme.type
    var selected by rememberSaveable(item.id) { mutableStateOf<String?>(null) }
    var free by rememberSaveable(item.id + "free") { mutableStateOf("") }
    val options = remember(item.options) { item.options.sortedByDescending { it.recommended } }
    Column(Modifier.fillMaxWidth().hairlineCard(c.surface, if (item.answer == null) c.accent.copy(alpha = 0.5f) else c.line).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HIcon(HIcons.Question, size = 18.dp, tint = c.accent)
            Spacer(Modifier.width(8.dp))
            Tag(item.header, color = c.accent, bg = c.accentSoft)
        }
        Spacer(Modifier.height(8.dp))
        Text(item.question, style = t.bodyMedium, color = c.text)
        Spacer(Modifier.height(10.dp))
        val answered = item.answer
        if (answered != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HIcon(HIcons.Check, size = 16.dp, tint = c.success)
                Spacer(Modifier.width(6.dp))
                Text("已回答：$answered", style = t.caption, color = c.text2)
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { o ->
                    val sel = selected == o.id
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(HoshinoShapes.card)
                            .background(if (sel) c.accentSoft else c.surface)
                            .border(1.dp, if (sel) c.accent else c.line, HoshinoShapes.card)
                            .clickable { selected = o.id; free = "" }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(16.dp).clip(CircleShape).border(1.5.dp, if (sel) c.accent else c.text3, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (sel) Box(Modifier.size(8.dp).clip(CircleShape).background(c.accent))
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(o.label, style = t.body, color = c.text)
                                if (o.recommended) {
                                    Spacer(Modifier.width(6.dp))
                                    Tag("推荐", color = c.accent, bg = c.accentSoft)
                                }
                            }
                            if (o.description.isNotEmpty()) Text(o.description, style = t.caption, color = c.text3)
                        }
                    }
                }
                if (item.allowFreeText) {
                    HTextField(free, { free = it; if (it.isNotEmpty()) selected = null }, placeholder = "或输入你的回答…")
                }
                val answer = selected?.let { id -> options.firstOrNull { it.id == id }?.label } ?: free.trim().ifEmpty { null }
                HButton(
                    "提交",
                    { if (answer != null) onAnswer(item.id, answer) },
                    Modifier.align(Alignment.End),
                    style = BtnStyle.Accent,
                    enabled = answer != null,
                    compact = true,
                )
            }
        }
    }
}

// ------------------------------------------------------------ Todo / Goal / Schedule

@Composable
private fun TodoCard(item: ChatItem.Todos) {
    val c = HoshinoTheme.colors
    val t = HoshinoTheme.type
    val done = item.items.count { it.done }
    Column(Modifier.fillMaxWidth().hairlineCard(c.surface, c.line).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HIcon(HIcons.ListIcon, size = 16.dp, tint = c.text2)
            Spacer(Modifier.width(8.dp))
            Text(item.title, style = t.bodyMedium, color = c.text, modifier = Modifier.weight(1f))
            Text("$done/${item.items.size}", style = t.codeSmall, color = c.text3)
        }
        Spacer(Modifier.height(8.dp))
        ThinProgress(if (item.items.isEmpty()) 0f else done.toFloat() / item.items.size)
        Spacer(Modifier.height(8.dp))
        item.items.forEach { td ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                when {
                    td.done -> Box(Modifier.size(16.dp).clip(HoshinoShapes.small).background(c.text), contentAlignment = Alignment.Center) {
                        HIcon(HIcons.Check, size = 12.dp, tint = c.bg)
                    }
                    td.active -> Spinner(16.dp)
                    else -> Box(Modifier.size(16.dp).clip(HoshinoShapes.small).border(1.5.dp, c.text3, HoshinoShapes.small))
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    td.text,
                    style = if (td.active) t.bodyMedium else t.body,
                    color = when {
                        td.done -> c.text3
                        td.active -> c.text
                        else -> c.text2
                    },
                    textDecoration = if (td.done) TextDecoration.LineThrough else null,
                )
            }
        }
    }
}

@Composable
private fun GoalCardView(item: ChatItem.GoalCard, onOpen: () -> Unit) {
    val c = HoshinoTheme.colors
    val t = HoshinoTheme.type
    val g = item.goal
    Column(Modifier.fillMaxWidth().hairlineCard(c.surface, c.line).clickable(onClick = onOpen).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HIcon(HIcons.Target, size = 16.dp, tint = c.accent)
            Spacer(Modifier.width(8.dp))
            Text("长期目标", style = t.caption, color = c.text3, modifier = Modifier.weight(1f))
            Tag(g.status.label, color = if (g.status == GoalStatus.Active) c.accent else c.text2, bg = if (g.status == GoalStatus.Active) c.accentSoft else c.surface2)
        }
        Spacer(Modifier.height(6.dp))
        Text(g.title, style = t.bodyMedium, color = c.text)
        if (g.description.isNotEmpty()) Text(g.description, style = t.caption, color = c.text2)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ThinProgress(g.progress, Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            Text("${(g.progress * 100).toInt()}%", style = t.codeSmall, color = c.text3)
        }
    }
}

@Composable
private fun ScheduleCardView(item: ChatItem.ScheduleCard, onOpen: () -> Unit) {
    val c = HoshinoTheme.colors
    val t = HoshinoTheme.type
    val s = item.schedule
    Column(Modifier.fillMaxWidth().hairlineCard(c.surface, c.line).clickable(onClick = onOpen).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HIcon(HIcons.Clock, size = 16.dp, tint = c.text2)
            Spacer(Modifier.width(8.dp))
            Text("schedule_create · 定时任务已创建", style = t.caption, color = c.text3, modifier = Modifier.weight(1f))
            Tag(if (s.enabled) "启用" else "暂停", color = if (s.enabled) c.success else c.text2)
        }
        Spacer(Modifier.height(6.dp))
        Text(s.title, style = t.bodyMedium, color = c.text)
        Spacer(Modifier.height(8.dp))
        KeyValue("重复", "${s.repeat.label} · ${s.rule}")
        KeyValue("时区", s.timezone)
        KeyValue("下次运行", futureTime(s.nextRun))
    }
}

@Composable
fun KeyValue(k: String, v: String) {
    val c = HoshinoTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(k, style = HoshinoTheme.type.caption, color = c.text3, modifier = Modifier.width(72.dp))
        Text(v, style = HoshinoTheme.type.caption, color = c.text)
    }
}

// ------------------------------------------------------------ 提示 / 用量

@Composable
private fun NoticeRow(text: String) {
    val c = HoshinoTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Hairline(Modifier.weight(1f))
        Text(text, style = HoshinoTheme.type.caption, color = c.text3, modifier = Modifier.padding(horizontal = 10.dp))
        Hairline(Modifier.weight(1f))
    }
}

@Composable
private fun UsageFooterRow(item: ChatItem.UsageFooter) {
    val c = HoshinoTheme.colors
    val t = HoshinoTheme.type
    var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
    val u = item.usage
    Column(Modifier.animateContentSize()) {
        Row(
            Modifier.clip(HoshinoShapes.small).clickable { expanded = !expanded }.padding(vertical = 4.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${formatTokens(u.total)} tokens · ${formatDuration(u.durationMs)}", style = t.codeSmall, color = c.text3)
            HIcon(if (expanded) HIcons.ChevronUp else HIcons.ChevronDown, size = 12.dp, tint = c.text3, modifier = Modifier.padding(start = 2.dp))
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.padding(top = 4.dp).widthIn(max = 260.dp).hairlineCard(c.surface, c.line).padding(12.dp)) {
                KeyValue("输入", "%,d".format(u.inputTokens))
                KeyValue("输出", "%,d".format(u.outputTokens))
                KeyValue("缓存读取", "%,d".format(u.cacheReadTokens))
                KeyValue("推理", "%,d".format(u.reasoningTokens))
                KeyValue("耗时", formatDuration(u.durationMs))
            }
        }
    }
}
