package app.hoshino.harness.core

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 消息流中的一条可渲染项。由 [SessionStore] 从 [HarnessEvent] 归约得到。所有字段不可变，Compose 可据此跳过重组。 */
@Immutable
sealed interface ChatItem {
    val id: String

    @Immutable
    data class User(override val id: String, val text: String, val attachments: List<Attachment>, val steer: Boolean) : ChatItem

    /** 思考文本是追加式 [StreamText]：每个 delta O(delta)，相等判断 O(1)。 */
    @Immutable
    data class Thinking(override val id: String, val content: StreamText, val running: Boolean, val startedAt: Long, val durationMs: Long) : ChatItem {
        constructor(id: String, text: String, running: Boolean, startedAt: Long, durationMs: Long) : this(id, StreamText.of(text), running, startedAt, durationMs)
        val text: String get() = content.toString()
    }

    /** 回答文本是追加式 [StreamText]；界面用增量 Markdown 解析直接读 [content]，[markdown] 只在复制/导出时物化。 */
    @Immutable
    data class Assistant(override val id: String, val content: StreamText, val streaming: Boolean) : ChatItem {
        constructor(id: String, markdown: String, streaming: Boolean) : this(id, StreamText.of(markdown), streaming)
        val markdown: String get() = content.toString()
    }

    /**
     * [input] 只保留开头、[output] 只保留尾部（各约 64 KiB，见 [OutputTail]）；[inputLength]/[outputLength] 是真实总长度。
     * 完整内容在 dsh 会话日志里，[callId] 不为空时可经 [HarnessClient.fullToolOutput] 按需读取。
     */
    @Immutable
    data class Tool(
        override val id: String,
        val name: String,
        val summary: String,
        val input: String,
        val output: String,
        val status: ToolStatus,
        val inputLength: Long = input.length.toLong(),
        val outputLength: Long = output.length.toLong(),
        val callId: String? = null,
        /** 无审批模式下自动放行（M2.7）：过程组里显示一行审计。 */
        val autoApproved: Boolean = false,
    ) : ChatItem {
        val outputTruncated: Boolean get() = outputLength > output.length
    }

    @Immutable
    data class Terminal(
        override val id: String,
        val command: String,
        val output: String,
        val status: ToolStatus,
        val exitCode: Int?,
        val autoApproved: Boolean,
        val outputLength: Long = output.length.toLong(),
        val callId: String? = null,
    ) : ChatItem {
        val outputTruncated: Boolean get() = outputLength > output.length
    }

    @Immutable
    data class Diff(override val id: String, val path: String, val lines: List<DiffLine>, val state: DiffState) : ChatItem {
        val added: Int get() = lines.count { it.type == DiffLineType.Add }
        val removed: Int get() = lines.count { it.type == DiffLineType.Remove }
    }
    @Immutable
    data class Subagents(override val id: String, val title: String, val workflow: Boolean, val tasks: List<SubTask>) : ChatItem
    @Immutable
    data class Approval(
        override val id: String,
        val title: String,
        val detail: String,
        val command: String,
        val decision: ApprovalDecision?,
        val oneShot: Boolean = false,
    ) : ChatItem
    @Immutable
    data class Question(
        override val id: String,
        val header: String,
        val question: String,
        val options: List<QuestionOption>,
        val allowFreeText: Boolean,
        val answer: String?,
    ) : ChatItem
    @Immutable
    data class Todos(override val id: String, val title: String, val items: List<TodoItem>) : ChatItem
    @Immutable
    data class GoalCard(override val id: String, val goal: Goal) : ChatItem
    @Immutable
    data class ScheduleCard(override val id: String, val schedule: Schedule) : ChatItem
    @Immutable
    data class Notice(override val id: String, val text: String) : ChatItem
    @Immutable
    data class UsageFooter(override val id: String, val usage: Usage) : ChatItem
}

@Immutable
data class QueuedMessage(val id: String, val text: String, val attachments: List<Attachment>)

/**
 * 一个展示分组（一轮）：从一条用户消息开始到下一条用户消息之前（与原 presentTurns 的分组规则一致）。
 *
 * 用身份相等：已完成的轮次在后续更新里始终是同一个实例，UI 可以按引用缓存它的展示结果，流式更新只替换最后一组。
 */
@Immutable
class TurnItems(val key: String, val items: List<ChatItem>) {
    override fun toString() = "TurnItems($key, ${items.size} items)"
}

/** 一次 dsh 轮次（turn/start）落在哪个展示分组上，以及它的开始时间。 */
@Immutable
data class TurnMark(val turnId: String, val groupKey: String, val startedAt: Long)

@Immutable
data class SessionState(
    val turns: List<TurnItems> = emptyList(),
    val trajectory: List<TrajectoryEntry> = emptyList(),
    val running: Boolean = false,
    val currentTurnId: String? = null,
    val contextUsage: ContextUsage = ContextUsage(0, 128_000, emptyList()),
    val stats: SessionStats = SessionStats(0, 0, 0, 0, 0, 0L, 0),
    val queued: List<QueuedMessage> = emptyList(),
    /** 本会话交付的文件（按交付顺序）。归属轮次由 UI 按 [turnMarks] 计算。 */
    val deliveries: List<DeliveredFile> = emptyList(),
    /** 按开始顺序排列的轮次标记。 */
    val turnMarks: List<TurnMark> = emptyList(),
    internal val pendingTurn: Pair<String, Long>? = null,
    internal val trajectorySeq: Int = 0,
) {
    /** 扁平条目列表（兼容旧调用方：复制会话、长截图、轨迹页）。按需计算并缓存在这个快照上。 */
    val items: List<ChatItem> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        if (turns.size == 1) turns[0].items else turns.flatMap { it.items }
    }

    companion object {
        /** 把一份扁平条目列表按用户消息分组（测试与只读视图使用）。 */
        fun of(items: List<ChatItem>): SessionState {
            val groups = mutableListOf<MutableList<ChatItem>>()
            for (item in items) { if (item is ChatItem.User || groups.isEmpty()) groups.add(mutableListOf()); groups.last().add(item) }
            return SessionState(turns = groups.map { TurnItems(it.first().id, it.toList()) })
        }
    }
}

/**
 * 订阅 [HarnessClient.events]，按会话归约出 [SessionState]。应用级单例（见 [Hoshino]）。
 *
 * 性能要点（M2.7）：
 * - 流式 delta 约每帧（[coalesceMs]）合并提交一次；任何非 delta 事件到来前先冲刷，保证顺序不变。
 * - 文本用 [StreamText] 追加，O(delta)；工具/终端输出只留尾部 [OutputTail.TAIL_LIMIT]。
 * - 状态按轮次分组：一次更新只复制最后一组的条目，其他轮次的 [TurnItems] 保持同一实例。
 * - 轨迹只存摘要。
 * - 只在内存保留最近 [maxRetainedSessions] 个会话（运行中、有排队消息、正在被界面订阅的会话不淘汰）；
 *   被淘汰的会话由 [HarnessClient.releaseSession] 释放，切回时从 dsh 持久化重放恢复。
 */
class SessionStore(
    private val client: HarnessClient,
    private val scope: CoroutineScope,
    private val maxRetainedSessions: Int = 4,
    private val coalesceMs: Long = 32,
    private val maxTrajectoryEntries: Int = 1000,
) {

    private val statesLock = Any()
    /** 访问顺序的 LinkedHashMap = LRU。 */
    private val states = LinkedHashMap<String, MutableStateFlow<SessionState>>(16, 0.75f, true)
    private val evicted = HashSet<String>()

    private val pendingLock = Any()
    private val pending = LinkedHashMap<String, Pair<HarnessEvent, StringBuilder>>()
    private var flushScheduled = false

    /** 归约次数统计（测试与诊断用）。 */
    @Volatile var reduceCount: Long = 0; private set
    @Volatile var publishCount: Long = 0; private set

    init {
        scope.launch { client.events.collect { dispatch(it) } }
    }

    /** 事件入口（测试可直接调用）。 */
    fun dispatch(e: HarnessEvent) {
        when (e) {
            is HarnessEvent.AssistantDelta -> buffer("a|${e.sessionId}|${e.id}", e, e.delta)
            is HarnessEvent.ThinkingDelta -> buffer("t|${e.sessionId}|${e.id}", e, e.delta)
            is HarnessEvent.ToolCallOutput -> buffer("o|${e.sessionId}|${e.id}", e, e.delta)
            else -> synchronized(pendingLock) { flushLocked(); reduce(e) }
        }
    }

    /** 立即提交所有攒着的 delta（测试用；正常路径由定时器提交）。 */
    fun flushNow() = synchronized(pendingLock) { flushLocked() }

    private fun buffer(key: String, e: HarnessEvent, delta: String) = synchronized(pendingLock) {
        val hit = pending[key]
        if (hit == null) {
            // 新条目的第一个 delta 立即提交，首字不等待。
            if (pending.isEmpty() && !flushScheduled) {
                reduce(e)
                flushScheduled = true
                scope.launch { delay(coalesceMs); synchronized(pendingLock) { flushScheduled = false; flushLocked() } }
                return@synchronized
            }
            pending[key] = e to StringBuilder(delta)
            if (!flushScheduled) {
                flushScheduled = true
                scope.launch { delay(coalesceMs); synchronized(pendingLock) { flushScheduled = false; flushLocked() } }
            }
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

    // ------------------------------------------------------------------ 会话保留（LRU）

    private fun flow(sessionId: String): MutableStateFlow<SessionState> {
        var restore = false
        val result = synchronized(statesLock) {
            states[sessionId] ?: MutableStateFlow(SessionState()).also {
                states[sessionId] = it
                restore = evicted.remove(sessionId)
            }
        }
        if (restore) client.retainSession(sessionId)
        trim()
        return result
    }

    /** 当前在内存中的会话 id（最近访问的在后）。 */
    fun retainedSessions(): List<String> = synchronized(statesLock) { states.keys.toList() }

    private fun pinned(flow: MutableStateFlow<SessionState>): Boolean {
        val s = flow.value
        return s.running || s.queued.isNotEmpty() || flow.subscriptionCount.value > 0
    }

    private fun trim() {
        if (!client.sessionRestoreSupported) return
        val released = mutableListOf<String>()
        synchronized(statesLock) {
            if (states.size <= maxRetainedSessions) return@synchronized
            val newest = states.keys.last()
            val it = states.entries.iterator()
            while (states.size > maxRetainedSessions && it.hasNext()) {
                val (id, f) = it.next()
                // 刚被访问的会话（正要被显示或刚收到事件）永不淘汰。
                if (id == newest || pinned(f)) continue
                it.remove(); evicted.add(id); released.add(id)
            }
        }
        for (id in released) client.releaseSession(id)
    }

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
        synchronized(statesLock) { states.remove(sessionId); evicted.remove(sessionId) }
    }

    // ------------------------------------------------------------------ 分组更新工具

    /** 追加一条新条目：用户消息开启新分组，其余进入最后一组。只复制最后一组。 */
    private fun SessionState.append(item: ChatItem): SessionState {
        val newGroup = item is ChatItem.User || turns.isEmpty()
        val nextTurns: List<TurnItems> = if (newGroup) {
            turns + TurnItems(item.id, listOf(item))
        } else {
            val last = turns.last()
            turns.dropLast(1) + TurnItems(last.key, last.items + item)
        }
        val mark = pendingTurn?.let { (turnId, at) -> TurnMark(turnId, nextTurns.last().key, at) }
        return copy(turns = nextTurns, turnMarks = if (mark == null) turnMarks else turnMarks + mark, pendingTurn = null)
    }

    /** 在最近的分组里查找条目（流式条目总在最后一组；找不到再向前找）。 */
    private fun SessionState.find(id: String): Pair<Int, Int>? {
        for (t in turns.indices.reversed()) {
            val items = turns[t].items
            for (i in items.indices.reversed()) if (items[i].id == id) return t to i
        }
        return null
    }

    private inline fun SessionState.replace(id: String, block: (ChatItem) -> ChatItem): SessionState {
        val (t, i) = find(id) ?: return this
        val group = turns[t]
        val old = group.items[i]
        val updated = block(old)
        if (updated === old) return this
        val items = group.items.toMutableList().also { it[i] = updated }
        val nextTurns = turns.toMutableList().also { it[t] = TurnItems(group.key, items) }
        return copy(turns = nextTurns)
    }

    private fun SessionState.traj(source: TrajSource, title: String, payload: CharSequence): SessionState {
        val seq = trajectorySeq + 1
        val entry = TrajectoryEntry(id = "t$seq", source = source, title = title, payload = TrajectorySummary.of(payload), at = System.currentTimeMillis())
        val next = if (trajectory.size >= maxTrajectoryEntries) trajectory.drop(trajectory.size - maxTrajectoryEntries + 1) + entry else trajectory + entry
        return copy(trajectory = next, trajectorySeq = seq)
    }

    // ------------------------------------------------------------------ 归约

    private fun reduce(e: HarnessEvent) {
        reduceCount++
        flow(e.sessionId).update { s -> reduceState(s, e) }
        publishCount++
        if (e is HarnessEvent.TurnFinished) trim()
    }

    private fun reduceState(s: SessionState, e: HarnessEvent): SessionState = when (e) {
        is HarnessEvent.TurnStarted -> s.copy(running = true, currentTurnId = e.turnId, pendingTurn = e.turnId to e.at)
        is HarnessEvent.UserMessage ->
            s.append(ChatItem.User(e.id, e.text, e.attachments, e.steer))
                .traj(TrajSource.User, if (e.steer) "插话 steer" else "用户消息", e.text)
        is HarnessEvent.SystemPrompt -> s.traj(TrajSource.System, "系统提示 · ${e.content.length} 字符", e.content)
        is HarnessEvent.ContextInjected -> s.traj(TrajSource.Context, e.label, e.content)
        is HarnessEvent.ThinkingDelta ->
            if (s.find(e.id) != null) s.replace(e.id) { if (it is ChatItem.Thinking) it.copy(content = it.content.append(e.delta)) else it }
            else s.append(ChatItem.Thinking(e.id, StreamText.of(e.delta), true, System.currentTimeMillis(), 0L))
        is HarnessEvent.ThinkingDone -> {
            val t = s.find(e.id)?.let { (g, i) -> s.turns[g].items[i] } as? ChatItem.Thinking
            s.replace(e.id) { if (it is ChatItem.Thinking) it.copy(running = false, durationMs = e.durationMs) else it }
                .traj(TrajSource.Reasoning, "思考 ${e.durationMs / 1000}s", t?.content ?: "")
        }
        is HarnessEvent.AssistantDelta ->
            if (s.find(e.id) != null) s.replace(e.id) { if (it is ChatItem.Assistant) it.copy(content = it.content.append(e.delta)) else it }
            else s.append(ChatItem.Assistant(e.id, StreamText.of(e.delta), true))
        is HarnessEvent.AssistantDone -> {
            val a = s.find(e.id)?.let { (g, i) -> s.turns[g].items[i] } as? ChatItem.Assistant
            s.replace(e.id) { if (it is ChatItem.Assistant && it.streaming) it.copy(streaming = false) else it }
                .let { if (a != null && a.streaming) it.traj(TrajSource.System, "助手回复", a.content) else it }
        }
        is HarnessEvent.ToolCallStarted -> {
            val item: ChatItem = if (e.kind == ToolKind.Bash) {
                ChatItem.Terminal(e.id, e.summary, "", ToolStatus.Running, null, e.autoApproved, callId = e.callId)
            } else {
                ChatItem.Tool(e.id, e.name, e.summary, OutputTail.head(e.input), "", ToolStatus.Running, inputLength = e.input.length.toLong(), callId = e.callId, autoApproved = e.autoApproved)
            }
            s.append(item).copy(stats = s.stats.copy(toolCalls = s.stats.toolCalls + 1))
                .traj(TrajSource.ToolCall, e.name, e.input)
        }
        is HarnessEvent.ToolCallOutput -> s.replace(e.id) {
            when (it) {
                is ChatItem.Tool -> it.copy(output = OutputTail.append(it.output, e.delta), outputLength = it.outputLength + e.delta.length)
                is ChatItem.Terminal -> it.copy(output = OutputTail.append(it.output, e.delta), outputLength = it.outputLength + e.delta.length)
                else -> it
            }
        }
        is HarnessEvent.ToolCallFinished -> {
            var out: String = ""
            var total = 0L
            s.replace(e.id) {
                when (it) {
                    is ChatItem.Tool -> it.copy(status = e.status).also { t -> out = t.output; total = t.outputLength }
                    is ChatItem.Terminal -> it.copy(status = e.status, exitCode = e.exitCode).also { t -> out = t.output; total = t.outputLength }
                    else -> it
                }
            }.traj(TrajSource.ToolResult, "结果 · ${e.status.label}" + if (total > out.length) " · 共 $total 字符（内存保留末尾）" else "", out)
        }
        is HarnessEvent.FileDiff ->
            s.append(ChatItem.Diff(e.id, e.path, e.lines, DiffState.Pending))
                .traj(TrajSource.ToolResult, "修改 ${e.path}", "${e.lines.count { it.type == DiffLineType.Add }} 行新增 · ${e.lines.count { it.type == DiffLineType.Remove }} 行删除")
        is HarnessEvent.DiffResolved ->
            s.replace(e.id) { if (it is ChatItem.Diff) it.copy(state = e.state) else it }
        is HarnessEvent.ApprovalRequested ->
            s.append(ChatItem.Approval(e.id, e.title, e.detail, e.command, null, e.oneShot))
                .traj(TrajSource.System, "请求审批", e.command)
        is HarnessEvent.ApprovalResolved ->
            s.replace(e.id) { if (it is ChatItem.Approval) it.copy(decision = e.decision) else it }
                .traj(TrajSource.User, "审批 · ${e.decision.label}", e.id)
        is HarnessEvent.QuestionAsked ->
            s.append(ChatItem.Question(e.id, e.header, e.question, e.options, e.allowFreeText, null))
                .traj(TrajSource.ToolCall, "ask_user_question", e.question)
        is HarnessEvent.QuestionAnswered ->
            s.replace(e.id) { if (it is ChatItem.Question) it.copy(answer = e.answer) else it }
                .traj(TrajSource.User, "回答", e.answer)
        is HarnessEvent.TodosUpdated ->
            if (s.find(e.id) != null) s.replace(e.id) { if (it is ChatItem.Todos) it.copy(title = e.title, items = e.items) else it }
            else s.append(ChatItem.Todos(e.id, e.title, e.items))
        is HarnessEvent.SubagentsUpdated ->
            if (s.find(e.id) != null) s.replace(e.id) { if (it is ChatItem.Subagents) it.copy(tasks = e.tasks) else it }
            else s.append(ChatItem.Subagents(e.id, e.title, e.workflow, e.tasks))
                .traj(TrajSource.Subagent, e.title, e.tasks.joinToString("\n") { "· ${it.agent}: ${it.title}" })
        is HarnessEvent.GoalUpdated ->
            if (s.find(e.id) != null) s.replace(e.id) { if (it is ChatItem.GoalCard) it.copy(goal = e.goal) else it }
            else s.append(ChatItem.GoalCard(e.id, e.goal))
        is HarnessEvent.ScheduleCreated ->
            s.append(ChatItem.ScheduleCard(e.id, e.schedule))
                .traj(TrajSource.ToolCall, "schedule_create", e.schedule.title)
        is HarnessEvent.Notice -> if (s.find(e.id) != null) s else s.append(ChatItem.Notice(e.id, e.text))
        is HarnessEvent.UsageReported -> s.append(ChatItem.UsageFooter("usage-${e.turnId}", e.usage)).copy(
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
        is HarnessEvent.FileDelivered -> {
            val i = s.deliveries.indexOfFirst { it.id == e.file.id }
            s.copy(deliveries = if (i < 0) s.deliveries + e.file else s.deliveries.toMutableList().also { it[i] = e.file })
        }
        is HarnessEvent.TurnFinished -> {
            // 运行中被中断：把仍在运行的条目收尾（只改动含运行条目的分组，其余分组保持同一实例）。
            val turns = if (!e.cancelled) s.turns else s.turns.map { group ->
                if (group.items.none { it.isRunning() }) group else TurnItems(group.key, group.items.map {
                    when (it) {
                        is ChatItem.Thinking -> if (it.running) it.copy(running = false) else it
                        is ChatItem.Assistant -> if (it.streaming) it.copy(streaming = false) else it
                        is ChatItem.Tool -> if (it.status == ToolStatus.Running) it.copy(status = ToolStatus.Failed) else it
                        is ChatItem.Terminal -> if (it.status == ToolStatus.Running) it.copy(status = ToolStatus.Failed) else it
                        else -> it
                    }
                })
            }
            val base = s.copy(turns = turns, running = false, currentTurnId = null, pendingTurn = null)
            if (e.cancelled) base.append(ChatItem.Notice("stop-${e.turnId}", "已停止本轮运行")) else base
        }
    }

    private fun ChatItem.isRunning() = when (this) {
        is ChatItem.Thinking -> running
        is ChatItem.Assistant -> streaming
        is ChatItem.Tool -> status == ToolStatus.Running
        is ChatItem.Terminal -> status == ToolStatus.Running
        else -> false
    }
}
