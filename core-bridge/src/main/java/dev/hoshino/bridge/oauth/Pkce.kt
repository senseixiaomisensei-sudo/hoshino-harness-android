// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package dev.hoshino.bridge.oauth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * PKCE（RFC 7636）与回调核对用的随机值，ChatGPT 登录与 OpenRouter 登录共用。每次登录新生成，不落盘。
 * 校验串是 32 个随机字节的 base64url（43 个字符，落在规范要求的 43–128 之间，字符都在允许范围内）。
 */
object Pkce {
    private val random = SecureRandom()

    /** 新的校验串（code_verifier）。 */
    fun verifier(bytes: Int = 32): String = randomString(bytes)

    /** S256：校验串的 SHA-256，base64url 不带补位。 */
    fun challenge(verifier: String): String =
        b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    /** 回调里原样带回、用来认出是不是这次登录的随机串（state）。 */
    fun state(): String = randomString(24)

    private fun randomString(bytes: Int): String = ByteArray(bytes).also(random::nextBytes).let(::b64url)

    private fun b64url(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
}
