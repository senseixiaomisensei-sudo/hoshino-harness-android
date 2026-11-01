// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
// (voice/SpeechChoice.kt、SpeechResolver.kt、SpeechSession.kt；kotlinx.serialization 换成 org.json，服务商换成本仓库的 StoredProvider 形态。)
package dev.hoshino.bridge.voice

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.receiveAsFlow
import org.json.JSONObject

/** 用户为「语音转文字」做的选择。具体用哪个引擎由 [SpeechResolver] 结合手机能力再定。 */
sealed interface SpeechChoice {
    /** 没选过：有手机自带识别就用它。 */
    data object Auto : SpeechChoice

    /** 明确选了手机自带识别。 */
    data object System : SpeechChoice

    /** 不用语音输入。 */
    data object Off : SpeechChoice

    /** 用某个服务商的某个模型转写，[method] 是试转时探测出、记住的写法。 */
    data class Provider(val providerId: String, val model: String, val method: TranscribeMethod) : SpeechChoice

    /** 预留：本机开源模型（如 sherpa-onnx + SenseVoice）离线转写。本批不实现引擎，解析为不可用。 */
    data class Offline(val modelId: String) : SpeechChoice
}

/**
 * 服务商转写音频的写法。存的是名字：只能往后加、不能改名或删，旧设置才读得回来。
 * - [AudioTranscriptions]：OpenAI 兼容 `POST /audio/transcriptions`（multipart，Whisper / gpt-4o-transcribe / SenseVoice 网关等）。
 * - [ChatInputAudio]：对话接口 `input_audio`，data 放裸 base64，附一句转写指令。
 * - [ChatInputAudioUri]：同上，data 换成 `data:audio/wav;base64,…`（有的服务只认这种）。
 * - [ChatInputAudioOnly]：data URI、只发音频不带指令（专做转写的模型不收文字块）。
 * - [GeminiInline]：Gemini 原生 `generateContent` + `inline_data`。
 */
enum class TranscribeMethod { AudioTranscriptions, ChatInputAudio, GeminiInline, ChatInputAudioUri, ChatInputAudioOnly }

/** 选择的存取格式（一段 JSON）。读不出来一律当 [SpeechChoice.Auto]，由调用方存进 SharedPreferences。 */
object SpeechChoiceCodec {
    fun encode(choice: SpeechChoice): String = JSONObject().apply {
        when (choice) {
            SpeechChoice.Auto -> put("type", "auto")
            SpeechChoice.System -> put("type", "system")
            SpeechChoice.Off -> put("type", "off")
            is SpeechChoice.Provider -> put("type", "provider").put("providerId", choice.providerId).put("model", choice.model).put("method", choice.method.name)
            is SpeechChoice.Offline -> put("type", "offline").put("model", choice.modelId)
        }
    }.toString()

    fun decode(raw: String?): SpeechChoice {
        if (raw.isNullOrBlank()) return SpeechChoice.Auto
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return SpeechChoice.Auto
        return when (o.optString("type")) {
            "auto" -> SpeechChoice.Auto
            "system" -> SpeechChoice.System
            "off" -> SpeechChoice.Off
            "provider" -> {
                val id = o.optString("providerId").takeIf { it.isNotEmpty() }
                val model = o.optString("model").takeIf { it.isNotEmpty() }
                val method = TranscribeMethod.entries.firstOrNull { it.name == o.optString("method") }
                if (id == null || model == null || method == null) SpeechChoice.Auto else SpeechChoice.Provider(id, model, method)
            }
            "offline" -> o.optString("model").takeIf { it.isNotEmpty() }?.let { SpeechChoice.Offline(it) } ?: SpeechChoice.Auto
            else -> SpeechChoice.Auto
        }
    }
}

/**
 * 手机自带识别的能力：系统说有没有（[available]）、是不是在本机识别（[onDevice]），
 * 以及在这台手机上试过时理不理我们（[responsive]；请求发出去 3 秒一个回调都没有就记成 false）。
 */
data class SystemCapability(val available: Boolean, val onDevice: Boolean, val responsive: Boolean = true) {
    val usable get() = available && responsive
}

/** 服务商在转写这里需要的全部信息（不含任何界面概念）。[key] 只在内存里，不写日志。 */
data class SttEndpoint(
    val providerId: String,
    val name: String,
    /** `openai-compatible` / `anthropic` / `openai-codex`（与 StoredProvider.type 一致）。 */
    val type: String,
    val baseUrl: String,
    val key: String?,
    val headers: Map<String, String> = emptyMap(),
    val models: List<String> = emptyList(),
) {
    override fun toString() = "SttEndpoint($providerId, $type, $baseUrl, key=${if (key.isNullOrEmpty()) "none" else "***"})"
}

/** 这一次实际要用的识别引擎；选的东西不在了就给出 [Unavailable] 和原因。 */
sealed interface SpeechEngineSpec {
    data class System(val onDevice: Boolean) : SpeechEngineSpec
    data class Provider(val endpoint: SttEndpoint, val model: String, val method: TranscribeMethod) : SpeechEngineSpec
    data class Unavailable(val why: Why) : SpeechEngineSpec

    enum class Why { Off, NoSystemNoModel, SystemGone, SystemNoResponse, ProviderGone, ProviderUnsupported, OfflineNotInstalled }
}

/** 把用户的选择、手机能力和现有服务商对起来，得出这次用哪个引擎。纯函数。 */
object SpeechResolver {
    fun resolve(choice: SpeechChoice, system: SystemCapability, providers: List<SttEndpoint>): SpeechEngineSpec = when (choice) {
        SpeechChoice.Off -> SpeechEngineSpec.Unavailable(SpeechEngineSpec.Why.Off)
        // 自带识别不响应时照实说、指去设置，不悄悄改用要花钱的服务商模型
        SpeechChoice.Auto -> when {
            system.usable -> SpeechEngineSpec.System(system.onDevice)
            system.available -> SpeechEngineSpec.Unavailable(SpeechEngineSpec.Why.SystemNoResponse)
            else -> SpeechEngineSpec.Unavailable(SpeechEngineSpec.Why.NoSystemNoModel)
        }
        SpeechChoice.System -> when {
            system.usable -> SpeechEngineSpec.System(system.onDevice)
            system.available -> SpeechEngineSpec.Unavailable(SpeechEngineSpec.Why.SystemNoResponse)
            else -> SpeechEngineSpec.Unavailable(SpeechEngineSpec.Why.SystemGone)
        }
        is SpeechChoice.Provider -> {
            val p = providers.firstOrNull { it.providerId == choice.providerId }
            when {
                p == null -> SpeechEngineSpec.Unavailable(SpeechEngineSpec.Why.ProviderGone)
                !Transcriber.supports(p) -> SpeechEngineSpec.Unavailable(SpeechEngineSpec.Why.ProviderUnsupported)
                else -> SpeechEngineSpec.Provider(p, choice.model, choice.method)
            }
        }
        is SpeechChoice.Offline -> SpeechEngineSpec.Unavailable(SpeechEngineSpec.Why.OfflineNotInstalled)
    }
}

/** 一次按住说话的会话事件。两种引擎（手机自带识别、服务商转写）对界面长一个样。 */
sealed interface SpeechEvent {
    /** 0..1，画波形。 */
    data class Level(val value: Float) : SpeechEvent

    /** 到此刻听到的全文：[tail] 是末尾还可能变的几个字，其余是 [stable]。 */
    data class Partial(val stable: String, val tail: String) : SpeechEvent

    /** 服务商：松开后在转最后一段。 */
    data object Converting : SpeechEvent

    /** 提示（不结束会话）。 */
    data class Hint(val kind: SpeechHint) : SpeechEvent

    /** 最终文字；空串 = 没听清。 */
    data class Final(val text: String) : SpeechEvent

    data class Failed(val why: SpeechFailure) : SpeechEvent
}

enum class SpeechHint {
    /** 开始录音 3 秒，麦克风送来的全是 0：多半被别的 App 占着或被系统静音。 */
    MicSilent,
    /** 松开后 3 秒还没转完：网络慢，最多再等到 30 秒上限。 */
    Slow,
    /** 单段到了 30 秒上限，自动切成下一段。 */
    SegmentCapped,
    /** 记住的写法不灵了，已自动重新探测并换用新写法。 */
    MethodRelearned,
}

sealed interface SpeechFailure {
    data object NoPermission : SpeechFailure
    data object Busy : SpeechFailure
    data object Network : SpeechFailure
    data object MicUnavailable : SpeechFailure
    /** 另一处（输入栏 / 悬浮胶囊）正在用麦克风。 */
    data object InUse : SpeechFailure
    data class Provider(val error: SttError) : SpeechFailure
    data class Unavailable(val why: SpeechEngineSpec.Why) : SpeechFailure
}

interface SpeechSession {
    /** 只给一个收集者；[SpeechEvent.Final]/[SpeechEvent.Failed] 之后或 [cancel] 之后流就结束。 */
    val events: Flow<SpeechEvent>

    /** 按着时能边说边出字（系统识别逐字；服务商按停顿分段转写）。 */
    val live: Boolean

    /** 松开后还要等服务商转最后一段（界面松开当下就显示「转文字…」）。 */
    val convertsAfterRelease: Boolean get() = false

    /** 松开：收尾并给 Final。 */
    fun release()

    /** 丢掉；幂等。 */
    fun cancel()
}

/**
 * 会话的事件出口：无界缓冲，界面晚一点开始收也不丢；结束事件发出后关上。
 * 取消时连同还没被收走的一起丢掉——用户已经不要这次了，迟到的结果不该再冒出来。
 */
class SessionEvents {
    private val channel = Channel<SpeechEvent>(Channel.UNLIMITED)

    @Volatile var cancelled = false
        private set

    @Volatile var finished = false
        private set

    val flow: Flow<SpeechEvent> = channel.receiveAsFlow().filter { !cancelled }

    fun send(e: SpeechEvent) {
        if (!finished && !cancelled) channel.trySend(e)
    }

    @Synchronized
    fun finish(e: SpeechEvent): Boolean {
        if (finished || cancelled) return false
        finished = true
        channel.trySend(e)
        channel.close()
        return true
    }

    @Synchronized
    fun cancel(): Boolean {
        if (cancelled) return false
        cancelled = true
        channel.close()
        return true
    }
}

/** 一开始就用不了的会话（没权限、引擎不可用、麦克风被另一处占着）：只发一个 Failed。 */
class FailedSession(why: SpeechFailure) : SpeechSession {
    private val out = SessionEvents().also { it.finish(SpeechEvent.Failed(why)) }
    override val events: Flow<SpeechEvent> = out.flow
    override val live = false
    override fun release() {}
    override fun cancel() { out.cancel() }
}
