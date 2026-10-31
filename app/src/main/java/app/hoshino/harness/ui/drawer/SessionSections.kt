package app.hoshino.harness.ui.drawer

import app.hoshino.harness.core.SessionInfo

/**
 * 侧栏分段（纯逻辑，可单测）：
 * - 置顶：置顶且未归档的会话，最近置顶的在前；在跑的置顶会话照样留在置顶段（行上有转圈）。
 * - 其余未归档会话按工作区分组（由调用方渲染）。
 * - 已归档单独一段。
 * 每个会话只出现在一段里。
 */
data class SessionSections(val pinned: List<SessionInfo>, val active: List<SessionInfo>, val archived: List<SessionInfo>)

fun sessionSections(filtered: List<SessionInfo>): SessionSections {
    val pinned = filtered.filter { !it.archived && it.pinned }.sortedByDescending { it.pinnedAt }
    val pinnedIds = pinned.mapTo(HashSet()) { it.id }
    return SessionSections(pinned, filtered.filter { !it.archived && it.id !in pinnedIds }, filtered.filter { it.archived })
}

/** 多选状态：进入多选时带上长按的那一行；全部取消后自动退出。 */
data class SessionSelection(val ids: Set<String> = emptySet(), val active: Boolean = false) {
    fun start(id: String) = SessionSelection(setOf(id), true)
    fun toggle(id: String): SessionSelection {
        val next = if (id in ids) ids - id else ids + id
        return if (next.isEmpty()) SessionSelection() else copy(ids = next)
    }
    fun selectAll(visible: List<String>) = copy(ids = ids + visible, active = true)
    /** 列表变化后去掉已经不存在的会话。 */
    fun retain(existing: Set<String>): SessionSelection {
        if (!active) return this
        val next = ids.intersect(existing)
        return if (next.isEmpty()) SessionSelection() else copy(ids = next)
    }
    fun clear() = SessionSelection()
}

/** 删除确认文案：写明标题或数量、在跑的会停止、当前会话会换成新会话。 */
fun deleteConfirmation(targets: List<SessionInfo>, currentId: String?): String {
    val running = targets.count { it.running }
    val head = if (targets.size == 1) "「${targets[0].title}」的对话记录、交付文件记录和本地缓存会被删除，无法恢复。" else "选中的 ${targets.size} 个会话的对话记录、交付文件记录和本地缓存会被删除，无法恢复。"
    val parts = mutableListOf(head)
    if (running > 0) parts += if (targets.size == 1) "它正在运行，会先停止。" else "其中 $running 个正在运行，会先停止。"
    if (currentId != null && targets.any { it.id == currentId }) parts += "当前打开的会话也在其中，删除后会切到新会话。"
    parts += "工作区里的文件不会被删除。"
    return parts.joinToString("\n")
}
