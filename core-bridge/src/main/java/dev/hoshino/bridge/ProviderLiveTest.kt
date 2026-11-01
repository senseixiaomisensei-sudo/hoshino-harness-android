// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
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
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * 「测试连接」= 真调一次模型（m2.10）。以前只拉 `/models` 数个数，死模型、零额度、5xx 都会「测试通过」，到聊天才报错。
 *
 * 规则（参照 Mika 3c02a5b / 19c92ac 的「测试并保存」，按本项目改写）：
 * - 候选按 [LiveTestRules.candidates] 排：同一家族里版本新的在前、正式版在前（见 [ModelVersion]），家族之间轮流取，
 *   最多试 [LiveTestRules.MAX_CANDIDATES] 个；
 * - 每个候选发一条极小的请求（OpenAI 兼容 `chat/completions` 或 Anthropic `messages`，`max_tokens` = [MAX_TOKENS]）；
 * - 401、非模型原因的 403、网络错误：立刻判失败（凭据或地址的问题，换模型没用）；
 * - 5xx、零额度/已下线/没开通/没有这个模型、402 余额不足、限流：记下这个模型没通过，接着试下一个；
 * - 全都是 5xx 才判「整家暂时不可用」，否则逐个列出没通过的原因。
 */
enum class LiveKind { Passed, Auth, Network, Server, ModelUnavailable, RateLimited, Other }

/** 一次试调用的结论。[reason] 是给人看的短原因（中文），不含 Key。 */
data class LiveCall(val kind: LiveKind, val status: Int = 0, val reason: String = "")

data class LiveTestResult(val ok: Boolean, val model: String?, val message: String, val tried: List<Pair<String, LiveCall>>)

object LiveTestRules {
    const val MAX_CANDIDATES = 4

    private val NOT_AVAILABLE = listOf(
        "not activated", "not been activated", "model.accessdenied", "model access denied", "accessdenied.unpurchased",
        "not authorized to access this model", "not authorized to use this model", "not authorized to access the model",
        "do not have access to the model", "does not have access to the model", "does not have access to model",
        "do not have access to model", "no access to model", "no permission to access the model", "no permission to use the model",
        "model_not_available", "model is not available", "model not available", "not available for your account",
        "未开通", "没有开通", "尚未开通", "无权限", "没有权限使用", "无权访问", "无权使用",
        "no longer available", "has been retired", "has been discontinued", "has been shut down",
        "已下线", "已停用", "已下架", "已停止服务",
    )
    private val MODEL_RETIRED = Regex("""\bmodels?\b[^;,\n]{0,80}?\b(?:is|has been|was)\s+(?:deprecated|retired)|\bdeprecated\s+models?\b""")
    private val QUOTA_ZERO = Regex(
        """\blimit\s*:\s*0(?![\d.])|"quotavalue"\s*:\s*"?0"?\s*[,}]|(?:doesn't|does not|don't|do not) have (?:a |any )?free quota tier|has no free quota tier""",
    )
    private val NOT_FOUND = listOf(
        "model_not_found", "model not found", "model does not exist", "model not exist", "no such model", "unknown model",
        "not a valid model", "invalid model", "not found for api version", "模型不存在", "找不到模型",
    )
    private val NON_CHAT_WORDS = listOf(
        "embed", "rerank", "tts", "whisper", "transcribe", "speech", "audio", "realtime", "image", "dall", "moderation", "ocr", "asr",
    )

    /** HTTP 状态码 + 响应正文 → 结论。[body] 只用来认类别，原话截短后放进原因。 */
    fun classify(status: Int, body: String): LiveCall {
        val lower = body.lowercase()
        val said = detail(body)
        val modelSide = NOT_AVAILABLE.any { it in lower } || MODEL_RETIRED.containsMatchIn(lower)
        return when {
            status in 200..299 -> LiveCall(LiveKind.Passed, status)
            status == 401 -> LiveCall(LiveKind.Auth, status, "接口拒绝了凭据（HTTP 401），请检查 API Key")
            status == 403 && modelSide -> LiveCall(LiveKind.ModelUnavailable, status, "这个账号用不了该模型（HTTP 403）${quote(said)}")
            status == 403 -> LiveCall(LiveKind.Auth, status, "接口拒绝访问（HTTP 403），请检查 API Key 或账号权限${quote(said)}")
            status == 402 -> LiveCall(LiveKind.ModelUnavailable, status, "账户余额或额度不足（HTTP 402）${quote(said)}")
            status == 429 && QUOTA_ZERO.containsMatchIn(lower) -> LiveCall(LiveKind.ModelUnavailable, status, "该模型在这个账号上的额度为零（HTTP 429）")
            status == 429 -> LiveCall(LiveKind.RateLimited, status, "被限流或额度暂时用完（HTTP 429），请稍后再试")
            status in 500..599 -> LiveCall(LiveKind.Server, status, "服务端出错（HTTP $status）")
            status == 404 -> LiveCall(LiveKind.ModelUnavailable, status,
                if (modelSide || NOT_FOUND.any { it in lower }) "没有这个模型或已下线（HTTP 404）" else "接口返回 HTTP 404（没有这个模型，或 Base URL 不对）")
            status == 400 && modelSide -> LiveCall(LiveKind.ModelUnavailable, status, "这个账号用不了该模型（HTTP 400）${quote(said)}")
            status == 400 && NOT_FOUND.any { it in lower } -> LiveCall(LiveKind.ModelUnavailable, status, "没有这个模型（HTTP 400）")
            // 其余 400 多半是这个模型不接受某个参数：凭据已经过了，换下一个试
            status == 400 -> LiveCall(LiveKind.ModelUnavailable, status, "请求被拒（HTTP 400）${quote(said)}")
            else -> LiveCall(LiveKind.Other, status, "接口返回 HTTP $status${quote(said)}")
        }
    }

    /** 400 说这个模型不收 `max_tokens`、要 `max_completion_tokens`（OpenAI 推理模型）：换参数名再发一次。 */
    fun wantsMaxCompletionTokens(status: Int, body: String): Boolean = status == 400 && "max_completion_tokens" in body.lowercase()

    /**
     * 试调用的先后：去掉看着不是对话模型的（向量、重排、语音、画图…）；按家族（`vendor/` 之后第一个字母段，如 gpt、claude、qwen）分组，
     * 组内版本新的在前（[ModelVersion.NEWEST_FIRST]），组间按列表里首次出现的先后轮流取——一个上游挂了不会把候选全占满。
     */
    fun candidates(ids: List<String>, limit: Int = MAX_CANDIDATES): List<String> {
        val chat = ids.map { it.trim() }.filter { it.isNotEmpty() }.distinct().filterNot(::looksNonChat).ifEmpty { ids.distinct() }
        val families = LinkedHashMap<String, MutableList<String>>()
        for (id in chat) families.getOrPut(familyOf(id)) { mutableListOf() } += id
        val sorted = families.values.map { group -> group.sortedWith { a, b -> ModelVersion.NEWEST_FIRST.compare(ModelVersion.of(a), ModelVersion.of(b)) } }
        val out = mutableListOf<String>()
        var rank = 0
        while (out.size < limit && sorted.any { it.size > rank }) {
            for (g in sorted) if (rank < g.size && out.size < limit) out += g[rank]
            rank++
        }
        return out
    }

    internal fun familyOf(id: String): String =
        id.substringAfterLast('/').lowercase().let { Regex("[a-z]+").find(it)?.value ?: it }

    internal fun looksNonChat(id: String): Boolean =
        id.lowercase().split(Regex("[^a-z0-9]+")).any { part -> NON_CHAT_WORDS.any { part.startsWith(it) } }

    /** 一个个试候选，规则见文件头。 */
    suspend fun run(candidates: List<String>, call: suspend (String) -> LiveCall): LiveTestResult {
        if (candidates.isEmpty()) return LiveTestResult(false, null, "连接失败：没有可测试的模型，请先拉取或手动添加模型", emptyList())
        val tried = mutableListOf<Pair<String, LiveCall>>()
        for (model in candidates) {
            val r = call(model)
            when (r.kind) {
                LiveKind.Passed -> {
                    val skipped = if (tried.isEmpty()) "" else "（先试的 " + tried.joinToString("、") { "${it.first}：${it.second.reason}" } + "）"
                    return LiveTestResult(true, model, "连接成功：已真调 $model 一次并收到回复$skipped", tried + (model to r))
                }
                LiveKind.Auth, LiveKind.Network, LiveKind.Other -> return LiveTestResult(false, null, "连接失败：${r.reason}", tried + (model to r))
                LiveKind.Server, LiveKind.ModelUnavailable, LiveKind.RateLimited -> tried += model to r
            }
        }
        val message = if (tried.all { it.second.kind == LiveKind.Server })
            "连接失败：整家服务暂时不可用，试了 ${tried.size} 个模型都返回服务端错误（" + tried.joinToString("、") { "HTTP ${it.second.status}" } + "），请稍后再试"
        else "连接失败：试了 ${tried.size} 个模型都没调通：\n" + tried.joinToString("\n") { "· ${it.first}：${it.second.reason}" }
        return LiveTestResult(false, null, message, tried)
    }

    private fun quote(said: String) = if (said.isBlank()) "" else "：$said"

    /** 服务端原话：`error.message` / `message` / `msg`，不是 JSON 就是整段；截到 160 字。 */
    internal fun detail(body: String): String {
        val root = runCatching { JSONObject(body) }.getOrNull()
        val err = root?.opt("error")
        val text = (err as? JSONObject)?.optString("message")?.takeIf { it.isNotBlank() } ?: (err as? String)
            ?: root?.optString("message")?.takeIf { it.isNotBlank() } ?: root?.optString("msg")?.takeIf { it.isNotBlank() }
            ?: if (root == null) body else ""
        return text.replace(Regex("\\s+"), " ").trim().take(160)
    }
}

/**
 * 从模型名读出的版本，只用来给同一家族排先后（不评 flash/pro/lite 谁好）。移植自 Mika `ModelOptions.kt` 的 `ModelVersion`。
 * - [numbers]：第一个像版本号的段（`2.5`、`qwen3`、`v3.2`、`4o`；以 b/k/m 结尾的是参数量或上下文长度，不算），紧跟的 1–2 位纯数字段接成小版本（`claude-3-5` → 3.5）；
 * - [date]：其余纯数字段拼成的日期或快照号；
 * - [stage]：0 正式版；1 `latest` 别名；2 预览/试验（preview、exp、beta、alpha、rc…）。
 */
internal data class ModelVersion(val numbers: List<Int>, val stage: Int, val date: Long?) {
    val parsed: Boolean get() = numbers.isNotEmpty() || date != null

    companion object {
        /** 读得出的在前；版本大的在前；同版本正式版、别名、预览依次；没写日期的在前，再按日期新的在前。 */
        val NEWEST_FIRST: Comparator<ModelVersion> = Comparator { a, b ->
            when {
                a.parsed != b.parsed -> if (a.parsed) -1 else 1
                !a.parsed -> 0
                else -> compareNumbers(b.numbers, a.numbers).takeIf { it != 0 }
                    ?: a.stage.compareTo(b.stage).takeIf { it != 0 }
                    ?: compareValues(b.date ?: Long.MAX_VALUE, a.date ?: Long.MAX_VALUE)
            }
        }

        fun of(id: String): ModelVersion {
            val parts = id.substringAfterLast('/').lowercase().split(Regex("[^a-z0-9.]+")).filter { it.isNotEmpty() }
            var numbers = emptyList<Int>()
            var at = -1
            for ((i, p) in parts.withIndex()) {
                val m = VERSION.matchEntire(p) ?: continue
                if (m.groupValues[3] in SIZE_SUFFIXES) continue
                numbers = m.groupValues[2].split('.').map { it.toInt() }
                at = i
                break
            }
            var end = at + 1
            if (at >= 0 && numbers.size == 1) {
                while (end < parts.size && numbers.size < 3 && MINOR.matches(parts[end])) numbers = numbers + parts[end++].toInt()
            }
            val rest = parts.filterIndexed { i, _ -> i < at || i >= end }
            val stage = when {
                rest.any { p -> PRE_RELEASE.any { p.startsWith(it) } } -> 2
                "latest" in rest -> 1
                else -> 0
            }
            return ModelVersion(numbers, stage, dateOf(rest.filter { p -> p.all { it.isDigit() } }))
        }

        private fun dateOf(digits: List<String>): Long? {
            if (digits.isEmpty()) return null
            val year = digits.firstOrNull { it.length == 4 && it.toInt() in 1990..2099 }
            if (year != null) {
                val md = (digits - year).take(2).map { it.take(2).toLong() }
                return year.toLong() * 10_000 + (md.getOrNull(0) ?: 0) * 100 + (md.getOrNull(1) ?: 0)
            }
            val joined = digits.joinToString("").take(14)
            return if (digits.size == 1 && joined.length == 6) 20_000_000 + joined.toLong() else joined.toLong()
        }

        private fun compareNumbers(a: List<Int>, b: List<Int>): Int {
            for (i in 0 until maxOf(a.size, b.size)) {
                val c = (a.getOrNull(i) ?: 0).compareTo(b.getOrNull(i) ?: 0)
                if (c != 0) return c
            }
            return 0
        }

        private val VERSION = Regex("([a-z]*)((?:0|[1-9]\\d?)(?:\\.\\d{1,3})*)([a-z]?)")
        private val MINOR = Regex("[1-9]?\\d")
        private val SIZE_SUFFIXES = setOf("b", "k", "m")
        private val PRE_RELEASE = listOf("preview", "exp", "beta", "alpha", "rc", "dev", "test")
    }
}

/**
 * 真发请求的那一半。超时沿用 m2.9.5 的设置（连接 6 s、不走代理、不跟随鉴权重定向），只把读取放宽到 30 s、整次 45 s：
 * 局域网 Ollama 冷启动加载模型时第一条回复可能要十几秒。网络错误用 m2.9.5 的 [describeNetworkError] 给中文原因。
 */
class ProviderLiveTest(
    private val http: OkHttpClient = OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).followRedirects(false)
        .connectTimeout(6, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).build(),
) {
    /** [type] 是 `anthropic` 或 `openai-compatible`；[models] 为空时由调用方先拉列表。 */
    suspend fun test(type: String, baseURL: String, key: String, headers: Map<String, String>, models: List<String>): LiveTestResult {
        val input = normalizeProviderInput(baseURL)
        val uri = runCatching { java.net.URI(input) }.getOrNull()
        require(uri != null && uri.scheme in listOf("http", "https") && uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "Base URL 格式不对，应类似 http://192.168.1.5:11434/v1"
        }
        return LiveTestRules.run(LiveTestRules.candidates(models)) { model -> call(type, input, uri, key, headers, model) }
    }

    suspend fun call(type: String, input: String, uri: java.net.URI, key: String, headers: Map<String, String>, model: String): LiveCall = withContext(Dispatchers.IO) {
        try {
            val first = send(type, input, key, headers, model, completionTokens = false)
            if (type != "anthropic" && LiveTestRules.wantsMaxCompletionTokens(first.first, first.second)) {
                val again = send(type, input, key, headers, model, completionTokens = true)
                LiveTestRules.classify(again.first, again.second)
            } else LiveTestRules.classify(first.first, first.second)
        } catch (e: CancellationException) {
            throw e
        } catch (e: java.io.IOException) {
            LiveCall(LiveKind.Network, 0, describeNetworkError(e, uri))
        }
    }

    private fun send(type: String, input: String, key: String, headers: Map<String, String>, model: String, completionTokens: Boolean): Pair<Int, String> {
        val base = providerBaseURL(type, input)
        val credential = normalizeApiKey(key)
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", PROMPT))
        val (url, body) = if (type == "anthropic") {
            "$base/v1/messages" to JSONObject().put("model", model).put("max_tokens", MAX_TOKENS).put("messages", messages)
        } else {
            "$base/chat/completions" to JSONObject().put("model", model).put("messages", messages).put("stream", false)
                .put(if (completionTokens) "max_completion_tokens" else "max_tokens", MAX_TOKENS)
        }
        val request = Request.Builder().url(url).header("Accept", "application/json").post(body.toString().toRequestBody(JSON_TYPE))
        headers.forEach { (name, value) -> request.header(name, value) }
        if (type == "anthropic") request.header("x-api-key", credential).header("anthropic-version", "2023-06-01")
        else if (credential.isNotEmpty()) request.header("Authorization", "Bearer $credential")
        return http.newCall(request.build()).execute().use { response -> response.code to readCapped(response.body) }
    }

    private fun readCapped(body: okhttp3.ResponseBody?): String {
        body ?: return ""
        val output = ByteArrayOutputStream()
        body.byteStream().use { input ->
            val buffer = ByteArray(8192)
            while (output.size() < BODY_CAP) {
                val n = input.read(buffer); if (n < 0) break
                output.write(buffer, 0, minOf(n, BODY_CAP - output.size()))
            }
        }
        return String(output.toByteArray(), Charsets.UTF_8)
    }

    companion object {
        /** 只要模型回一点点；有的服务端要求至少 16。 */
        const val MAX_TOKENS = 16
        const val PROMPT = "ping"
        private const val BODY_CAP = 64 * 1024
        private val JSON_TYPE = "application/json".toMediaType()
    }
}
