package app.hoshino.harness.core

import org.json.JSONObject

/**
 * Model-hosted web search steps. plugins/android-api/hosted-search.ts turns a provider's search call (web_search_call,
 * server_tool_use, web_search_tool_result) into one reasoning block starting with [MARK]; the chat shows it as a finished
 * "联网搜索" process step instead of thinking text. The model's own reasoning is untouched.
 */
object HostedSearchSteps {
    const val MARK = "🌐 "
    const val NAME = "联网搜索"

    fun isStep(text: String) = text.startsWith(MARK)

    /** Process events for one step: [id] must be stable for the same step so replays do not duplicate it. */
    fun events(sessionId: String, id: String, text: String): List<HarnessEvent> {
        val lines = text.removePrefix(MARK).trim().lines()
        val title = lines.firstOrNull().orEmpty().ifBlank { "模型自带搜索" }
        val sources = lines.drop(1).joinToString("\n").trim()
        return listOf(
            HarnessEvent.ToolCallStarted(sessionId, id, NAME, title, JSONObject().put("step", title).toString(), ToolKind.Generic, autoApproved = true),
            HarnessEvent.ToolCallOutput(sessionId, id, sources.ifBlank { "由模型提供商执行的联网搜索" }),
            HarnessEvent.ToolCallFinished(sessionId, id, ToolStatus.Success),
        )
    }

    /** Split reasoning blocks into (search steps, remaining thinking text). */
    fun split(blocks: List<String>): Pair<List<String>, String> = blocks.partition(::isStep).let { (steps, rest) -> steps to rest.joinToString("") }
}
