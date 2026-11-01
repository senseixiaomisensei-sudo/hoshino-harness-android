package dev.hoshino.bridge.voice

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** 语言标签、选择的存取与解析、WAV、分段、按住说话的新增状态（改一改、提示、慢转写）、去向与麦克风占用。 */
class VoiceLogicTest {
    // ---------- 语言标签
    @Test fun recognizerTagIsLanguageRegion() {
        assertEquals("zh-CN", SpeechLang.recognizerTag("zh-Hans-CN"))
        assertEquals("zh-CN", SpeechLang.recognizerTag("zh_CN"))
        assertEquals("zh-CN", SpeechLang.recognizerTag("zh-Hans"))
        assertEquals("zh-TW", SpeechLang.recognizerTag("zh-Hant"))
        assertEquals("zh-HK", SpeechLang.recognizerTag("zh-Hant-HK"))
        assertEquals("en-US", SpeechLang.recognizerTag("en-US-u-nu-latn"))
        assertEquals("ja", SpeechLang.recognizerTag("ja"))
        assertEquals("he-IL", SpeechLang.recognizerTag("iw-IL"))
        assertNull(SpeechLang.recognizerTag(""))
        assertNull(SpeechLang.recognizerTag("und"))
        assertNull(SpeechLang.recognizerTag(null))
        assertEquals("zh-CN", SpeechLang.fromLocale(Locale.forLanguageTag("zh-Hans-CN")))
    }

    @Test fun isoCodeForTranscriptionApi() {
        assertEquals("zh", SpeechLang.isoCode("zh-Hans-CN"))
        assertEquals("en", SpeechLang.isoCode("en_GB"))
        assertEquals("id", SpeechLang.isoCode("in-ID"))
        assertEquals("yue", SpeechLang.isoCode("yue-HK"))
        assertNull(SpeechLang.isoCode("und"))
        assertNull(SpeechLang.isoCode("  "))
    }

    // ---------- 选择的存取与解析
    @Test fun choiceRoundTrips() {
        for (c in listOf(SpeechChoice.Auto, SpeechChoice.System, SpeechChoice.Off, SpeechChoice.Provider("p", "whisper-1", TranscribeMethod.ChatInputAudioUri), SpeechChoice.Offline("sensevoice-small"))) {
            assertEquals(c, SpeechChoiceCodec.decode(SpeechChoiceCodec.encode(c)))
        }
        assertEquals(SpeechChoice.Auto, SpeechChoiceCodec.decode(null))
        assertEquals(SpeechChoice.Auto, SpeechChoiceCodec.decode("not json"))
        assertEquals(SpeechChoice.Auto, SpeechChoiceCodec.decode("""{"type":"provider","providerId":"p","model":"m","method":"Gone"}"""))
    }

    private val openai = SttEndpoint("p", "OpenAI", "openai-compatible", "https://api.openai.com/v1", "k")

    @Test fun resolverFollowsChoiceAndCapability() {
        val ok = SystemCapability(available = true, onDevice = true)
        val silent = SystemCapability(available = true, onDevice = false, responsive = false)
        val none = SystemCapability(available = false, onDevice = false)
        assertEquals(SpeechEngineSpec.System(true), SpeechResolver.resolve(SpeechChoice.Auto, ok, emptyList()))
        assertEquals(SpeechEngineSpec.Unavailable(SpeechEngineSpec.Why.SystemNoResponse), SpeechResolver.resolve(SpeechChoice.Auto, silent, listOf(openai)))
        assertEquals(SpeechEngineSpec.Unavailable(SpeechEngineSpec.Why.NoSystemNoModel), SpeechResolver.resolve(SpeechChoice.Auto, none, emptyList()))
        assertEquals(SpeechEngineSpec.Unavailable(SpeechEngineSpec.Why.SystemGone), SpeechResolver.resolve(SpeechChoice.System, none, emptyList()))
        assertEquals(SpeechEngineSpec.Unavailable(SpeechEngineSpec.Why.Off), SpeechResolver.resolve(SpeechChoice.Off, ok, emptyList()))
        val pick = SpeechChoice.Provider("p", "whisper-1", TranscribeMethod.AudioTranscriptions)
        assertEquals(SpeechEngineSpec.Provider(openai, "whisper-1", TranscribeMethod.AudioTranscriptions), SpeechResolver.resolve(pick, none, listOf(openai)))
        assertEquals(SpeechEngineSpec.Unavailable(SpeechEngineSpec.Why.ProviderGone), SpeechResolver.resolve(pick, ok, emptyList()))
        assertEquals(SpeechEngineSpec.Unavailable(SpeechEngineSpec.Why.ProviderUnsupported), SpeechResolver.resolve(pick, ok, listOf(openai.copy(type = "anthropic"))))
        assertEquals(SpeechEngineSpec.Unavailable(SpeechEngineSpec.Why.OfflineNotInstalled), SpeechResolver.resolve(SpeechChoice.Offline("x"), ok, emptyList()))
    }

    @Test fun endpointToStringHidesKey() {
        assertFalse(openai.copy(key = "sk-very-secret").toString().contains("sk-very-secret"))
    }

    // ---------- WAV
    @Test fun wavHeaderAndProbe() {
        val w = Wav.encode(ShortArray(16_000))
        assertEquals(44 + 32_000, w.size)
        assertEquals("RIFF", String(w, 0, 4, Charsets.US_ASCII))
        assertEquals(16_000, Wav.samples(w))
        val p = Wav.probe()
        assertEquals(19_200, Wav.samples(p))
        assertTrue("试转样本不是静音", p.drop(44).any { it != 0.toByte() })
        assertTrue(Wav.probe().contentEquals(p))
    }

    // ---------- 分段
    private fun tone(ms: Int) = ShortArray(16 * ms) { if (it % 2 == 0) 3000 else -3000 }
    private fun quiet(ms: Int) = ShortArray(16 * ms)

    @Test fun cutsAtPauseAfterMinimum() {
        val c = SegmentCutter()
        repeat(20) { assertEquals(SegmentCutter.Cut.None, c.feed(tone(100))) }  // 2 秒说话
        repeat(10) { assertEquals(SegmentCutter.Cut.None, c.feed(quiet(100))) } // 太短的段不在停顿处切
        repeat(10) { c.feed(tone(100)) }
        var cut = SegmentCutter.Cut.None
        repeat(8) { if (cut == SegmentCutter.Cut.None) cut = c.feed(quiet(100)) }
        assertEquals(SegmentCutter.Cut.Pause, cut)
        assertTrue(c.voiced)
    }

    @Test fun hardCapAtThirtySeconds() {
        val c = SegmentCutter()
        var cut = SegmentCutter.Cut.None
        var ms = 0
        while (cut == SegmentCutter.Cut.None) { cut = c.feed(tone(50)); ms += 50 }
        assertEquals(SegmentCutter.Cut.Cap, cut)
        assertEquals(30_000, ms)
        c.reset()
        assertEquals(0L, c.segmentMs)
    }

    @Test fun silentSegmentIsNotVoiced() {
        val c = SegmentCutter()
        repeat(100) { c.feed(quiet(50)) }
        assertFalse(c.voiced)
    }

    // ---------- 按住说话的新增状态
    private class FakeSession(override val live: Boolean = true, override val convertsAfterRelease: Boolean = false) : SpeechSession {
        override val events: Flow<SpeechEvent> = emptyFlow()
        var released = 0
        var cancelled = 0
        override fun release() { released++ }
        override fun cancel() { cancelled++ }
    }

    private var now = 0L
    private val hold = HoldToTalk(clock = { now })

    @Test fun releaseOnEditMarksDone() {
        val s = FakeSession().also { hold.press(it) }
        now = 1_000
        hold.aim(HoldToTalk.Target.Edit)
        hold.release()
        assertEquals(1, s.released)
        hold.onEvent(SpeechEvent.Final("改一下"))
        assertEquals(HoldToTalk.State.Done("改一下", edit = true), hold.state.value)
    }

    @Test fun earlyFinalReleasedOnEditIsEdit() {
        FakeSession().also { hold.press(it) }
        now = 1_000
        hold.onEvent(SpeechEvent.Final("好"))
        hold.aim(HoldToTalk.Target.Edit)
        hold.release()
        assertEquals(HoldToTalk.State.Done("好", edit = true), hold.state.value)
    }

    @Test fun hintsAndSlowConverting() {
        FakeSession(convertsAfterRelease = true).also { hold.press(it) }
        hold.onEvent(SpeechEvent.Hint(SpeechHint.MicSilent))
        assertEquals(SpeechHint.MicSilent, (hold.state.value as HoldToTalk.State.Listening).hint)
        hold.onEvent(SpeechEvent.Partial("第一段", ""))
        now = 5_000
        hold.release()
        assertEquals(HoldToTalk.State.Converting("第一段"), hold.state.value)
        hold.onEvent(SpeechEvent.Hint(SpeechHint.Slow))
        assertEquals(HoldToTalk.State.Converting("第一段", slow = true), hold.state.value)
        hold.onEvent(SpeechEvent.Partial("第一段第二段", ""))
        assertEquals(HoldToTalk.State.Converting("第一段第二段", slow = true), hold.state.value)
        hold.onEvent(SpeechEvent.Final("第一段第二段"))
        assertEquals(HoldToTalk.State.Done("第一段第二段"), hold.state.value)
    }

    @Test fun routingInterjectsWhileRunning() {
        assertEquals(VoiceRouting.Route.Interject("先停一下"), VoiceRouting.route("先停一下", "草稿", running = true, edit = false))
        assertEquals(VoiceRouting.Route.Fill("草稿先停一下"), VoiceRouting.route("先停一下", "草稿", running = true, edit = true))
        assertEquals(VoiceRouting.Route.Fill("hello world"), VoiceRouting.route("world", "hello", running = false, edit = false))
    }

    @Test fun micGateIsExclusive() {
        val chat = Any(); val capsule = Any()
        assertTrue(MicGate.acquire(chat))
        assertTrue("同一处重复拿不算冲突", MicGate.acquire(chat))
        assertFalse(MicGate.acquire(capsule))
        MicGate.release(capsule) // 别人放不掉
        assertFalse(MicGate.acquire(capsule))
        MicGate.release(chat)
        assertTrue(MicGate.acquire(capsule))
        MicGate.release(capsule)
        assertNull(MicGate.holder())
    }

    @Test fun systemRulesLevelAndSilence() {
        assertEquals(0f, SystemSpeechRules.level(-5f))
        assertEquals(1f, SystemSpeechRules.level(20f))
        assertEquals(SystemSpeechRules.Outcome.Finish, SystemSpeechRules.afterSilence(answered = true))
        assertEquals(SystemSpeechRules.Outcome.Fail(SpeechFailure.Unavailable(SpeechEngineSpec.Why.SystemNoResponse)), SystemSpeechRules.afterSilence(answered = false))
        assertTrue(SystemSpeechRules.languageMissing(12) && SystemSpeechRules.languageMissing(13) && !SystemSpeechRules.languageMissing(7))
        assertEquals(3_000L, SystemSpeechRules.NO_RESPONSE_MS)
    }
}
