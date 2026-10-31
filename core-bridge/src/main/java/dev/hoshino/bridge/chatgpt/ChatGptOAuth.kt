// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package dev.hoshino.bridge.chatgpt

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One sign-in attempt: fresh random values checked on the callback and on the code exchange. */
data class SignInAttempt(val state: String, val nonce: String, val verifier: String, val redirectUri: String) {
    /** PKCE S256: SHA-256 of the verifier, base64url without padding. */
    val challenge: String get() = ChatGptOAuth.b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
    override fun toString() = "SignInAttempt(redirectUri=$redirectUri)"
}

/** Tokens returned by an authorization-code or refresh-token exchange; [scopes] is what was actually granted. */
data class TokenSet(val accessToken: String, val refreshToken: String?, val idToken: String?, val expiresInSec: Long, val scopes: List<String>) {
    override fun toString() = "TokenSet(expiresInSec=$expiresInSec, scopes=$scopes)"
}

/** Exchange outcome: tokens, rejected (with the server code; [terminal] means this refresh token is dead), or a transient failure. */
sealed interface TokenResult {
    data class Ok(val tokens: TokenSet) : TokenResult
    /** [detail] is error_description / error.message for people to read; it never contains a token. */
    data class Rejected(val code: String, val terminal: Boolean, val detail: String? = null) : TokenResult
    data class Failed(val status: Int, val cause: Throwable? = null) : TokenResult
}

/** Verified identity from the ID token: [sub] is the account id, [email] is display-only and may be empty. */
data class IdClaims(val sub: String, val email: String)

/** Why a sign-in failed; the UI explains each reason. */
enum class SignInFailure { Denied, StateMismatch, ClientMismatch, NoClientId, TimedOut, Exchange, IdToken, NoPlanScope, Network }

class SignInException(val reason: SignInFailure, detail: String = "", cause: Throwable? = null) :
    Exception(if (detail.isEmpty()) reason.name else "${reason.name}: $detail", cause)

internal data class RawResponse(val status: Int, val body: String)

internal suspend fun OkHttpClient.executeRaw(request: Request): RawResponse = suspendCancellableCoroutine { cont ->
    val call = newCall(request)
    cont.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) {
            response.use {
                try {
                    val raw = RawResponse(it.code, it.body?.string().orEmpty())
                    if (cont.isActive) cont.resume(raw)
                } catch (e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }
            }
        }
    })
}

/**
 * "Sign in with ChatGPT" for open-source apps that run on the user's device, using the user's ChatGPT plan:
 * a public client without a secret, authorization code + PKCE (S256). The first sign-in registers with
 * `dynamic_agent_client`; the callback returns a client_id issued for this account, used for every later
 * exchange, refresh and sign-in. [hostId] (`urn:uuid:…`) is generated once per device and kept.
 * The redirect must be `http://127.0.0.1:<any port>/auth/callback`.
 *
 * The ID token is verified with the published JWKS (RS256): issuer, audience (= client_id), expiry and this
 * attempt's nonce. The granted scopes must include [PLAN_SCOPE] before the plan can be used.
 */
class ChatGptOAuth(
    private val http: OkHttpClient,
    private val issuer: String = ISSUER,
    private val now: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
    private val appName: String = APP_NAME,
) {
    private val tokenUrl = "$issuer/api/accounts/oauth/token"
    private val revokeUrl = "$issuer/api/accounts/oauth/revoke"
    private val jwksUrl = "$issuer/.well-known/jwks.json"

    /** Last JWKS (kid → key); fetched again once when a kid is unknown (the server rotated keys). */
    @Volatile private var jwks: Map<String, JSONObject> = emptyMap()

    fun newAttempt(redirectUri: String) = SignInAttempt(randomString(), randomString(), randomString(48), redirectUri)

    /**
     * Authorization page URL. A null [clientId] is the first sign-in: register via `dynamic_agent_client` with the app name.
     * Later sign-ins send the previous ID token ([idTokenHint], may be expired; never after sign-out) and email ([loginHint]).
     * The URL may contain an ID token: never log it.
     */
    fun authorizeUrl(attempt: SignInAttempt, clientId: String?, hostId: String, idTokenHint: String? = null, loginHint: String? = null): String =
        "$issuer/api/accounts/authorize".toHttpUrl().newBuilder()
            .addQueryParameter("client_id", clientId ?: REGISTER_CLIENT_ID)
            .apply { if (clientId == null) addQueryParameter("agent_name_hint", appName) }
            .addQueryParameter("ext_agent_host_id", hostId)
            .apply {
                if (clientId != null && !idTokenHint.isNullOrEmpty()) addQueryParameter("id_token_hint", idTokenHint)
                if (clientId != null && !loginHint.isNullOrEmpty()) addQueryParameter("login_hint", loginHint)
            }
            .addQueryParameter("response_type", "code")
            .addQueryParameter("redirect_uri", attempt.redirectUri)
            .addQueryParameter("scope", SCOPES)
            .addQueryParameter("resource", RESOURCE)
            .addQueryParameter("state", attempt.state)
            .addQueryParameter("nonce", attempt.nonce)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("code_challenge", attempt.challenge)
            .build().toString()

    /**
     * Read callback parameters into (code, client_id for this attempt). The state is checked first; a denial, a wrong state,
     * a client_id different from the requested one, or a first registration without a client_id throws [SignInException].
     */
    fun readCallback(params: Map<String, String>, attempt: SignInAttempt, clientId: String?): Pair<String, String> {
        if (!MessageDigest.isEqual(params["state"].orEmpty().toByteArray(), attempt.state.toByteArray())) throw SignInException(SignInFailure.StateMismatch)
        params["error"]?.let { throw SignInException(SignInFailure.Denied, it.take(80)) }
        val code = params["code"]?.takeIf { it.isNotEmpty() } ?: throw SignInException(SignInFailure.Exchange, "no code")
        val issued = params["client_id"]?.takeIf { it.isNotEmpty() }
        if (clientId != null && issued != null && issued != clientId) throw SignInException(SignInFailure.ClientMismatch)
        val id = issued ?: clientId ?: throw SignInException(SignInFailure.NoClientId)
        return code to id
    }

    suspend fun exchange(clientId: String, code: String, attempt: SignInAttempt): TokenResult = token(
        FormBody.Builder().add("grant_type", "authorization_code").add("client_id", clientId).add("code", code)
            .add("code_verifier", attempt.verifier).add("redirect_uri", attempt.redirectUri).add("resource", RESOURCE).build(),
    )

    /** No scope parameter: keeps the scopes originally granted. */
    suspend fun refresh(clientId: String, refreshToken: String): TokenResult = token(
        FormBody.Builder().add("grant_type", "refresh_token").add("client_id", clientId).add("refresh_token", refreshToken)
            .add("resource", RESOURCE).build(),
    )

    /** Revoke the refresh token; true when the server confirms (HTTP 200, also for an already-invalid token). Keeps the registration. */
    suspend fun revoke(clientId: String, refreshToken: String): Boolean = try {
        val form = FormBody.Builder().add("token", refreshToken).add("token_type_hint", "refresh_token").add("client_id", clientId).build()
        http.executeRaw(Request.Builder().url(revokeUrl).post(form).build()).status == 200
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    private suspend fun token(form: FormBody): TokenResult {
        val raw = try {
            http.executeRaw(Request.Builder().url(tokenUrl).post(form).build())
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            return TokenResult.Failed(0, e)
        }
        val root = runCatching { JSONObject(raw.body) }.getOrNull()
        if (raw.status in 500..599) return TokenResult.Failed(raw.status)
        if (raw.status != 200) {
            val error = root?.opt("error")
            val code = (error as? JSONObject)?.str("code") ?: (error as? String)?.takeIf { it.isNotEmpty() } ?: root?.str("code") ?: "http_${raw.status}"
            val detail = root?.str("error_description") ?: (error as? JSONObject)?.str("message")
            return TokenResult.Rejected(code, code in TERMINAL_REFRESH_ERRORS, detail?.take(300))
        }
        val access = root?.str("access_token") ?: return TokenResult.Failed(raw.status, IllegalStateException("token response has no access_token"))
        return TokenResult.Ok(
            TokenSet(
                accessToken = access,
                refreshToken = root.str("refresh_token"),
                idToken = root.str("id_token"),
                expiresInSec = (root.opt("expires_in") as? Number)?.toLong()?.takeIf { it > 0 } ?: DEFAULT_EXPIRES_SEC,
                scopes = root.str("scope")?.split(' ')?.filter { it.isNotEmpty() }.orEmpty(),
            ),
        )
    }

    /** Verify the ID token: signature (JWKS, RS256), issuer, audience, expiry, nonce. Returns the identity or throws [SignInException]. */
    suspend fun verifyIdToken(idToken: String, clientId: String, nonce: String): IdClaims {
        fun fail(why: String): Nothing = throw SignInException(SignInFailure.IdToken, why)
        val pieces = idToken.split('.')
        if (pieces.size != 3) fail("not a JWT")
        val header = decodeJson(pieces[0]) ?: fail("bad header")
        val claims = decodeJson(pieces[1]) ?: fail("bad payload")
        if (header.str("alg") != "RS256") fail("alg")
        val kid = header.str("kid")
        val key = jwk(kid, refresh = false) ?: jwk(kid, refresh = true) ?: fail("unknown key")
        val verified = runCatching {
            val n = BigInteger(1, b64urlDecode(key.str("n")!!))
            val e = BigInteger(1, b64urlDecode(key.str("e")!!))
            val pub = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(n, e))
            Signature.getInstance("SHA256withRSA").run {
                initVerify(pub)
                update("${pieces[0]}.${pieces[1]}".toByteArray(Charsets.US_ASCII))
                verify(b64urlDecode(pieces[2]))
            }
        }.getOrDefault(false)
        if (!verified) fail("signature")
        if (claims.str("iss") != issuer) fail("issuer")
        val audOk = when (val aud = claims.opt("aud")) {
            is String -> aud == clientId
            is JSONArray -> (0 until aud.length()).any { aud.opt(it) == clientId }
            else -> false
        }
        if (!audOk) fail("audience")
        val exp = (claims.opt("exp") as? Number)?.toLong() ?: fail("no exp")
        if (exp * 1000 + CLOCK_SKEW_MS < now()) fail("expired")
        if (claims.str("nonce") != nonce) fail("nonce")
        val sub = claims.str("sub")?.takeIf { it.isNotEmpty() } ?: fail("no sub")
        val email = claims.str("email") ?: claims.optJSONObject(PROFILE_CLAIM)?.str("email") ?: ""
        return IdClaims(sub, email)
    }

    private suspend fun jwk(kid: String?, refresh: Boolean): JSONObject? {
        if (refresh || jwks.isEmpty()) {
            val raw = try { http.executeRaw(Request.Builder().url(jwksUrl).get().build()) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                throw SignInException(SignInFailure.Network, "jwks", e)
            }
            val keys = runCatching { JSONObject(raw.body).getJSONArray("keys") }.getOrNull()
            jwks = if (keys == null) emptyMap() else (0 until keys.length()).mapNotNull { keys.optJSONObject(it) }
                .filter { it.str("kty") == "RSA" }.associateBy { it.str("kid").orEmpty() }
        }
        return if (kid == null) jwks.values.singleOrNull() else jwks[kid]
    }

    private fun decodeJson(part: String): JSONObject? = runCatching { JSONObject(String(b64urlDecode(part), Charsets.UTF_8)) }.getOrNull()

    private fun randomString(bytes: Int = 32): String = ByteArray(bytes).also(random::nextBytes).let(::b64url)

    companion object {
        const val ISSUER = "https://auth.openai.com"
        const val REGISTER_CLIENT_ID = "dynamic_agent_client"
        const val APP_NAME = "Hoshino"
        const val PLAN_SCOPE = "chatgpt.tokens.use.direct"
        const val SCOPES = "openid profile email offline_access resource.invoke $PLAN_SCOPE"
        const val RESOURCE = "https://api.openai.com/v1"
        /** API root used with the plan (model list, Responses). */
        const val API_BASE = "https://api.openai.com/v1"
        /** Where the user checks and manages plan usage. */
        const val USAGE_URL = "https://chatgpt.com/settings/usage"
        private const val PROFILE_CLAIM = "https://api.openai.com/profile"
        private const val DEFAULT_EXPIRES_SEC = 3600L
        private const val CLOCK_SKEW_MS = 120_000L

        /** The refresh token can no longer be used: clear tokens and sign in again with the same client_id. */
        val TERMINAL_REFRESH_ERRORS = setOf(
            "invalid_grant", "invalid_refresh_token", "token_expired", "refresh_token_expired",
            "refresh_token_invalidated", "refresh_token_reused",
        )

        fun b64url(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
        fun b64urlDecode(s: String): ByteArray = Base64.getUrlDecoder().decode(s.trimEnd('='))

        /** Device id: a UUIDv4 URN generated once per device and kept. */
        fun newHostId(): String = "urn:uuid:${java.util.UUID.randomUUID()}"
    }
}

internal fun JSONObject.str(k: String): String? = (opt(k) as? String)?.takeIf { it.isNotEmpty() }
