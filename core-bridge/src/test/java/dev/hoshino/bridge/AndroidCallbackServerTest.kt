package dev.hoshino.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/** m2.8.1: a failed callback must tell the kernel why (redacted), not answer an empty 500. */
class AndroidCallbackServerTest {
    private fun post(server: AndroidCallbackServer, method: String): Pair<Int, String> {
        val c = URL("http://127.0.0.1:${server.port}/$method").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Authorization", "Bearer ${server.token}")
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write("{}".toByteArray()) }
        val code = c.responseCode
        val text = (if (code >= 400) c.errorStream else c.inputStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
        return code to text
    }

    @Test fun failureReturnsRedactedJsonError() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val secret = "sk-live-0123456789abcdef"
        val server = AndroidCallbackServer(scope, {}, { redactCredentials(it, listOf(secret)) }) { method, _ ->
            when (method) {
                "ok" -> JSONObject().put("fine", true)
                else -> error("Shizuku 未运行/未授权 key=$secret")
            }
        }
        try {
            val (okCode, okBody) = post(server, "ok")
            assertEquals(200, okCode); assertTrue(JSONObject(okBody).getBoolean("fine"))
            val (code, body) = post(server, "system-shell")
            assertEquals(500, code)
            val error = JSONObject(body).getString("error")
            assertTrue(error, error.contains("Shizuku 未运行/未授权"))
            assertFalse("secret leaked: $error", error.contains(secret))
            assertTrue(error.contains("[凭据已隐藏]"))
        } finally { server.close(); scope.cancel() }
    }

    @Test fun emptyMessageFallsBackToTypeAndLongMessageIsCapped() {
        assertEquals("IllegalStateException", JSONObject(AndroidCallbackServer.errorBody(IllegalStateException(), { it })).getString("error"))
        val long = JSONObject(AndroidCallbackServer.errorBody(RuntimeException("x".repeat(10_000)), { it })).getString("error")
        assertEquals(AndroidCallbackServer.MAX_ERROR_CHARS, long.length)
        // A failing redactor never puts the raw message on the wire.
        assertEquals("RuntimeException", JSONObject(AndroidCallbackServer.errorBody(RuntimeException("secret"), { throw IllegalStateException() })).getString("error"))
    }
}
