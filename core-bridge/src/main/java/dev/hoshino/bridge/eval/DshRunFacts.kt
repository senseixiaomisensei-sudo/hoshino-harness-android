// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package dev.hoshino.bridge.eval

import dev.hoshino.bridge.metrics.DshEvent
import org.json.JSONObject

/**
 * 从一轮 dsh 会话日志里取评测要用的事实（对应 Mika `core/eval/RunFacts.kt` + `RecordedEvidence.kt`）。
 * Mika 的事实来自它自己的运行记录（读屏、执行入口）；Hoshino 的来自 dsh 原版会话日志：`assistant/message`、`tool/call`、`tool/result`、`turn/end`。
 */
object DshRunFacts {
    /** 问用户的工具（与 Mika 同名）。 */
    const val ASK_USER = "ask_user"

    /** 结果是 Agent 自己写出去的东西、不是读到的材料：不进证据。 */
    private val AUTHORED = setOf("write", "edit", "self_write", "self_apply", "self_snapshot", "self_rollback", "todo_write", "deliver_file", "clipboard_write", "share_text", "share_file")
    private val SHELL = setOf("bash", "system_shell", "terminal")

    /** 最终回答：这一轮最后一条带文字的 `assistant/message`（中途的过程说明不算）。没有为空串。 */
    fun answer(events: List<DshEvent>): String = events.lastOrNull { it.type == "assistant/message" && text(it).isNotBlank() }?.let(::text).orEmpty()

    private fun text(e: DshEvent): String = DshEvent.text(e.data.optJSONObject("message")?.optJSONArray("content"))

    /** 每次工具调用：名字、参数原文、结果文字、是否出错。按调用顺序。 */
    data class ToolUse(val callId: String, val name: String, val arguments: String, val result: String?, val isError: Boolean, val atMs: Long)

    fun tools(events: List<DshEvent>): List<ToolUse> {
        val calls = LinkedHashMap<String, ToolUse>()
        for (e in events) when (e.type) {
            "tool/call" -> e.data.optString("callId").takeIf { it.isNotEmpty() }?.let { id ->
                calls[id] = ToolUse(id, e.data.optString("name"), e.data.optString("arguments"), null, false, e.time)
            }
            "tool/result" -> {
                val m = e.data.optJSONObject("message") ?: continue
                val id = m.optJSONObject("source")?.optString("callId")?.takeIf { it.isNotEmpty() } ?: m.optString("toolCallId")
                calls[id]?.let { calls[id] = it.copy(result = DshEvent.text(m.optJSONArray("content")), isError = m.optBoolean("isError")) }
            }
        }
        return calls.values.toList()
    }

    /**
     * 评测核对回答用的材料：每次读到的东西一处（工具结果的文字）。和 Mika 的证据账本一样排除 Agent 自己写的东西：
     * 写文件、编辑之类的结果不算；命令输出里原样出现在命令里的行（回显）去掉；出错的调用只留它的错误文字（网页打不开也是看到的事实）。
     */
    fun evidence(events: List<DshEvent>): List<String> = tools(events).mapNotNull { t ->
        val r = t.result?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        when (t.name) {
            in AUTHORED -> null
            in SHELL -> {
                val command = runCatching { JSONObject(t.arguments).optString("command") }.getOrDefault(t.arguments)
                r.lines().filterNot { line -> line.isNotBlank() && line.trim().length >= 3 && line.trim() in command }.joinToString("\n").takeIf { it.isNotBlank() }
            }
            else -> r
        }
    }

    /** Agent 问了用户几次。 */
    fun askedUser(events: List<DshEvent>): Int = events.count { it.type == "tool/call" && it.data.optString("name") == ASK_USER }

    /**
     * 交给用户的原因要有材料上的证据（对应 Mika `RunFacts.takeoverBarrier`）：Agent 用 ask_user 交给用户，问题里说的是登录 / 验证，
     * 而且问之前最后一次读到的材料里看得出这种障碍（[BarrierSignals]）。只有 Agent 自己说、材料里看不出来的不算。
     */
    fun takeoverBarrier(events: List<DshEvent>): String? {
        val used = tools(events)
        val ask = used.indexOfFirst { it.name == ASK_USER }
        if (ask < 0) return null
        val question = runCatching { JSONObject(used[ask].arguments).optString("question") }.getOrDefault("")
        val read = used.subList(0, ask).lastOrNull { it.name !in AUTHORED && it.name != ASK_USER && !it.result.isNullOrBlank() }?.result ?: return null
        val kind = BarrierSignals.detect(listOf(read)) ?: return null
        return kind.takeIf { BarrierSignals.shows(it, listOf(question)) || QUESTION_HINT[it]?.containsMatchIn(question) == true }
    }

    private val QUESTION_HINT = mapOf(
        "login" to Regex("登录|登入|账号|sign ?in|log ?in", RegexOption.IGNORE_CASE),
        "captcha" to Regex("验证|captcha|robot", RegexOption.IGNORE_CASE),
    )

    /** 这一轮怎么结束的：`turn/end.reason`，没有为 null。 */
    fun endReason(events: List<DshEvent>): JSONObject? = events.lastOrNull { it.type == "turn/end" }?.data?.optJSONObject("reason")

    /**
     * 交给打分的失败原因：按 dsh 的结束原因生成，不用 Agent 自己说的话（对应 Mika `Scoring.failReason`）。
     * error 里 `TOOL_ACTIVATION_FAILED` 是运行环境的问题（记成评测器类），其余按模型出错；blocked 按安全停止；没失败返回 null。
     */
    fun failReason(reason: JSONObject?): String? {
        val kind = reason?.optString("kind") ?: return null
        return when (kind) {
            "completed", "aborted", "interrupted", "forked" -> null
            "error" -> {
                val error = reason.optJSONObject("error")
                val code = error?.optString("code").orEmpty()
                val status = error?.optInt("status")?.takeIf { it in 100..599 }
                if (code == "TOOL_ACTIVATION_FAILED") Scoring.HARNESS_PREFIX + "运行环境:工具启动失败"
                else "模型出错:" + listOfNotNull(code.takeIf { it.isNotBlank() }, status?.let { "HTTP $it" }).joinToString(" · ").ifBlank { "内核未返回错误详情" }
            }
            "blocked" -> "已安全停止(blocked)"
            "max-tokens" -> "输出达到 token 上限"
            else -> kind
        }
    }

    /**
     * 回答是不是如实说了「做不到」（RT9 结局判定合并前的保守替代）：Hoshino 没有 Mika 的 `end_not_done` 工具，
     * 也还没有判结局的轻量模型，这里只认回答里明白说做不成、查不到、不存在的话，判成 not_possible；其余不判（null）。
     * 误判方向是保守的：读类题的回答里带这种话会被算成没办成（拉低 Hoshino 的分），不会把编造的回答算成办成。
     * 报告里标「结局:文字判定」，人工改判规则同 Mika。
     */
    fun judgeFromText(answer: String): String? {
        if (answer.isBlank()) return null
        val head = answer.substringBefore("依据").take(600)
        return if (NOT_POSSIBLE.containsMatchIn(head)) "not_possible" else null
    }

    private val NOT_POSSIBLE = Regex(
        "做不到|做不了|无法(完成|做到|实现|办到|查到|找到|获取|提供|预测|设置|创建|访问|打开|执行)|没(有)?办法|办不到|不可能|" +
            "并不存在|不存在|没有这个|没有该|没有此|查不到|找不到|搜不到|不支持|不提供|" +
            "(?i:\\b(can ?not|can't|unable to|not possible|impossible|does not exist|doesn't exist|no such)\\b)",
    )
}
