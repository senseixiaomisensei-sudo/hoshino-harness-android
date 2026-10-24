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
    override fun describeFailure(failure: Throwable)=vault.redact(failure.message.orEmpty()).take(1500).ifBlank { "操作未完成，内核没有返回错误详情" }
    private val shizukuConnection=dev.hoshino.bridge.ShizukuConnection(context.applicationContext)
    private val systemShell=dev.hoshino.bridge.ShizukuShell(context.applicationContext)
    override val shizuku=shizukuConnection.state.map { ShizukuStatus.valueOf(it.name) }.stateIn(scope,SharingStarted.Eagerly,ShizukuStatus.valueOf(shizukuConnection.state.value.name))
    override suspend fun refreshShizuku(){shizukuConnection.refresh()}
    override suspend fun requestShizukuPermission()=withContext(Dispatchers.Main){shizukuConnection.requestPermission()}
    val runtime=NativeKernel(context,callbackHandler=::callback)
    private val eventBus=MutableSharedFlow<HarnessEvent>(extraBufferCapacity=1024)
    override val events=eventBus.asSharedFlow()
    override val connection=runtime.state.map { when(it.phase) { "已连接" -> ConnectionState.Connected; "启动中" -> ConnectionState.Connecting; else -> ConnectionState.Disconnected } }.stateIn(scope,SharingStarted.Eagerly,ConnectionState.Disconnected)
    override val kernel=runtime.state.map { KernelInfo("0.2.1-rc.2","24.18.1",it.phase=="已连接","proot + Alpine",it.phase=="已连接") }.stateIn(scope,SharingStarted.Eagerly,KernelInfo("0.2.1-rc.2","24.18.1",false,"proot + Alpine",false))
    private val sessionRows=MutableStateFlow<List<SessionInfo>>(emptyList())
    override val sessions=sessionRows.asStateFlow()
    private val workspaceRows=MutableStateFlow(listOf(Workspace("ws-default","workspace","/root/workspace")))
    override val workspaces=workspaceRows.asStateFlow()
    private val providerRows=MutableStateFlow<List<Provider>>(readProviders())
    override val providers=providerRows.asStateFlow()
    private val routingRow=MutableStateFlow(ModelRouting())
    override val routing=routingRow.asStateFlow()
    override val catalog=listOf(CatalogProvider("anthropic","Anthropic",ProviderProtocol.Anthropic,"https://api.anthropic.com"),CatalogProvider("openai","OpenAI",ProviderProtocol.OpenAICompatible,"https://api.openai.com/v1"),CatalogProvider("deepseek","DeepSeek",ProviderProtocol.OpenAICompatible,"https://api.deepseek.com/v1"),CatalogProvider("ollama","Ollama",ProviderProtocol.OpenAICompatible,"http://127.0.0.1:11434/v1"),CatalogProvider("custom","自定义供应商",ProviderProtocol.OpenAICompatible,""))
    private val approvals=java.util.concurrent.ConcurrentHashMap<String,CompletableDeferred<ApprovalDecision>>()
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
    val files=dev.hoshino.bridge.WorkspaceFiles(java.io.File(context.filesDir,"workspace"),java.io.File(context.filesDir,"hoshino-trash"))
    private val metadata=context.getSharedPreferences("m1-session-metadata",Context.MODE_PRIVATE)
    private val restartAfter=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    override fun activateSession(sessionId:String) { activeSession=sessionId }

    init {
        scope.launch { runtime.frames.collect { (stream,frame) ->
            try {
                if(stream.startsWith("session:"))accept(stream.removePrefix("session:"),frame)
                else if(stream.startsWith("terminal:"))acceptTerminal(stream.removePrefix("terminal:"),frame)
            }
            catch(failure: Exception) { runtime.diagnostic("mapping: ${failure.javaClass.simpleName}") }
        } }
        scope.launch { runtime.state.map { it.phase }.distinctUntilChanged().collect { if(it=="已连接")runCatching { refreshSessions();refreshControl() }.onFailure { runtime.diagnostic("sessions: ${it.javaClass.simpleName}") } } }
    }
    fun startKernel()=runtime.start()
    fun stopKernel() { approvals.values.forEach { it.complete(ApprovalDecision.Deny) };systemShell.close();runtime.stop() }
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
        return try {
            val chosen=withTimeoutOrNull(290000) { decision.await() } ?: ApprovalDecision.Deny
            publish(HarnessEvent.ApprovalResolved(session,id,chosen))
            JSONObject().put("approved",chosen!=ApprovalDecision.Deny).put("remember",chosen==ApprovalDecision.AllowSession)
        } finally { approvals.remove(id) }
    }
    override suspend fun respondApproval(sessionId: String,approvalId: String,decision: ApprovalDecision) { approvals[approvalId]?.complete(decision) }
    private fun JSONArray.rows()=(0 until length()).map { getJSONObject(it) }
    private fun content(array: JSONArray?,type: String)=array?.rows()?.filter { it.optString("type")==type }?.joinToString("") { it.optString("text") }.orEmpty()
    private suspend fun accept(sid: String,frame: JSONObject) {
        when(frame.optString("type")) {
            "snapshot" -> {
                frame.getJSONArray("records").rows().forEach { record -> durable(sid,record.getJSONObject("event")) }
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
    private suspend fun durable(sid: String,event: JSONObject) {
        val seq=event.getInt("seq");if(!seen.getOrPut(sid) { mutableSetOf() }.add(seq))return
        val id="e:$seq";val data=event.optJSONObject("data") ?: return
        when(event.getString("type")) {
            "turn/start" -> {tokenTurns[sid]=TurnTokens(event.optLong("time",System.currentTimeMillis()));publish(HarnessEvent.TurnStarted(sid,id));sessionRows.update { list -> list.map { if(it.id==sid)it.copy(running=true) else it } } }
            "turn/end" -> {
                tokenTurns.remove(sid)?.takeIf{it.reported}?.let{u->publish(HarnessEvent.UsageReported(sid,id,Usage(u.input,u.output,u.cache,u.reasoning,(event.optLong("time",System.currentTimeMillis())-u.start).coerceAtLeast(0))))}
                live.remove(sid)?.let { l -> if(l.text.isNotEmpty())publish(HarnessEvent.AssistantDone(sid,l.id));if(l.thinking.isNotEmpty())publish(HarnessEvent.ThinkingDone(sid,"r:${l.id}",android.os.SystemClock.elapsedRealtime()-l.started)) }
                val reason=data.optJSONObject("reason")
                publish(HarnessEvent.TurnFinished(sid,id,reason?.optString("kind")=="aborted"))
                sessionRows.update { list -> list.map { if(it.id==sid)it.copy(running=false,updatedAt=System.currentTimeMillis()) else it } }
                scope.launch{runCatching{api().rpc("session/list",JSONObject()).getJSONArray("items").rows().firstOrNull{it.optString("sessionId")==sid}?.optJSONObject("projections")?.optJSONObject("values")?.let{publishContext(sid,it)}}}
                if(reason?.optString("kind")=="error") {
                    // dsh TurnEndReasonMap.error contains `error: LlmFailure`.
                    val failure=reason.optJSONObject("error")
                    val facts=listOfNotNull(failure?.optString("code")?.takeIf { it.isNotBlank() },failure?.optInt("status")?.takeIf { it in 100..599 }?.let { "HTTP $it" }).joinToString(" · ")
                    val detail=vault.redact(failure?.optString("message").orEmpty()).take(1500).ifBlank { "内核未返回错误详情" }
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
        val client=api();val items=client.rpc("session/list",JSONObject()).getJSONArray("items").rows()
        val recovered=workspaceRows.value.toMutableList()
        val rows=items.map { row ->
            row.optJSONObject("projections")?.optJSONObject("values")?.let{publishContext(row.getString("sessionId"),it)}
            val id=row.getString("sessionId");val old=sessionRows.value.firstOrNull { it.id==id }
            val cwd=row.optString("cwd").replace(Regex("^/workspace(?=/|$)"),"/root/workspace")
            val saved=old?.workspaceId ?: metadata.getString("workspace:$id","ws-default")!!
            val workspace=recovered.firstOrNull{it.path==cwd} ?: recovered.firstOrNull{it.id==saved} ?: Workspace(saved,cwd.substringAfterLast('/').ifBlank{"历史工作区"},cwd.ifBlank{"/root/workspace"}).also{recovered.add(it)}
            val title=(row.optJSONObject("projections")?.optJSONObject("values")?.opt("title") as? String)?.takeIf{it.isNotBlank()} ?: old?.title
            SessionInfo(id,title ?: "会话 ${id.take(8)}",workspace.id,old?.presetId ?: metadata.getString("preset:$id","standard")!!,old?.tier ?: PermissionTier.entries.firstOrNull{it.key==metadata.getString("tier:$id",null)} ?: PermissionTier.WorkspaceWrite,old?.createdAt ?: row.getLong("updatedAt"),row.getLong("updatedAt"),row.getBoolean("running"),old?.archived ?: metadata.getBoolean("archived:$id",false),row.optString("parentSessionId").ifEmpty { null })
        }
        workspaceRows.value=recovered.distinctBy{it.id};sessionRows.value=rows
        sessionRows.value.forEach { client.follow(it.id) }
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
        val client=api();val id=client.rpc("session/create",JSONObject().put("cwd",path)).getString("sessionId")
        val now=System.currentTimeMillis();val result=SessionInfo(id,"新会话",workspaceId,presetId,tier,now,now)
        sessionRows.update { it.filterNot { row -> row.id==id }+result };activeSession=id;client.follow(id)
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
        val client=api();activeSession=sessionId
        val model=options.model ?: error("请先配置模型供应商")
        val provider=providers.value.first{it.id==model.providerId}
        val modelInfo=provider.models.first{it.id==model.modelId}
        val maxBudget=if(options.effort==ReasoningEffort.Max)maxOf(modelInfo.maxOutput,minOf(32768,(modelInfo.contextLength-1024).coerceAtLeast(modelInfo.maxOutput))) else modelInfo.maxOutput
        // /models often omits capabilities. An explicit user effort is authoritative.
        if(options.effort!=ReasoningEffort.Off && (!modelInfo.reasoning || maxBudget!=modelInfo.maxOutput || provider.protocol==ProviderProtocol.Anthropic && options.effort==ReasoningEffort.Max && modelInfo.defaultEffort!=ReasoningEffort.Max))
            upsertProvider(provider.copy(models=provider.models.map{if(it.id==model.modelId)it.copy(reasoning=true,defaultEffort=options.effort,maxOutput=maxBudget) else it}))
        require(attachments.none{it.kind==AttachmentKind.Image || it.kind==AttachmentKind.Camera} || modelInfo.vision){"当前模型未开启视觉，请在模型设置开启或切换模型"}
        control("session-options",JSONObject().put("sessionId",sessionId).put("tier",options.tier.key).put("planMode",options.planMode))
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
        client.rpc("session/prompt",JSONObject().put("sessionId",sessionId).put("requestId",UUID.randomUUID().toString()).put("mode","queue").put("content",content))
    }
    override suspend fun steer(sessionId: String,text: String) { api().rpc("session/prompt",JSONObject().put("sessionId",sessionId).put("requestId",UUID.randomUUID().toString()).put("mode","steer").put("content",JSONArray().put(JSONObject().put("type","text").put("text",text)))) }
    override suspend fun stop(sessionId: String) { api().rpc("session/cancel",JSONObject().put("sessionId",sessionId)) }
    private fun readProviders(): List<Provider> = vault.list().map { p ->
        val m=p.metadata;val configured=m.optJSONArray("models")?.rows()?.associateBy { it.getString("id") }.orEmpty()
        Provider(p.id,m.optString("name",p.id),m.optString("catalogId",p.id),if(p.type=="anthropic")ProviderProtocol.Anthropic else ProviderProtocol.OpenAICompatible,p.baseURL,if(vault.hasSecret(p.credentialRef))"••••••••" else "",m.optJSONArray("headerRefs")?.rows()?.map { it.getString("name") to "••••••••" }.orEmpty(),m.optBoolean("enabled",true),p.models.map { id -> val d=configured[id];ModelInfo(id,d?.optString("displayName",id) ?: id,d?.optInt("contextLength",128000) ?: 128000,d?.optInt("maxOutput",4096) ?: 4096,d?.optBoolean("vision",false) ?: false,d?.optBoolean("reasoning",p.type=="anthropic") ?: (p.type=="anthropic"),runCatching { ReasoningEffort.valueOf(d?.optString("defaultEffort") ?: if(p.type=="anthropic")"Medium" else "Off") }.getOrDefault(ReasoningEffort.Off)) })
    }
    override suspend fun upsertProvider(provider: Provider) {
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
    override suspend fun setRouting(routing: ModelRouting) { routingRow.value=routing }
    suspend fun control(action:String,request:JSONObject=JSONObject()):JSONObject=api().mobile("control",JSONObject().put("action",action).put("request",request))
    suspend fun refreshControl() {
        val state=control("inspect");val manifest=state.optJSONObject("manifest")
        val built=manifest?.optJSONArray("plugins")?.rows().orEmpty().map{row->Plugin(row.getString("id"),row.getString("id"),row.optString("name"),"0.2.1-rc.2",true,PluginSource.BuiltIn,PluginCategory.Tool,"Cordis · "+row.optString("source"))}
        val user=state.getJSONArray("plugins").rows().map{row->Plugin(row.getString("id"),row.getString("id"),"用户 Cordis 插件","0.1.0",row.getBoolean("enabled"),PluginSource.Local,PluginCategory.Tool,if(row.optBoolean("invalid"))"加载失败，请回滚" else "可热加载的用户工具")}
        pluginRows.value=built+user
        skillRows.value=state.getJSONArray("skills").rows().map{row->Skill(row.getString("name"),row.getString("name"),row.optString("description"),true,row.optString("source"),"")}
        inspectorRows.value=listOf(InspectorNode("dsh","Cordis","就绪",pluginRows.value.map{InspectorNode(it.id,it.pkg,if(it.enabled)"已挂载" else "已停用")}))
        val overrides=control("ui-read")
        val parsed=runCatching { check(overrides.optBoolean("valid",true));parseUiCustomization(overrides.getJSONObject("value")) }
        uiCustomization.value=parsed.getOrDefault(UiCustomization())
        uiCustomizationError.value=if(parsed.isSuccess)null else "UI 覆盖配置无效，已恢复原始界面；可在内核设置中回滚"
    }
    private fun parseUiCustomization(value:JSONObject):UiCustomization {
        fun scale(key:String)=if(value.has(key))value.getDouble(key).also{require(it.isFinite() && it in 0.8..1.5)}.toFloat() else null
        val accent=value.optString("accent").takeIf{it.isNotBlank()}?.also{require(it.matches(Regex("#[a-fA-F0-9]{6}")))}
        return UiCustomization(accent,scale("bodyScale"),scale("codeScale"),value.optString("tagline").takeIf{it.isNotBlank()},
            value.optJSONArray("capsules")?.rows()?.map{UiCapsule(it.getString("label"),it.getString("prompt"))},
            value.optJSONArray("commands")?.rows().orEmpty().map{UiCommand("/"+it.getString("name"),it.getString("description"),it.getString("prompt"))},
            value.optJSONArray("sidebar")?.rows().orEmpty().map{UiSidebarEntry(it.getString("route"),it.getBoolean("visible"))})
    }
    override suspend fun configurationSnapshots():List<String> {
        val rows=control("snapshots").getJSONArray("items");return (0 until rows.length()).map{rows.getString(it)}
    }
    override suspend fun rollbackConfiguration(snapshotId:String) {control("rollback",JSONObject().put("id",snapshotId));refreshControl()}
    override suspend fun setPluginEnabled(pluginId:String,enabled:Boolean) {require(plugins.value.none{it.id==pluginId && it.source==PluginSource.BuiltIn}){"内置依赖请通过源码组合替换"};control("enable-plugin",JSONObject().put("id",pluginId).put("enabled",enabled));refreshControl()}
    override suspend fun removePlugin(pluginId:String) {require(plugins.value.none{it.id==pluginId && it.source==PluginSource.BuiltIn});control("remove-plugin",JSONObject().put("id",pluginId));refreshControl()}
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
