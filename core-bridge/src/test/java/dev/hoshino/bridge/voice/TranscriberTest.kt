package dev.hoshino.bridge.voice

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class TranscriberTest {
    private lateinit var server: MockWebServer
    private val seen = mutableListOf<RecordedRequest>()
    private var route: (RecordedRequest) -> MockResponse = { MockResponse().setResponseCode(404) }

    @Before fun up() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse { synchronized(seen) { seen += request }; return route(request) }
        }
        server.start()
    }

    @After fun down() { server.shutdown() }

    private fun ep(key: String? = "sk-test-secret-1234") = SttEndpoint("p1", "P", "openai-compatible", server.url("/v1").toString(), key)
    private val t = Transcriber()
    private fun ok(body: String) = MockResponse().setResponseCode(200).setBody(body)

    @Test fun detectPrefersAudioTranscriptions() = runBlocking {
        route = { r -> if (r.path == "/v1/audio/transcriptions") ok("""{"text":""}""") else MockResponse().setResponseCode(404) }
        assertEquals(TranscribeMethod.AudioTranscriptions, t.detect(ep(), "whisper-1"))
        val r = seen.single()
        assertEquals("Bearer sk-test-secret-1234", r.getHeader("Authorization"))
        val body = r.body.readUtf8()
        assertTrue(body.contains("name=\"model\"") && body.contains("whisper-1"))
        assertTrue("内置试转音频是 WAV", body.contains("RIFF") && body.contains("WAVE"))
    }

    @Test fun detectFallsBackThroughChatWritings() = runBlocking {
        // 只认 data URI 的对话接口：裸 base64 那种回 400，data URI 那种回文字
        route = { r ->
            when {
                r.path == "/v1/audio/transcriptions" -> MockResponse().setResponseCode(404)
                r.path == "/v1/chat/completions" -> {
                    val data = JSONObject(r.body.readUtf8()).getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(0).getJSONObject("input_audio").getString("data")
                    if (data.startsWith("data:audio/wav;base64,")) ok("""{"choices":[{"message":{"content":"你好"}}]}""") else MockResponse().setResponseCode(400).setBody("bad audio")
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        assertEquals(TranscribeMethod.ChatInputAudioUri, t.detect(ep(), "qwen-omni"))
        assertEquals(listOf("/v1/audio/transcriptions", "/v1/chat/completions", "/v1/chat/completions"), seen.map { it.path })
    }

    @Test fun authErrorStopsDetectionAndHidesKey() = runBlocking {
        route = { MockResponse().setResponseCode(401).setBody("""{"error":"invalid key sk-test-secret-1234"}""") }
        try {
            t.detect(ep(), "whisper-1"); fail()
        } catch (e: SttError) {
            assertTrue(e is SttError.Auth)
            assertFalse("错误说明里不能带 Key", e.reason().contains("sk-test-secret-1234"))
        }
        assertEquals("Key 错了再换写法也没用", 1, seen.size)
    }

    @Test fun nothingWorksThrowsLastError() = runBlocking {
        route = { MockResponse().setResponseCode(400).setBody("audio not supported") }
        try { t.detect(ep(), "deepseek-chat"); fail() } catch (e: SttError) { assertTrue(e is SttError.BadRequest) }
        assertEquals(4, seen.size)
    }

    @Test fun languageRejectedRetriesWithout() = runBlocking {
        route = { r ->
            val b = r.body.readUtf8()
            if (b.contains("name=\"language\"")) MockResponse().setResponseCode(422).setBody("unsupported language") else ok("""{"text":" 帮我订票 "}""")
        }
        assertEquals("帮我订票", t.transcribe(ep(), "m", TranscribeMethod.AudioTranscriptions, Wav.silence(100), "zh"))
        assertEquals(2, seen.size)
    }

    @Test fun plainTextTranscriptionIsAccepted() = runBlocking {
        route = { ok("hello world\n") }
        assertEquals("hello world", t.transcribe(ep(), "m", TranscribeMethod.AudioTranscriptions, Wav.silence(100), null))
    }

    @Test fun jsonWithoutTextIsBadResponse() = runBlocking {
        route = { ok("""{"foo":1}""") }
        try { t.transcribe(ep(), "m", TranscribeMethod.AudioTranscriptions, Wav.silence(100), null); fail() } catch (e: SttError) { assertTrue(e is SttError.BadResponse) }
    }

    @Test fun chatContentArrayIsJoined() = runBlocking {
        route = { ok("""{"choices":[{"message":{"content":[{"type":"text","text":"Transcript: \"明天"},{"type":"text","text":"见\""}]}}]}""") }
        assertEquals("明天见", t.transcribe(ep(), "m", TranscribeMethod.ChatInputAudio, Wav.silence(100), "zh"))
        val body = JSONObject(seen.single().body.readUtf8())
        val parts = body.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        assertEquals("input_audio", parts.getJSONObject(0).getString("type"))
        assertTrue(parts.getJSONObject(1).getString("text").contains("Do not translate"))
    }

    @Test fun audioOnlyWritingSendsNoInstruction() = runBlocking {
        route = { ok("""{"choices":[{"message":{"content":"ok"}}]}""") }
        t.transcribe(ep(), "m", TranscribeMethod.ChatInputAudioOnly, Wav.silence(100), null)
        val parts = JSONObject(seen.single().body.readUtf8()).getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        assertEquals(1, parts.length())
    }

    @Test fun healingRelearnsAndRemembers() = runBlocking {
        // 记住的是 /audio/transcriptions，服务商后来把它下线了；对话接口还能用
        route = { r -> if (r.path == "/v1/chat/completions") ok("""{"choices":[{"message":{"content":"在吗"}}]}""") else MockResponse().setResponseCode(404) }
        var learned: TranscribeMethod? = null
        val text = t.transcribeHealing(ep(), "m", TranscribeMethod.AudioTranscriptions, Wav.silence(200), "zh") { learned = it }
        assertEquals("在吗", text)
        assertEquals(TranscribeMethod.ChatInputAudio, learned)
    }

    @Test fun healingDoesNotRetryOnQuota() = runBlocking {
        route = { MockResponse().setResponseCode(429) }
        try { t.transcribeHealing(ep(), "m", TranscribeMethod.AudioTranscriptions, Wav.silence(200), null) { fail("不该重探") }; fail() } catch (e: SttError) { assertTrue(e is SttError.Quota) }
        assertEquals(1, seen.size)
    }

    @Test fun geminiOrderAndNativeRequest() {
        val g = SttEndpoint("g", "Gemini", "openai-compatible", "https://generativelanguage.googleapis.com/v1beta/openai", "AIzaKEY")
        assertEquals(TranscribeMethod.GeminiInline, Transcriber.order(g)[1])
        assertFalse(TranscribeMethod.GeminiInline in Transcriber.order(ep()))
        val r = t.request(g, "gemini-2.5-flash", TranscribeMethod.GeminiInline, Wav.silence(10), "zh")
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent", r.url.toString())
        assertEquals("AIzaKEY", r.header("x-goog-api-key"))
        assertEquals(null, r.header("Authorization"))
    }

    @Test fun geminiReplyWithoutPartsIsEmptyText() = runBlocking {
        val g = SttEndpoint("g", "Gemini", "openai-compatible", server.url("/v1beta/openai").toString(), "k")
        route = { ok("""{"candidates":[{"finishReason":"STOP"}]}""") }
        assertEquals("", t.transcribe(g, "gm", TranscribeMethod.GeminiInline, Wav.silence(100), null))
        assertEquals("/v1beta/models/gm:generateContent", seen.single().path)
    }

    @Test fun networkErrorIsClassified() = runBlocking {
        val dead = SttEndpoint("x", "X", "openai-compatible", "http://127.0.0.1:1/v1", null)
        try { t.transcribe(dead, "m", TranscribeMethod.AudioTranscriptions, Wav.silence(10), null); fail() } catch (e: SttError) { assertTrue(e is SttError.Network); assertFalse(e.triesNext) }
    }

    @Test fun supportAndSpeechNames() {
        assertFalse(Transcriber.supports(ep().copy(type = "anthropic")))
        assertFalse(Transcriber.supports(ep().copy(type = "openai-codex")))
        assertTrue(Transcriber.supports(ep()))
        assertTrue(Transcriber.looksLikeSpeech("whisper-large-v3"))
        assertTrue(Transcriber.looksLikeSpeech("gpt-4o-mini-transcribe"))
        assertTrue(Transcriber.looksLikeSpeech("FunAudioLLM/SenseVoiceSmall"))
        assertFalse(Transcriber.looksLikeSpeech("deepseek-chat"))
    }

    @Test fun errorMapping() {
        assertTrue(Transcriber.errorOf(403, "") is SttError.Auth)
        assertTrue(Transcriber.errorOf(405, "") is SttError.NotFound)
        assertTrue(Transcriber.errorOf(415, "") is SttError.BadRequest)
        assertTrue(Transcriber.errorOf(503, "") is SttError.Server)
        assertTrue(Transcriber.errorOf(404, "").triesNext)
        assertFalse(Transcriber.errorOf(500, "").triesNext)
    }
}
