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

    /** Smallest context window / output limit a user override may set (tokens). */
    const val MIN_LIMIT = 4096
    /** Hard ceiling for any override (tokens); above the model's own limit is allowed but flagged by [limitWarning]. */
    const val MAX_LIMIT = 10_000_000

    /**
     * Carry the user's per-model context/output overrides from [previous] (the stored rows) onto freshly listed or merged
     * [rows], so refreshing the plan's model list never overwrites them. Each row keeps the model's own limits in
     * `officialContextLength` / `officialMaxOutput`; an override lives in `userContextLength` / `userMaxOutput` and becomes
     * the effective `contextLength` / `maxOutput` (what the kernel reads for contextWindow / maxTokens and compaction).
     * The per-model hosted-search override (`webSearchMode`) is carried the same way. Rows are edited in place and returned.
     */
    fun applyUserLimits(rows: JSONArray, previous: JSONArray?): JSONArray {
        val old = previous?.let { p -> (0 until p.length()).mapNotNull { p.optJSONObject(it) }.associateBy { it.optString("id") } }.orEmpty()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val officialCtx = row.positiveInt("officialContextLength") ?: row.positiveInt("contextLength") ?: 128000
            val officialOut = row.positiveInt("officialMaxOutput") ?: row.positiveInt("maxOutput") ?: 32000
            row.put("officialContextLength", officialCtx).put("officialMaxOutput", officialOut)
            val prior = old[row.optString("id")]
            prior?.str("webSearchMode")?.let { row.put("webSearchMode", it) }
            val userCtx = prior?.positiveInt("userContextLength") ?: row.positiveInt("userContextLength")
            val userOut = prior?.positiveInt("userMaxOutput") ?: row.positiveInt("userMaxOutput")
            if (userCtx != null) row.put("userContextLength", userCtx) else row.remove("userContextLength")
            if (userOut != null) row.put("userMaxOutput", userOut) else row.remove("userMaxOutput")
            row.put("contextLength", userCtx ?: officialCtx).put("maxOutput", userOut ?: officialOut)
        }
        return rows
    }

    /**
     * Set (or clear, when equal to the model's own limit or null) the user's override on one stored row.
     * Throws [IllegalArgumentException] with a people-facing message when a value is out of range.
     */
    fun setUserLimits(row: JSONObject, contextLength: Int?, maxOutput: Int?) {
        validateLimit("上下文窗口", contextLength); validateLimit("最大输出", maxOutput)
        if (contextLength != null && maxOutput != null) require(maxOutput < contextLength) { "最大输出需要小于上下文窗口" }
        val officialCtx = row.positiveInt("officialContextLength") ?: row.positiveInt("contextLength") ?: 128000
        val officialOut = row.positiveInt("officialMaxOutput") ?: row.positiveInt("maxOutput") ?: 32000
        row.put("officialContextLength", officialCtx).put("officialMaxOutput", officialOut)
        if (contextLength == null || contextLength == officialCtx) row.remove("userContextLength") else row.put("userContextLength", contextLength)
        if (maxOutput == null || maxOutput == officialOut) row.remove("userMaxOutput") else row.put("userMaxOutput", maxOutput)
        row.put("contextLength", row.positiveInt("userContextLength") ?: officialCtx).put("maxOutput", row.positiveInt("userMaxOutput") ?: officialOut)
    }

    fun validateLimit(label: String, value: Int?) {
        if (value == null) return
        require(value >= MIN_LIMIT) { "${label}不能小于 $MIN_LIMIT tokens" }
        require(value <= MAX_LIMIT) { "${label}不能大于 $MAX_LIMIT tokens" }
    }

    /** A caution when an override goes above the model's own limit (allowed; the plan may reject or truncate). */
    fun limitWarning(value: Int, official: Int?): String? =
        if (official != null && official > 0 && value > official) "超过模型官方上限 $official，套餐服务端可能拒绝或截断" else null

    private fun effortName(level: String) = when (level.lowercase()) {
        "none", "minimal" -> "Off"; "low" -> "Low"; "medium" -> "Medium"; "high" -> "High"; "xhigh" -> "XHigh"; "max" -> "Max"; else -> null
    }

    private fun JSONObject.positiveInt(k: String): Int? = (opt(k) as? Number)?.toInt()?.takeIf { it > 0 }
}
