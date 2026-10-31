package app.hoshino.harness.core

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * UI 与内核之间的唯一边界。
 *
 * - 会话内的一切运行过程（消息、思考、工具调用、审批、提问、Todo、子代理、用量……）
 *   通过 [events] 以 [HarnessEvent] 流的形式推送，由 [SessionStore] 归约成界面状态。
 * - 全局配置类数据（供应商、插件、MCP、定时任务……）用 [StateFlow] 暴露当前快照。
 * - 所有写操作都是 suspend 函数；真实实现里它们对应 dsh 的 JSON-RPC / stdio 请求（见 README）。
 *
 * 当前 UI 由 [FakeHarnessClient] 驱动，零后端即可运行。
 */
interface HarnessClient {
    /** Execution modes actually implemented by this host; Shizuku authorization is separate. */
    val supportedPermissionTiers: Set<PermissionTier> get() = setOf(PermissionTier.ReadOnly,PermissionTier.WorkspaceWrite)
    fun describeFailure(failure: Throwable): String = if(failure is UnsupportedOperationException) failure.message ?: "即将支持" else "操作未完成"
    /** Select the UI's active session without starting an Agent turn. */
    fun activateSession(sessionId: String) {}
    /**
     * 会话状态能否从内核持久化（dsh session JSONL）完整恢复。为 true 时 [SessionStore] 只在内存保留最近几个会话，
     * 被淘汰的会话通过 [releaseSession] 释放、切回时由 [retainSession]/[activateSession] 重新订阅并重放。
     */
    val sessionRestoreSupported: Boolean get() = false
    /** 内存淘汰：停止订阅该会话的事件流并忘掉去重状态，之后重新订阅会收到完整快照。 */
    fun releaseSession(sessionId: String) {}
    /** 被淘汰的会话再次被需要：重新订阅以从持久化恢复。 */
    fun retainSession(sessionId: String) {}
    /** 从会话日志读取某次工具调用的完整输出，写入缓存文件；不支持时返回 null（界面只显示内存里的尾部）。 */
    suspend fun fullToolOutput(sessionId: String, callId: String): java.io.File? = null
    val preferredSessionId:String? get()=null
    val uiCustomization: StateFlow<UiCustomization> get() = DefaultUiCustomization.value
    val uiCustomizationError: StateFlow<String?> get() = DefaultUiCustomization.error
    val configurationRollbackSupported:Boolean get() = false
    suspend fun configurationSnapshots():List<String> = throw UnsupportedOperationException("配置回滚即将支持")
    suspend fun rollbackConfiguration(snapshotId:String):Unit = throw UnsupportedOperationException("配置回滚即将支持")

    // ---------- 连接 & 会话 ----------
    val connection: StateFlow<ConnectionState>
    /** 会话内事件总线（热流）。实现方需保证同一会话事件有序。 */
    val events: SharedFlow<HarnessEvent>
    val workspaces: StateFlow<List<Workspace>>
    val sessions: StateFlow<List<SessionInfo>>
    suspend fun searchSessions(query:String):SessionSearchResults = SessionSearchResults(sessions.value.filter{it.title.contains(query,ignoreCase=true)}.map{SessionSearchHit(it.id,"")})

    suspend fun addWorkspace(name: String, path: String): Workspace
    suspend fun createSession(workspaceId: String, presetId: String, tier: PermissionTier): SessionInfo
    suspend fun renameSession(sessionId: String, title: String)
    suspend fun forkSession(sessionId: String): SessionInfo
    /** /export：导出会话为 ZIP，返回文件路径。 */
    suspend fun exportSession(sessionId: String): String
    suspend fun setArchived(sessionId: String, archived: Boolean)
    suspend fun deleteSession(sessionId: String)
    suspend fun setSessionTier(sessionId: String, tier: PermissionTier)
    suspend fun setSessionPreset(sessionId: String, presetId: String)
    /** @ 引用：在工作区中模糊搜索文件。 */
    suspend fun searchWorkspaceFiles(workspaceId: String, query: String): List<String>

    // ---------- 运行 ----------
    suspend fun send(sessionId: String, text: String, attachments: List<Attachment>, options: TurnOptions)
    /** 插话：运行中把一条消息立即注入当前轮次上下文。 */
    suspend fun steer(sessionId: String, text: String)
    /** 带附件的插话；不支持附件的实现只接受纯文字。 */
    suspend fun steer(sessionId: String, text: String, attachments: List<Attachment>) { require(attachments.isEmpty()) { "插话暂不支持附件" }; steer(sessionId, text) }

    // ---------- 会话管理与审批档（M2.7） ----------
    /** 批量删除（确认后调用）；默认逐个删除。 */
    suspend fun deleteSessions(sessionIds: List<String>) { for (id in sessionIds) deleteSession(id) }
    suspend fun setPinned(sessionId: String, pinned: Boolean): Unit = throw UnsupportedOperationException("置顶即将支持")
    /** 导出为 Markdown / JSON，保存到「下载/Hoshino」。 */
    suspend fun exportSessionFile(sessionId: String, format: SessionExportFormat): ExportedSession = throw UnsupportedOperationException("导出即将支持")
    /** 某会话（null = 新会话）当前的审批档。 */
    suspend fun approvalState(sessionId: String?): ApprovalState = ApprovalState()
    suspend fun setApprovalMode(sessionId: String?, mode: ApprovalMode, scope: ApprovalScope): ApprovalState = throw UnsupportedOperationException("审批设置需要真实内核")
    suspend fun stop(sessionId: String)
    suspend fun respondApproval(sessionId: String, approvalId: String, decision: ApprovalDecision)
    suspend fun answerQuestion(sessionId: String, questionId: String, answer: String)
    suspend fun resolveDiff(sessionId: String, diffId: String, accept: Boolean)
    /** /compact：压缩上下文。 */
    suspend fun compact(sessionId: String)
    val slashCommands: StateFlow<List<SlashCommand>>

    // ---------- 模型供应商 ----------
    val catalog: List<CatalogProvider>
    val providers: StateFlow<List<Provider>>
    val routing: StateFlow<ModelRouting>
    suspend fun upsertProvider(provider: Provider)
    suspend fun deleteProvider(providerId: String)
    suspend fun setProviderEnabled(providerId: String, enabled: Boolean)
    /** 测试连接，返回可读结果文本。 */
    suspend fun testProvider(provider: Provider): Result<String>
    /** 从供应商 /models 接口拉取模型列表。 */
    suspend fun fetchModels(provider: Provider): List<ModelInfo>
    suspend fun setRouting(routing: ModelRouting)

    // ---------- Agent 预设 ----------
    val presets: StateFlow<List<AgentPreset>>
    suspend fun upsertPreset(preset: AgentPreset)
    suspend fun deletePreset(presetId: String)

    // ---------- 扩展 ----------
    val plugins: StateFlow<List<Plugin>>
    /** 插件变更后需要重启内核才能生效。 */
    val restartRequired: StateFlow<Boolean>
    suspend fun setPluginEnabled(pluginId: String, enabled: Boolean)
    /** spec 形如 github:owner/repo#ref */
    suspend fun installPluginFromGit(spec: String): Result<Plugin>
    suspend fun linkLocalPlugin(path: String): Result<Plugin>
    /** 从本地 ZIP 导入插件（package.json 或 index.js 在根目录或唯一子目录）。 */
    suspend fun installPluginFromZip(archive: java.io.File, name: String): Result<Plugin> = Result.failure(UnsupportedOperationException("即将支持"))
    /** 插件市场（与电脑版 DSH「发现」相同的 format 1 插件索引）：sources / source-add / source-remove / query / plugin。 */
    suspend fun market(op: String, args: org.json.JSONObject = org.json.JSONObject()): org.json.JSONObject = throw UnsupportedOperationException("插件市场需要真实内核")
    suspend fun installFromMarket(source: String, name: String, version: String?): Result<Plugin> = Result.failure(UnsupportedOperationException("插件市场需要真实内核"))
    suspend fun removePlugin(pluginId: String)
    suspend fun readPluginConfig(pluginId: String): String
    suspend fun writePluginConfig(pluginId: String, content: String)

    val skills: StateFlow<List<Skill>>
    suspend fun setSkillEnabled(skillId: String, enabled: Boolean)
    suspend fun createSkill(name: String, description: String, content: String)

    val mcpServers: StateFlow<List<McpServer>>
    suspend fun upsertMcpServer(server: McpServer)
    suspend fun removeMcpServer(serverId: String)
    suspend fun setMcpToolEnabled(serverId: String, toolName: String, enabled: Boolean)

    val hooks: StateFlow<List<Hook>>
    val webhooks: StateFlow<List<WebhookRule>>
    suspend fun upsertHook(hook: Hook)
    suspend fun removeHook(hookId: String)
    suspend fun upsertWebhook(rule: WebhookRule)
    suspend fun removeWebhook(ruleId: String)

    // ---------- 自动化 ----------
    val schedules: StateFlow<List<Schedule>>
    suspend fun upsertSchedule(schedule: Schedule)
    suspend fun setScheduleEnabled(scheduleId: String, enabled: Boolean)
    suspend fun runScheduleNow(scheduleId: String)
    suspend fun deleteSchedule(scheduleId: String)

    val goals: StateFlow<List<Goal>>
    suspend fun upsertGoal(goal: Goal)
    suspend fun setGoalStatus(goalId: String, status: GoalStatus)

    // ---------- 权限 / 系统 ----------
    val shizuku: StateFlow<ShizukuStatus>
    suspend fun refreshShizuku()
    suspend fun requestShizukuPermission()

    val kernel: StateFlow<KernelInfo>
    suspend fun restartKernel()
    suspend fun kernelLogs(): List<String>

    val phoneCapabilities: StateFlow<List<PhoneCapability>>
    suspend fun requestCapability(capabilityId: String)

    val lspServers: StateFlow<List<LspServer>>
    val inspector: StateFlow<List<InspectorNode>>

    // ---------- 终端（proot Alpine） ----------
    val terminals: StateFlow<List<TerminalSession>>
    suspend fun openTerminal(): String
    suspend fun closeTerminal(terminalId: String)
    suspend fun terminalInput(terminalId: String, input: String)
}

/** 会话内事件。字段名与 dsh 事件保持语义一致，bridge 层负责映射。 */
sealed interface HarnessEvent {
    val sessionId: String

    /** [at]：轮次开始的墙钟时间（dsh 事件的 time，重放时同样准确），用于把旧的文件交付记录按时间归到轮次。 */
    data class TurnStarted(override val sessionId: String, val turnId: String, val at: Long = System.currentTimeMillis()) : HarnessEvent
    data class UserMessage(
        override val sessionId: String,
        val id: String,
        val text: String,
        val attachments: List<Attachment>,
        val steer: Boolean = false,
    ) : HarnessEvent
    data class SystemPrompt(override val sessionId: String, val id: String, val content: String) : HarnessEvent
    data class ContextInjected(override val sessionId: String, val id: String, val label: String, val content: String) : HarnessEvent
    data class ThinkingDelta(override val sessionId: String, val id: String, val delta: String) : HarnessEvent
    data class ThinkingDone(override val sessionId: String, val id: String, val durationMs: Long) : HarnessEvent
    data class AssistantDelta(override val sessionId: String, val id: String, val delta: String) : HarnessEvent
    data class AssistantDone(override val sessionId: String, val id: String) : HarnessEvent
    data class ToolCallStarted(
        override val sessionId: String,
        val id: String,
        val name: String,
        val summary: String,
        val input: String,
        val kind: ToolKind,
        val autoApproved: Boolean = false,
        /** dsh 会话日志里的 toolCallId；用于按需从 JSONL 读取完整输出。 */
        val callId: String? = null,
    ) : HarnessEvent
    data class ToolCallOutput(override val sessionId: String, val id: String, val delta: String) : HarnessEvent
    data class ToolCallFinished(override val sessionId: String, val id: String, val status: ToolStatus, val exitCode: Int? = null) : HarnessEvent
    data class FileDiff(
        override val sessionId: String,
        val id: String,
        val path: String,
        val lines: List<DiffLine>,
    ) : HarnessEvent
    data class DiffResolved(override val sessionId: String, val id: String, val state: DiffState) : HarnessEvent
    data class ApprovalRequested(
        override val sessionId: String,
        val id: String,
        val title: String,
        val detail: String,
        val command: String,
    ) : HarnessEvent
    data class ApprovalResolved(override val sessionId: String, val id: String, val decision: ApprovalDecision) : HarnessEvent
    data class QuestionAsked(
        override val sessionId: String,
        val id: String,
        val header: String,
        val question: String,
        val options: List<QuestionOption>,
        val allowFreeText: Boolean,
    ) : HarnessEvent
    data class QuestionAnswered(override val sessionId: String, val id: String, val answer: String) : HarnessEvent
    data class TodosUpdated(override val sessionId: String, val id: String, val title: String, val items: List<TodoItem>) : HarnessEvent
    data class SubagentsUpdated(
        override val sessionId: String,
        val id: String,
        val title: String,
        val workflow: Boolean,
        val tasks: List<SubTask>,
    ) : HarnessEvent
    data class GoalUpdated(override val sessionId: String, val id: String, val goal: Goal) : HarnessEvent
    data class ScheduleCreated(override val sessionId: String, val id: String, val schedule: Schedule) : HarnessEvent
    data class Notice(override val sessionId: String, val id: String, val text: String) : HarnessEvent
    data class UsageReported(override val sessionId: String, val turnId: String, val usage: Usage) : HarnessEvent
    data class ContextUsageChanged(override val sessionId: String, val usage: ContextUsage) : HarnessEvent
    data class TurnFinished(override val sessionId: String, val turnId: String, val cancelled: Boolean) : HarnessEvent
    /** deliver_file 交付的文件；归入 [DeliveredFile.turnId] 对应的轮次（旧记录按 deliveredAt 归到最近轮次）。 */
    data class FileDelivered(override val sessionId: String, val file: DeliveredFile) : HarnessEvent
}
