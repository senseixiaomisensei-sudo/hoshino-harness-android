package app.hoshino.harness.core

/**
 * 审批档。键与插件 hoshino-m2 的 approval.json `mode` 一致。
 * [None]（无审批）只能由用户主动开启；出厂默认仍是 [Manual]。
 * 审批档只决定「要不要弹确认卡」，权限档（只读 / 工作区 / 完全访问）的边界由内核插件先行判定，任何审批档都放宽不了。
 */
enum class ApprovalMode(val key: String, val label: String, val description: String) {
    Manual("manual", "手动", "每次写文件、执行命令、联网等操作都先弹卡确认。"),
    Rules("rules", "规则", "按你写的规则自动允许或拒绝，没有命中的仍弹卡。"),
    Model("model", "审批模型", "由你指定的审批模型判断，失败或超时仍交给你。"),
    Auto("auto", "自动", "按策略自动放行；删除、重置、安装插件、自更新等高危操作仍要确认。"),
    None("none", "无审批", "所有工具调用、文件写入、shell、联网、高危操作都自动执行，不再弹卡；审计照常记录。");

    companion object {
        val Default = Manual
        fun of(key: String?): ApprovalMode? = entries.firstOrNull { it.key == key }
    }
}

/** 某个会话此刻的审批设置：全局默认、本会话覆盖（可能没有）、实际生效的档。 */
data class ApprovalState(
    val defaultMode: ApprovalMode = ApprovalMode.Default,
    val sessionMode: ApprovalMode? = null,
    val effective: ApprovalMode = sessionMode ?: defaultMode,
) {
    val noApproval: Boolean get() = effective == ApprovalMode.None
    /** 本会话是否跟随默认（没有单独设置）。 */
    val followsDefault: Boolean get() = sessionMode == null
}

/** 切换审批档时应用到哪里。 */
enum class ApprovalScope(val key: String, val label: String) {
    Session("session", "仅本会话"),
    Default("default", "设为默认（新会话和未单独设置的会话）"),
    /** 清除本会话的单独设置，回到跟随默认。 */
    FollowDefault("clear", "跟随默认"),
}

object ApprovalModeRules {
    /** 开启无审批前是否需要先弹一次风险说明（用户勾过「不再提示」就不再弹）。关闭或切到其他档从不需要。 */
    fun needsRiskConfirmation(target: ApprovalMode, acknowledged: Boolean): Boolean = target == ApprovalMode.None && !acknowledged

    /** 选了某档后本地预估的新状态（真实结果以内核返回为准，用于乐观更新和新会话尚未创建时）。 */
    fun apply(state: ApprovalState, mode: ApprovalMode, scope: ApprovalScope): ApprovalState = when (scope) {
        ApprovalScope.Session -> state.copy(sessionMode = mode, effective = mode)
        ApprovalScope.Default -> state.copy(defaultMode = mode, sessionMode = null, effective = mode)
        ApprovalScope.FollowDefault -> state.copy(sessionMode = null, effective = state.defaultMode)
    }

    /** 无审批也绕不过去的系统级确认，界面上要讲清楚。 */
    const val SYSTEM_LIMITS = "安卓系统自己的确认无法绕过：安装 APK、运行时权限（通知、存储、相机等）、Shizuku 授权仍会由系统弹窗。"
    const val RISK_TEXT = "开启后，Agent 的所有工具调用、文件写入、shell 命令、联网请求和高危操作（删除、git reset、安装插件、自更新等）都会直接执行，不再询问你。\n\n" +
        "权限档仍然生效：只读会话依旧不能写，工作区修改依旧出不了工作区，系统命令仍只在完全访问会话里可用。你自己写的「拒绝」规则仍然生效，每次决定照常写入审计日志。\n\n" + SYSTEM_LIMITS
}

/**
 * 审批档的内存账本，语义与插件 approval.json 相同（默认档 + 每会话覆盖，会话覆盖优先）。
 * 演示客户端用它实现切换；单测用它验证切换规则与插件一致。
 */
class ApprovalBook(private var defaultMode: ApprovalMode = ApprovalMode.Default) {
    private val overrides = LinkedHashMap<String, ApprovalMode>()
    @Synchronized fun state(sessionId: String?): ApprovalState {
        val own = sessionId?.let { overrides[it] }
        return ApprovalState(defaultMode, own, own ?: defaultMode)
    }
    @Synchronized fun set(sessionId: String?, mode: ApprovalMode, scope: ApprovalScope): ApprovalState {
        when (scope) {
            ApprovalScope.Default -> { defaultMode = mode; sessionId?.let { overrides.remove(it) } }
            ApprovalScope.Session -> overrides[requireNotNull(sessionId) { "缺少会话" }] = mode
            ApprovalScope.FollowDefault -> overrides.remove(requireNotNull(sessionId) { "缺少会话" })
        }
        return state(sessionId)
    }
    @Synchronized fun forget(sessionIds: Collection<String>) { sessionIds.forEach { overrides.remove(it) } }
}
