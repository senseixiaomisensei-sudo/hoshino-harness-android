package dev.hoshino.bridge

import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.TimeUnit

/** dsh 0.2.2-alpha.2 Connection RPC envelopes and API Gateway Mux; no local agent loop. */
class SessionClient(private val onFrame: (JSONObject) -> Unit, private val onFailure: (String) -> Unit,
                    private val onScopedFrame: (String, JSONObject) -> Unit) : Closeable {
    constructor(onFrame: (JSONObject) -> Unit,onFailure: (String) -> Unit): this(onFrame,onFailure,{ _, _ -> })
    private val http = OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).followRedirects(false).connectTimeout(2,TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).build()
    private var base = ""
    private var cookie = ""
    private var socket: WebSocket? = null
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val streamRequests=java.util.concurrent.ConcurrentHashMap<String,String>()
    private val reconnecting=java.util.concurrent.atomic.AtomicBoolean(false)
    private val socketGeneration=java.util.concurrent.atomic.AtomicInteger()
    @Volatile private var closed=false
    private val followed=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    suspend fun connect(port: Int, token: String) = withContext(Dispatchers.IO) {
        base = "http://127.0.0.1:$port"
        http.newCall(Request.Builder().url("$base/mobile/connect?token=$token").build()).execute().use {
            check(it.code == 302) { "内核鉴权失败" }; cookie = it.header("Set-Cookie")!!.substringBefore(';')
        }
        val ready = kotlinx.coroutines.CompletableDeferred<Unit>()
        fun open(){
        val generation=socketGeneration.incrementAndGet()
        socket = http.newWebSocket(Request.Builder().url(base.replace("http:","ws:") + "/api/remote.mux").header("Cookie",cookie).build(), object: WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if(closed||generation!=socketGeneration.get()){webSocket.close(1000,null);return}
                streamRequests.values.forEach{webSocket.send(it)}
                if(ready.isCompleted)onFailure("restored")else ready.complete(Unit)
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val message=JSONObject(text)
                    if(message.optString("type")=="item") {
                        val value=message.getJSONObject("value")
                        onScopedFrame(message.optString("streamId"), value)
                        onFrame(value)
                    }
                    else if(message.optString("type")=="error") {
                        val code=message.optJSONObject("error")?.optString("code")?.takeIf { it.matches(Regex("[a-zA-Z0-9/_-]{1,80}")) } ?: "unknown"
                        onFailure("mux:$code")
                    }
                } catch(failure: Exception) {
                    val site=failure.stackTrace.firstOrNull { it.className.startsWith("dev.hoshino.") }
                    onFailure("frame:${failure.javaClass.simpleName}:${site?.methodName}:${site?.lineNumber}")
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if(closed||generation!=socketGeneration.get())return
                onFailure("reconnecting")
                if(reconnecting.compareAndSet(false,true))scope.launch{delay(100);reconnecting.set(false);if(!closed)open()}
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
                if(!closed&&generation==socketGeneration.get()){onFailure("reconnecting");if(reconnecting.compareAndSet(false,true))scope.launch{delay(100);reconnecting.set(false);if(!closed)open()}}
            }
        })
        }
        open()
        kotlinx.coroutines.withTimeout(15000) { ready.await() }
    }
    suspend fun rpc(method: String, request: JSONObject? = null): JSONObject {
        // Exact named parameter in dsh-api-session-controller/lib/typert.host.js.
        val args = JSONObject(); if(request != null) args.put(if(method=="session/list")"_request" else "request",request)
        return rpcArgs(method,args) as? JSONObject ?: JSONObject()
    }
    /** Named wire parameters from dsh's generated Typert metadata (including agentId). */
    suspend fun rpcArgs(method: String, args: JSONObject = JSONObject()): Any? = withContext(Dispatchers.IO) {
        require(method.matches(Regex("[a-zA-Z][a-zA-Z0-9/-]*")))
        val body = JSONObject().put("type","client-request").put("rpcId",UUID.randomUUID().toString()).put("method",method).put("payload",JSONObject().put("args",args))
        http.newCall(Request.Builder().url("$base/api/$method").header("Cookie",cookie).post(body.toString().toRequestBody("application/json".toMediaType())).build()).execute().use {
            check(it.isSuccessful) { "内核请求失败 (${it.code})" }
            val result=JSONObject(it.body!!.string()).getJSONObject("result")
            check(result.optBoolean("ok")) { val error=result.optJSONObject("error");"内核拒绝请求：" + (error?.optString("message")?.takeIf { message->message.isNotBlank() } ?: error?.optString("code","unknown")) }
            result.opt("value").takeUnless { value -> value==JSONObject.NULL }
        }
    }
    fun follow(sessionId: String) {
        if(!followed.add(sessionId))return
        val request=JSONObject().put("address",JSONObject().put("kind","session").put("sessionId",sessionId)).put("assistantStream",true)
        try {openStream("session:$sessionId","session/follow",request)}catch(failure:Exception){followed.remove(sessionId);throw failure}
    }
    fun openStream(id: String, endpoint: String, request: JSONObject) {
        openNamedStream(id,endpoint,JSONObject().put("request",request))
    }
    fun openNamedStream(id: String,endpoint: String,args: JSONObject) {
        val message=JSONObject().put("type","open").put("streamId",id).put("endpoint",endpoint).put("payload",JSONObject().put("args",args)).toString();streamRequests[id]=message
        check(socket?.send(message) == true)
    }
    fun streamInput(id: String,value: JSONObject) {
        check(socket?.send(JSONObject().put("type","item").put("streamId",id).put("value",value).toString())==true) { "终端连接已断开" }
    }
    fun cancelStream(id: String) {
        streamRequests.remove(id)
        if(id.startsWith("session:"))followed.remove(id.removePrefix("session:"))
        socket?.send(JSONObject().put("type","cancel").put("streamId",id).toString())
    }
    suspend fun mobile(method: String, request: JSONObject = JSONObject()): JSONObject = withContext(Dispatchers.IO) {
        require(method.matches(Regex("[a-z][a-z0-9/-]*")))
        http.newCall(Request.Builder().url("$base/mobile/$method").header("Cookie",cookie).post(request.toString().toRequestBody("application/json".toMediaType())).build()).execute().use {
            val body=it.body!!.string()
            check(it.isSuccessful) { runCatching{JSONObject(body).optString("error")}.getOrDefault("").ifBlank{"手机插件请求失败 (${it.code})"} }
            JSONObject(body)
        }
    }
    suspend fun prompt(sessionId: String, text: String) = rpc("session/prompt", JSONObject().put("sessionId",sessionId).put("requestId",UUID.randomUUID().toString()).put("mode","queue").put("content",JSONArray().put(JSONObject().put("type","text").put("text",text))))
    suspend fun uploadFile(sessionId:String,file:java.io.File,name:String):String=withContext(Dispatchers.IO) {
        val url=(base+"/api/session/uploadFileBinary").toHttpUrl().newBuilder().addQueryParameter("sessionId",sessionId).addQueryParameter("name",name).build()
        http.newCall(Request.Builder().url(url).header("Cookie",cookie).post(file.asRequestBody("application/octet-stream".toMediaType())).build()).execute().use {
            check(it.isSuccessful){"附件上传失败 (${it.code})"};val result=JSONObject(it.body!!.string());check(result.getBoolean("ok")){"内核未接受附件"};result.getJSONObject("value").getString("receiptId")
        }
    }
    suspend fun select(sessionId: String, provider: String, model: String) = rpc("session/selectModel", JSONObject().put("sessionId",sessionId).put("provider",provider).put("model",model))
    suspend fun reloadProviders() = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url("$base/mobile/providers").header("Cookie",cookie).post("{}".toRequestBody("application/json".toMediaType())).build()).execute().use { check(it.isSuccessful) { "模型配置未生效" } }
    }
    override fun close() { closed=true;scope.cancel();streamRequests.clear();followed.clear();socket?.close(1000,"shutdown"); http.dispatcher.executorService.shutdown();http.connectionPool.evictAll() }
}
