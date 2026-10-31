package dev.hoshino.bridge

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
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

/** Capability probe against a fake gateway: order, evidence rules, no model-name guessing, undecided on transient errors. */
class WebSearchProbeTest {
    private lateinit var server: MockWebServer
    private val seen = mutableListOf<Pair<String, JSONObject>>()
    private val probe = WebSearchProbe(OkHttpClient())

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    private fun gateway(handler: (path: String, body: JSONObject) -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = JSONObject(request.body.readUtf8())
                synchronized(seen) { seen += request.path!! to body }
                return handler(request.path!!, body)
            }
        }
    }

    private val base get() = server.url("/v1").toString()
    private fun ok(json: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(json)
    private val searched = """{"output":[{"type":"web_search_call","id":"ws_1","status":"completed","action":{"type":"search","query":"python"}},{"type":"message","content":[{"type":"output_text","text":"https://www.python.org","annotations":[{"type":"url_citation","url":"https://www.python.org","title":"Python"}]}]}]}"""

    @Test fun responsesWebSearchWinsWhenTheReplyShowsASearch() = runBlocking {
        gateway { path, _ -> if (path == "/v1/responses") ok(searched) else MockResponse().setResponseCode(404) }
        val r = probe.probe("openai-compatible", base, "gpt-6.1-terra", "sk-test-secret")
        assertEquals(WebSearchMethods.RESPONSES, r.method)
        assertEquals(1, seen.size)
        val body = seen.single().second
        assertEquals("web_search", body.getJSONArray("tools").getJSONObject(0).getString("type"))
        assertEquals("gpt-6.1-terra", body.getString("model"))
        assertFalse(body.getBoolean("store"))
        assertEquals("Bearer sk-test-secret", server.takeRequest().getHeader("Authorization"))
    }

    @Test fun acceptedButIgnoredToolIsNotSupport() = runBlocking {
        // A gateway that answers 200 without running any search must not be recorded as supporting it.
        gateway { path, body ->
            when {
                path == "/v1/responses" && body.getJSONArray("tools").getJSONObject(0).getString("type") == "web_search" -> ok("""{"output":[{"type":"message","content":[{"type":"output_text","text":"https://python.org","annotations":[]}]}]}""")
                path == "/v1/responses" -> ok(searched)
                else -> MockResponse().setResponseCode(400)
            }
        }
        val r = probe.probe("openai-compatible", base, "m", "k")
        assertEquals(WebSearchMethods.RESPONSES_PREVIEW, r.method)
        assertEquals("no-evidence", r.attempts[0].outcome)
    }

    @Test fun chatCompletionsWebSearchOptionsWithCitations() = runBlocking {
        gateway { path, body ->
            if (path == "/v1/chat/completions" && body.has("web_search_options")) ok("""{"choices":[{"message":{"role":"assistant","content":"https://www.python.org","annotations":[{"type":"url_citation","url_citation":{"url":"https://www.python.org","title":"Python"}}]}}]}""")
            else MockResponse().setResponseCode(400).setBody("""{"error":{"message":"unknown tool type"}}""")
        }
        val r = probe.probe("openai-compatible", "$base/chat/completions", "search-model", "k")
        assertEquals(WebSearchMethods.CHAT_OPTIONS, r.method)
        assertEquals(listOf("rejected", "rejected", "available"), r.attempts.map { it.outcome })
    }

    @Test fun variantOnlyWhenTheProviderListsIt() = runBlocking {
        gateway { path, body ->
            if (path == "/v1/chat/completions" && body.getString("model") == "llama:online") ok("""{"choices":[{"message":{"content":"x","annotations":[{"type":"url_citation","url_citation":{"url":"https://www.python.org"}}]}}]}""")
            else ok("""{"choices":[{"message":{"content":"x"}}]}""")
        }
        val unlisted = probe.probe("openai-compatible", base, "llama", "k")
        assertEquals(WebSearchMethods.NONE, unlisted.method)
        assertFalse("never invents a model name", seen.any { it.second.optString("model") == "llama:online" })
        val listed = probe.probe("openai-compatible", base, "llama", "k", listedModels = listOf("llama", "llama:online"))
        assertEquals(WebSearchMethods.CHAT_VARIANT + "llama:online", listed.method)
    }

    @Test fun anthropicServerToolNeedsServerToolBlocks() = runBlocking {
        gateway { path, body ->
            assertEquals("/v1/messages", path)
            assertEquals("web_search_20250305", body.getJSONArray("tools").getJSONObject(0).getString("type"))
            ok("""{"content":[{"type":"server_tool_use","id":"srvtoolu_1","name":"web_search","input":{"query":"python"}},{"type":"web_search_tool_result","tool_use_id":"srvtoolu_1","content":[]},{"type":"text","text":"https://www.python.org"}]}""")
        }
        val r = probe.probe("anthropic", server.url("/v1/messages").toString(), "claude-x", "sk-ant-secret")
        assertEquals(WebSearchMethods.ANTHROPIC, r.method)
        val request = server.takeRequest()
        assertEquals("sk-ant-secret", request.getHeader("x-api-key"))
        assertNull(request.getHeader("Authorization"))
    }

    @Test fun transientFailuresLeaveTheMethodUndecidedAndErrorsAreRedacted() = runBlocking {
        gateway { _, _ -> MockResponse().setResponseCode(429).setBody("""{"error":{"message":"slow down sk-live-key-123"}}""") }
        val r = probe.probe("openai-compatible", base, "m", "sk-live-key-123")
        assertNull(r.method)
        assertEquals("stops spending requests after a rate limit", 1, r.attempts.size)
        assertFalse(r.attempts.single().detail.contains("sk-live-key-123"))
    }

    @Test fun everythingRefusedMeansNone() = runBlocking {
        gateway { _, _ -> MockResponse().setResponseCode(400).setBody("""{"error":{"message":"unsupported"}}""") }
        val r = probe.probe("openai-compatible", base, "m", "k")
        assertEquals(WebSearchMethods.NONE, r.method)
        assertEquals(4, r.attempts.size)
        assertTrue(r.attempts.all { it.outcome == "rejected" })
    }

    @Test fun modesAndLabels() {
        assertEquals(WebSearchMode.Auto, WebSearchMode.of(null))
        assertEquals(WebSearchMode.Off, WebSearchMode.of("off"))
        assertEquals("尚未检测", WebSearchMethods.label(""))
        assertTrue(WebSearchMethods.label(WebSearchMethods.NONE).contains("内置搜索"))
    }
}
