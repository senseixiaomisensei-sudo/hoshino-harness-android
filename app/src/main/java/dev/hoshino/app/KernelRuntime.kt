package dev.hoshino.app

import android.content.Context
import android.os.BatteryManager
import dev.hoshino.bridge.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

data class ChatItem(val id: String, val role: String, val text: String = "", val thinking: String = "", val tool: String = "", val arguments: String = "", val result: String = "")
data class UiState(val status: String = "启动中", val diagnostics: List<String> = emptyList(), val messages: List<ChatItem> = emptyList(), val providers: List<Provider> = emptyList(), val provider: String = "", val model: String = "", val running: Boolean = false, val completedTurns: Int = 0, val error: String = "")
data class Approval(val description: String, val decision: CompletableDeferred<Boolean> = CompletableDeferred())

/** Service-owned state and transport adapter. Agent decisions and persistence belong to dsh. */
object KernelRuntime {
    val state = MutableStateFlow(UiState())
    val approval = MutableStateFlow<Approval?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    private val approvalMutex = kotlinx.coroutines.sync.Mutex()
    private val launcher: KernelLauncher = ProotKernelLauncher()
    private var callback: AndroidCallbackServer? = null
    private var client: SessionClient? = null
    private var session = ""
    private val events = sortedMapOf<Int, JSONObject>()
    private var live: ChatItem? = null
    var startedAt = 0L; private set
    var connectedAt = 0L; private set
    val sessionId: String get() = session

    fun start(context: Context) {
        if (!started.compareAndSet(false,true)) return
        startedAt = android.os.SystemClock.elapsedRealtime()
        scope.launch {
            try {
                val store=ProviderStore(context); state.update { it.copy(providers=store.list()) }
                callback=AndroidCallbackServer(scope, { message -> state.update { it.copy(diagnostics=(it.diagnostics+message).takeLast(50)) } }) { method, body ->
                    when(method) {
                        "providers" -> JSONArray(store.list().map { it.json() })
                        "credential" -> JSONObject().put("value", store.resolve(body.getString("ref")) ?: "").put("source","android-keystore")
                        "device-info" -> JSONObject().put("model","${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}").put("androidVersion",android.os.Build.VERSION.RELEASE).put("battery",(context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
                        "approve" -> {
                            approvalMutex.lock()
                            try {
                                val request=Approval(if(body.optString("kind")=="network") "允许向当前模型接口发送本次请求？" else "允许 ${body.optString("tool")} 修改 ${body.optString("path")}？")
                                approval.value=request
                                val accepted=withTimeoutOrNull(290000) { request.decision.await() } ?: false
                                JSONObject().put("approved",accepted)
                            } finally { approval.value=null;approvalMutex.unlock() }
                        }
                        "ready" -> {
                            val api=SessionClient(::receive) { reason -> state.update { it.copy(status="崩溃",error="内核连接中断",diagnostics=(it.diagnostics+"connection: $reason").takeLast(50)) } }; client=api
                            api.connect(body.getInt("port"),body.getString("token"))
                            session=api.rpc("session/create",JSONObject().put("cwd","/workspace")).getString("sessionId")
                            api.follow(session)
                            store.list().firstOrNull()?.let { p -> api.select(session,p.id,p.models.first()); state.update { it.copy(provider=p.id,model=p.models.first()) } }
                            connectedAt=android.os.SystemClock.elapsedRealtime()
                            state.update { it.copy(status="已连接") };JSONObject()
                        }
                        else -> error("Unknown callback")
                    }
                }
                launcher.launch(context,JSONObject().put("callbackPort",callback!!.port).put("callbackToken",callback!!.token),
                    { code -> state.update { it.copy(status="崩溃",error="内核退出 ($code)") } },
                    { diagnostic -> state.update { it.copy(diagnostics=(it.diagnostics+diagnostic).takeLast(50)) } })
            } catch (failure: Exception) {
                val safeTrace = listOf("host: ${failure.javaClass.simpleName}") + failure.stackTrace.take(5).map { "${it.className}.${it.methodName}:${it.lineNumber}" }
                state.update { it.copy(status="崩溃",error="内核启动失败；检查内置 rootfs 和独立 proot 二进制",diagnostics=(it.diagnostics+safeTrace).takeLast(50)) }
            }
        }
    }
    fun send(text: String) { scope.launch { action { client!!.prompt(session,text) } } }
    fun select(provider: String, model: String) { scope.launch { action { client!!.select(session,provider,model);state.update { it.copy(provider=provider,model=model) } } } }
    fun save(context: Context, provider: Provider, key: String, complete: (String?) -> Unit) { scope.launch {
        try { ProviderStore(context).save(provider,key);client?.reloadProviders();state.update { it.copy(providers=ProviderStore(context).list()) };client?.select(session,provider.id,provider.models.first());state.update { it.copy(provider=provider.id,model=provider.models.first()) };complete(null) }
        catch (_: Exception) { complete("保存失败，请检查 ID、地址与模型名称") }
    } }
    private suspend fun action(block: suspend () -> Unit) { try { block();state.update { it.copy(error="") } } catch (_: Exception) { state.update { it.copy(error="内核请求失败；请检查模型配置与会话状态") } } }
    @Synchronized private fun receive(frame: JSONObject) {
        when(frame.optString("type")) {
            "snapshot" -> {
                events.clear(); frame.getJSONArray("records").let { records -> for(i in 0 until records.length()) { val e=records.getJSONObject(i).getJSONObject("event"); events[e.getInt("seq")]=e } }
                val turns=events.values.filter { it.optString("type")=="turn/start" || it.optString("type")=="turn/end" }
                state.update { it.copy(completedTurns=turns.count { e -> e.optString("type")=="turn/end" },running=turns.lastOrNull()?.optString("type")=="turn/start") };render()
            }
            "event" -> {
                val event=frame.getJSONObject("event"); val isNew=events.put(event.getInt("seq"),event)==null
                if(event.optString("type")=="assistant/message") live=null
                if(event.optString("type")=="turn/start") state.update { it.copy(running=true) }
                if(event.optString("type")=="turn/end") state.update { it.copy(running=false,completedTurns=it.completedTurns+if(isNew) 1 else 0, error=if(event.getJSONObject("data").optJSONObject("reason")?.optString("kind")=="error") "模型请求失败；检查协议、模型与凭据" else it.error) }
                render()
            }
            "assistant-stream" -> {
                val stream=frame.getJSONObject("frame")
                when(stream.optString("type")) {
                    "start" -> live=ChatItem("live","assistant")
                    "chunk" -> {
                        val chunk=stream.getJSONObject("chunk"); val current=live ?: ChatItem("live","assistant")
                        live=when(chunk.optString("type")) { "text-delta" -> current.copy(text=current.text+chunk.optString("text")); "reasoning-delta" -> current.copy(thinking=current.thinking+chunk.optString("text")); else -> current }
                    }
                    "end" -> live=null
                }
                render()
            }
        }
    }
    private fun content(array: JSONArray?, type: String): String = if(array==null) "" else (0 until array.length()).map { array.getJSONObject(it) }.filter { it.optString("type")==type }.joinToString("") { it.optString("text") }
    private fun render() {
        val items=mutableListOf<ChatItem>()
        events.values.forEach { event ->
            val data=event.optJSONObject("data") ?: return@forEach; val id=event.getInt("seq").toString()
            when(event.getString("type")) {
                "user/message" -> if(data.optJSONObject("source")?.optString("kind")=="user") items += ChatItem(id,"user",text=content(data.optJSONArray("content"),"text"))
                "assistant/message" -> data.getJSONObject("message").let { m -> items += ChatItem(id,"assistant",text=content(m.optJSONArray("content"),"text"),thinking=content(m.optJSONArray("content"),"reasoning")) }
                "tool/call" -> items += ChatItem(data.getString("callId"),"tool",tool=data.getString("name"),arguments=data.getString("arguments"))
                "tool/result" -> { val m=data.getJSONObject("message");val i=items.indexOfLast { it.id==m.optString("toolCallId") }; if(i>=0) items[i]=items[i].copy(result=content(m.optJSONArray("content"),"text")) }
            }
        }
        live?.let { items+=it }
        state.update { it.copy(messages=items) }
    }
    fun stop() { launcher.stop();client?.close();callback?.close();started.set(false);approval.value?.decision?.complete(false);approval.value=null }
}
