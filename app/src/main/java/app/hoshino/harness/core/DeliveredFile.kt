package app.hoshino.harness.core

import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject

/**
 * deliver_file 交付的一份工作区文件。
 *
 * [turnId] 是交付发生时所在轮次的 id（与 [HarnessEvent.TurnStarted.turnId] 相同，形如 `e:<seq>`，来自 dsh 持久化日志的
 * 事件序号，因此应用重启、会话重放后仍然稳定）；[callId] 是产生它的 deliver_file 工具调用 id；[deliveredAt] 是交付时间。
 * 旧版本（M2.6 及以前）的记录三者都没有：读取时 [deliveredAt] 取文件修改时间，界面按时间归到最近的轮次。
 */
@Immutable
data class DeliveredFile(
    val id: String,
    val sessionId: String,
    val path: String,
    val name: String,
    val bytes: Long,
    val turnId: String? = null,
    val deliveredAt: Long = 0L,
    val callId: String? = null,
)

/** hoshino-deliveries.json 的读写；兼容只有 id/sessionId/path/name/bytes 的旧记录。 */
object DeliveredFileCodec {
    fun decode(text: String, fallbackTime: (path: String) -> Long = { 0L }): List<DeliveredFile> {
        val a = JSONArray(text)
        return (0 until a.length()).mapNotNull { i ->
            val r = a.optJSONObject(i) ?: return@mapNotNull null
            runCatching {
                val path = r.getString("path")
                DeliveredFile(
                    id = r.getString("id"),
                    sessionId = r.optString("sessionId"),
                    path = path,
                    name = r.optString("name", path.substringAfterLast('/')),
                    bytes = r.optLong("bytes"),
                    turnId = r.optString("turnId").takeIf { it.isNotBlank() },
                    deliveredAt = r.optLong("deliveredAt").takeIf { it > 0 } ?: fallbackTime(path),
                    callId = r.optString("callId").takeIf { it.isNotBlank() },
                )
            }.getOrNull()
        }
    }

    fun encode(rows: List<DeliveredFile>): String = JSONArray(rows.map { f ->
        JSONObject().put("id", f.id).put("sessionId", f.sessionId).put("path", f.path).put("name", f.name).put("bytes", f.bytes)
            .apply {
                f.turnId?.let { put("turnId", it) }
                if (f.deliveredAt > 0) put("deliveredAt", f.deliveredAt)
                f.callId?.let { put("callId", it) }
            }
    }).toString()
}
