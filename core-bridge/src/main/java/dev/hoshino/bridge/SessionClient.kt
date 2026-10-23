package dev.hoshino.bridge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.TimeUnit

/** dsh 0.2.1-rc.2 Connection RPC envelopes and API Gateway Mux; no local agent loop. */
class SessionClient(private val onFrame: (JSONObject) -> Unit, private val onFailure: () -> Unit) : Closeable {
    private val http = OkHttpClient.Builder().followRedirects(false).readTimeout(0, TimeUnit.SECONDS).build()
    private var base = ""
    private var cookie = ""
    private var socket: WebSocket? = null
    suspend fun connect(port: Int, token: String) = withContext(Dispatchers.IO) {
        base = "http://127.0.0.1:$port"
        http.newCall(Request.Builder().url("$base/mobile/connect?token=$token").build()).execute().use {
            check(it.code == 302) { "内核鉴权失败" }; cookie = it.header("Set-Cookie")!!.substringBefore(';')
        }
        val ready = kotlinx.coroutines.CompletableDeferred<Unit>()
        socket = http.newWebSocket(Request.Builder().url(base.replace("http:","ws:") + "/api/remote.mux").header("Cookie",cookie).build(), object: WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { ready.complete(Unit) }
            override fun onMessage(webSocket: WebSocket, text: String) { runCatching { val message=JSONObject(text); if(message.optString("type")=="item") onFrame(message.getJSONObject("value")) else if(message.optString("type")=="error") onFailure() }.onFailure { onFailure() } }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { ready.completeExceptionally(IllegalStateException("内核连接中断")); onFailure() }
        })
        kotlinx.coroutines.withTimeout(15000) { ready.await() }
    }
    suspend fun rpc(method: String, request: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        val args = JSONObject(); if(request != null) args.put("request",request)
        val body = JSONObject().put("type","client-request").put("rpcId",UUID.randomUUID().toString()).put("method",method).put("payload",JSONObject().put("args",args))
        http.newCall(Request.Builder().url("$base/api/$method").header("Cookie",cookie).post(body.toString().toRequestBody("application/json".toMediaType())).build()).execute().use {
            check(it.isSuccessful) { "内核请求失败 (${it.code})" }
            val result=JSONObject(it.body!!.string()).getJSONObject("result")
            check(result.optBoolean("ok")) { "内核拒绝请求：" + result.optJSONObject("error")?.optString("code", "unknown") }
            result.optJSONObject("value") ?: JSONObject()
        }
    }
    fun follow(sessionId: String) {
        val request=JSONObject().put("address",JSONObject().put("kind","session").put("sessionId",sessionId)).put("assistantStream",true)
        check(socket?.send(JSONObject().put("type","open").put("streamId","session").put("endpoint","session/follow").put("payload",JSONObject().put("args",JSONObject().put("request",request))).toString()) == true)
    }
    suspend fun prompt(sessionId: String, text: String) = rpc("session/prompt", JSONObject().put("sessionId",sessionId).put("requestId",UUID.randomUUID().toString()).put("mode","queue").put("content",JSONArray().put(JSONObject().put("type","text").put("text",text))))
    suspend fun select(sessionId: String, provider: String, model: String) = rpc("session/selectModel", JSONObject().put("sessionId",sessionId).put("provider",provider).put("model",model))
    suspend fun reloadProviders() = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url("$base/mobile/providers").header("Cookie",cookie).post("{}".toRequestBody("application/json".toMediaType())).build()).execute().use { check(it.isSuccessful) { "模型配置未生效" } }
    }
    override fun close() { socket?.close(1000,"shutdown"); http.dispatcher.executorService.shutdown();http.connectionPool.evictAll() }
}
