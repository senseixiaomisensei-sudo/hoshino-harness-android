package app.hoshino.harness.ui.chat

import androidx.compose.runtime.Immutable
import app.hoshino.harness.core.ApprovalDecision
import app.hoshino.harness.core.ChatItem
import app.hoshino.harness.core.DeliveredFile
import app.hoshino.harness.core.DiffState
import app.hoshino.harness.core.SessionState
import app.hoshino.harness.core.TurnItems
import app.hoshino.harness.core.TurnMark
import java.util.IdentityHashMap

/**
 * 一轮的展示结构：用户消息 → 过程组（思考/工具/中间回复/已处理审批）→ 待处理动作卡 → 最终回答 → 交付文件 → 用量。
 * [rows] 是这一轮在消息列表里的行（显示顺序），每行一个 LazyColumn item，带稳定 key 与 contentType。
 */
@Immutable
data class TurnPresentation(
    val id: String,
    val user: ChatItem.User?,
    val process: List<ChatItem>,
    val finalItems: List<ChatItem>,
    val actions: List<ChatItem>,
    val files: List<DeliveredFile> = emptyList(),
    val rows: List<ChatRow> = emptyList(),
)

/** 消息列表的一行。都是不可变的：已完成轮次的行在后续更新里是同一个实例，Compose 直接跳过。 */
@Immutable
sealed interface ChatRow {
    val key: String
    val contentType: String

    @Immutable
    data class UserRow(val turnKey: String, val item: ChatItem.User) : ChatRow {
        override val key get() = "u:${item.id}"
        override val contentType get() = "user"
    }

    @Immutable
    data class ProcessRow(val turnKey: String, val items: List<ChatItem>, val waiting: Boolean) : ChatRow {
        override val key get() = "p:$turnKey"
        override val contentType get() = "process"
    }

    @Immutable
    data class ItemRow(val turnKey: String, val item: ChatItem) : ChatRow {
        override val key get() = "i:${item.id}"
        override val contentType get() = when (item) {
            is ChatItem.Assistant -> "assistant"
            is ChatItem.Approval -> "approval"
            is ChatItem.Question -> "question"
            is ChatItem.Diff -> "diff"
            is ChatItem.UsageFooter -> "usage"
            is ChatItem.Notice -> "notice"
            else -> "item"
        }
    }

    @Immutable
    data class FilesRow(val turnKey: String, val files: List<DeliveredFile>) : ChatRow {
        override val key get() = "f:$turnKey"
        override val contentType get() = "files"
    }
}

/**
 * 把 [SessionState] 变成按轮次的展示结构，并按 [TurnItems] 身份缓存：
 * 已完成轮次的 [TurnItems] 在流式更新里始终是同一个实例，因此只有当前轮会被重新计算。
 * 纯 Kotlin，无 Compose 依赖，可在 JVM 单测里断言重算次数。
 */
class TurnPresenter {
    private class Entry(val collapse: Boolean, val files: List<DeliveredFile>, val presentation: TurnPresentation)

    private var cache = IdentityHashMap<TurnItems, Entry>()
    private var lastDeliveries: List<DeliveredFile>? = null
    private var lastMarks: List<TurnMark>? = null
    private var lastTurnCount = -1
    private var lastTurnKey: String? = null
    private var placement: Map<String, List<DeliveredFile>> = emptyMap()

    /** 计算过的轮次数（测试/诊断用）。 */
    var turnsComputed: Long = 0; private set
    var placementsComputed: Long = 0; private set

    fun present(state: SessionState, collapseApprovals: Boolean): List<TurnPresentation> {
        val turns = state.turns
        if (state.deliveries !== lastDeliveries || state.turnMarks !== lastMarks || turns.size != lastTurnCount || turns.lastOrNull()?.key != lastTurnKey) {
            placement = placeDeliveries(state.deliveries, state.turnMarks, turns)
            lastDeliveries = state.deliveries; lastMarks = state.turnMarks; lastTurnCount = turns.size; lastTurnKey = turns.lastOrNull()?.key
            placementsComputed++
        }
        // 当前轮每次更新都是新的 TurnItems 实例：按分组 key 找到它上一版的展示，复用没变的行实例。
        val previousByKey = HashMap<String, TurnPresentation>(4)
        turns.lastOrNull()?.let { last -> if (cache[last] == null) cache.entries.firstOrNull { it.key.key == last.key }?.let { previousByKey[last.key] = it.value.presentation } }
        val next = IdentityHashMap<TurnItems, Entry>(turns.size * 2)
        val out = ArrayList<TurnPresentation>(turns.size)
        for (group in turns) {
            val files = placement[group.key].orEmpty()
            val hit = cache[group]
            val entry = if (hit != null && hit.collapse == collapseApprovals && hit.files == files) hit
            else Entry(collapseApprovals, files, reuseRows(previousByKey[group.key], presentTurn(group, files, collapseApprovals)).also { turnsComputed++ })
            next[group] = entry
            out.add(entry.presentation)
        }
        cache = next
        return out
    }

    /** 新旧展示中 key 相同且内容相等的行，沿用旧实例（Compose 用引用比较即可判定未变化）。 */
    private fun reuseRows(previous: TurnPresentation?, next: TurnPresentation): TurnPresentation {
        if (previous == null) return next
        val old = HashMap<String, ChatRow>(previous.rows.size * 2).apply { previous.rows.forEach { put(it.key, it) } }
        var reused = false
        val rows = next.rows.map { row -> val o = old[row.key]; if (o != null && o !== row && o == row) { reused = true; o } else row }
        return if (reused) next.copy(rows = rows) else next
    }

    companion object {
        fun presentTurn(group: TurnItems, files: List<DeliveredFile>, collapseApprovals: Boolean): TurnPresentation {
            val items = group.items
            val user = items.firstOrNull() as? ChatItem.User
            val final = items.lastOrNull { it is ChatItem.Assistant }
            val actions = ArrayList<ChatItem>()
            val finals = ArrayList<ChatItem>()
            val process = ArrayList<ChatItem>()
            for (item in items) {
                when {
                    item === user -> Unit
                    item === final || item is ChatItem.UsageFooter -> finals.add(item)
                    item is ChatItem.Approval -> if (collapseApprovals && item.decision != null) process.add(item) else actions.add(item)
                    item is ChatItem.Question || item is ChatItem.Diff && item.state == DiffState.Pending -> actions.add(item)
                    else -> process.add(item)
                }
            }
            val waiting = actions.any { it is ChatItem.Approval && it.decision == null }
            val rows = ArrayList<ChatRow>(items.size + 2)
            user?.let { rows.add(ChatRow.UserRow(group.key, it)) }
            if (process.isNotEmpty()) rows.add(ChatRow.ProcessRow(group.key, process, waiting))
            actions.forEach { rows.add(ChatRow.ItemRow(group.key, it)) }
            // 文件卡紧跟最终回答，在用量脚注之前。
            var filesPlaced = files.isEmpty()
            for (item in finals) {
                if (!filesPlaced && item is ChatItem.UsageFooter) { rows.add(ChatRow.FilesRow(group.key, files)); filesPlaced = true }
                rows.add(ChatRow.ItemRow(group.key, item))
            }
            if (!filesPlaced) rows.add(ChatRow.FilesRow(group.key, files))
            return TurnPresentation(user?.id ?: group.key, user, process, finals, actions, files, rows)
        }

        /**
         * 交付文件归属：
         * 1. 有 turnId 且该轮在本会话中 → 该轮；
         * 2. 否则按 deliveredAt 归到开始时间不晚于它的最近一轮（早于所有轮次则归第一轮）；
         * 3. 都没有（无时间或无轮次标记）→ 最后一轮。
         */
        fun placeDeliveries(deliveries: List<DeliveredFile>, marks: List<TurnMark>, turns: List<TurnItems>): Map<String, List<DeliveredFile>> {
            if (deliveries.isEmpty() || turns.isEmpty()) return emptyMap()
            val keys = HashSet<String>(turns.size * 2).apply { turns.forEach { add(it.key) } }
            val byTurn = HashMap<String, String>(marks.size * 2).apply { marks.forEach { if (it.groupKey in keys) put(it.turnId, it.groupKey) } }
            val timed = marks.filter { it.groupKey in keys && it.startedAt > 0 }.sortedBy { it.startedAt }
            val last = turns.last().key
            // 插话（steer）的用户消息在界面上另起一组，但仍属于同一个 dsh 轮次：把文件放到该轮最后一个插话分组里，
            // 让文件卡紧跟这一轮真正的最终回答。只跨过没有自己轮次标记、且以插话开头的分组。
            val marked = HashSet<String>(marks.size * 2).apply { marks.forEach { add(it.groupKey) } }
            val index = HashMap<String, Int>(turns.size * 2).apply { turns.forEachIndexed { i, t -> put(t.key, i) } }
            fun spanEnd(key: String): String {
                var i = index[key] ?: return key
                while (i + 1 < turns.size) {
                    val next = turns[i + 1]
                    if (next.key in marked || (next.items.firstOrNull() as? ChatItem.User)?.steer != true) break
                    i++
                }
                return turns[i].key
            }
            val out = LinkedHashMap<String, MutableList<DeliveredFile>>()
            for (f in deliveries) {
                val key = f.turnId?.let { byTurn[it] }?.let(::spanEnd)
                    ?: if (f.deliveredAt > 0 && timed.isNotEmpty()) {
                        var lo = 0; var hi = timed.lastIndex; var found = -1
                        while (lo <= hi) { val mid = (lo + hi) ushr 1; if (timed[mid].startedAt <= f.deliveredAt) { found = mid; lo = mid + 1 } else hi = mid - 1 }
                        spanEnd(timed[if (found < 0) 0 else found].groupKey)
                    } else last
                out.getOrPut(key) { ArrayList() }.add(f)
            }
            return out
        }

        /** 折叠后留在过程组里的一行小字，例如「已允许 · write report.md」。 */
        fun approvalLine(item: ChatItem.Approval): String {
            val verb = when (item.decision) {
                ApprovalDecision.AllowOnce -> "已允许"
                ApprovalDecision.AllowSession -> "已允许（本会话）"
                ApprovalDecision.Deny -> "已拒绝"
                null -> "等待审批"
            }
            val first = item.detail.lineSequence().firstOrNull()?.trim().orEmpty()
            val looksLikePath = first.isNotEmpty() && first.length <= 160 && !first.startsWith("{") &&
                (first.contains('/') || first.contains('.') && !first.contains(' '))
            val target = if (looksLikePath) first.trimEnd('/').substringAfterLast('/') else null
            val command = item.command.lineSequence().firstOrNull()?.trim()?.take(80).orEmpty()
            val what = listOfNotNull(command.takeIf { it.isNotEmpty() }, target?.takeIf { it.isNotEmpty() && !command.contains(it) })
                .joinToString(" ").ifBlank { item.title }
            return "$verb · $what"
        }
    }
}

/** 兼容旧调用：一次性按用户消息分组展示（不折叠已处理审批）。 */
internal fun presentTurns(items: List<ChatItem>): List<TurnPresentation> =
    TurnPresenter().present(SessionState.of(items), collapseApprovals = false)

/** 消息列表的行（反向，配合 reverseLayout）。只在展示结果变化时重建。 */
fun flattenRowsReversed(turns: List<TurnPresentation>): List<ChatRow> {
    val out = ArrayList<ChatRow>(turns.sumOf { it.rows.size })
    for (t in turns.indices.reversed()) { val rows = turns[t].rows; for (r in rows.indices.reversed()) out.add(rows[r]) }
    return out
}
