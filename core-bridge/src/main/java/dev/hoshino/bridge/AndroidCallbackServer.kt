package dev.hoshino.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * [redact] scrubs stored credentials from a failure message before it is returned to the kernel in a 500 body
 * (m2.8.1: an empty 500 left the model with only "Android callback failed (500)"). Diagnostics stay class-name only.
 */
class AndroidCallbackServer(scope: CoroutineScope, private val onDiagnostic: (String) -> Unit = {}, private val redact: (String) -> String = { it }, private val handler: suspend (String, JSONObject) -> Any) : Closeable {
    private val server = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort
    val token: String = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    init {
        scope.launch(Dispatchers.IO) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                scope.launch(Dispatchers.IO) {
                    socket.use {
                        try {
                            socket.soTimeout = 10000
                            val input = socket.getInputStream().buffered()
                            fun line(): String { val out = StringBuilder(); while (true) { val c = input.read(); if (c < 0 || c == 10) break; if (c != 13) out.append(c.toChar()); require(out.length < 8192) }; return out.toString() }
                            val start = line().split(' ')
                            val headers = mutableMapOf<String,String>()
                            while (true) { val l = line(); if (l.isEmpty()) break; headers[l.substringBefore(':').lowercase()] = l.substringAfter(':').trim() }
                            val expected = "Bearer $token".toByteArray()
                            val authenticated = MessageDigest.isEqual(expected, (headers["authorization"] ?: "").toByteArray())
                            if (!authenticated || start.first() != "POST") {
                                onDiagnostic("callback: authentication rejected")
                                socket.getOutputStream().write("HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); return@use
                            }
                            val length = headers["content-length"]?.toInt() ?: 0
                            require(length in 0..8388608)
                            val bytes = ByteArray(length); var offset = 0
                            while (offset < length) { val n = input.read(bytes, offset, length-offset); check(n > 0); offset += n }
                            val result = handler(start[1].removePrefix("/"), JSONObject(String(bytes)))
                            val body = result.toString().toByteArray()
                            socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray() + body)
                        } catch (failure: Exception) {
                            onDiagnostic("callback: ${failure.javaClass.simpleName}")
                            val body = errorBody(failure, redact).toByteArray()
                            runCatching { socket.getOutputStream().write("HTTP/1.1 500 Error\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray() + body) }
                        }
                    }
                }
            }
        }
    }
    override fun close() { server.close() }

    companion object {
        const val MAX_ERROR_CHARS = 2000
        /** `{"error": message}` for a failed callback: redacted, capped, never empty (falls back to the exception type). */
        fun errorBody(failure: Throwable, redact: (String) -> String): String {
            val message = runCatching { redact(failure.message.orEmpty()) }.getOrDefault("").trim().take(MAX_ERROR_CHARS)
            return JSONObject().put("error", message.ifEmpty { failure.javaClass.simpleName.ifEmpty { "Exception" } }).toString()
        }
    }
}
