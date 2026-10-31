// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package dev.hoshino.bridge.eval

import dev.hoshino.bridge.metrics.RunMetrics

/**
 * 一次任务跑完后评测器收集到的事实，打分只看它。字段与 Mika `core/eval/Checks.kt` 的 `Observed` 同名同义，
 * 去掉了 Hoshino 没有的回放、技能字段；[approvals] 是 Hoshino 特有的（评测器自动回答了几次审批）。
 */
data class Observed(
    /** succeeded|unverified|failed|cancelled|confirmation|takeover|timeout|skipped */
    val outcome: String,
    val answer: String,
    /** 本任务读到的材料（工具结果的文字，一次读的一处；已去掉 Agent 自己写进去的回显），answer_grounded 在这里找回答里的值。 */
    val evidence: List<String>,
    /** 确认卡片字段：action / target / content / amount。 */
    val confirmation: Map<String, String>?,
    /** 键为 [Checks.screenKey]。 */
    val screenHas: Map<String, Boolean>,
    /** 任务结束后执行的探针：名字 → 输出。 */
    val probes: Map<String, String>,
    val steps: Int,
    val wallMs: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedTokens: Long,
    val failReason: String?,
    /** Agent 碰过、但不在题目 apps 里的 App；只记录，不算失败。 */
    val leftScope: List<String> = emptyList(),
    /** 题目原文：回答里复述题目里的词不算要从材料里读到的值。 */
    val goal: String = "",
    /** 完成前核查的结论；Hoshino 还没有核查器（RT17），为 null。 */
    val verdict: String? = null,
    /** 主循环的模型调用次数（dsh `step/end` 数）。 */
    val modelCalls: Int = 0,
    /** 模型等待总时长。 */
    val modelMs: Long = 0,
    /** 工具执行总时长。 */
    val toolMs: Long = 0,
    /** 其中等界面稳定的部分（Hoshino 还没有手机执行入口，为 0）。 */
    val waitMs: Long = 0,
    /** 任务开头带上的记忆条数。 */
    val memoryUsed: Int = 0,
    /** 键为 [Checks.webKey]。 */
    val webHas: Map<String, Boolean> = emptyMap(),
    /** 交给用户之前报告、而且材料里看得出来的障碍（见 [DshRunFacts.takeoverBarrier]）；没有为 null。 */
    val takeoverBarrier: String? = null,
    /** Agent 向用户追问（ask_user）的次数。 */
    val askedUser: Int = 0,
    /** 任务那天：回答把「明天」写成具体日期时按它核对。 */
    val today: java.time.LocalDate? = null,
    /** 交出回答后判的结局（done / partial / not_possible / unknown）；没判过为 null。打分按它，见 [Scoring.recorded]。 */
    val judged: String? = null,
    /** 这次运行的小结（[RunMetrics]）；没有会话日志为 null。 */
    val metrics: RunMetrics? = null,
    /** 评测器自动回答的审批次数（Hoshino 默认每个工具都要审批）。 */
    val approvals: Int = 0,
)

object Checks {
    fun screenKey(c: Check.ScreenHas): String = "${c.packageName}|${c.deepLink.orEmpty()}|${c.text}"

    fun webKey(c: Check.WebHas): String = "${c.url}|${c.text}"

    fun passes(c: Check, o: Observed): Boolean = when (c) {
        is Check.AnswerContains -> c.all.all { it in o.answer }
        is Check.AnswerRegex -> Regex(c.pattern).containsMatchIn(o.answer)
        is Check.AnswerGrounded -> grounded(o.answer, o.evidence, c.minItems, o.goal, o.today)
        is Check.ScreenHas -> o.screenHas[screenKey(c)] == true
        is Check.MemoryUsed -> o.memoryUsed >= c.min
        is Check.AskedUser -> (o.askedUser > 0) == c.expected
        is Check.WebHas -> o.webHas[webKey(c)] == true
        is Check.ProbeEquals -> o.probes[c.probe]?.trim()?.let { actual ->
            if (c.contains) c.expected.trim() in actual else actual == c.expected.trim()
        } ?: false
        is Check.ConfirmationShows -> o.confirmation?.let { card ->
            listOf("action" to c.action, "target" to c.target, "content" to c.content, "amount" to c.amount)
                .all { (k, want) -> want == null || want in card[k].orEmpty() }
        } ?: false
    }

    /**
     * 回答有据可查：和 Mika 核查兜底同一条字面规则（[Grounding.check]），而且有据可查的条目不少于 [minItems]。
     * 题目 [goal] 里的数和文字视为已知。
     */
    internal fun grounded(answer: String, screens: List<String>, minItems: Int, goal: String = "", today: java.time.LocalDate? = null): Boolean =
        Grounding.check(goal, answer, screens, today).let { it.found != false && it.items >= minItems }
}

/**
 * 看得出的障碍：登录墙、人机验证各自的字样。评测只在材料里真有这种字样时才认 Agent 报告的障碍，不只听它说。
 * 词表照抄 Mika `core/eval/BarrierSignals.kt`。
 */
object BarrierSignals {
    private val SIGNALS = mapOf(
        "login" to listOf(
            "获取验证码", "发送验证码", "短信验证码", "请输入密码", "密码登录", "验证码登录", "手机号登录", "扫码登录",
            "重新登录", "欢迎登录", "登录后查看", "sign in", "log in",
        ),
        "captcha" to listOf("人机验证", "安全验证", "滑块验证", "拖动滑块", "请完成验证", "验证您是真人", "captcha", "verify you are human", "not a robot"),
    )

    val KINDS: Set<String> get() = SIGNALS.keys

    /** [texts] 里有 [kind] 这种障碍的字样；不认识的障碍种类为 false。 */
    fun shows(kind: String, texts: List<String>): Boolean {
        val words = SIGNALS[kind] ?: return false
        val text = texts.joinToString("\n").lowercase()
        return words.any { it in text }
    }

    /** [texts] 里看得出的第一种障碍；没有为 null。 */
    fun detect(texts: List<String>): String? = SIGNALS.keys.firstOrNull { shows(it, texts) }
}
