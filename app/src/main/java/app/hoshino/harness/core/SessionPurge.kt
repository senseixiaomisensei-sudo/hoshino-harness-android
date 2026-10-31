package app.hoshino.harness.core

import java.io.File

/**
 * 删除会话的持久化部分。dsh 0.2.2-alpha.2 没有删除会话的 RPC，也不能改它的源码，所以分两步：
 *  1. 立刻：界面不再列出（墓碑），停掉、归档、清掉插件与 Android 侧的会话数据。
 *  2. 内核不在运行时（下一次启动内核之前）：按墓碑删掉 `DSH_HOME/sessions/<项目>/<会话>/` 整个目录，
 *     连同它派生的子 Agent 会话（不含用户分叉的会话）。这时没有进程持有写租约，删除是安全的；dsh 的工作区登记允许指向已不存在的会话。
 * 只用 java.io，便于在 JVM 单测里验证。
 */
class SessionPurge(private val tombstones: File, private val sessionsRoot: File) {
    @Synchronized fun pending(): Set<String> = runCatching { tombstones.readLines().map { it.trim() }.filter { ID.matches(it) }.toSet() }.getOrDefault(emptySet())

    @Synchronized fun mark(ids: Collection<String>) {
        val next = pending() + ids.filter { ID.matches(it) }
        tombstones.parentFile?.mkdirs()
        val temp = File(tombstones.parentFile, tombstones.name + ".tmp")
        temp.writeText(next.joinToString("\n", postfix = "\n"))
        if (!temp.renameTo(tombstones)) { tombstones.delete(); check(temp.renameTo(tombstones)) { "无法记录待删除会话" } }
    }

    /**
     * 删除墓碑里的会话目录（含 parentSession 指向它们的子会话，递归）。只能在内核停止时调用。
     * 返回实际删掉的会话 id；删除失败的保留在墓碑里，下次再试。
     */
    @Synchronized fun purge(): Set<String> {
        val wanted = pending()
        if (wanted.isEmpty()) return emptySet()
        val headers = scan()
        val doomed = wanted.toMutableSet()
        var grew = true
        // 只连带删除子 Agent 会话（origin=subagent）；用户分叉出来的会话是独立对话（自带继承的事件副本），保留。
        while (grew) { grew = false; for (h in headers) if (h.subagent && h.parent != null && h.parent in doomed && doomed.add(h.id)) grew = true }
        val removed = mutableSetOf<String>()
        val failed = mutableSetOf<String>()
        val root = sessionsRoot.canonicalFile
        for (h in headers) if (h.id in doomed) {
            val dir = h.dir.canonicalFile
            // 只删 sessions 根之下、至少两级（项目/会话）的目录，绝不越界。
            if (dir.parentFile?.parentFile?.path?.startsWith(root.path) != true || dir == root) { failed += h.id; continue }
            if (dir.deleteRecursively()) removed += h.id else failed += h.id
        }
        // 墓碑里找不到目录的（只存在于本地、或已删过）视为完成。
        val left = wanted.filter { it in failed }
        tombstones.parentFile?.mkdirs()
        if (left.isEmpty()) tombstones.delete() else tombstones.writeText(left.joinToString("\n", postfix = "\n"))
        return removed + (wanted - failed)
    }

    data class Header(val id: String, val parent: String?, val dir: File, val subagent: Boolean = false)

    /** 扫描会话目录的 JSONL 头行（第一行），只读 id 与 parentSession，不读正文。 */
    fun scan(): List<Header> {
        if (!sessionsRoot.isDirectory) return emptyList()
        return sessionsRoot.walkTopDown().maxDepth(3).filter { it.isFile && LOG.matches(it.name) }.groupBy { it.parentFile!! }.mapNotNull { (dir, files) ->
            val first = runCatching { files.maxBy { LOG.find(it.name)?.groupValues?.get(1)?.toIntOrNull() ?: 0 }.bufferedReader().use { it.readLine() } }.getOrNull() ?: return@mapNotNull null
            val id = field(first, "id") ?: return@mapNotNull null
            Header(id, field(first, "parentSession"), dir, field(first, "origin") == "subagent")
        }
    }

    companion object {
        private val ID = Regex("[A-Za-z0-9_-]{1,150}")
        private val LOG = Regex("session(?:\\.v([0-9]+))?\\.jsonl")
        /** 头行是 JSON；这里只需要两个顶层字符串字段，用正则避免依赖 org.json（JVM 单测里不可用）。 */
        fun field(json: String, name: String): String? = Regex("\"" + Regex.escape(name) + "\"\\s*:\\s*\"([^\"\\\\]{1,200})\"").find(json)?.groupValues?.get(1)
    }
}
