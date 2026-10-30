package app.hoshino.harness.core

/*
 * 领域模型。与 dsh（DeepSeek Harness headless 内核）协议字段一一对应的地方用 key 标注，
 * 便于以后在 M0 bridge 中做 JSON <-> Kotlin 映射。
 */

enum class ConnectionState(val label: String) {
    Connecting("连接中"), Connected("已连接"), Disconnected("未连接")
}

enum class PermissionTier(val label: String, val key: String, val description: String) {
    ReadOnly("只读", "read-only", "只看不改，所有写操作都会被拒绝。"),
    WorkspaceWrite("工作区内修改", "workspace-write", "只能修改当前工作区；危险操作会弹出审批卡。"),
    FullAccess("完全访问", "full-access", "通过 Shizuku 使用系统执行能力；危险操作仍需审批。"),
}

enum class ReasoningEffort(val label: String, val key: String) {
    Off("关", "off"), Low("低", "low"), Medium("中", "medium"), High("高", "high"), XHigh("超高", "xhigh"), Max("max", "max")
}

enum class ShizukuStatus(val label: String) {
    NotInstalled("未安装"), NotRunning("未运行"), NotAuthorized("未授权"), Ready("已就绪")
}

data class Workspace(val id: String, val name: String, val path: String)
data class SessionSearchHit(val sessionId:String,val snippet:String)
data class SessionSearchResults(val items:List<SessionSearchHit>,val hasMore:Boolean=false)

data class ModelRef(val providerId: String, val modelId: String)

data class SessionInfo(
    val id: String,
    val title: String,
    val workspaceId: String,
    val presetId: String,
    val tier: PermissionTier,
    val createdAt: Long,
    val updatedAt: Long,
    val running: Boolean = false,
    val archived: Boolean = false,
    val parentId: String? = null,
)

enum class AttachmentKind(val label: String) { File("文件"), Image("图片"), Camera("拍照"), WorkspaceFile("工作区文件") }

data class Attachment(val id: String, val name: String, val kind: AttachmentKind, val sizeLabel: String = "")

data class TurnOptions(
    val presetId: String,
    val tier: PermissionTier,
    val model: ModelRef?,
    val effort: ReasoningEffort,
    val planMode: Boolean,
)

data class Usage(
    val inputTokens: Int,
    val outputTokens: Int,
    val cacheReadTokens: Int,
    val reasoningTokens: Int,
    val durationMs: Long,
) {
    val total: Int get() = inputTokens + outputTokens
}

data class ContextUsage(val usedTokens: Int, val limitTokens: Int, val breakdown: List<Pair<String, Int>>) {
    val fraction: Float get() = if (limitTokens == 0) 0f else (usedTokens.toFloat() / limitTokens).coerceIn(0f, 1f)
}

enum class ToolStatus(val label: String) { Running("运行中"), Success("成功"), Failed("失败"), Denied("被拒") }

enum class ToolKind { Generic, Bash, Edit }

enum class DiffLineType { Hunk, Context, Add, Remove }

data class DiffLine(val type: DiffLineType, val text: String, val oldNo: Int? = null, val newNo: Int? = null)

enum class DiffState(val label: String) { Pending("待确认"), Accepted("已接受"), Reverted("已撤销") }

data class TodoItem(val id: String, val text: String, val done: Boolean, val active: Boolean = false)

enum class TaskStatus(val label: String) { Pending("等待"), Running("运行中"), Done("完成"), Failed("失败") }

data class SubTask(
    val id: String,
    val title: String,
    val agent: String,
    val status: TaskStatus,
    val progress: Float,
    val log: List<String> = emptyList(),
)

data class QuestionOption(val id: String, val label: String, val description: String = "", val recommended: Boolean = false)

enum class GoalStatus(val label: String) { Active("进行中"), Paused("暂停"), Done("完成") }

data class Goal(
    val id: String,
    val title: String,
    val description: String,
    val status: GoalStatus,
    val progress: Float,
    val updatedAt: Long,
)

enum class Repeat(val label: String) { Once("一次"), Daily("每天"), Weekly("每周"), Cron("cron") }

data class ScheduleRun(val at: Long, val ok: Boolean, val summary: String)

data class Schedule(
    val id: String,
    val title: String,
    val prompt: String,
    val repeat: Repeat,
    val rule: String,
    val timezone: String,
    val nextRun: Long?,
    val enabled: Boolean,
    val runs: List<ScheduleRun> = emptyList(),
)

enum class ApprovalDecision(val label: String) { AllowOnce("允许一次"), AllowSession("本会话总是允许"), Deny("拒绝") }

// ---------- 模型供应商（opencode 风格：一个供应商 → 多个模型） ----------

enum class ProviderProtocol(val label: String) {
    Anthropic("Anthropic"), OpenAICompatible("OpenAI 兼容"), OpenAIResponses("OpenAI Responses")
}

data class ModelInfo(
    val id: String,
    val displayName: String,
    val contextLength: Int,
    val maxOutput: Int,
    val vision: Boolean,
    val reasoning: Boolean,
    val defaultEffort: ReasoningEffort,
    /** Model-hosted web search override: "auto" | "on" | "off" (see dev.hoshino.bridge.WebSearchMode). */
    val webSearchMode: String = "auto",
    /** Detected hosted-search method id, "none", or "" when not yet known (see dev.hoshino.bridge.WebSearchMethods). */
    val webSearchMethod: String = "",
)

data class Provider(
    val id: String,
    val name: String,
    val catalogId: String,
    val protocol: ProviderProtocol,
    val baseUrl: String,
    val apiKey: String,
    val headers: List<Pair<String, String>>,
    val enabled: Boolean,
    val models: List<ModelInfo>,
)

data class CatalogProvider(val id: String, val name: String, val protocol: ProviderProtocol, val baseUrl: String)

data class ModelRouting(
    val main: ModelRef? = null,
    val subagent: ModelRef? = null,
    val compact: ModelRef? = null,
    val vision: ModelRef? = null,
    val approval: ModelRef? = null,
)

// ---------- Agent 预设 ----------

data class AgentPreset(
    val id: String,
    val name: String,
    val description: String,
    val builtIn: Boolean,
    val systemPrompt: String,
    val tools: List<String>,
    val defaultModel: ModelRef?,
    val defaultTier: PermissionTier,
)

// ---------- 扩展 ----------

enum class PluginSource(val label: String) { BuiltIn("内置"), Git("Git"), Local("本地") }

enum class PluginCategory(val label: String) {
    Model("模型"), Tool("工具"), Session("会话"), Sandbox("沙箱"), Storage("存储"), Schedule("调度"), UI("UI")
}

data class Plugin(
    val id: String,
    val name: String,
    val pkg: String,
    val version: String,
    val enabled: Boolean,
    val source: PluginSource,
    val category: PluginCategory,
    val description: String,
)

data class Skill(
    val id: String,
    val name: String,
    val description: String,
    val enabled: Boolean,
    val source: String,
    val content: String,
)

enum class McpTransport(val label: String) { Stdio("stdio"), Http("HTTP") }

enum class McpStatus(val label: String) { Connected("已连接"), Connecting("连接中"), Error("错误"), Disabled("已停用") }

data class McpTool(val name: String, val description: String, val enabled: Boolean)

data class McpServer(
    val id: String,
    val name: String,
    val transport: McpTransport,
    val target: String,
    val env: String,
    val oauth: Boolean,
    val status: McpStatus,
    val tools: List<McpTool>,
)

data class Hook(
    val id: String,
    val event: String,
    val matcher: String,
    val command: String,
    val compat: String,
    val enabled: Boolean,
)

data class WebhookRule(val id: String, val name: String, val url: String, val events: String, val enabled: Boolean)

data class SlashCommand(val name: String, val description: String, val source: String = "内置")

// ---------- 内核 / 工具台 ----------

data class KernelInfo(
    val dshVersion: String,
    val nodeVersion: String,
    val running: Boolean,
    val sandbox: String,
    val sandboxReady: Boolean,
)

data class LspServer(val name: String, val language: String, val status: String, val version: String)

data class InspectorNode(val name: String, val kind: String, val status: String, val children: List<InspectorNode> = emptyList())

data class PhoneCapability(val id: String, val name: String, val description: String, val granted: Boolean)

enum class TerminalStream { Input, Output, Error }

data class TerminalLine(val text: String, val stream: TerminalStream)

data class TerminalSession(val id: String, val title: String, val lines: List<TerminalLine>)

enum class TrajSource(val key: String) {
    System("system"), User("user"), Reasoning("reasoning"), ToolCall("tool_call"),
    ToolResult("tool_result"), Subagent("subagent"), Context("context injection")
}

data class TrajectoryEntry(
    val id: String,
    val source: TrajSource,
    val title: String,
    val payload: String,
    val at: Long,
)

data class SessionStats(
    val turns: Int,
    val inputTokens: Int,
    val outputTokens: Int,
    val cacheReadTokens: Int,
    val reasoningTokens: Int,
    val totalDurationMs: Long,
    val toolCalls: Int,
)

enum class ApprovalPolicy(val label: String, val description: String) {
    Always("每次询问", "所有写操作与命令都弹审批卡"),
    Dangerous("危险操作才询问", "仅对删除、联网安装、越出工作区等操作审批（默认）"),
    Remember("本会话记住", "同类操作在本会话内只审批一次"),
}
