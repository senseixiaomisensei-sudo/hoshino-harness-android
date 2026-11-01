// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
// (voice/SystemSpeech.kt 的 failureOf/afterError/afterSilence/languageMissing；错误码写成数值，纯 JVM 可测。)
package dev.hoshino.bridge.voice

/**
 * 手机自带识别（android.speech.SpeechRecognizer）的错误码怎么处理。错误码写成数值（与 SDK 常量一致），
 * 这样规则留在纯逻辑包里单测；App 侧只负责把回调转进来。
 */
object SystemSpeechRules {
    const val ERROR_NETWORK_TIMEOUT = 1
    const val ERROR_NETWORK = 2
    const val ERROR_AUDIO = 3
    const val ERROR_SERVER = 4
    const val ERROR_CLIENT = 5
    const val ERROR_SPEECH_TIMEOUT = 6
    const val ERROR_NO_MATCH = 7
    const val ERROR_RECOGNIZER_BUSY = 8
    const val ERROR_INSUFFICIENT_PERMISSIONS = 9
    const val ERROR_TOO_MANY_REQUESTS = 10
    const val ERROR_SERVER_DISCONNECTED = 11
    const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
    const val ERROR_LANGUAGE_UNAVAILABLE = 13

    /** 开始听之后等识别服务第一个回调的上限：超过就当这一段没响应（3 秒无响应提示）。 */
    const val NO_RESPONSE_MS = 3_000L

    /** 松开后等最终结果的上限。 */
    const val FINAL_WAIT_MS = 2_000L

    /** 连着这么多次不响应才记成「这台手机的识别服务不理我们」。 */
    const val SILENT_STRIKES = 2

    /** null 表示「这一段没听到话」（没匹配上、没开口），按着时重开、松开后照已有的给结果。 */
    fun failureOf(code: Int): SpeechFailure? = when (code) {
        ERROR_NO_MATCH, ERROR_SPEECH_TIMEOUT -> null
        ERROR_RECOGNIZER_BUSY, ERROR_TOO_MANY_REQUESTS -> SpeechFailure.Busy
        ERROR_NETWORK, ERROR_NETWORK_TIMEOUT, ERROR_SERVER, ERROR_SERVER_DISCONNECTED -> SpeechFailure.Network
        ERROR_INSUFFICIENT_PERMISSIONS -> SpeechFailure.NoPermission
        ERROR_AUDIO, ERROR_CLIENT -> SpeechFailure.MicUnavailable
        else -> SpeechFailure.Unavailable(SpeechEngineSpec.Why.SystemGone)
    }

    sealed interface Outcome {
        data object Retry : Outcome
        data object Finish : Outcome
        data class Fail(val why: SpeechFailure) : Outcome
    }

    /**
     * [holding] 手还按着，[heard] 已经听到过字。已经听到的字不丢：松开后、或按着时中途出错，都按听到的收尾。
     * 松开后有的服务在 stopListening 之后报 ERROR_CLIENT，什么都没听到时那是没听清，不是麦克风坏了。
     */
    fun afterError(code: Int, holding: Boolean, heard: Boolean): Outcome {
        val failure = failureOf(code)
        return when {
            failure == null && holding -> Outcome.Retry
            failure == null || heard -> Outcome.Finish
            !holding && code == ERROR_CLIENT -> Outcome.Finish
            else -> Outcome.Fail(failure)
        }
    }

    /** [NO_RESPONSE_MS] 内一个回调都没有：回过话的按已听到的收尾；从没回过话报 SystemNoResponse。 */
    fun afterSilence(answered: Boolean): Outcome =
        if (answered) Outcome.Finish else Outcome.Fail(SpeechFailure.Unavailable(SpeechEngineSpec.Why.SystemNoResponse))

    /** 本机识别说这个语言用不了（没装语言包等）：换成联网识别再试。 */
    fun languageMissing(code: Int) = code == ERROR_LANGUAGE_NOT_SUPPORTED || code == ERROR_LANGUAGE_UNAVAILABLE

    /** 识别服务给的 rmsdB 大致在 -2..10，压到 0..1 画波形。 */
    fun level(rmsdB: Float): Float = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
}
