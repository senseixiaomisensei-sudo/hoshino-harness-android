// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package dev.hoshino.bridge.chatgpt

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/** Why the plan model list could not be read. */
class ModelListException(val status: Int, message: String) : Exception(message)

/**
 * The model list for a ChatGPT sign-in: `GET {apiBase}/models` with the plan access token (Mika's ModelCatalog approach).
 * Both shapes are accepted: the OpenAI `data` list (`id`) and the ChatGPT `models` list (`slug`, `display_name`, only
 * `visibility: list`, server order kept). Capabilities the endpoint states are used; anything it leaves out is taken from
 * the offline metadata table, never guessed beyond the narrow defaults documented in [merge].
 */
object ChatGptModels {
    /** Model families that are not chat models on the OpenAI API. */
    private val NON_CHAT = Regex("(^|[-_.])(embedding|embed|tts|whisper|dall-e|moderation|transcribe|realtime|audio|image|sora|search-preview)([-_.]|$)|^(babbage|davinci|text-|omni-moderation|gpt-image|chatgpt-image)")
    private val NON_CHAT_TYPES = setOf("embedding", "embeddings", "rerank", "image", "images", "video", "audio", "tts", "stt", "asr", "speech", "transcription", "moderation")

    suspend fun fetch(http: OkHttpClient, apiBase: String, accessToken: String): JSONArray {
        val raw = http.executeRaw(Request.Builder().url(apiBase.trimEnd('/') + "/models").header("Authorization", "Bearer $accessToken").get().build())
        if (raw.status == 401) throw ModelListException(401, "ChatGPT 登录已失效，请重新登录")
        if (raw.status == 403) throw ModelListException(403, "这个 ChatGPT 账号不能在 Hoshino 使用套餐模型（需要 Plus 或 Pro）")
        if (raw.status !in 200..299) throw ModelListException(raw.status, "模型列表接口返回 HTTP ${raw.status}")
        return parse(raw.body)
    }

    /** Parse a model list body into rows {id, displayName?, vision?, contextLength?, reasoning?, reasoningLevels?}. */
    fun parse(body: String): JSONArray {
        val root = JSONObject(body)
        val data = root.optJSONArray("data") ?: root.optJSONArray("models") ?: throw ModelListException(200, "模型列表格式无法识别")
        val out = JSONArray()
        val seen = HashSet<String>()
        for (i in 0 until minOf(data.length(), 2000)) {
            val o = data.optJSONObject(i) ?: continue
            val visibility = o.str("visibility")
            if (visibility != null && visibility != "list") continue
            val id = (o.str("id") ?: o.str("slug"))?.trim()?.takeIf { it.isNotEmpty() && it.length <= 200 } ?: continue
            if (o.str("type")?.lowercase() in NON_CHAT_TYPES || NON_CHAT.containsMatchIn(id.lowercase())) continue
            if (!seen.add(id)) continue
            val row = JSONObject().put("id", id)
            (o.str("display_name") ?: o.str("name"))?.let { row.put("displayName", it) }
            val inputs = (o.optJSONArray("input_modalities") ?: o.optJSONObject("modalities")?.optJSONArray("input"))
            if (inputs != null) row.put("vision", (0 until inputs.length()).any { inputs.optString(it).let { m -> m == "image" || m.startsWith("image/") } })
            (o.positiveInt("context_window") ?: o.positiveInt("context_length"))?.let { row.put("contextLength", it) }
            (o.positiveInt("max_output_tokens"))?.let { row.put("maxOutput", it) }
            val levels = o.optJSONArray("supported_reasoning_levels")
            if (levels != null) {
                val names = (0 until levels.length()).mapNotNull { j -> (levels.opt(j) as? String) ?: (levels.opt(j) as? JSONObject)?.str("effort") }
                row.put("reasoning", names.isNotEmpty()).put("reasoningLevels", JSONArray(names))
                (o.str("default_reasoning_level"))?.let { row.put("defaultReasoningLevel", it) }
            }
            out.put(row)
        }
        return out
    }

    /**
     * Merge listed rows with the offline table (`codex-models.json` rows: id, displayName, contextLength, maxOutput, vision,
     * reasoning, defaultEffort, reasoningLevels). The listing decides which models exist and their order; listed fields win.
     * A model neither describes gets: context 128K / output 32K, no vision, and reasoning only for GPT-5+ / o-series ids
     * (inferred: every such model in the OpenAI catalog reasons). When [listed] is null (offline), the table is returned.
     */
    fun merge(listed: JSONArray?, offline: JSONArray): JSONArray {
        val table = (0 until offline.length()).mapNotNull { offline.optJSONObject(it) }.associateBy { it.optString("id") }
        if (listed == null) return JSONArray(table.values.map { JSONObject(it.toString()) })
        val out = JSONArray()
        for (i in 0 until listed.length()) {
            val row = listed.getJSONObject(i)
            val id = row.getString("id")
            val base = table[id]
            val reasoningGuess = Regex("^(gpt-([5-9]|\\d{2,})|o\\d)").containsMatchIn(id)
            val levels = row.optJSONArray("reasoningLevels") ?: base?.optJSONArray("reasoningLevels")
            val reasoning = if (row.has("reasoning")) row.getBoolean("reasoning") else base?.optBoolean("reasoning", reasoningGuess) ?: reasoningGuess
            val default = row.optString("defaultReasoningLevel", "").ifEmpty { null }
                ?.let { effortName(it) } ?: base?.optString("defaultEffort", "")?.ifEmpty { null } ?: if (reasoning) "Medium" else "Off"
            out.put(JSONObject()
                .put("id", id)
                .put("displayName", row.optString("displayName", "").ifEmpty { base?.optString("displayName", id) ?: id })
                .put("contextLength", if (row.has("contextLength")) row.getInt("contextLength") else base?.optInt("contextLength", 128000) ?: 128000)
                .put("maxOutput", if (row.has("maxOutput")) row.getInt("maxOutput") else base?.optInt("maxOutput", 32000) ?: 32000)
                .put("vision", if (row.has("vision")) row.getBoolean("vision") else base?.optBoolean("vision", false) ?: false)
                .put("reasoning", reasoning)
                .put("defaultEffort", default)
                .apply { if (levels != null) put("reasoningLevels", levels) })
        }
        return out
    }

    private fun effortName(level: String) = when (level.lowercase()) {
        "none", "minimal" -> "Off"; "low" -> "Low"; "medium" -> "Medium"; "high" -> "High"; "xhigh" -> "XHigh"; "max" -> "Max"; else -> null
    }

    private fun JSONObject.positiveInt(k: String): Int? = (opt(k) as? Number)?.toInt()?.takeIf { it > 0 }
}
