package app.hoshino.harness.core

import org.json.JSONObject
import java.util.UUID

/**
 * WP9-B native side of the kernel context plugins (`plugins/hoshino-context`).
 * Kept out of DshHarnessClient so parallel branches touching that file only meet two one-line hooks.
 */

/** Manual /compact: runs the original dsh compaction command through `/mobile/context` and returns a visible notice. */
internal suspend fun DshHarnessClient.compactSession(sessionId: String): HarnessEvent {
    val value = api().mobile("context", JSONObject().put("action", "compact").put("sessionId", sessionId))
    return HarnessEvent.Notice(sessionId, "compact:${UUID.randomUUID()}", compactNoticeText(value.optBoolean("ok"), value.optString("text")))
}

/** Kernel status for one session: cache hit rate, prefix breaks, budget and recent outcomes (for later UI). */
internal suspend fun DshHarnessClient.contextStatus(sessionId: String): JSONObject =
    api().mobile("context", JSONObject().put("action", "status").put("sessionId", sessionId))

/** Read or update `<selfHome>/config/context.json` (compaction strategy, guards, retries, outcome judge). */
internal suspend fun DshHarnessClient.contextSettings(update: JSONObject? = null): JSONObject =
    api().mobile("context", JSONObject().put("action", "settings").apply { if (update != null) put("settings", update) })

/** Kernel → app notice (compaction, safety stop, failure fix-it text, outcome label). Unknown kinds are ignored. */
internal fun contextNotice(body: JSONObject): HarnessEvent? {
    val sessionId = body.optString("sessionId").ifBlank { return null }
    val kind = body.optString("kind")
    if (kind !in setOf("compaction", "guard", "failure", "outcome")) return null
    val text = body.optString("text").take(1200).ifBlank { return null }
    return HarnessEvent.Notice(sessionId, "context:$kind:${UUID.randomUUID()}", text)
}

/** dsh command-compact replies in English; show the user a Chinese line with the same facts. */
internal fun compactNoticeText(ok: Boolean, text: String): String {
    val compacted = Regex("""Compacted (\d+) history items \(~(\d+) tokens\)""").find(text)
    return when {
        compacted != null -> "已压缩上下文：折叠 ${compacted.groupValues[1]} 条较早记录（约 ${compacted.groupValues[2]} tokens），你说过的原话已逐字保留。"
        text.startsWith("No compactable history") -> "暂时没有可以压缩的历史。"
        text.contains("not idle") || text.contains("unavailable") -> "当前正在运行或正在压缩，等这一轮结束后再压缩。"
        ok -> "压缩完成：$text"
        else -> "压缩没有完成：${text.ifBlank { "内核没有返回原因" }}"
    }
}
