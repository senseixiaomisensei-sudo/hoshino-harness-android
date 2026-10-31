package dev.hoshino.bridge.metrics

import org.json.JSONArray
import org.json.JSONObject

/**
 * dsh 原版会话日志里的一条持久事件（`@deepseek-ai/dsh-session` 的 `SessionEventMap`）：`seq` 连续、`time` 是毫秒时间戳、`data` 按 `type` 不同。
 * 磁盘上的 JSONL 每行要么是 `{"event":{…}}`，要么就是事件本身（见 `OriginalSessionHistory`）；内核推给界面的 snapshot/event 帧里也是同样的对象。
 * 这里只读、不改，日志仍由 dsh 自己写。
 */
data class DshEvent(val seq: Int, val type: String, val time: Long, val data: JSONObject) {
    val turn: Int? get() = if (data.has("turn")) data.optInt("turn") else null
    val step: Int? get() = if (data.has("step")) data.optInt("step") else null

    companion object {
        /** 一行 JSONL 或一个事件对象；不是带 seq/type/data 的事件（文件头、损坏的行）返回 null。 */
        fun parse(line: String): DshEvent? = runCatching { parse(JSONObject(line)) }.getOrNull()

        fun parse(raw: JSONObject): DshEvent? {
            val e = raw.optJSONObject("event") ?: raw
            if (!e.has("seq") || !e.has("type")) return null
            val data = e.optJSONObject("data") ?: return null
            return DshEvent(e.optInt("seq"), e.optString("type"), time(e.opt("time")), data)
        }

        /** dsh 的 time 是毫秒数；兼容旧记录里的数字字符串与 ISO 时间。 */
        fun time(value: Any?): Long = when (value) {
            is Number -> value.toLong().coerceAtLeast(0)
            is String -> value.toLongOrNull() ?: runCatching { java.time.Instant.parse(value).toEpochMilli() }.getOrDefault(0L)
            else -> 0L
        }

        /** 内容块数组里某一类块的文字（text / reasoning），按顺序拼起来。 */
        fun text(content: JSONArray?, type: String = "text"): String {
            if (content == null) return ""
            val sb = StringBuilder()
            for (i in 0 until content.length()) {
                val block = content.optJSONObject(i) ?: continue
                if (block.optString("type") == type) sb.append(block.optString("text"))
            }
            return sb.toString()
        }

        /**
         * 一段 dsh 紧凑流（`assistant/message.stream` / `assistant/attempt.stream`）里第一个 token 的时间，
         * 规则与 dsh-llm `assistantStreamFirstTokenTime` 一致：非空的文字/思考片段，或带名字的工具调用片段。没有为 null。
         */
        fun firstTokenTime(stream: JSONArray?): Long? {
            if (stream == null) return null
            for (i in 0 until stream.length()) {
                val r = stream.optJSONObject(i) ?: continue
                when (r.optString("type")) {
                    "chunk" -> {
                        val c = r.optJSONObject("chunk") ?: continue
                        val token = when (c.optString("type")) {
                            "text-delta", "reasoning-delta" -> c.optString("text").isNotEmpty()
                            "tool-call-delta" -> c.optString("argumentsDelta").isNotEmpty() || c.has("name")
                            else -> false
                        }
                        if (token) return time(r.opt("time"))
                    }
                    "tool-call-chunks" -> if (r.has("name")) return time(r.opt("time0")) else runFirst(r, "args")?.let { return it }
                    "text-chunks", "reasoning-chunks" -> runFirst(r, "texts")?.let { return it }
                }
            }
            return null
        }

        private fun runFirst(run: JSONObject, key: String): Long? {
            val parts = run.optJSONArray(key) ?: return null
            val dt = run.optJSONArray("dt")
            var t = time(run.opt("time0"))
            for (i in 0 until parts.length()) {
                if (i > 0) t += dt?.optLong(i - 1) ?: 0L
                if (parts.optString(i).isNotEmpty()) return t
            }
            return null
        }
    }
}

/** 会话日志按轮切开：每轮从 `turn/start` 到 `turn/end`（没结束的到日志末尾）。 */
data class DshTurn(val turn: Int, val startSeq: Int, val endSeq: Int?, val events: List<DshEvent>) {
    /** 这一轮第一条用户原话的 seq（界面上用户消息的 id 是 `e:<seq>`）；没有为 null。 */
    val userSeq: Int? get() = events.firstOrNull { it.type == "user/message" && it.data.optJSONObject("source")?.optString("kind") == "user" }?.seq
    val ended: Boolean get() = endSeq != null

    companion object {
        fun split(events: List<DshEvent>): List<DshTurn> {
            val out = mutableListOf<DshTurn>()
            var current: MutableList<DshEvent>? = null
            var turn = 0
            var start = 0
            for (e in events.sortedBy { it.seq }) {
                if (e.type == "turn/start") {
                    current?.let { out += DshTurn(turn, start, null, it) }
                    current = mutableListOf(e); turn = e.turn ?: (turn + 1); start = e.seq
                    continue
                }
                val c = current ?: continue
                c += e
                if (e.type == "turn/end" && (e.turn == null || e.turn == turn)) {
                    out += DshTurn(turn, start, e.seq, c); current = null
                }
            }
            current?.let { out += DshTurn(turn, start, null, it) }
            return out
        }
    }
}
