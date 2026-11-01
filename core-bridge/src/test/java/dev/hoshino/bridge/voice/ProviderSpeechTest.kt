package dev.hoshino.bridge.voice

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

class ProviderSpeechTest {
    /** 假录音器：测试直接推块；stop 之后不再收。 */
    private class FakeRecorder(private val ok: Boolean = true) : Recorder {
        var sink: ((ShortArray) -> Unit)? = null
        var stopped = 0
        var cancelled = 0
        override fun start(onChunk: (ShortArray) -> Unit): Boolean { if (ok) sink = onChunk; return ok }
        override fun stop() { stopped++; sink = null }
        override fun cancel() { cancelled++; sink = null }
        fun push(chunk: ShortArray) { sink?.invoke(chunk) }
        fun tone(ms: Int) = repeat(ms / 50) { push(ShortArray(800) { i -> if (i % 2 == 0) 3000 else -3000 }) }
        fun quiet(ms: Int) = repeat(ms / 50) { push(ShortArray(800)) }
    }

    private suspend fun collect(s: SpeechSession): List<SpeechEvent> = withTimeout(10_000) { s.events.toList() }

    @Test fun segmentsAreTranscribedWhileHoldingAndJoined() = runBlocking {
        val rec = FakeRecorder()
        val calls = Collections.synchronizedList(mutableListOf<Int>())
        val s = ProviderSpeech(rec, { wav -> calls += Wav.samples(wav); if (calls.size == 1) "帮我订票" else "明天下午" })
        rec.tone(4_000); rec.quiet(800)       // 第一段在停顿处切开
        rec.tone(1_000)
        delay(200)
        s.release()
        val events = collect(s)
        assertEquals(2, calls.size)
        assertTrue("第一段在按着时就出字", events.any { it is SpeechEvent.Partial && it.stable == "帮我订票" })
        assertTrue(events.contains(SpeechEvent.Converting))
        assertEquals(SpeechEvent.Final("帮我订票明天下午"), events.last())
        assertTrue(events.any { it is SpeechEvent.Level && it.value > 0f })
        assertEquals(1, rec.stopped)
    }

    @Test fun tooShortNeverCallsProvider() = runBlocking {
        val rec = FakeRecorder()
        var called = false
        val s = ProviderSpeech(rec, { called = true; "x" })
        rec.tone(300)
        s.release()
        assertEquals(SpeechEvent.Final(""), collect(s).last())
        assertFalse(called)
    }

    @Test fun silentRecordingSkipsProviderAndHintsMic() = runBlocking {
        val rec = FakeRecorder()
        var called = false
        val s = ProviderSpeech(rec, { called = true; "谢谢观看" })
        rec.quiet(3_500)
        s.release()
        val events = collect(s)
        assertTrue(events.contains(SpeechEvent.Hint(SpeechHint.MicSilent)))
        assertEquals(SpeechEvent.Final(""), events.last())
        assertFalse("全是安静的段不送，免得模型编字", called)
    }

    @Test fun thirtySecondSegmentCap() = runBlocking {
        val rec = FakeRecorder()
        val sizes = Collections.synchronizedList(mutableListOf<Int>())
        val s = ProviderSpeech(rec, { wav -> sizes += Wav.samples(wav); "段" })
        rec.tone(31_000)
        s.release()
        val events = collect(s)
        assertTrue(events.contains(SpeechEvent.Hint(SpeechHint.SegmentCapped)))
        assertEquals(30 * 16_000, sizes.first())
        assertTrue(sizes.all { it <= 30 * 16_000 })
    }

    @Test fun slowTranscriptionHintsThenTimesOut() = runBlocking {
        val rec = FakeRecorder()
        val s = ProviderSpeech(rec, { delay(5_000); "迟到" }, timeoutMs = 600, slowMs = 200)
        rec.tone(1_000)
        s.release()
        val events = collect(s)
        assertTrue(events.contains(SpeechEvent.Hint(SpeechHint.Slow)))
        assertEquals(SpeechEvent.Failed(SpeechFailure.Network), events.last())
    }

    @Test fun providerErrorReportedWhenNothingHeard() = runBlocking {
        val rec = FakeRecorder()
        val err = SttError.Auth(401, "bad")
        val s = ProviderSpeech(rec, { throw err })
        rec.tone(1_000)
        s.release()
        assertEquals(SpeechEvent.Failed(SpeechFailure.Provider(err)), collect(s).last())
    }

    @Test fun partialSuccessKeepsText() = runBlocking {
        val rec = FakeRecorder()
        var n = 0
        val s = ProviderSpeech(rec, { if (++n == 1) "前半句" else throw SttError.Server(502, "x") })
        rec.tone(4_000); rec.quiet(800); rec.tone(1_000)
        delay(200)
        s.release()
        assertEquals(SpeechEvent.Final("前半句"), collect(s).last())
    }

    @Test fun micUnavailableFailsAtOnce() = runBlocking {
        val s = ProviderSpeech(FakeRecorder(ok = false), { "x" })
        assertEquals(listOf(SpeechEvent.Failed(SpeechFailure.MicUnavailable)), collect(s))
    }

    @Test fun cancelDropsEverything() = runBlocking {
        val rec = FakeRecorder()
        val s = ProviderSpeech(rec, { "x" })
        rec.tone(1_000)
        s.cancel()
        s.cancel()
        assertEquals(1, rec.cancelled)
        assertEquals(emptyList<SpeechEvent>(), collect(s))
    }
}
