package dev.hoshino.bridge.metrics

import org.json.JSONArray
import org.json.JSONObject

/**
 * 测试用的 dsh 会话日志构造器：事件形状照 `@deepseek-ai/dsh-session` 0.2.2-alpha.2 的 `SessionEventMap`
 * （turn/start、user/message、step/start、assistant/message{message,stream,usage}、tool/call、tool/result{message.source.callId}、step/end、turn/end{reason}）。
 */
class Dsh(private var seq: Int = 1, var now: Long = 1_000_000L) {
    val events = mutableListOf<DshEvent>()

    fun at(t: Long) = apply { now = t }
    fun after(ms: Long) = apply { now += ms }

    fun ev(type: String, data: JSONObject): Dsh = apply { events += DshEvent(seq++, type, now, data) }

    fun turnStart(turn: Int = 1) = ev("turn/start", JSONObject().put("turn", turn))
    fun user(text: String, kind: String = "user", turn: Int = 1) = ev("user/message", JSONObject().put("turn", turn).put("source", JSONObject().put("kind", kind))
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text))))
    fun stepStart(step: Int, turn: Int = 1) = ev("step/start", JSONObject().put("turn", turn).put("step", step))
    fun stepEnd(step: Int, turn: Int = 1) = ev("step/end", JSONObject().put("turn", turn).put("step", step))

    /** 一条助手消息；[firstTokenAt] 是流里第一个非空片段的时间（packed text run）。 */
    fun assistant(step: Int, text: String, firstTokenAt: Long? = null, usage: JSONObject? = usage(100, 20), turn: Int = 1, toolCall: String? = null): Dsh {
        val stream = JSONArray()
        if (firstTokenAt != null) {
            if (toolCall != null) stream.put(JSONObject().put("type", "tool-call-chunks").put("time0", firstTokenAt).put("index", 0).put("dt", JSONArray()).put("id", "c").put("name", toolCall).put("args", JSONArray().put("{}")))
            else stream.put(JSONObject().put("type", "text-chunks").put("time0", firstTokenAt).put("index", 0).put("dt", JSONArray().put(5)).put("texts", JSONArray().put("").put(text.ifEmpty { "x" })))
        }
        val content = JSONArray()
        if (text.isNotEmpty()) content.put(JSONObject().put("type", "text").put("text", text))
        val data = JSONObject().put("turn", turn).put("step", step).put("message", JSONObject().put("role", "assistant").put("content", content)).put("stream", stream)
        usage?.let { data.put("usage", it) }
        return ev("assistant/message", data)
    }

    fun toolCall(id: String, name: String, args: String = "{}", step: Int = 1, turn: Int = 1) =
        ev("tool/call", JSONObject().put("turn", turn).put("step", step).put("callId", id).put("name", name).put("arguments", args))

    fun toolResult(id: String, text: String, error: Boolean = false, step: Int = 1, turn: Int = 1) =
        ev("tool/result", JSONObject().put("turn", turn).put("step", step).put("message", JSONObject().put("role", "tool")
            .put("source", JSONObject().put("kind", "tool").put("callId", id)).put("toolCallId", id).put("isError", error)
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))))

    fun retry(id: String, step: Int = 1) = ev("llm/retry", JSONObject().put("retryId", id).put("turn", 1).put("step", step).put("provider", "p").put("mode", "normal").put("policyKey", "k").put("retry", 1).put("maxRetries", 3))
    fun retryStarted(id: String, step: Int = 1) = ev("llm/retry-started", JSONObject().put("retryId", id).put("turn", 1).put("step", step))

    fun turnEnd(kind: String = "completed", turn: Int = 1, extra: JSONObject? = null) =
        ev("turn/end", JSONObject().put("turn", turn).put("reason", (extra ?: JSONObject()).put("kind", kind)))

    companion object {
        fun usage(input: Long, output: Long, cacheRead: Long = 0, cacheWrite: Long = 0, reasoning: Long = 0) = JSONObject()
            .put("inputTokens", input).put("outputTokens", output).put("cacheReadTokens", cacheRead).put("cacheWriteTokens", cacheWrite).put("reasoningTokens", reasoning)

        /** 一轮典型的「读一次再回答」：两次模型调用、一次 web_fetch。 */
        fun turn(goal: String = "查一下", answer: String = "答案", toolResult: String = "页面内容", tool: String = "web_fetch"): List<DshEvent> = Dsh()
            .turnStart().user(goal)
            .after(10).stepStart(1).after(400).assistant(1, "", firstTokenAt = 1_000_300L, toolCall = tool)
            .toolCall("c1", tool, """{"url":"https://example.com"}""").after(1_000).toolResult("c1", toolResult).stepEnd(1)
            .after(5).stepStart(2).after(800).assistant(2, answer, firstTokenAt = 1_001_700L).stepEnd(2)
            .after(5).turnEnd().events
    }
}
