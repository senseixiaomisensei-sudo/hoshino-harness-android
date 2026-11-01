// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package dev.hoshino.bridge.oauth

import dev.hoshino.bridge.phone.adb.OwnListeningPorts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CompletableFuture

/** OpenRouter 官方 PKCE 登录：授权页地址、回调核对、换 Key、整个流程走共用的本机回调。 */
class OpenRouterAuthTest {
    private lateinit var server: MockWebServer
    private val http = OkHttpClient()

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    private fun auth() = OpenRouterAuth(http, authUrl = server.url("/auth").toString(), keysUrl = server.url("/api/v1/auth/keys").toString())

    private fun json(code: Int = 200, body: String) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    /** RFC 7636 附录 B 的例子。ChatGPT 登录也用同一个实现。 */
    @Test fun pkceChallengeIsS256OfTheVerifier() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        val v = Pkce.verifier()
        assertTrue(v.length in 43..128)
        assertTrue(v, v.all { it.isLetterOrDigit() || it in "-._~" })
        assertNotEquals(v, Pkce.verifier())
        assertNotEquals(Pkce.state(), Pkce.state())
        val chatGpt = dev.hoshino.bridge.chatgpt.SignInAttempt("s", "n", "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk", "http://127.0.0.1:1/auth/callback")
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", chatGpt.challenge)
    }

    @Test fun authorizeUrlCarriesCallbackChallengeLabelAndState() {
        val a = OpenRouterAuth(http)
        val attempt = a.newAttempt("http://localhost:4321/auth/callback")
        val url = a.authorizeUrl(attempt).toHttpUrl()
        assertEquals("openrouter.ai", url.host)
        assertEquals("/auth", url.encodedPath)
        assertEquals("http://localhost:4321/auth/callback", url.queryParameter("callback_url"))
        assertEquals(attempt.challenge, url.queryParameter("code_challenge"))
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        assertEquals("Hoshino Harness", url.queryParameter("key_label"))
        assertEquals(attempt.state, url.queryParameter("state"))
        // 校验串本身不出现在授权页地址里，也不出现在 toString 里
        assertFalse(url.toString().contains(attempt.verifier))
        assertFalse(attempt.toString().contains(attempt.verifier))
    }

    @Test fun callbackNeedsTheSameStateAndACode() {
        val a = auth()
        val attempt = a.newAttempt("http://localhost:1/auth/callback")
        assertTrue(a.accepts(mapOf("code" to "c", "state" to attempt.state), attempt))
        assertFalse(a.accepts(mapOf("code" to "c", "state" to "other"), attempt))
        assertFalse(a.accepts(mapOf("code" to "c"), attempt))
        assertTrue(a.accepts(mapOf("error" to "access_denied"), attempt))
        assertFalse(a.accepts(emptyMap(), attempt))
        assertEquals("c", a.readCallback(mapOf("code" to "c", "state" to attempt.state), attempt))
        val denied = runCatching { a.readCallback(mapOf("error" to "access_denied"), attempt) }.exceptionOrNull() as AccountSignInException
        assertEquals(AccountSignInFailure.Denied, denied.reason)
        val forged = runCatching { a.readCallback(mapOf("code" to "c", "state" to "x"), attempt) }.exceptionOrNull() as AccountSignInException
        assertEquals(AccountSignInFailure.Rejected, forged.reason)
    }

    @Test fun exchangePostsCodeAndVerifierAndReturnsTheKey() = runBlocking {
        val a = auth()
        val attempt = a.newAttempt("http://localhost:1/auth/callback")
        server.enqueue(json(body = """{"key":"sk-or-v1-SECRET","user_id":"u1"}"""))
        assertEquals("sk-or-v1-SECRET", a.exchange("the-code", attempt))
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/v1/auth/keys", req.path)
        assertTrue(req.getHeader("Content-Type").orEmpty().startsWith("application/json"))
        val body = JSONObject(req.body.readUtf8())
        assertEquals("the-code", body.getString("code"))
        assertEquals(attempt.verifier, body.getString("code_verifier"))
        assertEquals("S256", body.getString("code_challenge_method"))
    }

    @Test fun exchangeErrorsKeepOnlyTheStatus() = runBlocking {
        val a = auth()
        val attempt = a.newAttempt("http://localhost:1/auth/callback")
        server.enqueue(json(403, """{"error":{"message":"Invalid code SHOULD-NOT-LEAK"}}"""))
        val expired = runCatching { a.exchange("c", attempt) }.exceptionOrNull() as AccountSignInException
        assertEquals(AccountSignInFailure.Expired, expired.reason)
        assertFalse(expired.message.orEmpty().contains("SHOULD-NOT-LEAK"))
        server.enqueue(json(400, """{"error":{"message":"bad method"}}"""))
        assertEquals(AccountSignInFailure.Rejected, (runCatching { a.exchange("c", attempt) }.exceptionOrNull() as AccountSignInException).reason)
        server.enqueue(json(200, """{"nokey":true}"""))
        assertEquals("no_key", (runCatching { a.exchange("c", attempt) }.exceptionOrNull() as AccountSignInException).code)
        server.enqueue(json(502, "{}"))
        assertEquals(AccountSignInFailure.Network, (runCatching { a.exchange("c", attempt) }.exceptionOrNull() as AccountSignInException).reason)
        // 每个原因都有中文说明
        AccountSignInFailure.entries.forEach { assertTrue(it.name, it.message().isNotBlank()) }
    }

    /** 整个流程：回调地址是本机 localhost，state 对不上的回 400 接着等；对上的换到 Key，浏览器收到结果页；端口用完即释放。 */
    @Test fun signInRunsThroughTheSharedLoopbackCallback() = runBlocking {
        server.enqueue(json(body = """{"key":"sk-or-v1-abc"}"""))
        val opened = CompletableFuture<String>()
        val pages = mutableListOf<AccountSignInException?>()
        val job = async(Dispatchers.IO) {
            signInWithOpenRouter(auth(), openBrowser = { opened.complete(it) }, page = { pages += it; "<p>done</p>" }, timeoutMs = 10_000)
        }
        val url = withContext(Dispatchers.IO) { opened.get() }.toHttpUrl()
        val callback = url.queryParameter("callback_url")!!.toHttpUrl()
        assertEquals("localhost", callback.host)
        assertEquals(LoopbackCallback.PATH, callback.encodedPath)
        assertTrue("扫无线调试端口时要跳过自己的回调端口", callback.port in OwnListeningPorts.snapshot())
        val bad = callback.newBuilder().host("127.0.0.1").addQueryParameter("code", "c").addQueryParameter("state", "forged").build()
        val badCode = withContext(Dispatchers.IO) { http.newCall(Request.Builder().url(bad).build()).execute().use { it.code } }
        assertEquals(400, badCode)
        val good = callback.newBuilder().host("127.0.0.1")
            .addQueryParameter("code", "the-code").addQueryParameter("state", url.queryParameter("state")).build()
        val page = withContext(Dispatchers.IO) { http.newCall(Request.Builder().url(good).build()).execute().use { it.body!!.string() } }
        assertEquals("<p>done</p>", page)
        assertEquals("sk-or-v1-abc", job.await())
        assertEquals(listOf<AccountSignInException?>(null), pages)
        assertFalse(callback.port in OwnListeningPorts.snapshot())
    }

    /** 用户在授权页拒绝：回调只带 error，结果页照样回，登录以 Denied 结束。 */
    @Test fun denialEndsTheSignIn() = runBlocking {
        val opened = CompletableFuture<String>()
        val job = async(Dispatchers.IO) {
            runCatching { signInWithOpenRouter(auth(), openBrowser = { opened.complete(it) }, page = { "<p>${it?.reason}</p>" }, timeoutMs = 10_000) }
        }
        val callback = withContext(Dispatchers.IO) { opened.get() }.toHttpUrl().queryParameter("callback_url")!!.toHttpUrl()
        val denied = callback.newBuilder().host("127.0.0.1").addQueryParameter("error", "access_denied").build()
        val page = withContext(Dispatchers.IO) { http.newCall(Request.Builder().url(denied).build()).execute().use { it.body!!.string() } }
        assertEquals("<p>Denied</p>", page)
        assertEquals(AccountSignInFailure.Denied, (job.await().exceptionOrNull() as AccountSignInException).reason)
        assertEquals(0, server.requestCount)
    }
}
