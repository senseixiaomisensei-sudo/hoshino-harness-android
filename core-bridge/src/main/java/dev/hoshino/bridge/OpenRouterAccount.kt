package dev.hoshino.bridge

import android.content.Context
import dev.hoshino.bridge.oauth.AccountSignInException
import dev.hoshino.bridge.oauth.AccountSignInFailure
import dev.hoshino.bridge.oauth.OpenRouterAuth
import dev.hoshino.bridge.oauth.message
import dev.hoshino.bridge.oauth.signInWithOpenRouter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** 设置页显示的 OpenRouter 账号登录状态。 */
data class AccountLoginState(
    val status: String = "未登录 · 用 OpenRouter 账号授权，不用手填 Key",
    val signedIn: Boolean = false,
    val waiting: Boolean = false,
    val error: String? = null,
)

/**
 * 「用账号登录 › OpenRouter」：OpenRouter 官方 PKCE 授权换来的 Key 存成单独的供应商 [PROVIDER_ID]（`openrouter-oauth`），
 * 不顶掉用户手填 Key 的 OpenRouter。Key 只经 [ProviderStore] 用 Keystore 包裹保存（[CREDENTIAL_REF]），不进日志。
 * 回调复用 ChatGPT 登录的本机回调（[dev.hoshino.bridge.oauth.LoopbackCallback]）。
 * 退出只清本机：删掉这个供应商与 Key，不调用 OpenRouter（Key 仍在用户的 OpenRouter 账号里，可在 [OpenRouterAuth.KEYS_PAGE] 删除）。
 */
class OpenRouterAccount(private val context: Context, private val auth: OpenRouterAuth = OpenRouterAuth(defaultHttp())) {
    private val vault = ProviderStore(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var signIn: Job? = null

    val state = MutableStateFlow(AccountLoginState())

    init { publish() }

    fun signedIn(): Boolean = vault.hasSecret(CREDENTIAL_REF) && vault.list().any { it.id == PROVIDER_ID }

    fun publish(waiting: Boolean = false, error: String? = null) {
        val signed = signedIn()
        val status = when {
            waiting -> "等待浏览器授权"
            signed -> "已登录 · Key 只加密保存在本机"
            error != null -> error
            else -> AccountLoginState().status
        }
        state.value = AccountLoginState(status, signed, waiting, error)
    }

    /**
     * 开始登录。[openBrowser] 收到授权页地址（不要记日志）；拿到 Key 后 [onKey] 负责存成供应商（含模型列表），
     * 它抛错时 Key 不留下，界面显示原因。
     */
    fun beginLogin(openBrowser: (String) -> Unit, onKey: suspend (String) -> Unit) {
        signIn?.cancel()
        publish(waiting = true)
        signIn = scope.launch {
            try {
                val key = signInWithOpenRouter(auth, openBrowser = { url -> scope.launch(Dispatchers.Main) { openBrowser(url) } }, page = ::page)
                onKey(key)
                publish()
            } catch (cancelled: CancellationException) {
                publish()
                throw cancelled
            } catch (failure: AccountSignInException) {
                publish(error = failure.reason.message())
            } catch (failure: Exception) {
                publish(error = "已拿到授权，但保存失败：" + (vault.redact(failure.message.orEmpty()).take(120).ifBlank { failure.javaClass.simpleName }))
            }
        }
    }

    fun cancelLogin() { signIn?.cancel(); signIn = null; publish() }

    private fun page(failure: AccountSignInException?): String {
        val text = when {
            failure == null -> "已用 OpenRouter 登录，可以回到 Hoshino 了。"
            failure.reason == AccountSignInFailure.Denied -> "已取消授权，可以回到 Hoshino 了。"
            else -> "登录没有完成：${failure.reason.message()} 可以回到 Hoshino 再试一次。"
        }
        return "<!doctype html><meta charset=utf-8><meta name=viewport content=\"width=device-width\"><title>Hoshino</title><p style=\"font:16px sans-serif;margin:32px\">$text</p>"
    }

    companion object {
        const val PROVIDER_ID = "openrouter-oauth"
        /** = Provider(PROVIDER_ID).credentialRef */
        const val CREDENTIAL_REF = "KEY_OPENROUTER_OAUTH"
        const val DISPLAY_NAME = "OpenRouter（账号登录）"
        /** OpenRouter 的自动路由模型：登录后列表拉不到时至少有它。 */
        const val AUTO_MODEL = "openrouter/auto"
        private fun defaultHttp() = OkHttpClient.Builder().followRedirects(false).connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
        @Volatile private var instance: OpenRouterAccount? = null
        fun get(context: Context) = instance ?: synchronized(this) { instance ?: OpenRouterAccount(context.applicationContext).also { instance = it } }
    }
}
