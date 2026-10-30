// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package dev.hoshino.bridge.chatgpt

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * One ChatGPT account sign-in: the registered [clientId], the verified identity ([sub], [email]) and tokens.
 * Stored whole as one Keystore-wrapped secret. Signing out clears the tokens ([signedIn] false) but keeps the
 * registration and identity, so the next sign-in reuses the same client_id.
 */
data class ChatGptSession(
    val clientId: String,
    val sub: String,
    val email: String = "",
    val accessToken: String = "",
    val refreshToken: String = "",
    val idToken: String = "",
    val expiresAtMs: Long = 0,
    val scopes: List<String> = emptyList(),
) {
    val signedIn: Boolean get() = accessToken.isNotEmpty() || refreshToken.isNotEmpty()

    /** The user allowed this app to use the ChatGPT plan. */
    val planUsage: Boolean get() = ChatGptOAuth.PLAN_SCOPE in scopes

    fun signedOut() = copy(accessToken = "", refreshToken = "", idToken = "", expiresAtMs = 0)

    fun toJson(): String = JSONObject()
        .put("clientId", clientId).put("sub", sub).put("email", email)
        .put("accessToken", accessToken).put("refreshToken", refreshToken).put("idToken", idToken)
        .put("expiresAtMs", expiresAtMs).put("scopes", JSONArray(scopes)).toString()

    /** Never print tokens. */
    override fun toString() = "ChatGptSession(clientId=$clientId, signedIn=$signedIn, expiresAtMs=$expiresAtMs, scopes=$scopes)"

    companion object {
        fun fromJson(text: String?): ChatGptSession? = text?.takeIf { it.isNotBlank() }?.let {
            runCatching {
                val o = JSONObject(it)
                val clientId = o.getString("clientId")
                val scopes = o.optJSONArray("scopes")
                ChatGptSession(
                    clientId = clientId,
                    sub = o.optString("sub", ""),
                    email = o.optString("email", ""),
                    accessToken = o.optString("accessToken", ""),
                    refreshToken = o.optString("refreshToken", ""),
                    idToken = o.optString("idToken", ""),
                    expiresAtMs = o.optLong("expiresAtMs", 0),
                    scopes = if (scopes == null) emptyList() else (0 until scopes.length()).map { i -> scopes.getString(i) },
                )
            }.getOrNull()
        }
    }
}

/** Why a plan access token could not be produced. Messages are shown to people and never contain a token. */
sealed class ChatGptAccessException(message: String) : Exception(message) {
    /** Refresh token rejected or absent: tokens were cleared; sign in again. */
    class SignInExpired : ChatGptAccessException("ChatGPT 登录已失效，请到设置 → 模型 → ChatGPT 重新登录")
    /** Signed in, but this account did not grant (or is not eligible for) plan usage. */
    class NotEligible : ChatGptAccessException("这个 ChatGPT 账号没有授权 Hoshino 使用套餐额度（需要 ChatGPT Plus 或 Pro；免费版和 Go 不支持）")
    /** The token endpoint refused for a non-token reason (client configuration); tokens are kept. */
    class Rejected(code: String) : ChatGptAccessException("ChatGPT 授权服务拒绝了刷新请求（$code），请稍后重试或重新登录")
    /** Network or 5xx; tokens are kept. */
    class Transient(status: Int) : ChatGptAccessException(if (status > 0) "ChatGPT 授权服务暂时不可用（HTTP $status），请稍后重试" else "连不上 ChatGPT 授权服务，请检查网络后重试")
}

/**
 * The access token used per request: when it is close to expiry (under [refreshAheadMs]) or was rejected ([get] with fresh),
 * the refresh token is exchanged and the rotated pair saved ([save]). Only one refresh runs at a time — a refresh token is
 * single-use, so concurrent refreshes would invalidate each other. A dead refresh token clears the tokens and throws
 * [ChatGptAccessException.SignInExpired]; network or server errors throw [ChatGptAccessException.Transient] and keep the tokens.
 */
class ChatGptTokens(
    private val auth: ChatGptOAuth,
    private val load: () -> ChatGptSession?,
    private val save: (ChatGptSession) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val refreshAheadMs: Long = 5 * 60_000L,
) {
    private val lock = Mutex()

    suspend fun get(fresh: Boolean): String = lock.withLock {
        val s = load()?.takeIf { it.signedIn } ?: throw ChatGptAccessException.SignInExpired()
        if (!fresh && s.accessToken.isNotEmpty() && now() < s.expiresAtMs - refreshAheadMs) {
            if (!s.planUsage) throw ChatGptAccessException.NotEligible()
            return@withLock s.accessToken
        }
        if (s.refreshToken.isEmpty()) {
            save(s.signedOut())
            throw ChatGptAccessException.SignInExpired()
        }
        when (val r = auth.refresh(s.clientId, s.refreshToken)) {
            is TokenResult.Ok -> {
                val t = r.tokens
                val next = s.copy(
                    accessToken = t.accessToken,
                    refreshToken = t.refreshToken ?: s.refreshToken,
                    idToken = t.idToken ?: s.idToken,
                    expiresAtMs = now() + t.expiresInSec * 1000,
                    scopes = t.scopes.ifEmpty { s.scopes },
                )
                save(next)
                if (!next.planUsage) throw ChatGptAccessException.NotEligible()
                next.accessToken
            }
            is TokenResult.Rejected -> {
                if (r.terminal) {
                    save(s.signedOut())
                    throw ChatGptAccessException.SignInExpired()
                }
                // Client configuration and similar: retrying will not help, but the tokens are not dead either.
                throw ChatGptAccessException.Rejected(r.code)
            }
            is TokenResult.Failed -> throw ChatGptAccessException.Transient(r.status)
        }
    }
}

/**
 * Run one sign-in: open the loopback callback → [openBrowser] with the authorize URL → wait → check → exchange →
 * verify the ID token → require the plan scope. [clientId] is the previously registered one (null the first time; a newly
 * issued one is handed to [onRegistered] before the exchange, so a failed exchange never re-registers).
 * [previous] supplies the ID token and email hints. Failures throw [SignInException].
 * [page] renders the HTML shown in the browser after the callback (an `error` parameter means the user cancelled).
 */
suspend fun signInWithChatGpt(
    auth: ChatGptOAuth,
    hostId: String,
    clientId: String?,
    previous: ChatGptSession?,
    openBrowser: (String) -> Unit,
    onRegistered: (String) -> Unit,
    page: (Map<String, String>) -> String,
    timeoutMs: Long = 5 * 60_000L,
    now: () -> Long = System::currentTimeMillis,
): ChatGptSession {
    val (params, attempt) = LoopbackCallback.open().use { cb ->
        val attempt = auth.newAttempt(cb.redirectUri)
        val hint = previous?.takeIf { it.signedIn && it.clientId == clientId }
        openBrowser(auth.authorizeUrl(attempt, clientId, hostId, hint?.idToken, previous?.email?.takeIf { clientId != null }))
        (cb.await(timeoutMs, { it["state"] == attempt.state }, page) ?: throw SignInException(SignInFailure.TimedOut)) to attempt
    }
    val (code, issued) = auth.readCallback(params, attempt, clientId)
    if (issued != clientId) onRegistered(issued)
    val tokens = when (val r = auth.exchange(issued, code, attempt)) {
        is TokenResult.Ok -> r.tokens
        is TokenResult.Rejected -> throw SignInException(SignInFailure.Exchange, listOfNotNull(r.code, r.detail).joinToString(" — "))
        is TokenResult.Failed -> throw SignInException(SignInFailure.Network, if (r.status > 0) "HTTP ${r.status}" else "network", r.cause)
    }
    val idToken = tokens.idToken ?: throw SignInException(SignInFailure.IdToken, "no id_token")
    val who = auth.verifyIdToken(idToken, issued, attempt.nonce)
    if (ChatGptOAuth.PLAN_SCOPE !in tokens.scopes) throw SignInException(SignInFailure.NoPlanScope)
    return ChatGptSession(
        clientId = issued, sub = who.sub, email = who.email,
        accessToken = tokens.accessToken, refreshToken = tokens.refreshToken.orEmpty(), idToken = idToken,
        expiresAtMs = now() + tokens.expiresInSec * 1000, scopes = tokens.scopes,
    )
}

/** People-facing explanation for a failed sign-in (Mika's wording, adapted). */
fun SignInFailure.message(): String = when (this) {
    SignInFailure.Denied -> "已取消登录。如果授权页提示「所需权限不可用」：使用套餐额度需要 ChatGPT Plus 或 Pro，免费版和 Go 账号可以改用其他服务商的 API Key。"
    SignInFailure.StateMismatch, SignInFailure.ClientMismatch -> "登录回来的信息和这次对不上，请再试一次。"
    SignInFailure.NoClientId -> "ChatGPT 没有完成对 Hoshino 的注册，请再试一次。"
    SignInFailure.TimedOut -> "登录超时了，请再试一次。"
    SignInFailure.Exchange -> "ChatGPT 没有接受这次登录。如果这个账号不是 Plus 或 Pro（免费版、Go 都不行），就不能把套餐额度用在 Hoshino 里；否则请再试一次。"
    SignInFailure.IdToken -> "核对不了你的 ChatGPT 账号，请再试一次。"
    SignInFailure.NoPlanScope -> "没有允许 Hoshino 使用你的 ChatGPT 套餐。请重新登录并允许使用套餐额度（需要 Plus 或 Pro）。"
    SignInFailure.Network -> "连不上 ChatGPT，请检查网络后再试。"
}
