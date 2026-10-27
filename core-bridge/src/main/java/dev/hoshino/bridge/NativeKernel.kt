package dev.hoshino.bridge

import android.content.Context
import android.os.BatteryManager
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class NativeKernelState(val phase: String="未连接", val logs: List<String> = emptyList(), val startedAt: Long=0, val readyAt: Long=0)

/** Lifecycle and authenticated transports only. dsh remains the Agent and persistence owner. */
class NativeKernel(private val context: Context,
                   private val launcher: KernelLauncher = ProotKernelLauncher(),
                   private val callbackHandler: suspend (String, JSONObject) -> Any) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val started=AtomicBoolean(false)
    private val generation=AtomicInteger()
    val state=MutableStateFlow(NativeKernelState())
    private val incoming=Channel<Pair<String,JSONObject>>(Channel.UNLIMITED)
    val frames: Flow<Pair<String,JSONObject>> = incoming.receiveAsFlow()
    private var ready=CompletableDeferred<SessionClient>()
    private var api: SessionClient?=null
    private var process: Process?=null
    private var startup: Job?=null
    private var callback: AndroidCallbackServer?=null
    fun start() {
        if(!started.compareAndSet(false,true))return
        val current=generation.incrementAndGet()
        state.update { it.copy(phase="启动中",startedAt=SystemClock.elapsedRealtime()) }
        val previous=startup
        startup=scope.launch {
            try {
                previous?.join();ensureActive()
                val store=ProviderStore(context)
                callback=AndroidCallbackServer(scope,::diagnostic) { method,body ->
                    when(method) {
                        "providers" -> JSONArray(store.list().filter { it.metadata.optBoolean("enabled",true) }.map { store.runtimeJson(it) })
                        "credential" -> JSONObject().put("value",store.resolve(body.getString("ref")) ?: "").put("source","android-keystore")
                        "device-info" -> JSONObject().put("model","${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}").put("androidVersion",android.os.Build.VERSION.RELEASE).put("battery",(context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
                        "ready" -> {
                            check(current==generation.get())
                            val connected=SessionClient({}, { if(current==generation.get()) {diagnostic("connection: $it"); state.update { s -> s.copy(phase="崩溃") }} }, { id,frame -> if(current==generation.get())incoming.trySend(id to frame) })
                            connected.connect(body.getInt("port"),body.getString("token"));api=connected
                            ready.complete(connected)
                            state.update { it.copy(phase="已连接",readyAt=SystemClock.elapsedRealtime()) }
                            java.io.File(context.filesDir,"m2-runtime-health.json").writeText(JSONObject().put("phase","ready").put("startedAt",state.value.startedAt).put("readyAt",state.value.readyAt).toString())
                            JSONObject()
                        }
                        else -> callbackHandler(method,body)
                    }
                }
                process=launcher.launch(context,JSONObject().put("callbackPort",callback!!.port).put("callbackToken",callback!!.token),
                    { code -> if(current==generation.get()) {diagnostic("内核退出 ($code)");state.update { it.copy(phase="崩溃") }} },::diagnostic)
            } catch(failure: Exception) {
                if(current==generation.get()) {
                    diagnostic("host: ${failure.javaClass.simpleName}")
                    state.update { it.copy(phase="崩溃") }
                    ready.completeExceptionally(IllegalStateException("内核启动失败"))
                }
            }
        }
    }
    suspend fun client(): SessionClient = withTimeout(300000) { ready.await() }
    fun diagnostic(message: String) { state.update { it.copy(logs=(it.logs+message).takeLast(50)) } }
    fun stop() {
        generation.incrementAndGet();started.set(false);startup?.cancel();launcher.stop();api?.close();api=null;callback?.close();callback=null
        ready.cancel();ready=CompletableDeferred();state.update { it.copy(phase="未连接") }
    }
    suspend fun stopAndWait()=withContext(Dispatchers.IO) {
        val child=process;val starting=startup;stop();starting?.join()
        if(child!=null && !child.waitFor(15,java.util.concurrent.TimeUnit.SECONDS)) {
            child.destroyForcibly();child.waitFor(5,java.util.concurrent.TimeUnit.SECONDS)
        }
        process=null
    }
}
