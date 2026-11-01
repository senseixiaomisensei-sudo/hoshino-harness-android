// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
// (res/values-zh/strings_voice.xml 的说法，改写为本项目的中文文案。)
package app.hoshino.harness.voice

import dev.hoshino.bridge.voice.SpeechEngineSpec
import dev.hoshino.bridge.voice.SpeechFailure
import dev.hoshino.bridge.voice.SpeechHint

/** 语音输入的界面文案（输入栏、悬浮胶囊、设置共用）。 */
object VoiceCopy {
    const val HOLD_TO_TALK = "按住说话"
    const val RELEASE_TO_FILL = "松手填入输入框 · 上滑取消"
    const val RELEASE_TO_INTERJECT = "松手插话给当前任务 · 上滑取消"
    const val RELEASE_TO_CANCEL = "松手取消"
    const val CONVERTING = "转文字…"
    const val CONVERTING_SLOW = "转写有点慢，最多再等 30 秒…"
    const val NOT_HEARD = "没听清，再说一次"
    const val PERMISSION_RATIONALE = "按住说话需要麦克风权限：只在你按住时录音，录音不保存。"
    const val PERMISSION_DENIED = "没有麦克风权限，不能按住说话。可以在系统设置 › 应用 › Hoshino › 权限 里打开。"

    fun hint(h: SpeechHint): String = when (h) {
        SpeechHint.MicSilent -> "麦克风没有声音：可能被别的 App 占用或被静音"
        SpeechHint.Slow -> CONVERTING_SLOW
        SpeechHint.SegmentCapped -> "这一段到 30 秒了，已自动接着下一段"
        SpeechHint.MethodRelearned -> "转写写法变了，已自动换用新写法并记住"
    }

    fun why(w: SpeechEngineSpec.Why): String = when (w) {
        SpeechEngineSpec.Why.Off -> "语音输入已关闭，可在 设置 › 模型 › 语音转文字 打开"
        SpeechEngineSpec.Why.NoSystemNoModel -> "这台手机没有自带语音识别，请到 设置 › 模型 › 语音转文字 选一个服务商模型"
        SpeechEngineSpec.Why.SystemGone -> "手机自带的语音识别不见了，请到 设置 › 模型 › 语音转文字 重新选"
        SpeechEngineSpec.Why.SystemNoResponse -> "手机自带的语音识别 3 秒没有响应，请到 设置 › 模型 › 语音转文字 换一个"
        SpeechEngineSpec.Why.ProviderGone -> "选的语音模型所属的服务商已删除或停用，请到 设置 › 模型 › 语音转文字 重新选"
        SpeechEngineSpec.Why.ProviderUnsupported -> "这个服务商不提供语音转文字，请换一个"
        SpeechEngineSpec.Why.OfflineNotInstalled -> "离线转写模型还没有安装（本版本只预留了接口）"
    }

    fun failure(f: SpeechFailure?): String = when (f) {
        null -> NOT_HEARD
        SpeechFailure.NoPermission -> PERMISSION_DENIED
        SpeechFailure.Busy -> "语音识别正忙，稍后再按"
        SpeechFailure.Network -> "网络不通或转写超时（30 秒），没转出文字"
        SpeechFailure.MicUnavailable -> "麦克风用不了：可能正被别的 App 占用"
        SpeechFailure.InUse -> "麦克风正被另一处（输入栏或悬浮胶囊）使用"
        is SpeechFailure.Provider -> "转写失败：" + f.error.reason()
        is SpeechFailure.Unavailable -> why(f.why)
    }

    fun elapsed(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }
}
