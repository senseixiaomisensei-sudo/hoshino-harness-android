// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package dev.hoshino.bridge

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections

/** m2.10「测试连接」真调一次模型：候选排序、5xx/零额度换下一个、全 5xx 判整家不可用、凭据错误立刻停、中文网络原因。 */
class ProviderLiveTestTest {
    private lateinit var server: MockWebServer
    private val seen = Collections.synchronizedList(mutableListOf<RecordedRequest>())

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    /** 按请求体里的 model 回不同的状态码；[byModel] 没写的回 200。 */
    private fun respond(byModel: Map<String, Pair<Int, String>>) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                val body = request.body.readUtf8()
                val model = runCatching { JSONObject(body).getString("model") }.getOrDefault("")
                val (code, text) = byModel[model] ?: (200 to """{"choices":[{"message":{"content":"pong"}}]}""")
                return MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(text)
            }
        }
    }

    private fun base() = server.url("/v1").toString()
    private fun test(models: List<String>, type: String = "openai-compatible") = runBlocking { ProviderLiveTest().test(type, base(), "sk-test-123456", emptyMap(), models) }

    // ---------- 候选排序 ----------

    @Test fun candidatesPutTheNewestVersionOfEachFamilyFirst() {
        assertEquals(listOf("gpt-4.1", "gpt-4o", "gpt-3.5-turbo"), LiveTestRules.candidates(listOf("gpt-3.5-turbo", "gpt-4o", "gpt-4.1")))
        // 正式版在预览前；日期新的在前
        assertEquals(listOf("gemini-2.5-pro", "gemini-2.5-pro-preview-05-06"), LiveTestRules.candidates(listOf("gemini-2.5-pro-preview-05-06", "gemini-2.5-pro")))
        assertEquals("claude-sonnet-4-5", LiveTestRules.candidates(listOf("claude-3-5-sonnet-20241022", "claude-sonnet-4-5"))[0])
        // 参数量不是版本：qwen3:8b 读成 3
        assertEquals(listOf("qwen3:8b", "qwen2.5:72b"), LiveTestRules.candidates(listOf("qwen2.5:72b", "qwen3:8b")))
    }

    @Test fun candidatesSkipNonChatModelsAndRotateFamilies() {
        val ids = listOf("text-embedding-3-large", "meituan/longcat-flash-lite", "openai/gpt-4o", "openai/gpt-5", "anthropic/claude-3-haiku", "whisper-1")
        val c = LiveTestRules.candidates(ids, limit = 3)
        // 每家各出一个最新的，向量/语音模型不试
        assertEquals(listOf("meituan/longcat-flash-lite", "openai/gpt-5", "anthropic/claude-3-haiku"), c)
        assertEquals(4, LiveTestRules.candidates((1..9).map { "m$it" }).size)
    }

    // ---------- 判定 ----------

    @Test fun classifyKnowsModelSideFromAccountSide() {
        assertEquals(LiveKind.Passed, LiveTestRules.classify(200, "{}").kind)
        assertEquals(LiveKind.Auth, LiveTestRules.classify(401, "{}").kind)
        assertEquals(LiveKind.Auth, LiveTestRules.classify(403, """{"error":{"message":"Forbidden"}}""").kind)
        assertEquals(LiveKind.ModelUnavailable, LiveTestRules.classify(403, """{"error":{"message":"Model not activated for this account"}}""").kind)
        assertEquals(LiveKind.ModelUnavailable, LiveTestRules.classify(429, """{"error":{"message":"Quota exceeded, limit: 0, model: gemini-x"}}""").kind)
        assertEquals(LiveKind.RateLimited, LiveTestRules.classify(429, """{"error":{"message":"Too many requests"}}""").kind)
        assertEquals(LiveKind.Server, LiveTestRules.classify(502, "Bad Gateway").kind)
        assertEquals(LiveKind.ModelUnavailable, LiveTestRules.classify(404, """{"error":{"code":"model_not_found"}}""").kind)
        assertEquals(LiveKind.ModelUnavailable, LiveTestRules.classify(402, """{"error":{"message":"Insufficient credits"}}""").kind)
        assertTrue(LiveTestRules.classify(401, "{}").reason.contains("API Key"))
    }

    // ---------- 真发请求 ----------

    @Test fun passesOnTheFirstCandidateWithATinyChatCompletion() {
        respond(emptyMap())
        val r = test(listOf("gpt-4o", "gpt-4.1"))
        assertTrue(r.message, r.ok)
        assertEquals("gpt-4.1", r.model)
        assertTrue(r.message.contains("已真调 gpt-4.1"))
        val req = seen.single()
        assertEquals("POST", req.method)
        assertEquals("/v1/chat/completions", req.path)
        assertEquals("Bearer sk-test-123456", req.getHeader("Authorization"))
    }

    @Test fun requestBodyIsSmall() {
        respond(emptyMap())
        val bodies = mutableListOf<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                bodies += request.body.readUtf8()
                return MockResponse().setBody("{}")
            }
        }
        assertTrue(test(listOf("m1")).ok)
        val body = JSONObject(bodies.single())
        assertEquals(ProviderLiveTest.MAX_TOKENS, body.getInt("max_tokens"))
        assertFalse(body.getBoolean("stream"))
        assertEquals(1, body.getJSONArray("messages").length())
    }

    /** Mika 19c92ac：第一个候选 5xx，第二个通过 → 成功，并说明先试的那个为什么没过。 */
    @Test fun serverErrorMovesOnToTheNextCandidate() {
        respond(mapOf("gpt-5" to (502 to "Bad Gateway")))
        val r = test(listOf("gpt-4o", "gpt-5"))
        assertTrue(r.message, r.ok)
        assertEquals("gpt-4o", r.model)
        assertTrue(r.message, r.message.contains("gpt-5") && r.message.contains("HTTP 502"))
        assertEquals(2, seen.size)
    }

    /** 试的全都是 5xx：整家暂时不可用。 */
    @Test fun allServerErrorsMeanTheWholeProviderIsDown() {
        respond(mapOf("a-3" to (500 to ""), "b-2" to (503 to ""), "c-1" to (502 to "")))
        val r = test(listOf("a-3", "b-2", "c-1"))
        assertFalse(r.ok)
        assertTrue(r.message, r.message.contains("整家服务暂时不可用"))
        assertEquals(3, seen.size)
    }

    /** 5xx 之后碰到 401：Key 没证明过是对的，立刻判失败，不再试后面的。 */
    @Test fun authErrorAfterServerErrorStops() {
        respond(mapOf("a-3" to (502 to ""), "b-2" to (401 to """{"error":{"message":"invalid key sk-test-123456"}}""")))
        val r = test(listOf("a-3", "b-2", "c-1"))
        assertFalse(r.ok)
        assertTrue(r.message, r.message.contains("API Key"))
        assertEquals(2, seen.size)
    }

    @Test fun zeroQuotaMovesOnButARealMixIsListed() {
        respond(mapOf("gemini-3-pro" to (429 to """{"error":{"message":"limit: 0"}}""")))
        val ok = test(listOf("gemini-3-pro", "gemini-2.5-flash"))
        assertTrue(ok.message, ok.ok)
        assertEquals("gemini-2.5-flash", ok.model)
        seen.clear()
        respond(mapOf("x-2" to (429 to """{"error":{"message":"limit: 0"}}"""), "x-1" to (502 to "")))
        val mixed = test(listOf("x-2", "x-1"))
        assertFalse(mixed.ok)
        assertTrue(mixed.message, mixed.message.contains("都没调通") && mixed.message.contains("额度为零") && mixed.message.contains("HTTP 502"))
    }

    @Test fun reasoningModelsGetMaxCompletionTokens() {
        val bodies = mutableListOf<JSONObject>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val b = JSONObject(request.body.readUtf8()); bodies += b
                return if (b.has("max_tokens")) MockResponse().setResponseCode(400).setBody("""{"error":{"message":"Unsupported parameter: 'max_tokens'. Use 'max_completion_tokens' instead."}}""")
                else MockResponse().setBody("{}")
            }
        }
        val r = test(listOf("o3"))
        assertTrue(r.message, r.ok)
        assertEquals(2, bodies.size)
        assertEquals(ProviderLiveTest.MAX_TOKENS, bodies[1].getInt("max_completion_tokens"))
    }

    @Test fun anthropicUsesMessagesWithItsHeaders() {
        respond(emptyMap())
        val r = runBlocking { ProviderLiveTest().test("anthropic", server.url("/").toString(), "sk-ant-xyz", emptyMap(), listOf("claude-sonnet-4-5")) }
        assertTrue(r.message, r.ok)
        val req = seen.single()
        assertEquals("/v1/messages", req.path)
        assertEquals("sk-ant-xyz", req.getHeader("x-api-key"))
        assertEquals("2023-06-01", req.getHeader("anthropic-version"))
        assertNull(req.getHeader("Authorization"))
    }

    /** m2.9.5 的中文网络原因保留：端口没人听 → 「连接被拒绝」+ Ollama 提示，不再试后面的。 */
    @Test fun networkErrorsKeepTheReadableChineseReason() {
        val port = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val r = runBlocking { ProviderLiveTest().test("openai-compatible", "127.0.0.1:$port", "", emptyMap(), listOf("qwen3:8b", "llama3.2")) }
        assertFalse(r.ok)
        assertTrue(r.message, r.message.contains("连不上") || r.message.contains("超时"))
        assertEquals(1, r.tried.size)
    }

    @Test fun noModelsIsExplained() {
        val r = runBlocking { LiveTestRules.run(emptyList()) { error("not called") } }
        assertFalse(r.ok)
        assertTrue(r.message.contains("没有可测试的模型"))
    }
}
