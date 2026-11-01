// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package dev.hoshino.bridge.oauth

import dev.hoshino.bridge.chatgpt.executeRaw
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/** 账号登录没成的原因；界面按它说人话。异常文字里只有类别和错误代码，不放 Key、授权码。 */
enum class AccountSignInFailure { Denied, Expired, TimedOut, Rejected, Network }

class AccountSignInException(val reason: AccountSignInFailure, val code: String? = null, cause: Throwable? = null) :
    Exception(if (code.isNullOrBlank()) reason.name else "${reason.name}: $code", cause)

/** 给人看的中文原因。 */
fun AccountSignInFailure.message(): String = when (this) {
    AccountSignInFailure.Denied -> "已取消授权。"
    AccountSignInFailure.Expired -> "授权码已过期或对不上（有效期 10 分钟），请重新登录一次。"
    AccountSignInFailure.TimedOut -> "登录超时了，请再试一次。"
    AccountSignInFailure.Rejected -> "OpenRouter 没有接受这次登录，请再试一次。"
    AccountSignInFailure.Network -> "连不上 OpenRouter，请检查网络或代理后再试。"
}

/** 一次 OpenRouter 授权的随机值，每次新生成。[callbackUrl] 是这次本机监听的回调地址。 */
data class OpenRouterAttempt(val state: String, val verifier: String, val callbackUrl: String) {
    val challenge: String get() = Pkce.challenge(verifier)
    override fun toString() = "OpenRouterAttempt(callbackUrl=$callbackUrl)"
}

/**
 * 用 OpenRouter 账号登录：OpenRouter 官方给第三方应用的 OAuth PKCE（https://openrouter.ai/docs/use-cases/oauth-pkce）。
 * 浏览器打开授权页，用户同意后带着 `code` 回到本机回调，再用 `code` 和校验串 `POST /api/v1/auth/keys` 换一把属于该用户的 API Key。
 * 换到的就是普通 OpenRouter Key，之后与手填 Key 的 OpenRouter 一样用（[API_BASE]）。
 * Key 不进日志；异常文字里只有类别和状态码，不带响应正文。
 */
class OpenRouterAuth(
    private val http: OkHttpClient,
    private val authUrl: String = AUTH_URL,
    private val keysUrl: String = KEYS_URL,
) {
    fun newAttempt(callbackUrl: String) = OpenRouterAttempt(Pkce.state(), Pkce.verifier(), callbackUrl)

    /** 授权页地址：回调地址、S256 校验、Key 名字（预填，用户可改）、state。 */
    fun authorizeUrl(attempt: OpenRouterAttempt): String = authUrl.toHttpUrl().newBuilder()
        .addQueryParameter("callback_url", attempt.callbackUrl)
        .addQueryParameter("code_challenge", attempt.challenge)
        .addQueryParameter("code_challenge_method", "S256")
        .addQueryParameter("key_label", KEY_LABEL)
        .addQueryParameter("state", attempt.state)
        .build().toString()

    /**
     * 本机回调收不收这一次：state 对得上的收；不带 state、不带 code、只带 `error` 的也收（用户在授权页拒绝时可能不带 state），当作没同意。
     * 其余（带 code 却没有或对不上 state，或什么都没带）不是这次发起的，回 400 接着等。
     */
    fun accepts(params: Map<String, String>, attempt: OpenRouterAttempt): Boolean {
        val state = params["state"]
        if (state != null) return state == attempt.state
        return params["code"].isNullOrEmpty() && !params["error"].isNullOrEmpty()
    }

    /** 从回调取出 `code`；没有就是用户没同意（或授权页报了错）。 */
    fun readCallback(params: Map<String, String>, attempt: OpenRouterAttempt): String {
        val state = params["state"]
        if (state != null && state != attempt.state) throw AccountSignInException(AccountSignInFailure.Rejected, "state")
        val code = params["code"]?.takeIf { it.isNotEmpty() }
            ?: throw AccountSignInException(AccountSignInFailure.Denied, params["error"]?.take(40) ?: "no_code")
        if (state == null) throw AccountSignInException(AccountSignInFailure.Rejected, "state")
        return code
    }

    /** 用 `code` 和校验串换 Key。403：code/校验串不对或已过期；400：校验方式不对；429/5xx：暂时失败。 */
    suspend fun exchange(code: String, attempt: OpenRouterAttempt): String {
        val body = JSONObject().put("code", code).put("code_verifier", attempt.verifier).put("code_challenge_method", "S256")
            .toString().toRequestBody(JSON_TYPE)
        val raw = try {
            http.executeRaw(Request.Builder().url(keysUrl).header("Accept", "application/json").post(body).build())
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw AccountSignInException(AccountSignInFailure.Network, "io", e)
        }
        when {
            raw.status == 403 -> throw AccountSignInException(AccountSignInFailure.Expired, "http_403")
            raw.status == 429 || raw.status in 500..599 -> throw AccountSignInException(AccountSignInFailure.Network, "http_${raw.status}")
            raw.status != 200 -> throw AccountSignInException(AccountSignInFailure.Rejected, "http_${raw.status}")
        }
        val key = runCatching { JSONObject(raw.body).opt("key") as? String }.getOrNull()
        return key?.takeIf { it.isNotBlank() } ?: throw AccountSignInException(AccountSignInFailure.Rejected, "no_key")
    }

    companion object {
        const val AUTH_URL = "https://openrouter.ai/auth"
        const val KEYS_URL = "https://openrouter.ai/api/v1/auth/keys"
        /** 换到的 Key 调用的接口根，与 OpenRouter 预设相同。 */
        const val API_BASE = "https://openrouter.ai/api/v1"
        /** 用户在 OpenRouter 上管理（删除）Key 的页面。 */
        const val KEYS_PAGE = "https://openrouter.ai/settings/keys"
        /** 授权页上预填的 Key 名字，用户在 Keys 页上靠它认出是哪一把。 */
        const val KEY_LABEL = "Hoshino Harness"
        /** 回调地址写 localhost（OpenRouter 允许本机回调任意端口）；监听只绑 127.0.0.1。 */
        const val CALLBACK_HOST = "localhost"
        private val JSON_TYPE = "application/json".toMediaType()
    }
}

/**
 * 走一遍 OpenRouter 登录：开本机回调 → [openBrowser] 打开授权页 → 等回调 → 换 Key。
 * 换 Key（至多 [finishTimeoutMs]）做完才把结果页回给浏览器：[page] 收到 null 是成功，否则是失败原因。
 * 失败抛 [AccountSignInException]；取消照常往外抛。返回换到的 API Key。
 */
suspend fun signInWithOpenRouter(
    auth: OpenRouterAuth,
    openBrowser: (String) -> Unit,
    page: (AccountSignInException?) -> String,
    timeoutMs: Long = 5 * 60_000L,
    finishTimeoutMs: Long = 20_000L,
): String {
    var outcome: Result<String>? = null
    LoopbackCallback.open().use { cb ->
        val attempt = auth.newAttempt(cb.redirectUri(OpenRouterAuth.CALLBACK_HOST))
        openBrowser(auth.authorizeUrl(attempt))
        cb.await(timeoutMs, { auth.accepts(it, attempt) }) { params ->
            val r = try {
                Result.success(withTimeout(finishTimeoutMs) { auth.exchange(auth.readCallback(params, attempt), attempt) })
            } catch (e: TimeoutCancellationException) {
                Result.failure(AccountSignInException(AccountSignInFailure.Network, "timeout", e))
            } catch (e: CancellationException) {
                throw e
            } catch (e: AccountSignInException) {
                Result.failure(e)
            } catch (e: Exception) {
                Result.failure(AccountSignInException(AccountSignInFailure.Network, cause = e))
            }
            outcome = r
            page(r.exceptionOrNull() as AccountSignInException?)
        } ?: throw AccountSignInException(AccountSignInFailure.TimedOut)
    }
    return outcome!!.getOrThrow()
}
