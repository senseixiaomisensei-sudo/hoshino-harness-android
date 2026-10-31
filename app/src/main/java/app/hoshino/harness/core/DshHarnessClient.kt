package app.hoshino.harness.core

import android.content.Context
import dev.hoshino.bridge.NativeKernel
import dev.hoshino.bridge.ProviderStore
import dev.hoshino.bridge.Provider as StoredProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Only adapts the real dsh Session Controller/Gateway to the ZIP's unmodified event reducer. */
class DshHarnessClient(private val context: Context) : UnavailableHarnessClient() {
    override val supportedPermissionTiers=PermissionTier.entries.toSet()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val vault=ProviderStore(context)
    private val metadata=context.getSharedPreferences("m1-session-metadata",Context.MODE_PRIVATE)
    private val nativeCache=NativeSessionCache(context)
    private val cachedData=nativeCache.load()
    private val diskHistory=if(cachedData==null)OriginalSessionHistory.read(context)else emptyList()
    private val initialRows=cachedData?.let{nativeCache.rows(it)}?:diskHistory.map{OriginalSessionHistory.row(it,metadata)}
    private val cachedRecords=java.util.concurrent.ConcurrentHashMap<String,List<JSONObject>>()
    private val outbox=PendingKernelMessages(context)
    private val pendingRequestIds=java.util.concurrent.ConcurrentHashMap<String,String>()
    private val draining=java.util.concurrent.atomic.AtomicBoolean(false)
    override fun describeFailure(failure: Throwable)=vault.redact(failure.message.orEmpty()).take(1500).ifBlank { "操作未完成，内核没有返回错误详情" }
    private val shizukuConnection=dev.hoshino.bridge.ShizukuConnection(context.applicationContext)
    private val systemShell=dev.hoshino.bridge.ShizukuShell(context.applicationContext)
    override val shizuku=shizukuConnection.state.map { ShizukuStatus.valueOf(it.name) }.stateIn(scope,SharingStarted.Eagerly,ShizukuStatus.valueOf(shizukuConnection.state.value.name))
    override suspend fun refreshShizuku(){shizukuConnection.refresh()}
    override suspend fun requestShizukuPermission()=withContext(Dispatchers.Main){shizukuConnection.requestPermission()}
    val chatGptAuth=dev.hoshino.bridge.ChatGptAuth.get(context)
    val runtime=NativeKernel(context,callbackHandler=::callback)
    private val eventBus=MutableSharedFlow<HarnessEvent>(extraBufferCapacity=1024)
    override val events=eventBus.asSharedFlow()
    override val connection=runtime.state.map { when(it.phase) { "已连接" -> ConnectionState.Connected; "启动中", "重新连接" -> ConnectionState.Connecting; else -> ConnectionState.Disconnected } }.stateIn(scope,SharingStarted.Eagerly,ConnectionState.Disconnected)
    override val kernel=runtime.state.map { KernelInfo("0.2.2-alpha.2","24.18.1",it.phase=="已连接","proot + Alpine",it.phase=="已连接") }.stateIn(scope,SharingStarted.Eagerly,KernelInfo("0.2.2-alpha.2","24.18.1",false,"proot + Alpine",false))
    private val sessionRows=MutableStateFlow<List<SessionInfo>>(initialRows)
    override val sessions=sessionRows.asStateFlow()
    private val workspaceRows=MutableStateFlow(listOf(Workspace("ws-default","workspace","/root/workspace")))
    override val workspaces=workspaceRows.asStateFlow()
    private val providerRows=MutableStateFlow<List<Provider>>(readProviders())
    override val providers=providerRows.asStateFlow()
    private val routingRow=MutableStateFlow(ModelRouting())
    override val routing=routingRow.asStateFlow()
    override val catalog=listOf(CatalogProvider("anthropic","Anthropic",ProviderProtocol.Anthropic,"https://api.anthropic.com"),CatalogProvider("openai","OpenAI",ProviderProtocol.OpenAICompatible,"https://api.openai.com/v1"),CatalogProvider("deepseek","DeepSeek",ProviderProtocol.OpenAICompatible,"https://api.deepseek.com/v1"),CatalogProvider("ollama","Ollama",ProviderProtocol.OpenAICompatible,"http://127.0.0.1:11434/v1"),CatalogProvider("custom","自定义供应商",ProviderProtocol.OpenAICompatible,""))
    private val approvals=java.util.concurrent.ConcurrentHashMap<String,CompletableDeferred<ApprovalDecision>>()
    private val questions=java.util.concurrent.ConcurrentHashMap<String,CompletableDeferred<String>>()
    private val seen=mutableMapOf<String,MutableSet<Int>>()
    private data class Live(val id: String,val started: Long=android.os.SystemClock.elapsedRealtime(),var text: String="",var thinking: String="")
    private val live=mutableMapOf<String,Live>()
    private val calls=mutableMapOf<String,String>()
    private val capacities=java.util.concurrent.ConcurrentHashMap<String,Int>()
    private data class TurnTokens(val start:Long,var input:Int=0,var output:Int=0,var cache:Int=0,var reasoning:Int=0,var reported:Boolean=false)
    private val tokenTurns=mutableMapOf<String,TurnTokens>()
    private var activeSession=""
    private val terminalRows=MutableStateFlow<List<TerminalSession>>(emptyList())
    override val terminals=terminalRows.asStateFlow()
    private val terminalOwners=java.util.concurrent.ConcurrentHashMap<String,String>()
    private val terminalSequences=java.util.concurrent.ConcurrentHashMap<String,Int>()
    private val terminalWritable=java.util.concurrent.ConcurrentHashMap<String,Boolean>()
    private val pluginRows=MutableStateFlow<List<Plugin>>(emptyList())
    override val plugins=pluginRows.asStateFlow()
    private val skillRows=MutableStateFlow<List<Skill>>(emptyList())
    override val skills=skillRows.asStateFlow()
    private val inspectorRows=MutableStateFlow<List<InspectorNode>>(emptyList())
    override val inspector=inspectorRows.asStateFlow()
    override val uiCustomization=MutableStateFlow(UiCustomization())
    override val uiCustomizationError=MutableStateFlow<String?>(null)
    override val configurationRollbackSupported=true
    val m2=M2Runtime(context){systemShell.installApk(it)}
    override val presets=MutableStateFlow(listOf("chat" to "聊天","standard" to "Agent","plan" to "Plan","ask" to "Ask","research" to "Research","review" to "Review","goal" to "Goal","auto" to "Auto","orchestrator" to "Orchestrator").map{(id,name)->AgentPreset(id,name,"",true,"",emptyList(),null,PermissionTier.WorkspaceWrite)})
    val files=dev.hoshino.bridge.WorkspaceFiles(java.io.File(context.filesDir,"workspace"),java.io.File(context.filesDir,"hoshino-trash"))
    private val restartAfter=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    override val preferredSessionId:String? get()=metadata.getString("last-active-session",null)
    override fun activateSession(sessionId:String) { activeSession=sessionId;metadata.edit().putString("last-active-session",sessionId).apply();if(runtime.state.value.phase=="已连接")scope.launch{runCatching{api().follow(sessionId)}} }

    init {
        val saved=cachedData?.optJSONObject("events")
        if(saved!=null)saved.keys().forEach{id->val a=saved.optJSONArray(id)?:JSONArray();cachedRecords[id]=(0 until a.length()).map{a.getJSONObject(it)}}
        else diskHistory.firstOrNull{it.id==preferredSessionId}?.let{cachedRecords[it.id]=it.events}
        scope.launch { runtime.frames.collect { (stream,frame) ->
            try {
                if(stream.startsWith("session:"))accept(stream.removePrefix("session:"),frame)
                else if(stream.startsWith("terminal:"))acceptTerminal(stream.removePrefix("terminal:"),frame)
            }
            catch(failure: Exception) { runtime.diagnostic("mapping: ${failure.javaClass.simpleName}") }
        } }
        scope.launch { runtime.state.map { it.phase }.distinctUntilChanged().collect { if(it=="已连接")runCatching { flushOutbox();refreshSessions();scope.launch{runCatching{refreshControl();resumeInterrupted()}} }.onFailure { runtime.diagnostic("sessions: ${it.javaClass.simpleName}") } } }
    }
    fun startKernel()=runtime.start()
    fun stopKernel() { approvals.values.forEach { it.complete(ApprovalDecision.Deny) };systemShell.close();runtime.stop() }
    suspend fun closeForRestore(){stopKernel();runtime.stopAndWait()}
    fun disposeAfterRestore(){scope.cancel()}
    override suspend fun restartKernel() {
        for(session in sessions.value.filter { it.running })runCatching { api().rpc("session/cancel",JSONObject().put("sessionId",session.id)) }
        withTimeoutOrNull(5000) { sessions.first { rows -> rows.none { it.running } } }
        approvals.values.forEach { it.complete(ApprovalDecision.Deny) }
        runtime.stopAndWait();runtime.start()
        // Keep the reducer's delivered sequence set: replaying it would duplicate the ZIP store's items.
    }
    override suspend fun kernelLogs()=runtime.state.value.logs
    suspend fun api()=runtime.client()
    private suspend fun publish(event: HarnessEvent)=eventBus.emit(event)
    private suspend fun callback(method: String,body: JSONObject): Any {
        when(method) {
            "system-access" -> {shizukuConnection.refresh();return JSONObject().put("authorized",shizukuConnection.state.value==dev.hoshino.bridge.ShizukuConnectionState.Ready)}
            "system-shell" -> {
                shizukuConnection.refresh()
                check(shizukuConnection.state.value==dev.hoshino.bridge.ShizukuConnectionState.Ready){"Shizuku 尚未运行或授权已失效"}
                val sid=body.getString("sessionId")
                check(sessionRows.value.firstOrNull{it.id==sid}?.tier==PermissionTier.FullAccess){"系统命令仅允许在已授权的完全访问会话中执行"}
                return systemShell.execute(body.getString("id"),sid,body.getString("command"),body.optInt("timeout",120000)) { text -> appendAgentTerminal(sid,text,TerminalStream.Output) }
            }
            "system-shell-cancel" -> {systemShell.cancel(body.getString("id"),body.getString("sessionId"));return JSONObject()}
            "terminal-output" -> {appendAgentTerminal(body.getString("sessionId"),body.getString("text"),if(body.optString("stream")=="stderr")TerminalStream.Error else TerminalStream.Output);return JSONObject()}
            "ui-action" -> return withContext(Dispatchers.Main) {
                AppSurface.activity()
                when(body.getString("action")) {
                    "app_screenshot" -> {
                        val bitmap=AppSurface.capture();val dir=java.io.File(context.cacheDir,"share").apply{mkdirs()};val file=java.io.File(dir,"tool-screenshot.jpg")
                        try{file.outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,90,it)};AppMedia(context).imageBlock(file,"Hoshino-app-screenshot.jpg")}finally{bitmap.recycle();file.delete()}
                    }
                    "clipboard_read" -> {val cm=context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager;JSONObject().put("text",vault.redact(cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()))}
                    "clipboard_write" -> {app.hoshino.harness.ui.components.copyToClipboard(context,body.getString("text"));JSONObject().put("written",true)}
                    "share_text" -> {app.hoshino.harness.ui.components.shareText(context,body.getString("text"));JSONObject().put("opened",true)}
                    "share_file" -> {AppMedia(context).shareFile(files.resolve(body.getString("path")));JSONObject().put("opened",true)}
                    else -> error("Unknown UI action")
                }
            }
            "self-status" -> return JSONObject().put("currentModel",routing.value.main?.modelId ?: "").put("provider",routing.value.main?.providerId ?: "").put("logs",JSONArray(runtime.state.value.logs))
            "self-changed" -> {scope.launch {runCatching {refreshControl()}};return JSONObject()}
            "bridge-v1" -> return try{m2.handle(body)}catch(failure:Exception){JSONObject().put("ok",false).put("error",vault.redact(failure.message.orEmpty()).take(1200).ifBlank{"原生能力执行失败"})}
            "question" -> {
                val sid=body.getString("sessionId");val id=UUID.randomUUID().toString();val answer=CompletableDeferred<String>();questions[id]=answer
                val options=body.optJSONArray("options")?:JSONArray()
                publish(HarnessEvent.QuestionAsked(sid,id,"需要你的输入",body.getString("question"),(0 until options.length()).map{QuestionOption(it.toString(),options.getString(it))},true));M2Notifications.question(context,sid,id,body.getString("question"))
                return try{val value=withTimeout(290000){answer.await()};publish(HarnessEvent.QuestionAnswered(sid,id,value));JSONObject().put("answer",value)}finally{questions.remove(id);M2Notifications.dismiss(context,id)}
            }
            "phone" -> return app.hoshino.harness.phone.PhoneController.of(context).handle(body.put("sessionId",body.optString("sessionId").ifEmpty{activeSession}))
            "restart-after-turn" -> {restartAfter.add(body.getString("sessionId"));return JSONObject()}
            "file-diff" -> {
                val before=body.optString("before","");val after=body.getString("after")
                val lines=before.lines().mapIndexed { i,line->DiffLine(DiffLineType.Remove,line,i+1,null) }+after.lines().mapIndexed{i,line->DiffLine(DiffLineType.Add,line,null,i+1)}
                publish(HarnessEvent.FileDiff(body.getString("sessionId"),body.getString("id"),body.getString("path"),lines));return JSONObject()
            }
        }
        if(method!="approve")error("手机能力即将支持")
        val session=body.optString("sessionId").ifEmpty { activeSession }
        check(session.isNotEmpty())
        val id=UUID.randomUUID().toString();val decision=CompletableDeferred<ApprovalDecision>();approvals[id]=decision
        val network=body.optString("kind")=="network"
        val detail=if(network) "向所选模型接口发送本次上下文" else vault.redact(listOf(body.optString("path"),body.optString("before"),body.optString("after"),body.optJSONObject("arguments")?.toString(2).orEmpty()).filter {it.isNotBlank() && it!="null"}.joinToString("\n").take(12000))
        publish(HarnessEvent.ApprovalRequested(session,id,if(network) "模型网络请求" else "工具执行审批",detail,if(network) "发送到当前 provider" else body.optString("command").ifEmpty{body.optString("tool")}))
        M2Notifications.approval(context,session,id,if(network)"模型网络请求" else body.optString("tool"))
        return try {
            val chosen=withTimeoutOrNull(290000) { decision.await() } ?: ApprovalDecision.Deny
            publish(HarnessEvent.ApprovalResolved(session,id,chosen))
            JSONObject().put("approved",chosen!=ApprovalDecision.Deny).put("remember",chosen==ApprovalDecision.AllowSession)
        } finally { approvals.remove(id);M2Notifications.dismiss(context,id) }
    }
    override suspend fun respondApproval(sessionId: String,approvalId: String,decision: ApprovalDecision) { approvals[approvalId]?.complete(decision) }
    override suspend fun answerQuestion(sessionId:String,questionId:String,answer:String){questions[questionId]?.complete(answer.take(32000))}
    private fun JSONArray.rows()=(0 until length()).map { getJSONObject(it) }
    private fun content(array: JSONArray?,type: String)=array?.rows()?.filter { it.optString("type")==type }?.joinToString("") { it.optString("text") }.orEmpty()
    private suspend fun accept(sid: String,frame: JSONObject) {
        when(frame.optString("type")) {
            "snapshot" -> {
                val pending=metadata.getBoolean("pending:$sid",false)
                val records=frame.getJSONArray("records").rows()
                records.forEach { record -> durable(sid,record.getJSONObject("event"),true) }
                if(pending){val last=records.map{it.getJSONObject("event")}.lastOrNull{it.optString("type") in setOf("turn/start","turn/end")};if(last?.optString("type")=="turn/end"&&last.optJSONObject("data")?.optJSONObject("reason")?.optString("kind")=="completed")metadata.edit().remove("pending:$sid").apply()}
                frame.optJSONObject("projections")?.optJSONObject("values")?.let{publishContext(sid,it)}
            }
            "event" -> durable(sid,frame.getJSONObject("event"))
            "assistant-stream" -> {
                val f=frame.getJSONObject("frame")
                when(f.optString("type")) {
                    "start" -> live[sid]=Live("a:${f.getString("attemptId")}")
                    "chunk" -> {
                        val l=live.getOrPut(sid) { Live("a:${f.getString("attemptId")}") };val chunk=f.getJSONObject("chunk");val delta=chunk.optString("text")
                        when(chunk.optString("type")) {
                            "text-delta" -> { l.text+=delta;publish(HarnessEvent.AssistantDelta(sid,l.id,delta)) }
                            "reasoning-delta" -> { l.thinking+=delta;publish(HarnessEvent.ThinkingDelta(sid,"r:${l.id}",delta)) }
                        }
                    }
                    "end" -> live[sid]?.let { l -> if(l.text.isNotEmpty())publish(HarnessEvent.AssistantDone(sid,l.id));if(l.thinking.isNotEmpty())publish(HarnessEvent.ThinkingDone(sid,"r:${l.id}",android.os.SystemClock.elapsedRealtime()-l.started)) }
                }
            }
        }
    }
    private suspend fun durable(sid: String,event: JSONObject,replay:Boolean=false) {
        val seq=event.getInt("seq");if(!seen.getOrPut(sid) { mutableSetOf() }.add(seq))return
        val id="e:$seq";val data=event.optJSONObject("data") ?: return
        cachedRecords[sid]=(cachedRecords[sid].orEmpty().filterNot{it.optInt("seq")==seq}+event).takeLast(300)
        if(event.optString("type") in setOf("user/message","assistant/message")){val time=OriginalSessionHistory.time(event.opt("time"));sessionRows.update{rows->rows.map{if(it.id==sid)it.copy(updatedAt=time)else it}}}
        when(event.getString("type")) {
            "turn/start" -> {if(!replay)M2SafeMode.recordRunningMemory(context);tokenTurns[sid]=TurnTokens(event.optLong("time",System.currentTimeMillis()));publish(HarnessEvent.TurnStarted(sid,id));sessionRows.update { list -> list.map { if(it.id==sid)it.copy(running=true) else it } } }
            "turn/end" -> {
                if(!replay)metadata.edit().remove("pending:$sid").apply()
                tokenTurns.remove(sid)?.takeIf{it.reported}?.let{u->publish(HarnessEvent.UsageReported(sid,id,Usage(u.input,u.output,u.cache,u.reasoning,(event.optLong("time",System.currentTimeMillis())-u.start).coerceAtLeast(0))))}
                live.remove(sid)?.let { l -> if(l.text.isNotEmpty())publish(HarnessEvent.AssistantDone(sid,l.id));if(l.thinking.isNotEmpty())publish(HarnessEvent.ThinkingDone(sid,"r:${l.id}",android.os.SystemClock.elapsedRealtime()-l.started)) }
                val reason=data.optJSONObject("reason")
                if(!replay&&!AppSurface.foreground)M2Notifications.post(context,"Hoshino 任务"+if(reason?.optString("kind")=="error")"失败"else"完成","打开应用查看会话结果")
                publish(HarnessEvent.TurnFinished(sid,id,reason?.optString("kind")=="aborted"))
                sessionRows.update { list -> list.map { if(it.id==sid)it.copy(running=false) else it } }
                scope.launch{persistCache()}
                scope.launch{runCatching{api().rpc("session/list",JSONObject()).getJSONArray("items").rows().firstOrNull{it.optString("sessionId")==sid}?.optJSONObject("projections")?.optJSONObject("values")?.let{publishContext(sid,it)}}}
                if(reason?.optString("kind")=="error") {
                    // dsh TurnEndReasonMap.error contains `error: LlmFailure`.
                    val failure=reason.optJSONObject("error")
                    val facts=listOfNotNull(failure?.optString("code")?.takeIf { it.isNotBlank() },failure?.optInt("status")?.takeIf { it in 100..599 }?.let { "HTTP $it" }).joinToString(" · ")
                    val detail=if(metadata.getString("session-provider:$sid",null)=="openai-codex"&&failure?.optInt("status") in setOf(401,403))"ChatGPT 登录已失效，请到设置 → 模型 → ChatGPT 订阅重新登录"else if(metadata.getString("session-provider:$sid",null)=="openai-codex"&&failure?.optInt("status")==429)"ChatGPT 订阅额度已用尽或受到限流，请稍后重试；模型设置中可重新登录"else vault.redact(failure?.optString("message").orEmpty()).take(1500).ifBlank { "内核未返回错误详情" }
                    publish(HarnessEvent.Notice(sid,"error:$seq","请求未完成${if(facts.isBlank()) "" else "（$facts）"}：$detail"))
                }
            }
            "user/message" -> if(data.optJSONObject("source")?.optString("kind")=="user") {
                val attachments=data.optJSONArray("content")?.rows().orEmpty().filter{it.optString("type") in setOf("image","file")}.map{block->val a=block.optJSONObject("attachment") ?: JSONObject();Attachment(a.optString("attachmentId"),a.optString("name","附件"),if(block.optString("type")=="image")AttachmentKind.Image else AttachmentKind.File,"${a.optLong("bytes")/1024} KB")}
                publish(HarnessEvent.UserMessage(sid,id,content(data.optJSONArray("content"),"text"),attachments))
            }
            "session/title" -> sessionRows.update { list -> list.map { if(it.id==sid)it.copy(title=data.optString("title",it.title)) else it } }
            "assistant/message" -> {
                data.optJSONObject("usage")?.let{usage->val u=tokenTurns.getOrPut(sid){TurnTokens(event.optLong("time",System.currentTimeMillis()))};u.reported=true;u.input+=usage.optInt("inputTokens");u.output+=usage.optInt("outputTokens");u.cache+=usage.optInt("cacheReadTokens");u.reasoning+=usage.optInt("reasoningTokens")}
                val message=data.getJSONObject("message");val text=content(message.optJSONArray("content"),"text");val reasoning=content(message.optJSONArray("content"),"reasoning")
                val l=live.remove(sid)
                if(l==null) {
                    if(reasoning.isNotEmpty()) {publish(HarnessEvent.ThinkingDelta(sid,"r:$id",reasoning));publish(HarnessEvent.ThinkingDone(sid,"r:$id",0))}
                    if(text.isNotEmpty()) {publish(HarnessEvent.AssistantDelta(sid,id,text));publish(HarnessEvent.AssistantDone(sid,id))}
                } else {
                    if(text.startsWith(l.text) && text.length>l.text.length)publish(HarnessEvent.AssistantDelta(sid,l.id,text.drop(l.text.length)))
                    if(text.isNotEmpty())publish(HarnessEvent.AssistantDone(sid,l.id))
                    if(l.thinking.isNotEmpty())publish(HarnessEvent.ThinkingDone(sid,"r:${l.id}",android.os.SystemClock.elapsedRealtime()-l.started))
                }
            }
            "request/context" -> {data.optInt("contextWindow").takeIf{it>0}?.let{capacities[sid]=it;publish(HarnessEvent.ContextUsageChanged(sid,ContextUsage(0,it,emptyList())))}}
            "tool/call" -> { val call=data.getString("callId");calls["$sid:$call"]=id;val name=data.getString("name");publish(HarnessEvent.ToolCallStarted(sid,id,name,name,data.getString("arguments"),when(name){"bash","terminal" -> ToolKind.Bash;"write","edit" -> ToolKind.Edit;else -> ToolKind.Generic}));if(name=="bash")appendAgentTerminal(sid,"$ "+JSONObject(data.getString("arguments")).optString("command"),TerminalStream.Input) }
            "tool/result" -> {
                val result=data.getJSONObject("message");val callId=calls["$sid:${result.getString("toolCallId")}"] ?: return
                publish(HarnessEvent.ToolCallOutput(sid,callId,content(result.optJSONArray("content"),"text")))
                publish(HarnessEvent.ToolCallFinished(sid,callId,if(result.optBoolean("isError"))ToolStatus.Failed else ToolStatus.Success))
                if(result.optString("toolName")=="bash" || terminalRows.value.any{it.id=="agent:$sid"})appendAgentTerminal(sid,content(result.optJSONArray("content"),"text"),if(result.optBoolean("isError"))TerminalStream.Error else TerminalStream.Output)
            }
        }
        if(event.getString("type")=="turn/end" && restartAfter.remove(sid))scope.launch {delay(500);restartKernel()}
    }
    private suspend fun publishContext(sid:String,values:JSONObject) {
        val pressure=values.optJSONObject("contextPressure")
        val parts=values.optJSONObject("contextBreakdown")
        if(pressure==null && parts==null)return
        val breakdown=mutableListOf<Pair<String,Int>>()
        parts?.let{breakdown+=listOf("系统提示（估算）" to it.optInt("systemTokens"),"工具定义（估算）" to it.optInt("toolsTokens"),"会话消息（估算）" to it.optInt("messageTokens"))}
        if(pressure?.has("pressureTokens")==true)breakdown+="最近实际输入" to pressure.optInt("pressureTokens")
        if(pressure?.has("projectedTokens")==true)breakdown+="下次请求（估算）" to pressure.optInt("projectedTokens")
        val used=when{pressure?.has("projectedTokens")==true->pressure.optInt("projectedTokens");pressure?.has("pressureTokens")==true->pressure.optInt("pressureTokens");else->parts?.let{it.optInt("systemTokens")+it.optInt("toolsTokens")+it.optInt("messageTokens")} ?: 0}
        val limit=pressure?.optInt("contextWindow")?.takeIf{it>0} ?: capacities[sid] ?: 128000
        publish(HarnessEvent.ContextUsageChanged(sid,ContextUsage(used.coerceAtLeast(0),limit,breakdown)))
    }
    suspend fun refreshSessions() {
        val history=withContext(Dispatchers.IO){OriginalSessionHistory.read(context)}.associateBy{it.id}
        val client=api();val items=client.rpc("session/list",JSONObject()).getJSONArray("items").rows()
        val recovered=workspaceRows.value.toMutableList()
        val rows=items.map { row ->
            row.optJSONObject("projections")?.optJSONObject("values")?.let{publishContext(row.getString("sessionId"),it)}
            val id=row.getString("sessionId");val old=sessionRows.value.firstOrNull { it.id==id }
            val cwd=row.optString("cwd").replace(Regex("^/workspace(?=/|$)"),"/root/workspace")
            val saved=old?.workspaceId ?: metadata.getString("workspace:$id","ws-default")!!
            val workspace=recovered.firstOrNull{it.path==cwd} ?: recovered.firstOrNull{it.id==saved} ?: Workspace(saved,cwd.substringAfterLast('/').ifBlank{"历史工作区"},cwd.ifBlank{"/root/workspace"}).also{recovered.add(it)}
            val title=(row.optJSONObject("projections")?.optJSONObject("values")?.opt("title") as? String)?.takeIf{it.isNotBlank()} ?: old?.title
            SessionInfo(id,title ?: "会话 ${id.take(8)}",workspace.id,old?.presetId ?: metadata.getString("preset:$id","standard")!!,old?.tier ?: PermissionTier.entries.firstOrNull{it.key==metadata.getString("tier:$id",null)} ?: PermissionTier.WorkspaceWrite,history[id]?.created ?: old?.createdAt ?: OriginalSessionHistory.time(row.opt("updatedAt")),history[id]?.updated ?: old?.updatedAt ?: 0L,row.getBoolean("running"),old?.archived ?: metadata.getBoolean("archived:$id",false),row.optString("parentSessionId").ifEmpty { null })
        }
        workspaceRows.value=recovered.distinctBy{it.id};sessionRows.value=rows+sessionRows.value.filter{local->metadata.getBoolean("local:${local.id}",false)&&rows.none{it.id==local.id}}
        preferredSessionId?.let{client.follow(it)}
        scope.launch{persistCache()}
    }
    override suspend fun searchSessions(query:String):SessionSearchResults {
        val value=api().rpc("session/search",JSONObject().put("query",query.trim()))
        val hits=value.getJSONArray("items").rows().map{SessionSearchHit(it.getString("sessionId"),vault.redact(it.getString("snippet")))}
        if(hits.any{hit->sessionRows.value.none{it.id==hit.sessionId}})refreshSessions()
        return SessionSearchResults(hits,value.optBoolean("hasMore"))
    }
    override suspend fun createSession(workspaceId: String,presetId: String,tier: PermissionTier): SessionInfo {
        require(tier!=PermissionTier.FullAccess || shizukuConnection.state.value==dev.hoshino.bridge.ShizukuConnectionState.Ready) { "请先启动 Shizuku 并授权 Hoshino Harness" }
        val path=workspaceRows.value.firstOrNull { it.id==workspaceId }?.path ?: "/root/workspace"
        val id=UUID.randomUUID().toString()
        val connected=runtime.state.value.phase=="已连接"
        if(connected)api().rpc("session/create",JSONObject().put("cwd",path).put("sessionId",id))else metadata.edit().putBoolean("local:$id",true).apply()
        val now=System.currentTimeMillis();val result=SessionInfo(id,"新会话",workspaceId,presetId,tier,now,0L)
        sessionRows.update { it.filterNot { row -> row.id==id }+result };activeSession=id;activateSession(id);if(connected)api().follow(id)
        metadata.edit().putString("workspace:$id",workspaceId).putString("preset:$id",presetId).putString("tier:$id",tier.key).apply()
        return result
    }
    override suspend fun renameSession(sessionId: String,title: String) { val value=api().rpc("session/rename",JSONObject().put("sessionId",sessionId).put("title",title));sessionRows.update { list -> list.map { if(it.id==sessionId)it.copy(title=value.getString("title")) else it } } }
    override suspend fun forkSession(sessionId: String): SessionInfo { val id=api().rpc("session/fork",JSONObject().put("sessionId",sessionId)).getString("sessionId");refreshSessions();return sessionRows.value.first { it.id==id } }
    override suspend fun setSessionTier(sessionId: String,tier: PermissionTier) {
        require(tier!=PermissionTier.FullAccess || shizukuConnection.state.value==dev.hoshino.bridge.ShizukuConnectionState.Ready) { "请先启动 Shizuku 并授权 Hoshino Harness" }
        control("session-options",JSONObject().put("sessionId",sessionId).put("tier",tier.key))
        sessionRows.update { list -> list.map { if(it.id==sessionId)it.copy(tier=tier) else it } };metadata.edit().putString("tier:$sessionId",tier.key).apply()
    }
    override suspend fun send(sessionId: String,text: String,attachments: List<Attachment>,options: TurnOptions) {
        if(runtime.state.value.phase!="已连接"){
            val id="waiting:"+UUID.randomUUID();outbox.add(PendingKernelMessages.Pending(id,sessionId,text,attachments,options,workspaceRows.value.firstOrNull{it.id==sessionRows.value.firstOrNull{s->s.id==sessionId}?.workspaceId}?.path?:"/root/workspace"));Hoshino.store.enqueue(sessionId,QueuedMessage(id,text,attachments));persistCache();startKernel();return
        }
        val client=api();activeSession=sessionId
        val model=options.model ?: error("请先配置模型供应商")
        val provider=providers.value.first{it.id==model.providerId}
        metadata.edit().putString("session-provider:$sessionId",provider.id).apply()
        val modelInfo=provider.models.first{it.id==model.modelId}
        val maxBudget=if(options.effort==ReasoningEffort.Max)maxOf(modelInfo.maxOutput,minOf(32768,(modelInfo.contextLength-1024).coerceAtLeast(modelInfo.maxOutput))) else modelInfo.maxOutput
        // /models often omits capabilities. An explicit user effort is authoritative.
        if(options.effort!=ReasoningEffort.Off && (!modelInfo.reasoning || maxBudget!=modelInfo.maxOutput || provider.protocol==ProviderProtocol.Anthropic && options.effort==ReasoningEffort.Max && modelInfo.defaultEffort!=ReasoningEffort.Max))
            upsertProvider(provider.copy(models=provider.models.map{if(it.id==model.modelId)it.copy(reasoning=true,defaultEffort=options.effort,maxOutput=maxBudget) else it}))
        require(attachments.none{it.kind==AttachmentKind.Image || it.kind==AttachmentKind.Camera} || modelInfo.vision){"当前模型未开启视觉，请在模型设置开启或切换模型"}
        control("session-options",JSONObject().put("sessionId",sessionId).put("tier",options.tier.key).put("planMode",options.planMode).put("mode",options.presetId).put("model",model.modelId))
        val selection=JSONObject().put("sessionId",sessionId).put("provider",model.providerId).put("model",model.modelId)
        // dsh exposes no reasoning capability for a non-reasoning model; even
        // sending "off" then causes session/model-unavailable before any HTTP call.
        if(providerRows.value.firstOrNull{it.id==model.providerId}?.models?.firstOrNull{it.id==model.modelId}?.reasoning==true)
            selection.put("reasoningEffort",options.effort.key)
        client.rpc("session/selectModel",selection)
        val content=JSONArray().put(JSONObject().put("type","text").put("text",text))
        val media=AppMedia(context)
        for(attachment in attachments) {
            val file=if(attachment.kind==AttachmentKind.WorkspaceFile)files.resolve(attachment.name) else media.keepOriginal(sessionId,attachment)
            if(attachment.kind==AttachmentKind.Image || attachment.kind==AttachmentKind.Camera)content.put(media.imageBlock(file,attachment.name))
            else content.put(JSONObject().put("type","file").put("receiptId",client.uploadFile(sessionId,file,attachment.name)))
        }
        metadata.edit().putBoolean("pending:$sessionId",true).apply()
        try{client.rpc("session/prompt",JSONObject().put("sessionId",sessionId).put("requestId",pendingRequestIds[sessionId]?:UUID.randomUUID().toString()).put("mode","queue").put("content",content))}catch(failure:Exception){metadata.edit().remove("pending:$sessionId").apply();throw failure}
    }
    private fun persistCache(){runCatching{val keep=preferredSessionId?:activeSession;nativeCache.save(sessionRows.value,cachedRecords.filterKeys{it==keep},vault::redact)}}
    fun restoreVisibleCache(){runBlocking{withTimeout(2000){eventBus.subscriptionCount.first{it>0}};for((id,records)in cachedRecords.toMap())for(event in records)runCatching{durable(id,event,true)}}}
    fun cancelWaiting(id:String){if(id.startsWith("waiting:"))outbox.remove(id)}
    fun restoreWaitingMessages(){for(p in outbox.list())Hoshino.store.enqueue(p.sessionId,QueuedMessage(p.id,p.text,p.attachments))}
    private suspend fun flushOutbox(){
        if(!draining.compareAndSet(false,true))return
        try{val pending=outbox.list();if(pending.isEmpty())return;val existing=api().rpc("session/list",JSONObject()).getJSONArray("items").rows().map{it.getString("sessionId")}.toMutableSet();for(p in pending){
            try{
                if(p.sessionId !in existing){api().rpc("session/create",JSONObject().put("sessionId",p.sessionId).put("cwd",p.cwd));existing.add(p.sessionId);metadata.edit().remove("local:${p.sessionId}").apply()}
                api().follow(p.sessionId);pendingRequestIds[p.sessionId]=p.id;send(p.sessionId,p.text,p.attachments,p.options);outbox.remove(p.id);Hoshino.store.removeQueued(p.sessionId,p.id)
            }catch(failure:Exception){publish(HarnessEvent.Notice(p.sessionId,"waiting-error:${p.id}","等待内核消息仍已保留："+describeFailure(failure)))}finally{pendingRequestIds.remove(p.sessionId)}
        }}finally{draining.set(false)}
    }
    override suspend fun steer(sessionId: String,text: String) { api().rpc("session/prompt",JSONObject().put("sessionId",sessionId).put("requestId",UUID.randomUUID().toString()).put("mode","steer").put("content",JSONArray().put(JSONObject().put("type","text").put("text",text)))) }
    private suspend fun resumeInterrupted(){if(M2SafeMode.enabled(context))return;val live=api().rpc("session/list",JSONObject()).getJSONArray("items").rows().associateBy{it.getString("sessionId")};for(row in sessions.value){if(metadata.getBoolean("pending:${row.id}",false)&&live[row.id]?.optBoolean("running")!=true){api().rpc("session/prompt",JSONObject().put("sessionId",row.id).put("requestId",UUID.randomUUID().toString()).put("mode","queue").put("content",JSONArray().put(JSONObject().put("type","text").put("text","Resume the interrupted user task from the persisted session log and last completed step. Inspect actual files before repeating any side effect. Do not duplicate completed actions."))))}}}
    override suspend fun stop(sessionId: String) { api().rpc("session/cancel",JSONObject().put("sessionId",sessionId)) }
    private fun readProviders(): List<Provider> = vault.list().map { p ->
        val m=p.metadata;val configured=m.optJSONArray("models")?.rows()?.associateBy { it.getString("id") }.orEmpty()
        Provider(p.id,m.optString("name",p.id),m.optString("catalogId",p.id),if(p.type=="anthropic")ProviderProtocol.Anthropic else ProviderProtocol.OpenAICompatible,p.baseURL,if(vault.hasSecret(p.credentialRef)||p.type=="openai-codex"&&chatGptAuth.state.value.signedIn)"••••••••" else "",m.optJSONArray("headerRefs")?.rows()?.map { it.getString("name") to "••••••••" }.orEmpty(),m.optBoolean("enabled",true),p.models.map { id -> val d=configured[id];ModelInfo(id,d?.optString("displayName",id) ?: id,d?.optInt("contextLength",128000) ?: 128000,d?.optInt("maxOutput",4096) ?: 4096,d?.optBoolean("vision",false) ?: false,d?.optBoolean("reasoning",p.type=="anthropic") ?: (p.type=="anthropic"),runCatching { ReasoningEffort.valueOf(d?.optString("defaultEffort") ?: if(p.type=="anthropic")"Medium" else "Off") }.getOrDefault(ReasoningEffort.Off)) })
    }
    suspend fun setChatGptEnabled(enabled:Boolean){
        val stored=vault.list().firstOrNull{it.id=="openai-codex"}
        val models=JSONArray(context.assets.open("codex-models.json").bufferedReader().use{it.readText()})
        val metadata=JSONObject().put("name","ChatGPT 订阅（实验）").put("catalogId","openai-codex").put("enabled",enabled).put("models",models)
        vault.save(stored?.copy(metadata=metadata)?:StoredProvider("openai-codex","openai-codex","https://chatgpt.com/backend-api",models.rows().map{it.getString("id")},metadata),"")
        providerRows.value=readProviders();if(runtime.state.value.phase=="已连接")api().reloadProviders()
    }
    override suspend fun upsertProvider(provider: Provider) {
        if(provider.id=="openai-codex") {val stored=vault.list().firstOrNull{it.id==provider.id}?:error("请通过 ChatGPT 订阅入口启用");vault.save(stored.copy(metadata=JSONObject(stored.metadata.toString()).put("enabled",provider.enabled)),"");providerRows.value=readProviders();if(runtime.state.value.phase=="已连接")api().reloadProviders();return}

        require(provider.protocol!=ProviderProtocol.OpenAIResponses) { "OpenAI Responses 即将支持" }
        val id=provider.id.takeIf { it.matches(Regex("[a-z][a-z0-9-]*")) } ?: "p-"+provider.id.lowercase().replace(Regex("[^a-z0-9-]"),"-")
        val prior=vault.list().firstOrNull { it.id==id };val oldRefs=prior?.metadata?.optJSONArray("headerRefs")?.rows().orEmpty()
        val oldValues=oldRefs.associate { it.getString("name") to vault.resolve(it.getString("ref")) }
        val headers=JSONArray(provider.headers.mapIndexed { i,pair -> val ref="HEADER_"+id.uppercase().replace('-','_')+"_$i";if(pair.second!="••••••••")vault.putSecret(ref,pair.second) else oldValues[pair.first]?.let { vault.putSecret(ref,it) };JSONObject().put("name",pair.first).put("ref",ref) })
        val metadata=JSONObject().put("name",provider.name).put("catalogId",provider.catalogId).put("enabled",provider.enabled).put("headerRefs",headers).put("models",JSONArray(provider.models.map { m -> JSONObject().put("id",m.id).put("displayName",m.displayName).put("contextLength",m.contextLength).put("maxOutput",m.maxOutput).put("vision",m.vision).put("reasoning",m.reasoning).put("defaultEffort",m.defaultEffort.name) }))
        vault.save(StoredProvider(id,if(provider.protocol==ProviderProtocol.Anthropic)"anthropic" else "openai-compatible",provider.baseUrl,provider.models.map { it.id },metadata),provider.apiKey.takeUnless { it=="••••••••" }.orEmpty())
        providerRows.value=readProviders();if(connection.value==ConnectionState.Connected)api().reloadProviders()
    }
    override suspend fun deleteProvider(providerId: String) { vault.delete(providerId);providerRows.value=readProviders();if(connection.value==ConnectionState.Connected)api().reloadProviders() }
    override suspend fun setProviderEnabled(providerId: String,enabled: Boolean) { upsertProvider(providerRows.value.first { it.id==providerId }.copy(enabled=enabled)) }
    override suspend fun setRouting(routing: ModelRouting) {
        routingRow.value=routing
        fun ref(value:ModelRef?):Any = value?.let{JSONObject().put("providerId",it.providerId).put("modelId",it.modelId)}?:JSONObject.NULL
        val saved=m2Control("config",JSONObject().put("name","routing.json"))
        saved.put("main",ref(routing.main)).put("compact",ref(routing.compact)).put("approval",ref(routing.approval)).put("vision",ref(routing.vision))
        m2Control("config",JSONObject().put("name","routing.json").put("value",saved))
    }
    suspend fun control(action:String,request:JSONObject=JSONObject()):JSONObject=api().mobile("control",JSONObject().put("action",action).put("request",request))
    suspend fun m2Control(operation:String,request:JSONObject=JSONObject()):JSONObject=control("m2",request.put("operation",operation).apply{if(!has("sessionId"))put("sessionId",activeSession)})
    override suspend fun setSessionPreset(sessionId:String,presetId:String){metadata.edit().putString("preset:$sessionId",presetId).apply();sessionRows.update{rows->rows.map{if(it.id==sessionId)it.copy(presetId=presetId)else it}}}
    override suspend fun upsertPreset(preset:AgentPreset){val current=m2Control("config",JSONObject().put("name","modes.json"));val rows=current.optJSONArray("modes")?:JSONArray();val next=JSONArray();for(i in 0 until rows.length()){val row=rows.getJSONObject(i);if(row.optString("id")!=preset.id)next.put(row)};next.put(JSONObject().put("id",preset.id).put("name",preset.name).put("description",preset.description).put("systemPrompt",preset.systemPrompt).put("tools",JSONArray(preset.tools)));m2Control("config",JSONObject().put("name","modes.json").put("value",JSONObject().put("modes",next)));refreshControl()}
    override suspend fun deletePreset(presetId:String){val current=m2Control("config",JSONObject().put("name","modes.json"));val rows=current.optJSONArray("modes")?:JSONArray();val next=JSONArray();for(i in 0 until rows.length()){val row=rows.getJSONObject(i);if(row.optString("id")!=presetId)next.put(row)};m2Control("config",JSONObject().put("name","modes.json").put("value",JSONObject().put("modes",next)));refreshControl()}
    suspend fun refreshControl() {
        val state=control("inspect");val manifest=state.optJSONObject("manifest")
        val built=manifest?.optJSONArray("plugins")?.rows().orEmpty().map{row->Plugin(row.getString("id"),row.getString("id"),row.optString("name"),"0.2.2-alpha.2",true,PluginSource.BuiltIn,PluginCategory.Tool,"Cordis · "+row.optString("source"))}
        val user=state.getJSONArray("plugins").rows().map{row->Plugin(row.getString("id"),row.getString("id"),"用户 Cordis 插件","0.1.0",row.getBoolean("enabled"),PluginSource.Local,PluginCategory.Tool,if(row.optBoolean("invalid"))"加载失败，请回滚" else "可热加载的用户工具")}
        pluginRows.value=built+user
        skillRows.value=state.getJSONArray("skills").rows().map{row->Skill(row.getString("name"),row.getString("name"),row.optString("description"),true,row.optString("source"),"")}
        inspectorRows.value=listOf(InspectorNode("dsh","Cordis","就绪",pluginRows.value.map{InspectorNode(it.id,it.pkg,if(it.enabled)"已挂载" else "已停用")}))
        val overrides=control("ui-read")
        val parsed=runCatching { check(overrides.optBoolean("valid",true));parseUiCustomization(overrides.getJSONObject("value")) }
        uiCustomization.value=parsed.getOrDefault(UiCustomization())
        uiCustomizationError.value=if(parsed.isFailure)"UI 覆盖配置无效，已恢复原始界面；可在内核设置中回滚" else overrides.optJSONObject("slotErrors")?.takeIf{it.length()>0}?.let{"部分 slot 已恢复默认组件："+it.toString().take(2000)}
        runCatching{val config=m2Control("state");presets.value=config.getJSONArray("modes").rows().map{r->AgentPreset(r.getString("id"),r.optString("name",r.getString("id")),r.optString("description"),r.getString("id") in setOf("chat","standard","plan","ask","research","review","goal","auto","orchestrator"),r.optString("systemPrompt"),r.optJSONArray("tools")?.let{a->(0 until a.length()).map{a.getString(it)}}.orEmpty(),null,if(r.optBoolean("readOnly"))PermissionTier.ReadOnly else PermissionTier.WorkspaceWrite)};val prefs=config.optJSONObject("configuration")?.optJSONObject("preferences.json");if(parsed.isSuccess&&!uiCustomization.value.raw.has("process")){uiCustomization.value=uiCustomization.value.copy(raw=JSONObject(uiCustomization.value.raw.toString()).put("process",JSONObject().put("display",prefs?.optString("process","collapsed")?:"collapsed").put("types",prefs?.optJSONObject("typeDefaults")?:JSONObject())))}}
        M2SafeMode.ready(context)
    }
    private fun parseUiCustomization(value:JSONObject):UiCustomization {
        fun scale(key:String)=if(value.has(key))value.getDouble(key).also{require(it.isFinite() && it in 0.8..1.5)}.toFloat() else null
        val accent=value.optString("accent").takeIf{it.isNotBlank()}?.also{require(it.matches(Regex("#[a-fA-F0-9]{6}")))}
        return UiCustomization(accent,scale("bodyScale"),scale("codeScale"),value.optString("tagline").takeIf{it.isNotBlank()},
            value.optJSONArray("capsules")?.rows()?.map{UiCapsule(it.getString("label"),it.getString("prompt"))},
            value.optJSONArray("commands")?.rows().orEmpty().map{UiCommand("/"+it.getString("name"),it.getString("description"),it.getString("prompt"))},
            value.optJSONArray("sidebar")?.rows().orEmpty().map{UiSidebarEntry(it.getString("route"),it.getBoolean("visible"))},value)
    }
    override suspend fun configurationSnapshots():List<String> {
        val rows=control("snapshots").getJSONArray("items");return (0 until rows.length()).map{rows.getString(it)}
    }
    override suspend fun rollbackConfiguration(snapshotId:String) {control("rollback",JSONObject().put("id",snapshotId));refreshControl()}
    override suspend fun setPluginEnabled(pluginId:String,enabled:Boolean) {require(plugins.value.none{it.id==pluginId && it.source==PluginSource.BuiltIn}){"内置依赖请通过源码组合替换"};control("enable-plugin",JSONObject().put("id",pluginId).put("enabled",enabled));refreshControl()}
    override suspend fun removePlugin(pluginId:String) {require(plugins.value.none{it.id==pluginId && it.source==PluginSource.BuiltIn});control("remove-plugin",JSONObject().put("id",pluginId));refreshControl()}
    override suspend fun installPluginFromGit(spec:String):Result<Plugin> = runCatching{val result=m2Control("install-plugin",JSONObject().put("spec",spec));check(result.optBoolean("ok")){result.optString("error","插件安装失败")+"\n"+result.optString("detail")};val id=result.getString("id");control("reload",JSONObject().put("plugin",id));refreshControl();plugins.value.first{it.id==id}}
    override suspend fun linkLocalPlugin(path:String):Result<Plugin> = installPluginFromGit(path)
    // 插件市场：内核（proot 里的 Node）直连读取索引；Node 不走 Android 的系统代理，失败时改由 Android 网络栈（系统代理/VPN）下载索引交给内核解析。
    override suspend fun market(op:String,args:JSONObject):JSONObject{
        val request=JSONObject(args.toString()).put("op",op);systemProxy()?.let{request.put("proxy",it)}
        return try{m2Control("market",request)}catch(first:Exception){
            val url=request.optString("url");if(url.isBlank()||op !in setOf("query","plugin","install","source-add"))throw first
            val file=runCatching{androidDownloadIndex(url)}.getOrElse{throw IllegalStateException((first.message?:"索引读取失败")+"\n系统网络也失败："+(it.message?:it.javaClass.simpleName))}
            m2Control("market",JSONObject().put("op","load-file").put("url",url).put("file",file))
            request.remove("refresh");m2Control("market",request)
        }
    }
    private fun systemProxy():String?{val info=runCatching{(context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).defaultProxy}.getOrNull()?:return null;val host=info.host;return if(host.isNullOrBlank()||info.port<=0)null else "http://$host:${info.port}"}
    private suspend fun androidDownloadIndex(url:String):String=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){
        val candidates=listOf(url)+if(java.net.URI(url).host=="cdn.jsdelivr.net")listOf("fastly.jsdelivr.net","gcore.jsdelivr.net","testingcf.jsdelivr.net").map{url.replaceFirst("cdn.jsdelivr.net",it)} else emptyList()
        val hash=java.security.MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString(""){"%02x".format(it)}
        val dir=java.io.File(context.filesDir,"hoshino/market-cache").also{it.mkdirs()};val target=java.io.File(dir,"$hash.json");val tmp=java.io.File(dir,"$hash.tmp")
        val errors=mutableListOf<String>()
        for(candidate in candidates){
            try{
                var current=java.net.URL(candidate)
                fun open(u:java.net.URL)=(u.openConnection() as java.net.HttpURLConnection).apply{connectTimeout=15000;readTimeout=30000;instanceFollowRedirects=false;setRequestProperty("Accept","application/json");setRequestProperty("User-Agent","Hoshino-Harness/0.2 (DSH plugin market)")}
                var conn=open(current);var hops=0
                while(conn.responseCode in 300..399){val location=conn.getHeaderField("Location")?:error("HTTP ${conn.responseCode} 无跳转地址");check(hops++<5){"跳转次数过多"};val next=java.net.URL(current,location);conn.disconnect();check(!(current.protocol=="https"&&next.protocol!="https")){"拒绝从 https 跳转到 http"};current=next;conn=open(current)}
                check(conn.responseCode in 200..299){"HTTP ${conn.responseCode}"}
                conn.inputStream.use{input->tmp.outputStream().use{out->val b=ByteArray(65536);var total=0L;while(true){val n=input.read(b);if(n<0)break;total+=n;check(total<=128L*1024*1024){"索引超过 128 MiB"};out.write(b,0,n)}}}
                conn.disconnect();check(tmp.renameTo(target)||run{target.delete();tmp.renameTo(target)}){"无法写入索引缓存"}
                return@withContext "market-cache/$hash.json"
            }catch(e:Exception){tmp.delete();errors+=java.net.URL(candidate).host+" "+(e.message?:e.javaClass.simpleName)}
        }
        error(errors.joinToString("；"))
    }
    override suspend fun installFromMarket(source:String,name:String,version:String?):Result<Plugin> = runCatching{val args=JSONObject().put("url",source).put("name",name);if(version!=null)args.put("version",version);val result=market("install",args);check(result.optBoolean("ok")){result.optString("error","插件安装失败")+"\n"+result.optString("detail").takeLast(1200)};val id=result.getString("id");control("reload",JSONObject().put("plugin",id));refreshControl();plugins.value.firstOrNull{it.id==id}?:error("已安装，但内核未能加载 $id；可在已安装中查看或移除")}
    override suspend fun installPluginFromZip(archive:java.io.File,name:String):Result<Plugin> = runCatching{
        val id=java.util.UUID.randomUUID().toString();val root=java.io.File(context.filesDir,"hoshino/plugin-sources/$id").also{it.mkdirs()}.canonicalFile
        try{
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){java.util.zip.ZipFile(archive).use{zip->var total=0L;var count=0
                for(entry in zip.entries()){count++;check(count<=20000){"ZIP 文件过多"};val n=entry.name;if(n.startsWith("__MACOSX/"))continue
                    require(!n.contains('\\')&&!n.startsWith("/")&&n.split('/').none{it==".."}){"ZIP 含不安全路径：$n"}
                    val dest=java.io.File(root,n).canonicalFile;require(dest.path.startsWith(root.path+java.io.File.separator)){"ZIP 含不安全路径：$n"}
                    if(entry.isDirectory){dest.mkdirs();continue};dest.parentFile!!.mkdirs()
                    zip.getInputStream(entry).use{input->dest.outputStream().use{out->val b=ByteArray(65536);while(true){val r=input.read(b);if(r<0)break;total+=r;check(total<=256L*1024*1024){"插件解压后超过 256 MiB"};out.write(b,0,r)}}}}}}
            val result=m2Control("install-plugin-zip",JSONObject().put("dir","plugin-sources/$id").put("name",name))
            check(result.optBoolean("ok")){result.optString("error","插件安装失败")+"\n"+result.optString("detail")}
            val pid=result.getString("id");control("reload",JSONObject().put("plugin",pid));refreshControl();plugins.value.first{it.id==pid}
        }catch(e:Exception){root.deleteRecursively();throw e}finally{archive.delete()}
    }
    override suspend fun readPluginConfig(pluginId:String)=control("config-read",JSONObject().put("path","plugins/$pluginId/config.json")).getString("content")
    override suspend fun writePluginConfig(pluginId:String,content:String) {control("config-write",JSONObject().put("path","plugins/$pluginId/config.json").put("content",content));control("reload",JSONObject().put("plugin",pluginId));refreshControl()}
    override suspend fun createSkill(name:String,description:String,content:String) {control("create-skill",JSONObject().put("name",name).put("description",description).put("content",content));refreshControl()}
    override suspend fun resolveDiff(sessionId:String,diffId:String,accept:Boolean) {control("resolve-diff",JSONObject().put("sessionId",sessionId).put("id",diffId).put("accept",accept));publish(HarnessEvent.DiffResolved(sessionId,diffId,if(accept)DiffState.Accepted else DiffState.Reverted))}
    override suspend fun addWorkspace(name:String,path:String):Workspace {
        val file=files.resolve(path);if(!file.exists())files.createDirectory(path);check(file.isDirectory)
        val public=files.publicPath(file);val result=api().rpc("workspace/create",JSONObject().put("path",public))
        val registered=result.getJSONObject("workspace")
        val id=registered.getString("workspaceId")
        val renamed=api().rpc("workspace/rename",JSONObject().put("workspaceId",id).put("title",name)).getJSONObject("workspace")
        val row=Workspace(id,renamed.getString("title"),renamed.getString("path"));workspaceRows.update{it.filterNot{old->old.id==id}+row};return row
    }
    override suspend fun searchWorkspaceFiles(workspaceId:String,query:String)=files.search(query)
    override suspend fun setArchived(sessionId:String,archived:Boolean) {api().rpc(if(archived)"workspace/archiveSession" else "workspace/unarchiveSession",JSONObject().put("sessionId",sessionId));metadata.edit().putBoolean("archived:$sessionId",archived).apply();sessionRows.update{list->list.map{if(it.id==sessionId)it.copy(archived=archived) else it}}}
    override suspend fun openTerminal():String {
        val sid=activeSession.takeIf{sessionRows.value.any{row->row.id==it}} ?: createSession("ws-default","standard",PermissionTier.WorkspaceWrite).id
        check(sessionRows.value.first{it.id==sid}.tier!=PermissionTier.ReadOnly){"只读会话不允许启动 shell"}
        val id=UUID.randomUUID().toString();val client=api()
        client.rpcArgs("terminal/create",JSONObject().put("agentId",sid).put("request",JSONObject().put("id",id).put("cols",80).put("rows",24)))
        terminalOwners[id]=sid;terminalSequences[id]=0;terminalRows.update{it+TerminalSession(id,"bash",emptyList())}
        client.openNamedStream("terminal:$id","terminal/attach",JSONObject().put("agentId",sid).put("id",id));return id
    }
    override suspend fun closeTerminal(terminalId:String) {
        val sid=terminalOwners.remove(terminalId)
        if(sid!=null){api().cancelStream("terminal:$terminalId");api().rpcArgs("terminal/close",JSONObject().put("agentId",sid).put("id",terminalId))}
        terminalSequences.remove(terminalId);terminalWritable.remove(terminalId);terminalRows.update{it.filterNot{row->row.id==terminalId}}
    }
    override suspend fun terminalInput(terminalId:String,input:String) {
        val sid=terminalOwners[terminalId] ?: error("Agent 日志只读")
        check(sessionRows.value.firstOrNull{it.id==sid}?.tier!=PermissionTier.ReadOnly){"只读会话"}
        withTimeout(15000){while(terminalWritable[terminalId]!=true)delay(20)}
        val data=when(input){"^C"->"\u0003";"\t","\u001b","\u001b[A","\u001b[B"->input;else->input+"\r"}
        require(data.toByteArray().size<=16000){"单次输入过长"}
        val sequence=terminalSequences.compute(terminalId){_,old->(old ?: 0)+1}!!
        api().streamInput("terminal:$terminalId",JSONObject().put("type","write").put("sequence",sequence).put("data",data))
    }
    private fun acceptTerminal(id:String,frame:JSONObject) {
        when(frame.optString("type")) {
            "snapshot"->{terminalWritable[id]=frame.optBoolean("writable");terminalRows.update{list->list.map{if(it.id==id)it.copy(lines=listOf(TerminalLine(frame.getString("screen"),TerminalStream.Output))) else it}}}
            "output"->terminalRows.update{list->list.map{if(it.id==id)it.copy(lines=(it.lines+TerminalLine(frame.getString("data"),TerminalStream.Output)).takeLast(2000)) else it}}
            "state"->{terminalWritable[id]=frame.optBoolean("writable");val info=frame.getJSONObject("info");if(info.optString("state")!="running")terminalRows.update{list->list.map{if(it.id==id)it.copy(title="bash · ${info.optString("state")}") else it}}}
            "ack"->if(frame.has("refusal"))terminalWritable[id]=false
        }
    }
    private fun appendAgentTerminal(sid:String,text:String,stream:TerminalStream) {val id="agent:$sid";terminalRows.update{list->val row=list.firstOrNull{it.id==id} ?: TerminalSession(id,"Agent · 只读",emptyList());list.filterNot{it.id==id}+row.copy(lines=(row.lines+TerminalLine(vault.redact(text),stream)).takeLast(2000))}}
    override suspend fun fetchModels(provider: Provider): List<ModelInfo> {
        require(provider.protocol!=ProviderProtocol.OpenAIResponses) { "OpenAI Responses 即将支持" }
        val stored=vault.list().firstOrNull { it.id==provider.id }
        val key=if(provider.apiKey=="••••••••")stored?.let { vault.resolve(it.credentialRef) }.orEmpty() else provider.apiKey
        val existing=stored?.metadata?.optJSONArray("headerRefs")?.rows()?.associate { it.getString("name") to vault.resolve(it.getString("ref")).orEmpty() }.orEmpty()
        val headers=provider.headers.associate { it.first to if(it.second=="••••••••")existing[it.first].orEmpty() else it.second }
        val result=try { dev.hoshino.bridge.ProviderProbe().models(StoredProvider(provider.id,if(provider.protocol==ProviderProtocol.Anthropic)"anthropic" else "openai-compatible",provider.baseUrl,emptyList()),key,headers) }
            catch(cancelled: CancellationException) { throw cancelled } catch(_: Exception) { throw IllegalStateException("拉取模型失败，请检查协议、地址与凭据") }
        return result.rows().map { row -> provider.models.firstOrNull { it.id==row.getString("id") } ?: ModelInfo(row.getString("id"),row.getString("name"),128000,4096,false,provider.protocol==ProviderProtocol.Anthropic,if(provider.protocol==ProviderProtocol.Anthropic)ReasoningEffort.Medium else ReasoningEffort.Off) }
    }
    override suspend fun testProvider(provider: Provider): Result<String> = try {
        Result.success("连接成功，可获取 ${fetchModels(provider).size} 个模型")
    } catch(cancelled: CancellationException) { throw cancelled } catch(_: Exception) { Result.failure(IllegalStateException("连接失败，请检查协议、地址和凭据")) }
}

