package dev.hoshino.bridge

import android.content.Context
import dev.hoshino.bridge.chatgpt.ChatGptAccessException
import dev.hoshino.bridge.chatgpt.ChatGptModels
import dev.hoshino.bridge.chatgpt.ChatGptOAuth
import dev.hoshino.bridge.chatgpt.ChatGptSession
import dev.hoshino.bridge.chatgpt.ChatGptTokens
import dev.hoshino.bridge.chatgpt.SignInException
import dev.hoshino.bridge.chatgpt.SignInFailure
import dev.hoshino.bridge.chatgpt.message
import dev.hoshino.bridge.chatgpt.signInWithChatGpt
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/**
 * State shown in Settings. [email] comes from the verified ID token; [needsRelogin] is set once after upgrading from the
 * retired Codex-CLI login so the user is told to sign in again.
 */
data class ChatGptAuthState(
    val status: String = "未登录",
    val signedIn: Boolean = false,
    val waiting: Boolean = false,
    val email: String = "",
    val needsRelogin: Boolean = false,
    val error: String? = null,
)

/** Where sign-in and plan requests go. Tests point both at a local fixture. */
data class ChatGptOAuthEndpoints(val issuer: String = ChatGptOAuth.ISSUER, val apiBase: String = ChatGptOAuth.API_BASE)

/**
 * "Sign in with ChatGPT" exactly as Mika does it (see dev.hoshino.bridge.chatgpt): dynamic agent client registration,
 * loopback callback on any port, PKCE + state + nonce, RS256 ID-token verification, mandatory plan scope, serialized
 * refresh, revoke on sign-out. The whole session is one Keystore-wrapped secret ([SESSION]); the device id and issued
 * client_id are not secrets and live in plain preferences, like Mika's chatgpt-host.json.
 *
 * Never logs tokens or the authorize URL (it can carry an ID token hint).
 */
class ChatGptAuth(private val context: Context, val endpoints: ChatGptOAuthEndpoints = ChatGptOAuthEndpoints()) {
    private val vault = ProviderStore(context)
    private val registration = context.getSharedPreferences("hoshino-chatgpt-registration", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder().followRedirects(false).connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    val oauth = ChatGptOAuth(http, endpoints.issuer)
    private val tokens = ChatGptTokens(oauth, ::session, ::saveSession)
    @Volatile private var signIn: Job? = null

    val state = MutableStateFlow(ChatGptAuthState())

    init {
        val legacy = LEGACY_REFS.any { vault.hasSecret(it) }
        if (legacy) {
            // The retired Codex-CLI client tokens (chatgpt.com backend) cannot be used with the new route: clear them once.
            LEGACY_REFS.forEach { vault.removeSecret(it) }
            registration.edit().putBoolean(LEGACY_FLAG, true).apply()
        }
        publish()
    }

    fun session(): ChatGptSession? = ChatGptSession.fromJson(vault.resolve(SESSION))

    private fun saveSession(next: ChatGptSession) {
        vault.putSecret(SESSION, next.toJson())
        publish()
    }

    private fun publish(waiting: Boolean = false, error: String? = null) {
        val s = session()
        val relogin = registration.getBoolean(LEGACY_FLAG, false)
        val status = when {
            waiting -> "等待浏览器登录"
            s?.signedIn == true -> "已登录" + (s.email.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: "")
            relogin -> "登录方式已更新，请重新登录一次"
            error != null -> error
            s != null -> "已退出登录"
            else -> "未登录"
        }
        state.value = ChatGptAuthState(status, s?.signedIn == true, waiting, s?.email.orEmpty(), relogin && s?.signedIn != true, error)
    }

    /** Device id (generated once) and the client_id issued at the first registration, if any. */
    @Synchronized fun registration(): Pair<String, String?> {
        val host = registration.getString("hostId", null)?.takeIf { it.isNotBlank() } ?: ChatGptOAuth.newHostId().also { registration.edit().putString("hostId", it).apply() }
        return host to registration.getString("clientId", null)?.takeIf { it.isNotBlank() }
    }

    @Synchronized private fun rememberClient(clientId: String) { registration.edit().putString("clientId", clientId).apply() }

    /**
     * Start a browser sign-in. [openBrowser] receives the authorize URL (do not log it). Completion is reported through
     * [state]; [onSignedIn] runs after the session is stored (used to refresh the model list).
     */
    fun beginLogin(openBrowser: (String) -> Unit, onSignedIn: suspend () -> Unit = {}) {
        signIn?.cancel()
        publish(waiting = true)
        signIn = scope.launch {
            try {
                val (hostId, clientId) = registration()
                val session = signInWithChatGpt(oauth, hostId, clientId, session(), openBrowser = { url -> scope.launch(Dispatchers.Main) { openBrowser(url) } },
                    onRegistered = ::rememberClient, page = ::page)
                registration.edit().remove(LEGACY_FLAG).apply()
                saveSession(session)
                runCatching { onSignedIn() }
            } catch (cancelled: CancellationException) {
                publish()
                throw cancelled
            } catch (failure: SignInException) {
                publish(error = failure.reason.message())
            } catch (failure: Exception) {
                publish(error = SignInFailure.Network.message())
            }
        }
    }

    fun cancelLogin() { signIn?.cancel(); signIn = null; publish() }

    private fun page(params: Map<String, String>): String {
        val cancelled = params.containsKey("error")
        val text = if (cancelled) "已取消登录，可以回到 Hoshino 了。" else "已用 ChatGPT 登录，可以回到 Hoshino 了。"
        return "<!doctype html><meta charset=utf-8><meta name=viewport content=\"width=device-width\"><title>Hoshino</title><p style=\"font:16px sans-serif;margin:32px\">$text</p>"
    }

    /**
     * Plan access token for one request; refreshes ahead of expiry, or immediately when [forceRefresh] (the server said 401).
     * A dead refresh token signs the user out locally and throws with a sign-in-again message.
     */
    suspend fun accessToken(forceRefresh: Boolean = false): String = withContext(Dispatchers.IO) {
        try { tokens.get(forceRefresh) } catch (failure: ChatGptAccessException) {
            if (failure is ChatGptAccessException.SignInExpired) publish(error = failure.message)
            throw failure
        }
    }

    /** The plan's model list from `GET /v1/models` (rows as in [ChatGptModels.parse]). */
    suspend fun listModels(): JSONArray = withContext(Dispatchers.IO) {
        try { ChatGptModels.fetch(http, endpoints.apiBase, accessToken()) }
        catch (failure: dev.hoshino.bridge.chatgpt.ModelListException) {
            if (failure.status != 401) throw failure
            ChatGptModels.fetch(http, endpoints.apiBase, accessToken(forceRefresh = true))
        }
    }

    /** Revoke the refresh token on the server (best effort), then clear local tokens. Registration and identity stay. */
    suspend fun signOut(): Boolean = withContext(Dispatchers.IO) {
        signIn?.cancel()
        val s = session() ?: return@withContext true.also { publish() }
        val revoked = s.refreshToken.isEmpty() || oauth.revoke(s.clientId, s.refreshToken)
        saveSession(s.signedOut())
        revoked
    }

    fun logout() { scope.launch { runCatching { signOut() } } }

    /** Forget the registration too (used when the ChatGPT provider is removed). The device id stays. */
    fun forget() {
        signIn?.cancel()
        vault.removeSecret(SESSION)
        registration.edit().remove("clientId").remove(LEGACY_FLAG).apply()
        publish()
    }

    fun close() { signIn?.cancel(); scope.cancel() }

    companion object {
        const val SESSION = "CHATGPT_SESSION"
        /** The provider's credential reference; resolved per request through [accessToken]. */
        const val CREDENTIAL_REF = "KEY_OPENAI_CODEX"
        private const val LEGACY_FLAG = "legacy-codex-login"
        /** Secrets written by the retired Codex-CLI login (client app_EMoamEEZ73f0CkXaXp7hrann). */
        val LEGACY_REFS = listOf("CHATGPT_ACCESS_TOKEN", "CHATGPT_REFRESH_TOKEN", "CHATGPT_ID_TOKEN", "CHATGPT_TOKEN_EXPIRES")
        @Volatile private var instance: ChatGptAuth? = null
        fun installForTest(context: Context, endpoints: ChatGptOAuthEndpoints): ChatGptAuth {
            check(context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
            check(java.net.URI(endpoints.issuer).host in setOf("127.0.0.1", "localhost"))
            instance?.close()
            return ChatGptAuth(context.applicationContext, endpoints).also { instance = it }
        }
        fun get(context: Context) = instance ?: synchronized(this) { instance ?: ChatGptAuth(context.applicationContext).also { instance = it } }
    }
}
