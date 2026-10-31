package app.hoshino.harness.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.hoshino.harness.core.*
import app.hoshino.harness.ui.components.*
import app.hoshino.harness.ui.theme.HoshinoTheme

/** 过程组里每个条目展示用的原始文本（不走 Markdown 解析，长输出只读内存里的尾部）。 */
internal fun ChatItem.processText(): String = when (this) {
    is ChatItem.Thinking -> text
    is ChatItem.Tool -> "参数\n$input\n\n结果\n$output"
    is ChatItem.Terminal -> "$ $command\n$output"
    else -> plainText()
}

private fun ChatItem.processLength(): Long = when (this) {
    is ChatItem.Thinking -> content.length.toLong()
    is ChatItem.Tool -> input.length.toLong() + output.length
    is ChatItem.Terminal -> command.length.toLong() + output.length
    else -> 0L
}

/**
 * 每轮一个折叠的过程组。参数是不可变的 [ChatRow.ProcessRow]：已完成轮次的行是同一实例，整卡跳过重组；
 * 运行中的计时只让标题里的时长文字每秒重组（[ElapsedLabel]）。已处理的审批（「审批后折叠」开启时）在这里只留一行小字。
 */
@Composable
internal fun ProcessTimeline(row: ChatRow.ProcessRow, actions: MessageActions, display: String = "collapsed", types: Map<String, Boolean> = emptyMap()) {
    val items = row.items
    if (items.isEmpty()) return
    val c = HoshinoTheme.colors
    val context = androidx.compose.ui.platform.LocalContext.current
    val summary = remember(row) { ProcessSummary.of(items, row.waiting) }
    var expanded by rememberSaveable(row.turnKey, display) { mutableStateOf(display != "collapsed") }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).hairlineCard(c.surface, c.line)) {
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(12.dp)) {
            Text(if (expanded) "▾ 过程" else "▸ 过程", style = HoshinoTheme.type.bodyMedium, color = c.text, modifier = Modifier.width(64.dp))
            if (summary.runningSince != null) {
                ElapsedLabel(summary.runningSince, 1000) { _ ->
                    val ms = summary.finishedMs + (System.currentTimeMillis() - summary.runningSince).coerceAtLeast(0)
                    Text(summary.title(ms), style = HoshinoTheme.type.caption, color = if (summary.failed) c.danger else c.text2)
                }
            } else {
                Text(summary.title(summary.finishedMs), style = HoshinoTheme.type.caption, color = if (summary.failed) c.danger else c.text2)
            }
        }
        if (expanded) {
            // 无审批模式自动放行的调用不产生审批卡；在过程组里留一行审计（与「审批后折叠」的一行小字同级）。
            if (summary.autoApproved > 0) Text(
                ProcessSummary.autoApprovalLine(summary.autoApproved),
                style = HoshinoTheme.type.caption,
                color = c.text3,
                modifier = Modifier.padding(start = 18.dp, end = 12.dp, top = 2.dp, bottom = 6.dp),
            )
            val steps = remember(row) { items.filterNot { it is ChatItem.Approval } }
            val stepIndex = remember(row) { HashMap<String, Int>().apply { steps.forEachIndexed { i, it -> put(it.id, i) } } }
            items.forEach { item ->
                if (item is ChatItem.Approval) {
                    Text(
                        TurnPresenter.approvalLine(item),
                        style = HoshinoTheme.type.caption,
                        color = if (item.decision == ApprovalDecision.Deny) c.danger else c.text3,
                        modifier = Modifier.padding(start = 18.dp, end = 12.dp, top = 2.dp, bottom = 6.dp),
                    )
                    return@forEach
                }
                val index = stepIndex[item.id] ?: 0
                key(item.id) { ProcessStep(item, index, index == steps.lastIndex, actions, display, types, context) }
            }
        }
    }
}

@Composable
private fun ProcessStep(item: ChatItem, index: Int, last: Boolean, actions: MessageActions, display: String, types: Map<String, Boolean>, context: android.content.Context) {
    val c = HoshinoTheme.colors
    val type = when (item) { is ChatItem.Thinking -> "thinking"; is ChatItem.Terminal -> "terminal"; is ChatItem.Diff -> "diff"; else -> "tool" }
    var open by rememberSaveable(item.id, display) { mutableStateOf(types[type] == true || display == "expanded" || display == "current" && last) }
    var full by remember { mutableStateOf(false) }
    val label = when (item) { is ChatItem.Thinking -> "思考"; is ChatItem.Tool -> item.name; is ChatItem.Terminal -> "终端 · " + item.command.take(70); is ChatItem.Diff -> item.path; is ChatItem.Assistant -> "中间回复"; else -> "事件 ${index + 1}" }
    Column(Modifier.padding(start = 10.dp, end = 6.dp, bottom = 4.dp)) {
        Text("${if (open) "▾" else "▸"} ${index + 1}. $label", style = HoshinoTheme.type.caption, color = c.text2, modifier = Modifier.fillMaxWidth().clickable { open = !open }.padding(8.dp))
        if (if (display == "current") last else open) {
            val length = item.processLength()
            if (length > 12000) {
                var output by rememberSaveable(item.id) { mutableStateOf(false) }
                Text("${if (output) "▾" else "▸"} 长输出 · $length 字符", color = c.text3, modifier = Modifier.clickable { output = !output }.padding(8.dp))
                if (output) SelectionContainer { Text(remember(item) { item.processText().take(12000) }, style = HoshinoTheme.type.code, color = c.text) }
                HButton("查看全部", { full = true }, compact = true)
                val callId = (item as? ChatItem.Tool)?.callId ?: (item as? ChatItem.Terminal)?.callId
                val truncated = (item as? ChatItem.Tool)?.outputTruncated == true || (item as? ChatItem.Terminal)?.outputTruncated == true
                if (truncated) TruncatedNote("内存仅保留末尾 64 KiB", callId)
            } else if (item is ChatItem.Thinking || item is ChatItem.Tool || item is ChatItem.Terminal) {
                SelectionContainer { Text(remember(item) { item.processText() }, style = HoshinoTheme.type.code, color = c.text, modifier = Modifier.padding(8.dp)) }
            } else ChatItemView(item, actions)
        }
    }
    if (full) HSheet(onDismiss = { full = false }, title = "查看全部") {
        val text = remember(item) { item.processText() }
        HButton("复制全文", { copyToClipboard(context, text) })
        SelectionContainer { Text(text, style = HoshinoTheme.type.code, color = c.text, modifier = Modifier.heightIn(max = 640.dp).verticalScroll(rememberScrollState()).padding(12.dp)) }
    }
}

/** 过程组标题的静态部分；只依赖不可变的条目列表，按行缓存。 */
@Immutable
internal data class ProcessSummary(
    val tools: Int,
    val files: Int,
    val approvals: Int,
    val action: String,
    val running: Boolean,
    val failed: Boolean,
    val waiting: Boolean,
    val finishedMs: Long,
    val runningSince: Long?,
    /** 无审批模式下自动放行的工具调用数。 */
    val autoApproved: Int = 0,
) {
    fun title(durationMs: Long): String {
        val status = if (waiting) "等待审批" else if (running) "运行中" else if (failed) "失败" else "已完成"
        val approvalsText = (if (approvals > 0) " · 审批 $approvals" else "") + (if (autoApproved > 0) " · 自动放行 $autoApproved" else "")
        return if (running) "$action · $tools 步 · ${formatDuration(durationMs)} · $status"
        else "已运行 $tools 步 · ${formatDuration(durationMs)}${if (files > 0) " · 改了 $files 个文件" else ""}$approvalsText · $status"
    }

    companion object {
        fun isAutoApproved(item: ChatItem): Boolean = item is ChatItem.Tool && item.autoApproved || item is ChatItem.Terminal && item.autoApproved
        fun autoApprovalLine(count: Int): String = "无审批 · 自动放行 $count 次工具调用（已记入审计日志）"

        fun of(items: List<ChatItem>, waiting: Boolean): ProcessSummary {
            val running = items.any { it is ChatItem.Thinking && it.running || it is ChatItem.Tool && it.status == ToolStatus.Running || it is ChatItem.Terminal && it.status == ToolStatus.Running }
            val failed = items.any { it is ChatItem.Tool && it.status == ToolStatus.Failed || it is ChatItem.Terminal && it.status == ToolStatus.Failed }
            val thinking = items.filterIsInstance<ChatItem.Thinking>()
            val current = items.lastOrNull { it !is ChatItem.Approval }
            val action = when (current) { is ChatItem.Tool -> current.name; is ChatItem.Terminal -> current.command.lineSequence().firstOrNull().orEmpty().take(60); is ChatItem.Thinking -> "思考中"; else -> "处理任务" }
            return ProcessSummary(
                tools = items.count { it is ChatItem.Tool || it is ChatItem.Terminal },
                files = items.filterIsInstance<ChatItem.Diff>().map { it.path }.distinct().size,
                approvals = items.count { it is ChatItem.Approval },
                action = action,
                running = running,
                failed = failed,
                waiting = waiting,
                finishedMs = thinking.filter { !it.running }.sumOf { it.durationMs },
                runningSince = thinking.filter { it.running }.minOfOrNull { it.startedAt },
                autoApproved = items.count(::isAutoApproved),
            )
        }
    }
}
