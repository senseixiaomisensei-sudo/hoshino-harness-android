// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package dev.hoshino.bridge.chatgpt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.Socket
import java.net.URLDecoder
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64

/** Sign-in with a fake issuer and JWKS (no network): authorize URL, callback, exchange, refresh, revoke, ID token, full flow. */
class ChatGptAuthTest {
    private lateinit var server: MockWebServer
    private lateinit var auth: ChatGptOAuth
    private val keys: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private var clock = 1_800_000_000_000L
    private val issuer get() = server.url("/").toString().trimEnd('/')

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        auth = ChatGptOAuth(OkHttpClient(), issuer, now = { clock })
    }

    @After fun tearDown() { server.shutdown() }

    private fun b64(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)

    private fun jwt(claims: String, kid: String = "k1", key: KeyPair = keys, alg: String = "RS256"): String {
        val head = b64("""{"alg":"$alg","kid":"$kid","typ":"JWT"}""".toByteArray())
        val body = b64(claims.toByteArray())
        val sig = Signature.getInstance("SHA256withRSA").run { initSign(key.private); update("$head.$body".toByteArray()); sign() }
        return "$head.$body.${b64(sig)}"
    }

    private fun jwks(kid: String = "k1"): MockResponse {
        val pub = keys.public as RSAPublicKey
        fun unsigned(b: java.math.BigInteger) = b.toByteArray().let { if (it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
        return MockResponse().setBody("""{"keys":[{"kty":"RSA","kid":"$kid","alg":"RS256","n":"${b64(unsigned(pub.modulus))}","e":"${b64(unsigned(pub.publicExponent))}"}]}""")
    }

    private fun claims(aud: String = "client-1", nonce: String = "n", exp: Long = clock / 1000 + 3600, iss: String = issuer) =
        """{"iss":"$iss","aud":"$aud","sub":"user-1","email":"a@b.test","nonce":"$nonce","exp":$exp,"iat":${clock / 1000}}"""

    private fun query(url: String): Map<String, String> = url.toHttpUrl().let { u -> u.queryParameterNames.associateWith { u.queryParameter(it)!! } }

    // ---------- authorize URL and PKCE ----------

    @Test fun eachAttemptHasFreshRandomValuesAndAnS256Challenge() {
        val a = auth.newAttempt("http://127.0.0.1:1/auth/callback")
        val b = auth.newAttempt("http://127.0.0.1:1/auth/callback")
        assertTrue(a.state != b.state && a.nonce != b.nonce && a.verifier != b.verifier)
        assertTrue(a.verifier.length in 43..128)
        assertEquals(b64(MessageDigest.getInstance("SHA-256").digest(a.verifier.toByteArray())), a.challenge)
        assertFalse(a.challenge.contains('='))
        assertFalse("attempt toString never prints secrets", a.toString().contains(a.verifier))
    }

    @Test fun firstSignInRegistersWithTheAppNameAndHostId() {
        val a = auth.newAttempt("http://127.0.0.1:4321/auth/callback")
        val url = auth.authorizeUrl(a, clientId = null, hostId = "urn:uuid:h", idTokenHint = "old", loginHint = "a@b.test")
        assertTrue(url.startsWith("$issuer/api/accounts/authorize?"))
        val q = query(url)
        assertEquals("dynamic_agent_client", q["client_id"])
        assertEquals("Hoshino", q["agent_name_hint"])
        assertEquals("urn:uuid:h", q["ext_agent_host_id"])
        assertEquals("code", q["response_type"])
        assertEquals("http://127.0.0.1:4321/auth/callback", q["redirect_uri"])
        assertEquals("openid profile email offline_access resource.invoke chatgpt.tokens.use.direct", q["scope"])
        assertEquals("https://api.openai.com/v1", q["resource"])
        assertEquals(a.state, q["state"])
        assertEquals(a.nonce, q["nonce"])
        assertEquals("S256", q["code_challenge_method"])
        assertEquals(a.challenge, q["code_challenge"])
        assertNull("first registration sends no hints", q["id_token_hint"])
        assertNull(q["login_hint"])
    }

    @Test fun laterSignInsReuseTheIssuedClientAndSendHints() {
        val q = query(auth.authorizeUrl(auth.newAttempt("http://127.0.0.1:1/auth/callback"), "client-1", "urn:uuid:h", "idt", "a@b.test"))
        assertEquals("client-1", q["client_id"])
        assertNull(q["agent_name_hint"])
        assertEquals("idt", q["id_token_hint"])
        assertEquals("a@b.test", q["login_hint"])
    }

    @Test fun hostIdIsAnUuidUrn() { assertTrue(ChatGptOAuth.newHostId().matches(Regex("urn:uuid:[0-9a-f-]{36}"))) }

    // ---------- callback ----------

    @Test fun callbackChecksStateAndClientId() {
        val a = auth.newAttempt("r")
        assertEquals("c" to "issued", auth.readCallback(mapOf("state" to a.state, "code" to "c", "client_id" to "issued"), a, null))
        assertEquals("c" to "client-1", auth.readCallback(mapOf("state" to a.state, "code" to "c"), a, "client-1"))
        expect(SignInFailure.StateMismatch) { auth.readCallback(mapOf("state" to "x", "code" to "c"), a, null) }
        expect(SignInFailure.StateMismatch) { auth.readCallback(mapOf("code" to "c"), a, null) }
        expect(SignInFailure.Denied) { auth.readCallback(mapOf("state" to a.state, "error" to "access_denied"), a, null) }
        expect(SignInFailure.ClientMismatch) { auth.readCallback(mapOf("state" to a.state, "code" to "c", "client_id" to "other"), a, "client-1") }
        expect(SignInFailure.NoClientId) { auth.readCallback(mapOf("state" to a.state, "code" to "c"), a, null) }
    }

    // ---------- token exchange ----------

    @Test fun exchangeAndRefreshPostTheDocumentedForms() = runBlocking {
        val tokens = """{"access_token":"at","refresh_token":"rt","id_token":"it","token_type":"Bearer","expires_in":3600,
            "scope":"chatgpt.tokens.use.direct email offline_access openid profile resource.invoke"}"""
        server.enqueue(MockResponse().setBody(tokens))
        server.enqueue(MockResponse().setBody(tokens))
        val a = auth.newAttempt("http://127.0.0.1:9/auth/callback")
        val ok = auth.exchange("client-1", "code-1", a) as TokenResult.Ok
        assertEquals(TokenSet("at", "rt", "it", 3600, listOf("chatgpt.tokens.use.direct", "email", "offline_access", "openid", "profile", "resource.invoke")), ok.tokens)
        assertFalse(ok.tokens.toString().contains("at,") || ok.tokens.toString().contains("rt"))
        val first = server.takeRequest()
        assertEquals("/api/accounts/oauth/token", first.path)
        assertEquals(
            mapOf("grant_type" to "authorization_code", "client_id" to "client-1", "code" to "code-1", "code_verifier" to a.verifier,
                "redirect_uri" to "http://127.0.0.1:9/auth/callback", "resource" to "https://api.openai.com/v1"),
            form(first.body.readUtf8()),
        )
        auth.refresh("client-1", "rt")
        assertEquals(
            mapOf("grant_type" to "refresh_token", "client_id" to "client-1", "refresh_token" to "rt", "resource" to "https://api.openai.com/v1"),
            form(server.takeRequest().body.readUtf8()),
        )
    }

    @Test fun refreshErrorsSayWhetherTheTokenIsGone() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}"""))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"code":"refresh_token_reused"}}"""))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"invalid_client"}"""))
        server.enqueue(MockResponse().setResponseCode(503))
        assertEquals(TokenResult.Rejected("invalid_grant", true), auth.refresh("c", "r"))
        assertEquals(TokenResult.Rejected("refresh_token_reused", true), auth.refresh("c", "r"))
        assertEquals(TokenResult.Rejected("invalid_client", false), auth.refresh("c", "r"))
        assertEquals(503, (auth.refresh("c", "r") as TokenResult.Failed).status)
    }

    @Test fun revokePostsTheRefreshToken() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))
        assertTrue(auth.revoke("client-1", "rt"))
        val r = server.takeRequest()
        assertEquals("/api/accounts/oauth/revoke", r.path)
        assertEquals(mapOf("token" to "rt", "token_type_hint" to "refresh_token", "client_id" to "client-1"), form(r.body.readUtf8()))
        server.enqueue(MockResponse().setResponseCode(500))
        assertFalse(auth.revoke("client-1", "rt"))
    }

    // ---------- ID token ----------

    @Test fun idTokenIsVerifiedAgainstJwksAndClaims() = runBlocking {
        server.enqueue(jwks())
        assertEquals(IdClaims("user-1", "a@b.test"), auth.verifyIdToken(jwt(claims()), "client-1", "n"))
        assertEquals("/.well-known/jwks.json", server.takeRequest().path)
        expectSuspend(SignInFailure.IdToken) { auth.verifyIdToken(jwt(claims(aud = "other")), "client-1", "n") }
        expectSuspend(SignInFailure.IdToken) { auth.verifyIdToken(jwt(claims(nonce = "x")), "client-1", "n") }
        expectSuspend(SignInFailure.IdToken) { auth.verifyIdToken(jwt(claims(iss = "https://evil.test")), "client-1", "n") }
        expectSuspend(SignInFailure.IdToken) { auth.verifyIdToken(jwt(claims(exp = clock / 1000 - 3600)), "client-1", "n") }
        expectSuspend(SignInFailure.IdToken) { auth.verifyIdToken(jwt(claims(), alg = "none"), "client-1", "n") }
        val stranger = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        expectSuspend(SignInFailure.IdToken) { auth.verifyIdToken(jwt(claims(), key = stranger), "client-1", "n") }
        // Unknown kid: fetch the JWKS once more; still unknown means rejected.
        server.enqueue(jwks())
        expectSuspend(SignInFailure.IdToken) { auth.verifyIdToken(jwt(claims(), kid = "k2"), "client-1", "n") }
        assertEquals(2, server.requestCount)
    }

    @Test fun audienceMayBeAnArray() = runBlocking {
        server.enqueue(jwks())
        val c = claims().replace("\"aud\":\"client-1\"", "\"aud\":[\"x\",\"client-1\"]")
        assertEquals("user-1", auth.verifyIdToken(jwt(c), "client-1", "n").sub)
    }

    // ---------- access tokens ----------

    private fun session(expiresAt: Long = clock + 3_600_000) = ChatGptSession(
        clientId = "client-1", sub = "user-1", email = "a@b.test", accessToken = "at", refreshToken = "rt", idToken = "it",
        expiresAtMs = expiresAt, scopes = listOf(ChatGptOAuth.PLAN_SCOPE),
    )

    @Test fun tokensRefreshNearExpiryAndStoreTheRotatedPair() = runBlocking {
        var stored: ChatGptSession? = session()
        val tokens = ChatGptTokens(auth, { stored }, { stored = it }, now = { clock })
        assertEquals("at", tokens.get(false))
        assertEquals(0, server.requestCount)
        stored = session(expiresAt = clock + 60_000)
        server.enqueue(MockResponse().setBody("""{"access_token":"at2","refresh_token":"rt2","expires_in":3600,"scope":"${ChatGptOAuth.PLAN_SCOPE} openid"}"""))
        assertEquals("at2", tokens.get(false))
        assertEquals("rt2", stored!!.refreshToken)
        assertEquals(clock + 3_600_000, stored!!.expiresAtMs)
        // Rejected by the API: refresh even before expiry.
        server.enqueue(MockResponse().setBody("""{"access_token":"at3","expires_in":3600}"""))
        assertEquals("at3", tokens.get(true))
        assertEquals("rt2", stored!!.refreshToken)
        server.takeRequest()
        assertEquals("uses the rotated refresh token", "rt2", form(server.takeRequest().body.readUtf8())["refresh_token"])
    }

    @Test fun concurrentRefreshesAreSerialized() = runBlocking {
        var stored: ChatGptSession? = session(expiresAt = 0)
        val tokens = ChatGptTokens(auth, { stored }, { stored = it }, now = { clock })
        server.enqueue(MockResponse().setBody("""{"access_token":"at2","refresh_token":"rt2","expires_in":3600,"scope":"${ChatGptOAuth.PLAN_SCOPE}"}""").setBodyDelay(200, java.util.concurrent.TimeUnit.MILLISECONDS))
        val results = (1..5).map { async(Dispatchers.IO) { tokens.get(false) } }.map { it.await() }
        assertEquals(List(5) { "at2" }, results)
        assertEquals("one refresh only: the refresh token is single-use", 1, server.requestCount)
    }

    @Test fun deadRefreshTokenSignsOutButTemporaryFailuresKeepIt() = runBlocking {
        var stored: ChatGptSession? = session(expiresAt = 0)
        val tokens = ChatGptTokens(auth, { stored }, { stored = it }, now = { clock })
        server.enqueue(MockResponse().setResponseCode(503))
        try { tokens.get(false); fail() } catch (e: ChatGptAccessException.Transient) { }
        assertTrue(stored!!.signedIn)
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}"""))
        try { tokens.get(false); fail() } catch (e: ChatGptAccessException.SignInExpired) { }
        assertFalse(stored!!.signedIn)
        assertEquals("client-1", stored!!.clientId)
        try { tokens.get(false); fail() } catch (e: ChatGptAccessException.SignInExpired) { }
    }

    @Test fun refreshWithoutThePlanScopeIsNotEligible() = runBlocking {
        var stored: ChatGptSession? = session(expiresAt = 0)
        val tokens = ChatGptTokens(auth, { stored }, { stored = it }, now = { clock })
        server.enqueue(MockResponse().setBody("""{"access_token":"at2","expires_in":3600,"scope":"openid email"}"""))
        try { tokens.get(false); fail() } catch (e: ChatGptAccessException.NotEligible) { assertTrue(e.message!!.contains("Plus")) }
    }

    @Test fun sessionRoundTripsThroughJsonAndHidesTokens() {
        val s = session()
        assertEquals(s, ChatGptSession.fromJson(s.toJson()))
        assertNull(ChatGptSession.fromJson("not json"))
        assertFalse(s.toString().contains("\"at\"") || s.toString().contains("rt"))
        assertFalse(s.signedOut().signedIn)
        assertEquals("client-1", s.signedOut().clientId)
    }

    // ---------- loopback ----------

    @Test fun loopbackBindsLocalhostAndHandlesOneCallback() = runBlocking {
        LoopbackCallback.open().use { cb ->
            assertTrue(cb.redirectUri.matches(Regex("""http://127\.0\.0\.1:\d+/auth/callback""")))
            val client = OkHttpClient()
            val waiting = async(Dispatchers.IO) { cb.await(10_000, { it["state"] == "s" }) { "<p>ok ${it["code"]}</p>" } }
            withContext(Dispatchers.IO) {
                val miss = client.newCall(Request.Builder().url("http://127.0.0.1:${cb.port}/favicon.ico").build()).execute()
                assertEquals(404, miss.code); miss.close()
                val stranger = client.newCall(Request.Builder().url("${cb.redirectUri}?code=x&state=other").build()).execute()
                assertEquals("a callback from another attempt is refused", 400, stranger.code); stranger.close()
                val hit = client.newCall(Request.Builder().url("${cb.redirectUri}?code=a%20b&state=s").build()).execute()
                assertEquals(200, hit.code)
                assertEquals("<p>ok a b</p>", hit.body!!.string())
            }
            assertEquals(mapOf("code" to "a b", "state" to "s"), waiting.await())
            assertTrue(InetAddress.getByName(LoopbackCallback.HOST).isLoopbackAddress)
        }
    }

    @Test fun loopbackTimesOutAndClosesThePort() = runBlocking {
        val cb = LoopbackCallback.open()
        val port = cb.port
        assertNull(cb.await(400) { "" })
        cb.close()
        assertTrue("closed port no longer accepts", runCatching { Socket("127.0.0.1", port).close() }.isFailure)
    }

    // ---------- full sign-in ----------

    /** Fake authorization server: the token response signs an ID token for this attempt's nonce; [scope] is what it grants. */
    private fun authServer(nonce: () -> String, scope: String) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/.well-known/jwks.json" -> jwks()
                "/api/accounts/oauth/token" -> MockResponse().setBody(
                    """{"access_token":"at","refresh_token":"rt","id_token":"${jwt(claims(aud = "issued-1", nonce = nonce()))}","expires_in":3600,"scope":"$scope"}""",
                )
                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    /** Fake browser: read the authorize URL and return to the callback with a code and [extra]. */
    private fun browser(seen: MutableMap<String, String>, extra: (Map<String, String>) -> String): (String) -> Unit = { url ->
        val q = query(url)
        seen.putAll(q)
        Thread { OkHttpClient().newCall(Request.Builder().url("${q["redirect_uri"]}?${extra(q)}").build()).execute().close() }.start()
    }

    @Test fun fullSignInRegistersExchangesVerifiesAndChecksThePlanScope() = runBlocking {
        val seen = mutableMapOf<String, String>()
        authServer({ seen["nonce"]!! }, "openid email ${ChatGptOAuth.PLAN_SCOPE}")
        var registered: String? = null
        val session = signInWithChatGpt(
            auth, "urn:uuid:h", clientId = null, previous = null,
            openBrowser = browser(seen) { q -> "code=c1&state=${q["state"]}&client_id=issued-1" },
            onRegistered = { registered = it }, page = { "done" }, now = { clock },
        )
        assertEquals("dynamic_agent_client", seen["client_id"])
        assertTrue(seen["redirect_uri"]!!.matches(Regex("""http://127\.0\.0\.1:\d+/auth/callback""")))
        assertEquals("issued-1", registered)
        assertEquals(
            ChatGptSession("issued-1", "user-1", "a@b.test", "at", "rt", session.idToken, clock + 3_600_000, listOf("openid", "email", ChatGptOAuth.PLAN_SCOPE)),
            session,
        )
        assertTrue(session.planUsage)
    }

    @Test fun signInAgainUsesTheIssuedClientAndHints() = runBlocking {
        val seen = mutableMapOf<String, String>()
        authServer({ seen["nonce"]!! }, ChatGptOAuth.PLAN_SCOPE)
        val previous = session().copy(clientId = "issued-1", idToken = "old-id-token")
        signInWithChatGpt(auth, "urn:uuid:h", "issued-1", previous,
            openBrowser = browser(seen) { q -> "code=c1&state=${q["state"]}" }, onRegistered = { fail("already registered") }, page = { "" }, now = { clock })
        assertEquals("issued-1", seen["client_id"])
        assertEquals("old-id-token", seen["id_token_hint"])
        assertEquals("a@b.test", seen["login_hint"])
    }

    @Test fun signInWithoutThePlanScopeFails() = runBlocking {
        val seen = mutableMapOf<String, String>()
        authServer({ seen["nonce"]!! }, "openid email")
        try {
            signInWithChatGpt(
                auth, "urn:uuid:h", clientId = "issued-1", previous = null,
                openBrowser = browser(seen) { q -> "code=c1&state=${q["state"]}" },
                onRegistered = { fail("already registered") }, page = { "done" }, now = { clock },
            )
            fail()
        } catch (e: SignInException) {
            assertEquals(SignInFailure.NoPlanScope, e.reason)
            assertTrue(e.reason.message().contains("Plus"))
        }
        assertEquals("issued-1", seen["client_id"])
    }

    @Test fun deniedSignInReportsDenied() = runBlocking {
        val seen = mutableMapOf<String, String>()
        authServer({ seen["nonce"]!! }, ChatGptOAuth.PLAN_SCOPE)
        try {
            signInWithChatGpt(auth, "urn:uuid:h", null, null,
                openBrowser = browser(seen) { q -> "error=access_denied&state=${q["state"]}" }, onRegistered = {}, page = { "" }, now = { clock })
            fail()
        } catch (e: SignInException) { assertEquals(SignInFailure.Denied, e.reason) }
    }

    @Test fun abandonedSignInTimesOut() = runBlocking {
        try {
            signInWithChatGpt(auth, "urn:uuid:h", null, null, openBrowser = {}, onRegistered = {}, page = { "" }, timeoutMs = 300)
            fail()
        } catch (e: SignInException) {
            assertEquals(SignInFailure.TimedOut, e.reason)
        }
    }

    private fun form(body: String) = body.split('&').associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }

    private fun expect(reason: SignInFailure, block: () -> Unit) {
        try { block(); fail("expected $reason") } catch (e: SignInException) { assertEquals(reason, e.reason) }
    }

    private suspend fun expectSuspend(reason: SignInFailure, block: suspend () -> Unit) {
        try { block(); fail("expected $reason") } catch (e: SignInException) { assertEquals(reason, e.reason) }
    }
}
