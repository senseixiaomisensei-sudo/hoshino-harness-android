// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
// (voice/Transcriber.kt 与 core/model/ModelHttp.kt 的错误映射；kotlinx.serialization 换成 org.json，另加语言参数退让、记住的写法失灵时自动重探。)
package dev.hoshino.bridge.voice

import dev.hoshino.bridge.providerBaseURL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

/** 转写失败的原因（界面按种类给中文说明）。[detail] 已去掉 Key，只截一小段服务端原话。 */
sealed class SttError(val detail: String) : Exception(detail) {
    class Auth(status: Int, detail: String) : SttError("HTTP $status $detail")
    class Quota(status: Int, detail: String) : SttError("HTTP $status $detail")
    /** 路径或模型不存在（404/405）：换一种写法可能就行。 */
    class NotFound(status: Int, detail: String) : SttError("HTTP $status $detail")
    /** 请求不合（400/413/415/422）：换一种写法可能就行。 */
    class BadRequest(status: Int, detail: String) : SttError("HTTP $status $detail")
    class Server(status: Int, detail: String) : SttError("HTTP $status $detail")
    class Network(detail: String) : SttError(detail)
    /** 回了 2xx 但不是这种写法的格式。 */
    class BadResponse(detail: String) : SttError(detail)

    /** 只有「这个接口不是这么用的」才值得换下一种写法；Key、额度、服务端、网络的问题换写法也没用。 */
    val triesNext get() = this is NotFound || this is BadRequest || this is BadResponse

    fun reason(): String = when (this) {
        is Auth -> "服务商拒绝了 API Key（$detail）"
        is Quota -> "额度不够或请求太频繁（$detail）"
        is NotFound -> "这个服务商或模型不提供语音转文字（$detail）"
        is BadRequest -> "服务商不接受这段音频或这种写法（$detail）"
        is Server -> "服务商出错了（$detail）"
        is Network -> "网络不通（$detail）"
        is BadResponse -> "服务商回的内容看不懂（$detail）"
    }
}

/**
 * 用服务商的模型把一段 WAV 转成文字，写法见 [TranscribeMethod]。只按协议发请求，不按模型名特判。
 * 每次请求最多等 [SINGLE_SEGMENT_TIMEOUT_MS]（与单段 30 秒上限一致）。
 */
class Transcriber(
    private val http: OkHttpClient = defaultClient(),
) {
    suspend fun transcribe(ep: SttEndpoint, model: String, method: TranscribeMethod, wav: ByteArray, language: String?): String =
        withContext(Dispatchers.IO) {
            try {
                send(ep, model, method, wav, language)
            } catch (e: SttError) {
                // 有的网关不认识 language 参数或这个语言代码（400/422）：去掉语言再试一次，让模型自己判断
                if (e is SttError.BadRequest && !language.isNullOrBlank() && method == TranscribeMethod.AudioTranscriptions) send(ep, model, method, wav, null)
                else throw e
            }
        }

    /**
     * 按记住的写法转；那种写法不灵了（404/400/格式不对）就重新探测一次，探到新写法时用它转并回调 [onRelearn]，
     * 由调用方存下。Key、额度、网络的错误原样抛出。
     */
    suspend fun transcribeHealing(
        ep: SttEndpoint, model: String, method: TranscribeMethod, wav: ByteArray, language: String?,
        onRelearn: (TranscribeMethod) -> Unit = {},
    ): String = try {
        transcribe(ep, model, method, wav, language)
    } catch (e: SttError) {
        if (!e.triesNext) throw e
        val other = detect(ep, model, exclude = setOf(method))
        onRelearn(other)
        transcribe(ep, model, other, wav, language)
    }

    /**
     * 选模型时试转：用 [Wav.probe] 内置短音频按 [order] 依次试，返回第一个通过的写法；都不行抛最后一个错误。
     * 通过 = 回 2xx 且解析得出文字字段（空串也算）。Key/额度的问题再试别的写法也没用，直接抛。
     */
    suspend fun detect(ep: SttEndpoint, model: String, exclude: Set<TranscribeMethod> = emptySet()): TranscribeMethod {
        val wav = Wav.probe()
        var last: SttError? = null
        for (method in order(ep) - exclude) {
            try {
                transcribe(ep, model, method, wav, null)
                return method
            } catch (e: SttError) {
                last = e
                if (!e.triesNext) throw e
            }
        }
        throw last ?: SttError.NotFound(404, "没有可试的写法")
    }

    private fun send(ep: SttEndpoint, model: String, method: TranscribeMethod, wav: ByteArray, language: String?): String {
        val request = request(ep, model, method, wav, language)
        val (status, body) = try {
            http.newCall(request).execute().use { r -> r.code to (r.body?.string()?.take(MAX_BODY) ?: "") }
        } catch (e: IOException) {
            throw SttError.Network(e.javaClass.simpleName + (e.message?.let { ": " + scrub(it, ep) } ?: ""))
        }
        if (status !in 200..299) throw errorOf(status, scrub(body, ep).take(160))
        return parse(method, body)
    }

    internal fun request(ep: SttEndpoint, model: String, method: TranscribeMethod, wav: ByteArray, language: String?): Request {
        val base = providerBaseURL(ep.type, ep.baseUrl).trimEnd('/')
        return when (method) {
            TranscribeMethod.AudioTranscriptions -> {
                val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("file", "audio.wav", wav.toRequestBody(WAV))
                    .addFormDataPart("model", model)
                    .addFormDataPart("response_format", "json")
                    .apply { if (!language.isNullOrBlank()) addFormDataPart("language", language) }
                    .build()
                builder("$base/audio/transcriptions", ep, gemini = false).post(body).build()
            }
            TranscribeMethod.ChatInputAudio, TranscribeMethod.ChatInputAudioUri, TranscribeMethod.ChatInputAudioOnly -> {
                val data = if (method == TranscribeMethod.ChatInputAudio) base64(wav) else "data:audio/wav;base64,${base64(wav)}"
                val content = JSONArray().put(JSONObject().put("type", "input_audio").put("input_audio", JSONObject().put("data", data).put("format", "wav")))
                // 专做转写的模型不收文字块；通用模型没有这句指令会当成聊天回话
                if (method != TranscribeMethod.ChatInputAudioOnly) content.put(JSONObject().put("type", "text").put("text", instruction(language)))
                val payload = JSONObject().put("model", model).put("stream", false)
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
                builder("$base/chat/completions", ep, gemini = false).post(payload.toString().toRequestBody(JSON)).build()
            }
            TranscribeMethod.GeminiInline -> {
                val parts = JSONArray()
                    .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "audio/wav").put("data", base64(wav))))
                    .put(JSONObject().put("text", instruction(language)))
                val payload = JSONObject().put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
                builder("${geminiRoot(ep.baseUrl)}/v1beta/models/$model:generateContent", ep, gemini = true).post(payload.toString().toRequestBody(JSON)).build()
            }
        }
    }

    private fun builder(url: String, ep: SttEndpoint, gemini: Boolean): Request.Builder = Request.Builder().url(url).apply {
        ep.headers.forEach { (k, v) -> header(k, v) }
        val key = ep.key?.trim().orEmpty()
        if (key.isNotEmpty()) { if (gemini) header("x-goog-api-key", key) else header("Authorization", "Bearer $key") }
    }

    private fun parse(method: TranscribeMethod, body: String): String {
        val trimmed = body.trim()
        val root = runCatching { JSONObject(trimmed) }.getOrNull()
        val text: String? = when (method) {
            TranscribeMethod.AudioTranscriptions ->
                // response_format=json 应回 {"text":…}；有的网关无视它直接回纯文本
                root?.let { o -> o.opt("text") as? String } ?: trimmed.takeIf { root == null && !trimmed.startsWith("{") && !trimmed.startsWith("<") }
            TranscribeMethod.ChatInputAudio, TranscribeMethod.ChatInputAudioUri, TranscribeMethod.ChatInputAudioOnly -> {
                val msg = root?.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                when (val c = msg?.opt("content")) {
                    is String -> c
                    is JSONArray -> (0 until c.length()).mapNotNull { c.optJSONObject(it)?.opt("text") as? String }.joinToString("")
                    else -> if (msg != null && msg.isNull("content")) "" else null
                }
            }
            TranscribeMethod.GeminiInline -> {
                val cand = root?.optJSONArray("candidates")?.optJSONObject(0)
                // 没有任何文字块（静音时只回结束原因）按空串；连候选都没有才算格式不对
                if (cand == null) null else {
                    val parts = cand.optJSONObject("content")?.optJSONArray("parts")
                    if (parts == null) "" else (0 until parts.length()).mapNotNull { parts.optJSONObject(it)?.opt("text") as? String }.joinToString("")
                }
            }
        }
        return clean(text ?: throw SttError.BadResponse("没有文字字段：" + trimmed.take(120)))
    }

    private fun scrub(text: String, ep: SttEndpoint): String {
        var out = text
        ep.key?.takeIf { it.length >= 4 }?.let { out = out.replace(it, "***") }
        ep.headers.values.filter { it.length >= 8 }.forEach { out = out.replace(it, "***") }
        return out
    }

    private fun base64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)

    companion object {
        private val JSON = "application/json".toMediaType()
        private val WAV = "audio/wav".toMediaType()
        private const val MAX_BODY = 256 * 1024
        const val SINGLE_SEGMENT_TIMEOUT_MS = 30_000L

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(SINGLE_SEGMENT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(SINGLE_SEGMENT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()

        /** 发给模型的指令：只要原话，不要解释（模型面向英文）；知道语言时说一句，减少中英混说时被翻译。 */
        fun instruction(language: String?): String =
            "Transcribe this recording word for word in the language spoken" +
                (if (!language.isNullOrBlank()) " (most likely '$language')" else "") +
                ". Do not translate. Output only the transcript text."

        /** 有 Key 的 OpenAI 兼容服务商能试；Anthropic 不收音频；ChatGPT 订阅登录的不开放转写接口。 */
        fun supports(ep: SttEndpoint): Boolean = ep.type != "anthropic" && ep.type != "openai-codex"

        /** 探测顺序：专用转写接口 → Gemini 原生（谷歌的地址）→ 对话接口三种写法；只发音频不带指令的放最后，免得通用模型把录音当聊天回一段话被误选。 */
        fun order(ep: SttEndpoint): List<TranscribeMethod> = buildList {
            add(TranscribeMethod.AudioTranscriptions)
            if (isGoogle(ep.baseUrl)) add(TranscribeMethod.GeminiInline)
            add(TranscribeMethod.ChatInputAudio)
            add(TranscribeMethod.ChatInputAudioUri)
            add(TranscribeMethod.ChatInputAudioOnly)
        }

        fun isGoogle(baseUrl: String): Boolean = runCatching { java.net.URI(baseUrl.trim()).host.orEmpty() }.getOrDefault("").endsWith("generativelanguage.googleapis.com")

        /** Gemini 原生接口的根（scheme://host[:port]），OpenAI 兼容地址里的 /v1beta/openai 去掉。 */
        fun geminiRoot(baseUrl: String): String {
            val u = java.net.URI(baseUrl.trim())
            return java.net.URI(u.scheme, null, u.host, u.port, null, null, null).toString().trimEnd('/')
        }

        fun errorOf(status: Int, detail: String): SttError = when (status) {
            401, 403 -> SttError.Auth(status, detail)
            402, 429 -> SttError.Quota(status, detail)
            404, 405, 501 -> SttError.NotFound(status, detail)
            400, 413, 415, 422 -> SttError.BadRequest(status, detail)
            in 500..599 -> SttError.Server(status, detail)
            else -> SttError.BadRequest(status, detail)
        }

        private val SPEECH_TOKENS = listOf("asr", "transcribe", "whisper", "paraformer", "sensevoice", "stt", "speech", "audio", "voice", "omni")

        /** 列表里像语音模型的：名字按非字母数字切段，有一段以 asr、whisper、transcribe 等开头。 */
        fun looksLikeSpeech(model: String): Boolean =
            model.lowercase().split(Regex("[^a-z0-9]+")).any { seg -> SPEECH_TOKENS.any { seg.startsWith(it) } }

        /** 去掉模型常加的外壳：首尾空白、整段引号、「Transcript:」前缀。 */
        fun clean(text: String): String {
            var t = text.trim()
            for (p in listOf("transcript:", "transcription:", "转写：", "转写:")) if (t.lowercase().startsWith(p)) t = t.substring(p.length).trim()
            if (t.length >= 2 && ((t.first() == '"' && t.last() == '"') || (t.first() == '“' && t.last() == '”'))) t = t.substring(1, t.length - 1).trim()
            return t
        }
    }
}
