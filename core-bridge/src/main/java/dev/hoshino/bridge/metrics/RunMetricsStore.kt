// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package dev.hoshino.bridge.metrics

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 每次运行一份小结（[RunMetrics]），一行一份存在 [file]（JSON Lines），只留最近 [keep] 份；只存小结的数字和目标的前 80 个字，
 * 不存回答、工具参数和结果。读不出的行跳过，整个文件坏了按空的算；写是整份写到临时文件再换上，写不成只丢这一次。
 * 同一个文件可能有几个实例，读写用进程内同一把锁。照抄 Mika `RunMetricsStore`。
 */
class RunMetricsStore(private val file: File, private val keep: Int = DEFAULT_KEEP) {
    init { require(keep > 0) { "keep must be positive" } }

    /** 旧的在前。 */
    fun all(): List<RunMetrics> = synchronized(LOCK) { read() }

    /** 最近 [n] 份（旧的在前）；[n] 为 null 时全部。 */
    fun recent(n: Int?): List<RunMetrics> = all().let { if (n == null) it else it.takeLast(n) }

    fun get(runId: String): RunMetrics? = all().lastOrNull { it.runId == runId }

    /** 记一份；同一次运行已有的换掉。 */
    fun append(m: RunMetrics) = synchronized(LOCK) {
        write(read().filter { m.runId.isEmpty() || it.runId != m.runId } + m)
    }

    /** 改一次运行的那一份；没有就不动。 */
    fun update(runId: String, change: (RunMetrics) -> RunMetrics) = synchronized(LOCK) {
        val list = read()
        if (list.none { it.runId == runId }) return@synchronized
        write(list.map { if (it.runId == runId) change(it) else it })
    }

    fun clear() = synchronized(LOCK) { file.delete(); Unit }

    private fun read(): List<RunMetrics> {
        if (!file.isFile) return emptyList()
        val lines = runCatching { file.readLines() }.getOrDefault(emptyList())
        return lines.filter { it.isNotBlank() }.mapNotNull { runCatching { RunMetrics.fromJson(JSONObject(it)) }.getOrNull() }
    }

    private fun write(list: List<RunMetrics>) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(list.takeLast(keep).joinToString("") { it.toJson().toString() + "\n" })
            if (!tmp.renameTo(file)) error("rename failed")
        }
    }

    companion object {
        const val DEFAULT_KEEP = 500
        const val FILE_NAME = "run-metrics.jsonl"
        private val LOCK = Any()

        /** 导出给人分析用：一个 JSON 对象，含汇总（[RunStats]，最近 10/30/100 次与全部）和每次的小结，旧的在前。 */
        fun export(list: List<RunMetrics>): String = JSONObject().apply {
            put("engine", RunMetrics.ENGINE)
            put("stats", JSONObject().apply { for (n in WINDOWS) put(n?.toString() ?: "all", RunStats.of(if (n == null) list else list.takeLast(n)).toJson()) })
            put("runs", JSONArray(list.map { it.toJson() }))
        }.toString(2)

        /** OB5 的窗口：最近 10/30/100 次与全部。 */
        val WINDOWS: List<Int?> = listOf(10, 30, 100, null)
    }
}
