package dev.hoshino.bridge.metrics

import org.json.JSONObject
import java.io.File

/**
 * 只读 dsh 原版会话日志（`@deepseek-ai/dsh-session-persistence-jsonl`）：`<sessions 根>/<项目>/<会话>/session(.v<N>).jsonl`，
 * 第一行是文件头（带会话 `id`），之后每行一个持久事件。日志仍由 dsh 自己写，这里不改、不复制，只按需读出来算小结（OB3）
 * 和评测事实（EV2）。同一会话有几代格式文件时取版本号最大的（与 `OriginalSessionHistory` 相同）。
 */
object DshSessionLog {
    private val NAME = Regex("session(?:\\.v([0-9]+))?\\.jsonl")
    private const val MAX_DEPTH = 4

    /** 这个会话的日志文件；找不到为 null。先认目录名（会话 id 编码后就是目录名），再按文件头里的 id 找。 */
    fun find(root: File, sessionId: String): File? {
        if (!root.isDirectory || sessionId.isBlank()) return null
        val dirs = root.walkTopDown().maxDepth(MAX_DEPTH).filter { it.isFile && NAME.matches(it.name) }.groupBy { it.parentFile }
        dirs.entries.firstOrNull { it.key?.name == sessionId }?.let { return latest(it.value) }
        return dirs.values.map(::latest).firstOrNull { headerId(it) == sessionId }
    }

    private fun latest(files: List<File>): File = files.maxBy { NAME.matchEntire(it.name)?.groupValues?.get(1)?.toIntOrNull() ?: 0 }

    /** 文件头里的会话 id；读不出为 null。 */
    fun headerId(file: File): String? = runCatching {
        file.bufferedReader().use { it.readLine() }?.let { JSONObject(it).optString("id").takeIf(String::isNotBlank) }
    }.getOrNull()

    /** 全部持久事件（按 seq 排序、同 seq 去重）；文件头与读不出的行跳过。 */
    fun read(file: File): List<DshEvent> = runCatching {
        file.bufferedReader().useLines { lines -> lines.mapNotNull { DshEvent.parse(it) }.toList() }
    }.getOrDefault(emptyList()).distinctBy { it.seq }.sortedBy { it.seq }

    /** 每个结束了的轮次一份小结，`runId` 为 `<会话>#<轮>`，旧的在前。 */
    fun metrics(events: List<DshEvent>, sessionId: String): List<RunMetrics> =
        DshTurn.split(events).filter { it.ended }.mapNotNull { RunMetrics.of(it.events, runId(sessionId, it.turn)) }

    fun runId(sessionId: String, turn: Int) = "$sessionId#$turn"

    /** 最后一个轮次（可能还没结束）；没有为 null。 */
    fun lastTurn(events: List<DshEvent>): DshTurn? = DshTurn.split(events).lastOrNull()
}
