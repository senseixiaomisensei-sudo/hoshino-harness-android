// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package dev.hoshino.bridge.chatgpt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URLDecoder

/**
 * The sign-in callback listener: bound to 127.0.0.1 only (other devices cannot connect), on a free port chosen by the
 * system. After sign-in the browser returns to `http://127.0.0.1:<port>/auth/callback?…`; this reads the parameters and
 * answers with a "you can go back" page. Any other path (a favicon request) gets 404. Accepts one callback; [close] after use.
 */
class LoopbackCallback private constructor(private val server: ServerSocket) : Closeable {
    val port: Int get() = server.localPort
    val redirectUri: String get() = "http://$HOST:$port$PATH"

    /**
     * Wait at most [timeoutMs] for the callback; null on timeout. [page] renders the HTML for the received parameters.
     * Returns promptly when the coroutine is cancelled. Callbacks [accept] refuses (a different state: not this attempt)
     * get 400 and the wait continues.
     */
    suspend fun await(
        timeoutMs: Long,
        accept: (Map<String, String>) -> Boolean = { true },
        page: (Map<String, String>) -> String,
    ): Map<String, String>? = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + timeoutMs
        server.soTimeout = POLL_MS
        while (System.currentTimeMillis() < deadline) {
            ensureActive()
            val socket = try { server.accept() } catch (e: SocketTimeoutException) { continue } catch (e: IOException) { return@withContext null }
            socket.use { s ->
                s.soTimeout = READ_TIMEOUT_MS
                val reader = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val requestLine = try { reader.readLine() } catch (e: IOException) { null } ?: return@use
                if (requestLine.length > MAX_LINE) return@use
                while (true) {
                    val line = try { reader.readLine() } catch (e: IOException) { null }
                    if (line.isNullOrEmpty()) break
                }
                val parts = requestLine.split(' ')
                val target = parts.getOrNull(1).orEmpty()
                val path = target.substringBefore('?')
                if (parts.firstOrNull() != "GET" || path != PATH) {
                    respond(s.getOutputStream(), 404, "Not Found", "text/plain", "Not Found")
                    return@use
                }
                val params = parseQuery(target.substringAfter('?', ""))
                if (!accept(params)) {
                    respond(s.getOutputStream(), 400, "Bad Request", "text/plain", "Bad Request")
                    return@use
                }
                respond(s.getOutputStream(), 200, "OK", "text/html; charset=utf-8", page(params))
                return@withContext params
            }
        }
        null
    }

    override fun close() { runCatching { server.close() } }

    private fun respond(out: java.io.OutputStream, code: Int, reason: String, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nConnection: close\r\n\r\n"
        runCatching {
            out.write(head.toByteArray(Charsets.ISO_8859_1))
            out.write(bytes)
            out.flush()
        }
    }

    companion object {
        const val HOST = "127.0.0.1"
        const val PATH = "/auth/callback"
        private const val POLL_MS = 300
        private const val READ_TIMEOUT_MS = 5_000
        private const val MAX_LINE = 16_384

        /** Open a listener; port 0 lets the system choose. */
        fun open(port: Int = 0): LoopbackCallback = LoopbackCallback(ServerSocket(port, 4, InetAddress.getByName(HOST)))

        fun parseQuery(query: String): Map<String, String> = query.split('&').filter { it.isNotEmpty() }.associate { kv ->
            val k = kv.substringBefore('=')
            val v = kv.substringAfter('=', "")
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }
    }
}
