package app.hoshino.harness.core

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 消息流中的一条可渲染项。由 [SessionStore] 从 [HarnessEvent] 归约得到。 */
sealed interface ChatItem {
    val id: String

    data class User(override val id: String, val text: String, val attachments: List<Attachment>, val steer: Boolean) : ChatItem
    data class Thinking(override val id: String, val text: String, val running: Boolean, val startedAt: Long, val durationMs: Long) : ChatItem
    data class Assistant(override val id: String, val markdown: String, val streaming: Boolean) : ChatItem
    data class Tool(
        override val id: String,
        val name: String,
        val summary: String,
        val input: String,
        val output: String,
        val status: ToolStatus,
    ) : ChatItem
    data class Terminal(
        override val id: String,
        val command: String,
        val output: String,
        val status: ToolStatus,
        val exitCode: Int?,
        val autoApproved: Boolean,
    ) : ChatItem
    data class Diff(override val id: String, val path: String, val lines: List<DiffLine>, val state: DiffState) : ChatItem {
        val added: Int get() = lines.count { it.type == DiffLineType.Add }
        val removed: Int get() = lines.count { it.type == DiffLineType.Remove }
    }
    data class Subagents(override val id: String, val title: String, val workflow: Boolean, val tasks: List<SubTask>) : ChatItem
    data class Approval(
        override val id: String,
        val title: String,
        val detail: String,
        val command: String,
        val decision: ApprovalDecision?,
    ) : ChatItem
    data class Question(
        override val id: String,
        val header: String,
        val question: String,
        val options: List<QuestionOption>,
        val allowFreeText: Boolean,
        val answer: String?,
    ) : ChatItem
    data class Todos(override val id: String, val title: String, val items: List<TodoItem>) : ChatItem
    data class GoalCard(override val id: String, val goal: Goal) : ChatItem
    data class ScheduleCard(override val id: String, val schedule: Schedule) : ChatItem
    data class Notice(override val id: String, val text: String) : ChatItem
    data class UsageFooter(override val id: String, val usage: Usage) : ChatItem
}

data class QueuedMessage(val id: String, val text: String, val attachments: List<Attachment>)

data class SessionState(
    val items: List<ChatItem> = emptyList(),
    val trajectory: List<TrajectoryEntry> = emptyList(),
    val running: Boolean = false,
    val currentTurnId: String? = null,
    val contextUsage: ContextUsage = ContextUsage(0, 128_000, emptyList()),
    val stats: SessionStats = SessionStats(0, 0, 0, 0, 0, 0L, 0),
    val queued: List<QueuedMessage> = emptyList(),
)

/**
 * 订阅 [HarnessClient.events]，按会话归约出 [SessionState]。应用级单例（见 [Hoshino]）。
 * 接入真实 dsh 时无需改动本类：只要 bridge 发出同样的事件即可。
 */
class SessionStore(private val client: HarnessClient, private val scope: CoroutineScope) {

    private val states = ConcurrentHashMap<String, MutableStateFlow<SessionState>>()

    // 流式增量合并：模型每个 token 都发一次 delta，逐个归约会让整条消息（含 Markdown 解析）每秒重组上百次。
    // 这里把同一条目的连续 delta 攒起来，约每帧（32ms）提交一次；任何非 delta 事件到来前先冲刷，保证顺序不变。
    private val pendingLock = Any()
    private val pending = LinkedHashMap<String, Pair<HarnessEvent, StringBuilder>>()

    init {
        scope.launch {
            client.events.collect { e ->
                when (e) {
                    is HarnessEvent.AssistantDelta -> buffer("a|${e.sessionId}|${e.id}", e, e.delta)
                    is HarnessEvent.ThinkingDelta -> buffer("t|${e.sessionId}|${e.id}", e, e.delta)
                    is HarnessEvent.ToolCallOutput -> buffer("o|${e.sessionId}|${e.id}", e, e.delta)
                    else -> synchronized(pendingLock) { flushLocked(); reduce(e) }
                }
            }
        }
    }

    private var flushScheduled = false

    private fun buffer(key: String, e: HarnessEvent, delta: String) = synchronized(pendingLock) {
        val hit = pending[key]
        if (hit == null) {
            // 新条目的第一个 delta 立即提交，首字不等待。
            if (pending.isEmpty() && !flushScheduled) {
                reduce(e)
                flushScheduled = true
                scope.launch { kotlinx.coroutines.delay(32); synchronized(pendingLock) { flushScheduled = false; flushLocked() } }
                return@synchronized
            }
            pending[key] = e to StringBuilder(delta)
        } else hit.second.append(delta)
    }

    private fun flushLocked() {
        if (pending.isEmpty()) return
        val batch = pending.values.toList(); pending.clear()
        for ((e, text) in batch) reduce(
            when (e) {
                is HarnessEvent.AssistantDelta -> e.copy(delta = text.toString())
                is HarnessEvent.ThinkingDelta -> e.copy(delta = text.toString())
                is HarnessEvent.ToolCallOutput -> e.copy(delta = text.toString())
                else -> e
            },
        )
    }

    private fun flow(sessionId: String): MutableStateFlow<SessionState> =
        states.computeIfAbsent(sessionId) { MutableStateFlow(SessionState()) }

    fun state(sessionId: String): StateFlow<SessionState> = flow(sessionId).asStateFlow()

    fun enqueue(sessionId: String, message: QueuedMessage) =
        flow(sessionId).update { it.copy(queued = it.queued + message) }

    fun removeQueued(sessionId: String, messageId: String) =
        flow(sessionId).update { s -> s.copy(queued = s.queued.filterNot { it.id == messageId }) }

    fun popQueued(sessionId: String): QueuedMessage? {
        var popped: QueuedMessage? = null
        flow(sessionId).update { s ->
            popped = s.queued.firstOrNull()
            if (popped == null) s else s.copy(queued = s.queued.drop(1))
        }
        return popped
    }

    fun clear(sessionId: String) {
        states.remove(sessionId)
    }

    private inline fun <reified T : ChatItem> List<ChatItem>.replace(id: String, block: (T) -> ChatItem): List<ChatItem> =
        map { if (it.id == id && it is T) block(it) else it }

    private fun SessionState.traj(source: TrajSource, title: String, payload: String) =
        copy(
            trajectory = trajectory + TrajectoryEntry(
                id = "t${trajectory.size + 1}",
                source = source,
                title = title,
                payload = payload,
                at = System.currentTimeMillis(),
            ),
        )

    private fun reduce(e: HarnessEvent) {
        flow(e.sessionId).update { s ->
            when (e) {
                is HarnessEvent.TurnStarted -> s.copy(running = true, currentTurnId = e.turnId)
                is HarnessEvent.UserMessage ->
                    s.copy(items = s.items + ChatItem.User(e.id, e.text, e.attachments, e.steer))
                        .traj(TrajSource.User, if (e.steer) "插话 steer" else "用户消息", e.text)
                is HarnessEvent.SystemPrompt -> s.traj(TrajSource.System, "系统提示", e.content)
                is HarnessEvent.ContextInjected -> s.traj(TrajSource.Context, e.label, e.content)
                is HarnessEvent.ThinkingDelta -> {
                    val exists = s.items.any { it.id == e.id }
                    val items = if (exists) {
                        s.items.replace<ChatItem.Thinking>(e.id) { it.copy(text = it.text + e.delta) }
                    } else {
                        s.items + ChatItem.Thinking(e.id, e.delta, true, System.currentTimeMillis(), 0L)
                    }
                    s.copy(items = items)
                }
                is HarnessEvent.ThinkingDone -> {
                    val t = s.items.firstOrNull { it.id == e.id } as? ChatItem.Thinking
                    s.copy(items = s.items.replace<ChatItem.Thinking>(e.id) { it.copy(running = false, durationMs = e.durationMs) })
                        .traj(TrajSource.Reasoning, "思考 ${e.durationMs / 1000}s", t?.text ?: "")
                }
                is HarnessEvent.AssistantDelta -> {
                    val exists = s.items.any { it.id == e.id }
                    val items = if (exists) {
                        s.items.replace<ChatItem.Assistant>(e.id) { it.copy(markdown = it.markdown + e.delta) }
                    } else {
                        s.items + ChatItem.Assistant(e.id, e.delta, true)
                    }
                    s.copy(items = items)
                }
                is HarnessEvent.AssistantDone -> {
                    val a = s.items.firstOrNull { it.id == e.id } as? ChatItem.Assistant
                    s.copy(items = s.items.replace<ChatItem.Assistant>(e.id) { it.copy(streaming = false) })
                        .traj(TrajSource.System, "助手回复", a?.markdown ?: "")
                }
                is HarnessEvent.ToolCallStarted -> {
                    val item: ChatItem = if (e.kind == ToolKind.Bash) {
                        ChatItem.Terminal(e.id, e.summary, "", ToolStatus.Running, null, e.autoApproved)
                    } else {
                        ChatItem.Tool(e.id, e.name, e.summary, e.input, "", ToolStatus.Running)
                    }
                    s.copy(items = s.items + item, stats = s.stats.copy(toolCalls = s.stats.toolCalls + 1))
                        .traj(TrajSource.ToolCall, e.name, e.input)
                }
                is HarnessEvent.ToolCallOutput -> s.copy(
                    items = s.items.map {
                        when {
                            it.id != e.id -> it
                            it is ChatItem.Tool -> it.copy(output = it.output + e.delta)
                            it is ChatItem.Terminal -> it.copy(output = it.output + e.delta)
                            else -> it
                        }
                    },
                )
                is HarnessEvent.ToolCallFinished -> {
                    var out = ""
                    val items = s.items.map {
                        when {
                            it.id != e.id -> it
                            it is ChatItem.Tool -> it.copy(status = e.status).also { t -> out = t.output }
                            it is ChatItem.Terminal -> it.copy(status = e.status, exitCode = e.exitCode).also { t -> out = t.output }
                            else -> it
                        }
                    }
                    s.copy(items = items).traj(TrajSource.ToolResult, "结果 · ${e.status.label}", out)
                }
                is HarnessEvent.FileDiff ->
                    s.copy(items = s.items + ChatItem.Diff(e.id, e.path, e.lines, DiffState.Pending))
                        .traj(TrajSource.ToolResult, "修改 ${e.path}", e.lines.joinToString("\n") { it.text })
                is HarnessEvent.DiffResolved ->
                    s.copy(items = s.items.replace<ChatItem.Diff>(e.id) { it.copy(state = e.state) })
                is HarnessEvent.ApprovalRequested ->
                    s.copy(items = s.items + ChatItem.Approval(e.id, e.title, e.detail, e.command, null))
                        .traj(TrajSource.System, "请求审批", e.command)
                is HarnessEvent.ApprovalResolved ->
                    s.copy(items = s.items.replace<ChatItem.Approval>(e.id) { it.copy(decision = e.decision) })
                        .traj(TrajSource.User, "审批 · ${e.decision.label}", e.id)
                is HarnessEvent.QuestionAsked ->
                    s.copy(items = s.items + ChatItem.Question(e.id, e.header, e.question, e.options, e.allowFreeText, null))
                        .traj(TrajSource.ToolCall, "ask_user_question", e.question)
                is HarnessEvent.QuestionAnswered ->
                    s.copy(items = s.items.replace<ChatItem.Question>(e.id) { it.copy(answer = e.answer) })
                        .traj(TrajSource.User, "回答", e.answer)
                is HarnessEvent.TodosUpdated -> {
                    val exists = s.items.any { it.id == e.id }
                    val items = if (exists) {
                        s.items.replace<ChatItem.Todos>(e.id) { it.copy(title = e.title, items = e.items) }
                    } else {
                        s.items + ChatItem.Todos(e.id, e.title, e.items)
                    }
                    s.copy(items = items)
                }
                is HarnessEvent.SubagentsUpdated -> {
                    val exists = s.items.any { it.id == e.id }
                    val items = if (exists) {
                        s.items.replace<ChatItem.Subagents>(e.id) { it.copy(tasks = e.tasks) }
                    } else {
                        s.items + ChatItem.Subagents(e.id, e.title, e.workflow, e.tasks)
                    }
                    val st = if (exists) s.copy(items = items) else s.copy(items = items).traj(
                        TrajSource.Subagent, e.title, e.tasks.joinToString("\n") { "· ${it.agent}: ${it.title}" },
                    )
                    st
                }
                is HarnessEvent.GoalUpdated -> {
                    val exists = s.items.any { it.id == e.id }
                    val items = if (exists) {
                        s.items.replace<ChatItem.GoalCard>(e.id) { it.copy(goal = e.goal) }
                    } else {
                        s.items + ChatItem.GoalCard(e.id, e.goal)
                    }
                    s.copy(items = items)
                }
                is HarnessEvent.ScheduleCreated ->
                    s.copy(items = s.items + ChatItem.ScheduleCard(e.id, e.schedule))
                        .traj(TrajSource.ToolCall, "schedule_create", e.schedule.title)
                is HarnessEvent.Notice -> s.copy(items = s.items + ChatItem.Notice(e.id, e.text))
                is HarnessEvent.UsageReported -> s.copy(
                    items = s.items + ChatItem.UsageFooter("usage-${e.turnId}", e.usage),
                    stats = s.stats.copy(
                        turns = s.stats.turns + 1,
                        inputTokens = s.stats.inputTokens + e.usage.inputTokens,
                        outputTokens = s.stats.outputTokens + e.usage.outputTokens,
                        cacheReadTokens = s.stats.cacheReadTokens + e.usage.cacheReadTokens,
                        reasoningTokens = s.stats.reasoningTokens + e.usage.reasoningTokens,
                        totalDurationMs = s.stats.totalDurationMs + e.usage.durationMs,
                    ),
                )
                is HarnessEvent.ContextUsageChanged -> s.copy(contextUsage = e.usage)
                is HarnessEvent.TurnFinished -> {
                    // 运行中被中断：把仍在运行的条目收尾
                    val items = if (!e.cancelled) s.items else s.items.map {
                        when (it) {
                            is ChatItem.Thinking -> if (it.running) it.copy(running = false) else it
                            is ChatItem.Assistant -> if (it.streaming) it.copy(streaming = false) else it
                            is ChatItem.Tool -> if (it.status == ToolStatus.Running) it.copy(status = ToolStatus.Failed) else it
                            is ChatItem.Terminal -> if (it.status == ToolStatus.Running) it.copy(status = ToolStatus.Failed) else it
                            else -> it
                        }
                    }
                    val withNotice = if (e.cancelled) items + ChatItem.Notice("stop-${e.turnId}", "已停止本轮运行") else items
                    s.copy(items = withNotice, running = false, currentTurnId = null)
                }
            }
        }
    }
}
