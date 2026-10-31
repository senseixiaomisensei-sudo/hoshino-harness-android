// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package dev.hoshino.bridge.eval

@Suppress("EnumEntryName")
enum class FailureClass {
    perception, grounding, route, barrier, app_state, input, guard_stop, step_limit, model_error,
    false_success, verifier_reject, gate, harness, unknown,
    /** 交出时标了「没能核实」、核对项也没过。 */
    unverified_wrong,
    /** 并行评测里同时跑的另一题抢了这题要用的 App。 */
    interference,
    /** Hoshino 特有：这台设备上的 Hoshino 缺这道题要的能力（手机操作、记忆……），属于「做不了」而不是「做错」。 */
    missing_capability,
}

data class TaskScore(
    val id: String,
    val passed: Boolean,
    val falseSuccess: Boolean,
    val unverified: Boolean,
    /** 没过的核对项："outcome_is" 或 "<序号>:<类型>"。 */
    val failedChecks: List<String>,
    val failureClass: FailureClass?,
    /** 写类题造出来的数据标记（已替换 {run}），报告里列出供人工清理。 */
    val marker: String? = null,
    val leftScope: List<String> = emptyList(),
    /** 交给用户接管的障碍种类：单列，不算办成。 */
    val handedOver: String? = null,
)

/** 打分规则逐条照抄 Mika `core/eval/Scoring.kt`，好让两边的完成率可以直接比。 */
object Scoring {
    private val MODEL_ERROR = Regex("API Key|额度|限流|模型")

    /** 评测运行器自己出错时，失败原因以此开头。 */
    const val HARNESS_PREFIX = "评测器:"

    const val INTERFERENCE_PREFIX = "并行干扰:"

    /** Agent 如实报告做不成；期望失败的题只认这一种。 */
    const val GAVE_UP = "Agent 如实报告做不成"

    const val VERIFICATION_PREFIX = "核查未通过"

    /** Hoshino 缺这道题要的能力时的失败原因开头（只在评测器被要求硬跑时出现；默认按能力跳过）。 */
    const val MISSING_CAPABILITY_PREFIX = "缺能力:"

    /**
     * 按判的结局打分：回答如实说没办成、只办了一部分的（[Observed.judged] 为 partial / not_possible），
     * 和 Agent 如实放弃一样算 failed，不算报成功。
     */
    fun recorded(o: Observed): Observed =
        if ((o.outcome == "succeeded" || o.outcome == "unverified") && (o.judged == "partial" || o.judged == "not_possible"))
            o.copy(outcome = "failed", failReason = GAVE_UP)
        else o

    fun score(t: EvalTask, observed: Observed): TaskScore {
        val o = recorded(observed)
        val failed = failedChecks(t, o)
        return TaskScore(
            id = t.id,
            passed = failed.isEmpty(),
            falseSuccess = isFalseSuccess(o, failed),
            unverified = o.outcome == "unverified",
            failedChecks = failed,
            failureClass = initialClass(t, o),
            marker = t.marker?.takeIf { o.steps > 0 },
            leftScope = o.leftScope,
            handedOver = handedOver(t, o),
        )
    }

    /** 初判失败类别；办成的题返回 null。人看运行记录后可改判。 */
    fun initialClass(t: EvalTask, observed: Observed): FailureClass? {
        val o = recorded(observed)
        val failed = failedChecks(t, o)
        if (failed.isEmpty()) return null
        if (isFalseSuccess(o, failed)) return FailureClass.false_success
        if (o.outcome == "unverified") return FailureClass.unverified_wrong
        val reason = o.failReason.orEmpty()
        if (reason.startsWith(VERIFICATION_PREFIX)) return FailureClass.verifier_reject
        if (reason.startsWith(MISSING_CAPABILITY_PREFIX)) return FailureClass.missing_capability
        infraClass(o)?.let { return it }
        return when {
            "已安全停止" in reason -> FailureClass.guard_stop
            "步上限" in reason -> FailureClass.step_limit
            "核查未通过" in reason -> FailureClass.verifier_reject
            o.outcome == "takeover" && t.category != EvalCategory.Barrier -> FailureClass.barrier
            else -> FailureClass.unknown
        }
    }

    private fun infraClass(o: Observed): FailureClass? {
        val reason = o.failReason ?: return null
        return when {
            reason.startsWith(HARNESS_PREFIX) -> FailureClass.harness
            reason.startsWith(INTERFERENCE_PREFIX) -> FailureClass.interference
            MODEL_ERROR.containsMatchIn(reason) -> FailureClass.model_error
            else -> null
        }
    }

    private fun isFalseSuccess(o: Observed, failed: List<String>) = o.outcome == "succeeded" && failed.isNotEmpty()

    /** [EvalTask.acceptTakeoverOn] 没写时的默认：读类题撞上登录墙交给用户；别的类不认。 */
    fun takeoverKinds(t: EvalTask): List<String> =
        t.acceptTakeoverOn ?: if (t.category == EvalCategory.Read && t.expect == Expect.Succeeded) listOf("login") else emptyList()

    private val SIGNED_IN_KINDS = setOf("signedIn", "login", "site")

    /** 撞上题目认可的障碍、材料里也看得出来，交给了用户：返回障碍种类，单独记一类「交给用户」。 */
    fun handedOver(t: EvalTask, o: Observed): String? = o.takeoverBarrier?.takeIf { kind ->
        t.expect != Expect.Takeover && o.outcome == "takeover" && kind in takeoverKinds(t) &&
            t.requires.none { it.substringBefore(':') in SIGNED_IN_KINDS }
    }

    private fun failedChecks(t: EvalTask, o: Observed): List<String> {
        val expected = t.expect.wire // succeeded 时 unverified 不算
        val out = mutableListOf<String>()
        // 期望失败的题，只有 Agent 自己如实放弃才算；模型、评测器出错，撞步上限、被守卫停下都不算
        val honest = t.expect != Expect.Failed || o.failReason == GAVE_UP
        if (o.outcome != expected || !honest) out += "outcome_is"
        t.checks.forEachIndexed { i, c -> if (!Checks.passes(c, o)) out += "$i:${c.type}" }
        return out
    }
}
