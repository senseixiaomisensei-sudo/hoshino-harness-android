package dev.hoshino.bridge

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Model-hosted web search: how a given provider model can search on its own, and the user's override.
 *
 * Method ids (stored as provider model metadata `webSearchMethod`, read by plugins/android-api/hosted-search.ts):
 * - `responses:web_search` / `responses:web_search_preview` — OpenAI Responses hosted tool;
 * - `chat:web_search_options` — Chat Completions `web_search_options`;
 * - `chat:plugins-web` — the `plugins: [{id: "web"}]` request field several OpenAI-compatible gateways accept;
 * - `chat:variant:<model>` — a searching variant the provider's own model list names (e.g. `<model>:online`); never invented;
 * - `anthropic:web_search` — Anthropic `web_search_20250305` server tool;
 * - `none` — probed and unavailable. An empty value means not yet known.
 * [WebSearchMode] is the per-model override: Auto uses the detected method, On forces it (probing first if unknown), Off never.
 */
object WebSearchMethods {
    const val RESPONSES = "responses:web_search"
    const val RESPONSES_PREVIEW = "responses:web_search_preview"
    const val CHAT_OPTIONS = "chat:web_search_options"
    const val CHAT_PLUGINS = "chat:plugins-web"
    const val CHAT_VARIANT = "chat:variant:"
    const val ANTHROPIC = "anthropic:web_search"
    const val NONE = "none"

    fun label(method: String?): String = when {
        method.isNullOrEmpty() -> "尚未检测"
        method == RESPONSES -> "Responses · web_search"
        method == RESPONSES_PREVIEW -> "Responses · web_search_preview"
        method == CHAT_OPTIONS -> "Chat Completions · web_search_options"
        method == CHAT_PLUGINS -> "Chat Completions · plugins web"
        method.startsWith(CHAT_VARIANT) -> "联网变体 · ${method.removePrefix(CHAT_VARIANT)}"
        method == ANTHROPIC -> "Anthropic · web_search 服务端工具"
        method == NONE -> "不支持（使用 Hoshino 内置搜索）"
        else -> method
    }
}

enum class WebSearchMode(val key: String, val label: String) { Auto("auto", "自动"), On("on", "强制开"), Off("off", "关"); companion object { fun of(key: String?) = entries.firstOrNull { it.key == key } ?: Auto } }

/** One probe request's outcome; [detail] is a short server reason with credentials removed. */
data class ProbeAttempt(val method: String, val outcome: String, val status: Int, val detail: String = "") {
    fun json(): JSONObject = JSONObject().put("method", method).put("outcome", outcome).put("status", status).put("detail", detail)
}

/** [method] is the first method with evidence, [WebSearchMethods.NONE] when every probe was cleanly refused or ignored, null when undecided (network, 5xx, 429, auth). */
data class ProbeResult(val method: String?, val attempts: List<ProbeAttempt>)

/**
 * Explicit, small capability probe run from Settings when a provider model is added or the user asks.
 * Each request asks for one search with a tiny output budget and counts as available only with evidence in the reply
 * (a `web_search_call` item, `url_citation` annotations, or Anthropic `server_tool_use` / `web_search_tool_result` blocks):
 * gateways that silently drop unknown fields are not mistaken for search support. Model names are never rewritten unless
 * the provider's own model list explicitly names a searching variant.
 */
class WebSearchProbe(private val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).callTimeout(90, TimeUnit.SECONDS).followRedirects(false).build()) {
    private val json = "application/json".toMediaType()

    suspend fun probe(type: String, baseURL: String, model: String, apiKey: String, headers: Map<String, String> = emptyMap(), listedModels: Collection<String> = emptyList()): ProbeResult = withContext(Dispatchers.IO) {
        val base = providerBaseURL(type, baseURL)
        val attempts = mutableListOf<ProbeAttempt>()
        val plans: List<Pair<String, () -> Request>> = if (type == "anthropic") listOf(
            WebSearchMethods.ANTHROPIC to { anthropic(base, model, apiKey, headers) },
        ) else buildList {
            add(WebSearchMethods.RESPONSES to { responses(base, model, apiKey, headers, "web_search") })
            add(WebSearchMethods.RESPONSES_PREVIEW to { responses(base, model, apiKey, headers, "web_search_preview") })
            add(WebSearchMethods.CHAT_OPTIONS to { chat(base, model, apiKey, headers) { put("web_search_options", JSONObject()) } })
            add(WebSearchMethods.CHAT_PLUGINS to { chat(base, model, apiKey, headers) { put("plugins", JSONArray().put(JSONObject().put("id", "web").put("max_results", 2))) } })
            for (variant in variants(model, listedModels)) add(WebSearchMethods.CHAT_VARIANT + variant to { chat(base, variant, apiKey, headers) {} })
        }
        var undecided = false
        for ((method, build) in plans) {
            val attempt = try {
                http.newCall(build()).execute().use { response ->
                    val body = response.body?.let { b -> b.byteStream().use { it.readNBytesCompat(MAX_BODY) } }?.toString(Charsets.UTF_8).orEmpty()
                    classify(method, response.code, body, apiKey)
                }
            } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) {
                ProbeAttempt(method, "error", 0, failure.javaClass.simpleName)
            }
            attempts += attempt
            if (attempt.outcome == "available") return@withContext ProbeResult(method, attempts)
            if (attempt.outcome == "error") undecided = true
            // A dead key or rate limit says nothing about search; stop early instead of spending more requests.
            if (attempt.status == 401 || attempt.status == 403 && type == "anthropic" || attempt.status == 429) { undecided = true; break }
        }
        ProbeResult(if (undecided) null else WebSearchMethods.NONE, attempts)
    }

    internal fun classify(method: String, status: Int, body: String, secret: String): ProbeAttempt {
        val detail = redactCredentials(errorText(body), listOf(secret)).take(160)
        if (status == 429 || status == 401 || status >= 500) return ProbeAttempt(method, "error", status, detail)
        if (status !in 200..299) return ProbeAttempt(method, "rejected", status, detail)
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return ProbeAttempt(method, "no-evidence", status, "非 JSON 响应")
        if (root.opt("error") is JSONObject) return ProbeAttempt(method, "rejected", status, detail)
        return ProbeAttempt(method, if (evidence(method, root)) "available" else "no-evidence", status)
    }

    /** Evidence that a search actually ran. */
    internal fun evidence(method: String, root: JSONObject): Boolean = when {
        method.startsWith("responses:") -> root.optJSONArray("output").objects().any { item ->
            item.optString("type").startsWith("web_search") || item.optJSONArray("content").objects().any { c -> c.optJSONArray("annotations").objects().any { it.optString("type") == "url_citation" } }
        }
        method == WebSearchMethods.ANTHROPIC -> root.optJSONArray("content").objects().any { it.optString("type") in setOf("server_tool_use", "web_search_tool_result") }
        else -> root.optJSONArray("choices").objects().any { choice ->
            val message = choice.optJSONObject("message") ?: return@any false
            message.optJSONArray("annotations").objects().any { it.optString("type") == "url_citation" || it.has("url_citation") || it.has("url") }
        } || root.optJSONArray("citations").let { it != null && it.length() > 0 } || root.optJSONArray("search_results").let { it != null && it.length() > 0 }
    }

    private fun prompt() = "Search the web for the official homepage URL of the Python programming language and reply with only that URL."

    private fun Request.Builder.common(apiKey: String, headers: Map<String, String>, bearer: Boolean = true) = apply {
        headers.forEach { (name, value) -> header(name, value) }
        val key = normalizeApiKey(apiKey)
        if (bearer && key.isNotEmpty()) header("Authorization", "Bearer $key")
    }

    private fun responses(base: String, model: String, apiKey: String, headers: Map<String, String>, tool: String) =
        Request.Builder().url("$base/responses").common(apiKey, headers).post(JSONObject()
            .put("model", model).put("input", prompt()).put("store", false).put("max_output_tokens", 512)
            .put("tools", JSONArray().put(JSONObject().put("type", tool))).put("tool_choice", "required")
            .toString().toRequestBody(json)).build()

    private fun chat(base: String, model: String, apiKey: String, headers: Map<String, String>, extra: JSONObject.() -> Unit) =
        Request.Builder().url("$base/chat/completions").common(apiKey, headers).post(JSONObject()
            .put("model", model).put("max_tokens", 512)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt())))
            .apply(extra).toString().toRequestBody(json)).build()

    private fun anthropic(base: String, model: String, apiKey: String, headers: Map<String, String>) =
        Request.Builder().url("$base/v1/messages").common(apiKey, headers, bearer = false)
            .header("x-api-key", normalizeApiKey(apiKey)).header("anthropic-version", "2023-06-01")
            .post(JSONObject().put("model", model).put("max_tokens", 512)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt())))
                .put("tools", JSONArray().put(JSONObject().put("type", "web_search_20250305").put("name", "web_search").put("max_uses", 1)))
                .toString().toRequestBody(json)).build()

    companion object {
        private const val MAX_BODY = 1024 * 1024

        /** Searching variants of [model] that the provider's own list names; nothing is guessed beyond that list. */
        fun variants(model: String, listed: Collection<String>): List<String> {
            val names = listed.toSet()
            return listOf("$model:online", "$model-online", "$model-search", "$model-search-preview", "$model:search").filter { it in names && it != model }
        }

        internal fun errorText(body: String): String = runCatching {
            val root = JSONObject(body)
            val error = root.opt("error")
            (error as? JSONObject)?.optString("message")?.takeIf { it.isNotBlank() } ?: (error as? String) ?: root.optString("message")
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: body.take(160)
    }
}

private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
    while (out.size() < limit) { val n = read(buffer, 0, minOf(buffer.size, limit - out.size())); if (n < 0) break; out.write(buffer, 0, n) }
    return out.toByteArray()
}
