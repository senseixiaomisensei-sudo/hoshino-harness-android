// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
// (SteerState / steerLabel: app/task/TaskCenterState.kt, app/ui/ChatViewModel.kt; re-implemented over the dsh inbox events.)
// (Withdrawn / withdraw-or-take race: 0.1.27 · 9bd29de; here the race is arbitrated by dsh `session/updateQueue`, not a local lock.)
package app.hoshino.harness.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 插话的去向。插话走 dsh 原生的 `session/prompt mode=steer`：消息进 inbox 的 next-step，
 * 在下一次模型请求前被认领（变成 user/message）。界面状态完全从持久化事件推出，重放后一致。
 */
enum class SteerState(val label: String) {
    /** 内核收下了（进了 next-step），下一步模型请求前处理。 */
    Received("已收到，下一步处理"),
    /** 已交给当前任务的模型。 */
    Joined("已加入当前任务"),
    /** 用户按了停止，这句没来得及处理，随任务一起作废。 */
    Cancelled("已停止，这句没有处理"),
    /** 内核没收下（当前轮已结束等），改为排在下一条发送。 */
    Deferred("当前任务已结束，排在下一条发送"),
    /** 用户在模型取走前撤回了（`session/updateQueue remove`）；对话页和胶囊都不再显示这句。 */
    Withdrawn("已撤回");

    /** 还能撤回 / 编辑：内核收下了、模型还没取走。 */
    val withdrawable: Boolean get() = this == Received
}

/** 撤回一句插话的结果。 */
sealed interface SteerWithdrawal {
    /** 内核移除了它，模型不会看到。 */
    data object Withdrawn : SteerWithdrawal
    /** 模型已经取走（或它已不在待处理队列里），撤回不了。 */
    data object AlreadyTaken : SteerWithdrawal
    /** 这句不是本机发出的、仍在等待的插话（或当前内核不支持撤回）。 */
    data object NotWithdrawable : SteerWithdrawal
    /** 请求没送到内核等其他失败；插话保持原状。 */
    data class Failed(val reason: String) : SteerWithdrawal
}

/** inbox 里的一条消息（从 `agent/inbox/spliced` 的 inserted 里取出的必要字段）。 */
data class InboxMessage(
    val id: String,
    val sourceKind: String,
    val rpcId: String? = null,
    val text: String = "",
    /** 停止后交代：给用户看的文字（模型看到的是 text）。 */
    val display: String? = null,
    val attachments: List<Attachment> = emptyList(),
)

/**
 * dsh `ReactLoopInbox` 的镜像：按 `agent/inbox/spliced` 事件（target/start/removedCount/inserted/outcome）
 * 复现两条待处理列表，从而知道哪条插话被认领、哪条被取消。纯逻辑，可单测。
 */
class InboxMirror {
    private val lists = mapOf("next-turn" to mutableListOf<InboxMessage>(), "next-step" to mutableListOf())

    fun pending(target: String): List<InboxMessage> = lists[target].orEmpty().toList()

    /** 应用一次 splice，返回被移出的消息。越界（缓存里只有尾部事件时）会被夹住，不抛异常。 */
    fun splice(target: String, start: Int, removedCount: Int, inserted: List<InboxMessage>): List<InboxMessage> {
        val list = lists[target] ?: return emptyList()
        val from = start.coerceIn(0, list.size)
        val count = removedCount.coerceIn(0, list.size - from)
        val removed = list.subList(from, from + count).toList()
        repeat(count) { list.removeAt(from) }
        list.addAll(from, inserted)
        return removed
    }

    fun clear() = lists.values.forEach { it.clear() }

    /** 用内核快照里的 `inbox` 投影（某个 seq 时刻的完整待处理列表）整体替换镜像。 */
    fun reset(nextTurn: List<InboxMessage>, nextStep: List<InboxMessage>) {
        lists.getValue("next-turn").apply { clear(); addAll(nextTurn) }
        lists.getValue("next-step").apply { clear(); addAll(nextStep) }
    }
}

/** 一次 inbox 变化对界面意味着什么。 */
sealed interface InboxEffect {
    /** 一句插话进了 next-step：立即画出用户气泡（id = [steerItemId]）。 */
    data class SteerAdded(val message: InboxMessage) : InboxEffect
    /** 停止后交代进了 inbox：画成这一轮结尾的回答。 */
    data class StopNoteAdded(val message: InboxMessage) : InboxEffect
    /** 插话被取消（停止时作废）。 */
    data class SteerCancelled(val messageId: String) : InboxEffect
    /** 用户撤回的插话被内核移除：界面去掉这条气泡。 */
    data class SteerWithdrawn(val messageId: String) : InboxEffect
}

/**
 * 每个会话一份：根据持久化事件推出插话气泡与状态。
 * - next-step 插入、来源是用户 → 插话气泡（状态「已收到」）。
 * - 之后同 id 的 user/message 出现 → 不再重复画气泡，状态改为「已加入当前任务」。
 * - 被带 outcome=canceled 的 splice 移出 → 「已停止，这句没有处理」；若是用户要撤回的那句 → 「已撤回」。
 *
 * 撤回与模型取走的竞态由 dsh 裁决：两者都是对 inbox 的一次 splice，内核串行执行，取走在先则 `updateQueue`
 * 返回 `session/queue-item-not-found`。[withdrawn] 是此前已撤回的消息 id（持久化，重放事件后状态一致）。
 */
class SteerTracker(withdrawn: Set<String> = emptySet()) {
    private val mirror = InboxMirror()
    /** 用户要求撤回（或已撤回）的消息 id：它们被 canceled splice 移出时记为「已撤回」而不是「已停止」。 */
    private val withdrawing = HashSet<String>(withdrawn)
    private val steerIds = LinkedHashSet<String>()
    private val noteIds = HashSet<String>()
    val states = LinkedHashMap<String, SteerState>()

    @Synchronized
    fun onSplice(target: String, start: Int, removedCount: Int, inserted: List<InboxMessage>, outcome: String?): List<InboxEffect> {
        val removed = mirror.splice(target, start, removedCount, inserted)
        val effects = mutableListOf<InboxEffect>()
        if (outcome == "canceled") for (m in removed) if (m.id in steerIds && states[m.id] != SteerState.Joined) {
            if (m.id in withdrawing) { states[m.id] = SteerState.Withdrawn; effects += InboxEffect.SteerWithdrawn(m.id) }
            else { states[m.id] = SteerState.Cancelled; effects += InboxEffect.SteerCancelled(m.id) }
        }
        for (m in inserted) when {
            m.sourceKind == STOP_NOTE_SOURCE -> if (noteIds.add(m.id)) effects += InboxEffect.StopNoteAdded(m)
            target == "next-step" && m.sourceKind == "user" && steerIds.add(m.id) -> {
                states.putIfAbsent(m.id, SteerState.Received); effects += InboxEffect.SteerAdded(m)
            }
        }
        return effects
    }

    /**
     * 用会话快照的 `inbox` 投影校正镜像。快照里的事件只是最近一页，更早的 splice 看不到，
     * 投影才是这一刻 inbox 的真实内容。返回窗口里没见过、但仍在等待的插话/停止交代，界面要补画。
     */
    @Synchronized
    fun seed(nextTurn: List<InboxMessage>, nextStep: List<InboxMessage>): List<InboxEffect> {
        mirror.reset(nextTurn, nextStep)
        val effects = mutableListOf<InboxEffect>()
        for (m in nextStep) when {
            m.sourceKind == STOP_NOTE_SOURCE -> if (noteIds.add(m.id)) effects += InboxEffect.StopNoteAdded(m)
            m.sourceKind == "user" && steerIds.add(m.id) -> { states.putIfAbsent(m.id, SteerState.Received); effects += InboxEffect.SteerAdded(m) }
        }
        return effects
    }

    /** 收到一条 user/message。返回 true 表示它是已画过的插话：不要再画，只把状态改为已加入。 */
    @Synchronized
    fun onUserMessage(messageId: String): Boolean {
        if (messageId !in steerIds) return false
        states[messageId] = SteerState.Joined
        withdrawing.remove(messageId)
        return true
    }

    /** 这句插话现在能否撤回：本机看到它进了 next-step、还在待处理列表里、没被取走/作废。 */
    @Synchronized
    fun canWithdraw(messageId: String): Boolean =
        messageId in steerIds && states[messageId]?.withdrawable == true && mirror.pending("next-step").any { it.id == messageId }

    /**
     * 准备撤回：记下意图（之后的 canceled splice 记为「已撤回」），再由调用方发 `session/updateQueue remove`。
     * 返回 false 表示这句已不可撤回（已加入/已作废/已撤回），不要发请求。
     */
    @Synchronized
    fun beginWithdraw(messageId: String): Boolean {
        if (!canWithdraw(messageId)) return false
        withdrawing += messageId
        return true
    }

    /** 内核拒绝了撤回（多半已被模型取走）：撤销意图。已经看到移除事件的不受影响。 */
    @Synchronized
    fun withdrawFailed(messageId: String) {
        if (states[messageId] != SteerState.Withdrawn) withdrawing.remove(messageId)
    }

    /** 已撤回的消息 id（持久化用）。 */
    @Synchronized
    fun withdrawnIds(): Set<String> = states.filterValues { it == SteerState.Withdrawn }.keys.toSet()

    /**
     * 停止交代被下一轮认领后成了 user/message（来源仍是 [STOP_NOTE_SOURCE]）。
     * 如果它进 inbox 的那条 splice 不在已加载的事件里，就在这里补画一次。
     */
    fun onNoteMessage(message: InboxMessage): InboxEffect? =
        if (message.sourceKind == STOP_NOTE_SOURCE && noteIds.add(message.id)) InboxEffect.StopNoteAdded(message) else null

    /** 还没被认领的插话（停止时要作废的那些）。 */
    @Synchronized
    fun unconsumed(): List<String> = mirror.pending("next-step").filter { it.id in steerIds }.map { it.id }

    companion object {
        const val STOP_NOTE_SOURCE = "hoshino-stop-note"
        fun steerItemId(messageId: String) = "steer:$messageId"
        fun stopNoteItemId(messageId: String) = "stop-note:$messageId"
        fun messageIdOf(itemId: String): String? = itemId.takeIf { it.startsWith("steer:") }?.removePrefix("steer:")
    }
}

/** 界面读的全局插话状态（按聊天条目 id），以及正在写「停止后交代」的会话。 */
object Steering {
    private val _states = MutableStateFlow<Map<String, SteerState>>(emptyMap())
    val states: StateFlow<Map<String, SteerState>> = _states.asStateFlow()
    private val _summarizing = MutableStateFlow<Set<String>>(emptySet())
    val summarizing: StateFlow<Set<String>> = _summarizing.asStateFlow()

    fun set(itemId: String, state: SteerState) = _states.update { it + (itemId to state) }
    fun summarizing(sessionId: String, active: Boolean) = _summarizing.update { if (active) it + sessionId else it - sessionId }
    fun label(itemId: String): String? = _states.value[itemId]?.label
}
