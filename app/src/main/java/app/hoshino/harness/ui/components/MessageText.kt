package app.hoshino.harness.ui.components

import app.hoshino.harness.core.ChatItem

fun ChatItem.markdownText(): String = when(this) {
    is ChatItem.User -> text
    is ChatItem.Assistant -> markdown
    is ChatItem.Thinking -> text
    is ChatItem.Tool -> "### $name\n\n参数\n```json\n$input\n```\n\n结果\n```\n$output\n```"
    is ChatItem.Terminal -> "```sh\n$command\n```\n\n```\n$output\n```"
    is ChatItem.Diff -> "### $path\n\n```diff\n"+lines.joinToString("\n") { it.text }+"\n```"
    is ChatItem.Approval -> "$title\n\n$detail\n\n$command"
    is ChatItem.Question -> "$question\n\n"+options.joinToString("\n") { it.label }+"\n"+answer.orEmpty()
    is ChatItem.Todos -> "$title\n\n"+items.joinToString("\n") { "- [${if(it.done)"x" else " "}] ${it.text}" }
    is ChatItem.Subagents -> "$title\n\n"+tasks.joinToString("\n") { "- ${it.title}: ${it.status.label}" }
    is ChatItem.GoalCard -> goal.title+"\n"+goal.description
    is ChatItem.ScheduleCard -> schedule.title+"\n"+schedule.prompt
    is ChatItem.Notice -> text
    is ChatItem.UsageFooter -> "输入 ${usage.inputTokens} / 输出 ${usage.outputTokens} tokens"
}

private fun plainInline(text: String)=text.replace(Regex("\\*\\*(.+?)\\*\\*"),"$1").replace(Regex("`([^`]+)`"),"$1").replace(Regex("\\*([^*]+)\\*"),"$1")

fun ChatItem.plainText(): String = parseMarkdown(markdownText()).joinToString("\n\n") { block -> when(block) {
    is MdBlock.Code -> block.code
    is MdBlock.Heading -> plainInline(block.text)
    is MdBlock.Paragraph -> plainInline(block.text)
    is MdBlock.ListItem -> block.marker+" "+plainInline(block.text)
    is MdBlock.Quote -> plainInline(block.text)
    is MdBlock.Table -> (listOf(block.header)+block.rows).joinToString("\n") { it.joinToString("\t") }
    MdBlock.Rule -> ""
} }
