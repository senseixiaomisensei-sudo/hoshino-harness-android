package app.hoshino.harness.voice

import app.hoshino.harness.ui.settings.methodLabel
import app.hoshino.harness.ui.settings.speechSummary
import dev.hoshino.bridge.voice.SpeechChoice
import dev.hoshino.bridge.voice.SpeechEngineSpec
import dev.hoshino.bridge.voice.SpeechFailure
import dev.hoshino.bridge.voice.SpeechHint
import dev.hoshino.bridge.voice.SttEndpoint
import dev.hoshino.bridge.voice.SttError
import dev.hoshino.bridge.voice.SystemCapability
import dev.hoshino.bridge.voice.TranscribeMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 语音输入的文案：每种失败、提示、不可用原因都有中文说明，且失败说明里不带 Key；设置页摘要。 */
class VoiceCopyTest {
    @Test fun everyFailureAndHintHasText() {
        val failures = listOf(null, SpeechFailure.NoPermission, SpeechFailure.Busy, SpeechFailure.Network, SpeechFailure.MicUnavailable, SpeechFailure.InUse,
            SpeechFailure.Provider(SttError.Auth(401, "x"))) + SpeechEngineSpec.Why.entries.map { SpeechFailure.Unavailable(it) }
        for (f in failures) assertTrue(f.toString(), VoiceCopy.failure(f).isNotBlank())
        for (h in SpeechHint.entries) assertTrue(VoiceCopy.hint(h).isNotBlank())
        assertTrue("权限被拒要指去系统设置", VoiceCopy.failure(SpeechFailure.NoPermission).contains("设置"))
        assertTrue("3 秒无响应要说清楚", VoiceCopy.why(SpeechEngineSpec.Why.SystemNoResponse).contains("3 秒"))
        assertEquals("1:05", VoiceCopy.elapsed(65_000))
    }

    @Test fun summaryAndMethodLabels() {
        val ep = SttEndpoint("p", "硅基流动", "openai-compatible", "https://api.siliconflow.cn/v1", null)
        val ok = SystemCapability(available = true, onDevice = true)
        assertEquals("自动 · 手机自带（离线）", speechSummary(SpeechChoice.Auto, ok, emptyList()))
        assertEquals("已关闭", speechSummary(SpeechChoice.Off, ok, emptyList()))
        assertEquals("SenseVoiceSmall · 硅基流动", speechSummary(SpeechChoice.Provider("p", "SenseVoiceSmall", TranscribeMethod.AudioTranscriptions), ok, listOf(ep)))
        assertEquals("服务商已删除", speechSummary(SpeechChoice.Provider("gone", "m", TranscribeMethod.AudioTranscriptions), ok, listOf(ep)))
        assertEquals("手机自带 · 不响应", speechSummary(SpeechChoice.System, ok.copy(responsive = false), emptyList()))
        val labels = TranscribeMethod.entries.map(::methodLabel)
        assertEquals(labels.size, labels.toSet().size)
        assertFalse(labels.any { it.isBlank() })
    }
}
