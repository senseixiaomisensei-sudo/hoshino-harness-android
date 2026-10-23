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

class AndroidCallbackServer(scope: CoroutineScope, private val onDiagnostic: (String) -> Unit = {}, private val handler: suspend (String, JSONObject) -> Any) : Closeable {
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
                            require(length in 0..65536)
                            val bytes = ByteArray(length); var offset = 0
                            while (offset < length) { val n = input.read(bytes, offset, length-offset); check(n > 0); offset += n }
                            val result = handler(start[1].removePrefix("/"), JSONObject(String(bytes)))
                            val body = result.toString().toByteArray()
                            socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray() + body)
                        } catch (failure: Exception) {
                            onDiagnostic("callback: ${failure.javaClass.simpleName}")
                            runCatching { socket.getOutputStream().write("HTTP/1.1 500 Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()) }
                        }
                    }
                }
            }
        }
    }
    override fun close() { server.close() }
}
