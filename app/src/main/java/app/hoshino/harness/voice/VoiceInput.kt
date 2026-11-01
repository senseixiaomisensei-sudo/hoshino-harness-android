// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
// (voice/VoiceInput.kt、SpeechChoice.kt 的 SpeechSettings；另加麦克风单占用、写法自动重探后记住。)
package app.hoshino.harness.voice

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import dev.hoshino.bridge.ProviderStore
import dev.hoshino.bridge.voice.FailedSession
import dev.hoshino.bridge.voice.MicGate
import dev.hoshino.bridge.voice.ProviderSpeech
import dev.hoshino.bridge.voice.SpeechChoice
import dev.hoshino.bridge.voice.SpeechChoiceCodec
import dev.hoshino.bridge.voice.SpeechEngineSpec
import dev.hoshino.bridge.voice.SpeechEvent
import dev.hoshino.bridge.voice.SpeechFailure
import dev.hoshino.bridge.voice.SpeechHint
import dev.hoshino.bridge.voice.SpeechLang
import dev.hoshino.bridge.voice.SpeechResolver
import dev.hoshino.bridge.voice.SpeechSession
import dev.hoshino.bridge.voice.SttEndpoint
import dev.hoshino.bridge.voice.SystemSpeechRules
import dev.hoshino.bridge.voice.Transcriber
import dev.hoshino.bridge.voice.TranscribeMethod
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onCompletion
import java.util.Locale

/**
 * 「语音转文字」的选择单独存（和对话、子代理、压缩、视觉四种用途分开）。另记「手机自带识别不响应」：
 * 连着 [SystemSpeechRules.SILENT_STRIKES] 次一个回调都没有才记上；用户重新选「自动」或「手机自带」时清掉。
 */
class SpeechSettings(private val prefs: SharedPreferences) {
    private val _choice = MutableStateFlow(SpeechChoiceCodec.decode(prefs.getString(KEY, null)))
    val choice: StateFlow<SpeechChoice> = _choice.asStateFlow()
    private val _systemSilent = MutableStateFlow(prefs.getBoolean(SILENT, false))
    val systemSilent: StateFlow<Boolean> = _systemSilent.asStateFlow()

    fun set(choice: SpeechChoice) {
        val retry = choice == SpeechChoice.System || choice == SpeechChoice.Auto
        val edit = prefs.edit().putString(KEY, SpeechChoiceCodec.encode(choice))
        if (retry) edit.remove(SILENT).remove(STRIKES)
        edit.apply()
        _choice.value = choice
        if (retry) _systemSilent.value = false
    }

    fun noteSystemSilent() {
        val strikes = prefs.getInt(STRIKES, 0) + 1
        val edit = prefs.edit().putInt(STRIKES, strikes)
        if (strikes >= SystemSpeechRules.SILENT_STRIKES) edit.putBoolean(SILENT, true)
        edit.apply()
        if (strikes >= SystemSpeechRules.SILENT_STRIKES) _systemSilent.value = true
    }

    fun noteSystemAnswered() { if (prefs.getInt(STRIKES, 0) != 0) prefs.edit().remove(STRIKES).apply() }

    companion object {
        private const val KEY = "speech_choice"
        private const val SILENT = "speech_system_silent"
        private const val STRIKES = "speech_system_silent_strikes"
    }
}

/**
 * 语音输入的入口：对话输入栏和悬浮胶囊只经这里拿会话。先按选择、手机能力和现有服务商定下引擎（[spec]），
 * 再开一次会话（[start]）。麦克风同一时刻只给一处（[MicGate]）：会话结束或取消时自动放开。
 */
class VoiceInput private constructor(context: Context) {
    private val context = context.applicationContext
    val settings = SpeechSettings(this.context.getSharedPreferences("voice_input", Context.MODE_PRIVATE))
    private val store = ProviderStore(this.context)
    val transcriber = Transcriber()

    /** 能做转写的服务商（已启用、非 Anthropic、非 ChatGPT 订阅）；Key 只在内存里。 */
    fun endpoints(keys: Boolean = true): List<SttEndpoint> = runCatching {
        store.list().filter { it.metadata.optBoolean("enabled", true) }.map { p ->
            val headers = store.runtimeJson(p).optJSONObject("headers")
            SttEndpoint(
                providerId = p.id,
                name = p.metadata.optString("name", p.id),
                type = p.type,
                baseUrl = p.baseURL,
                key = if (keys) runCatching { store.resolve(p.credentialRef) }.getOrNull() else null,
                headers = headers?.keys()?.asSequence()?.associateWith { headers.optString(it) }.orEmpty(),
                models = p.models,
            )
        }
    }.getOrDefault(emptyList())

    fun capability() = SystemSpeech.capability(context, settings.systemSilent.value)

    /** 只定引擎，不解密 Key（按下时在主线程调，要快）。 */
    fun spec(): SpeechEngineSpec = SpeechResolver.resolve(settings.choice.value, capability(), endpoints(keys = false))

    fun hasPermission() = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** 识别语言：跟随系统语言，规整成「语言-地区」。 */
    fun languageTag(): String = SpeechLang.fromLocale(Locale.getDefault())

    /**
     * 开一次会话，按下时在主线程调。[owner] 是哪一处在用（输入栏 / 胶囊），用来占麦克风。
     * 没有录音权限、引擎用不了、麦克风被另一处占着时给出只发一个 Failed 的会话，界面按同一套处理。
     */
    fun start(owner: Any): SpeechSession {
        val spec = spec()
        if (spec is SpeechEngineSpec.Unavailable) return FailedSession(SpeechFailure.Unavailable(spec.why))
        if (!hasPermission()) return FailedSession(SpeechFailure.NoPermission)
        if (!MicGate.acquire(owner)) return FailedSession(SpeechFailure.InUse)
        val tag = languageTag()
        val session: SpeechSession = when (spec) {
            is SpeechEngineSpec.System -> SystemSpeechSession(context, spec.onDevice, tag, settings::noteSystemSilent, settings::noteSystemAnswered)
            is SpeechEngineSpec.Provider -> {
                val ep = spec.endpoint.copy(key = runCatching { store.resolve(spec.endpoint.let { e -> store.list().first { it.id == e.providerId }.credentialRef }) }.getOrNull())
                val iso = SpeechLang.isoCode(tag)
                var holder: ProviderSpeech? = null
                ProviderSpeech(PcmRecorder(), { wav ->
                    transcriber.transcribeHealing(ep, spec.model, spec.method, wav, iso) { learned ->
                        relearned(spec, learned)
                        holder?.hint(SpeechHint.MethodRelearned)
                    }
                }).also { holder = it }
            }
            is SpeechEngineSpec.Unavailable -> FailedSession(SpeechFailure.Unavailable(spec.why))
        }
        return Gated(session, owner)
    }

    private fun relearned(spec: SpeechEngineSpec.Provider, method: TranscribeMethod) {
        val cur = settings.choice.value
        if (cur is SpeechChoice.Provider && cur.providerId == spec.endpoint.providerId && cur.model == spec.model) settings.set(cur.copy(method = method))
    }

    /** 会话结束（Final/Failed）或取消时放开麦克风。 */
    private class Gated(private val inner: SpeechSession, private val owner: Any) : SpeechSession {
        override val events: Flow<SpeechEvent> = inner.events.onCompletion { MicGate.release(owner) }
        override val live get() = inner.live
        override val convertsAfterRelease get() = inner.convertsAfterRelease
        override fun release() = inner.release()
        override fun cancel() { inner.cancel(); MicGate.release(owner) }
    }

    companion object {
        @Volatile private var instance: VoiceInput? = null
        fun of(context: Context): VoiceInput = instance ?: synchronized(this) { instance ?: VoiceInput(context).also { instance = it } }
    }
}
