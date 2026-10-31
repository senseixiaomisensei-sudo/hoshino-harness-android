package app.hoshino.harness.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.hoshino.harness.core.ApprovalDecision
import app.hoshino.harness.core.ApprovalMode
import app.hoshino.harness.core.ApprovalModeRules
import app.hoshino.harness.core.ApprovalScope
import app.hoshino.harness.core.ApprovalState
import app.hoshino.harness.core.ExportedSession
import app.hoshino.harness.core.SessionExportFormat
import app.hoshino.harness.core.Attachment
import app.hoshino.harness.core.AttachmentKind
import app.hoshino.harness.core.HarnessEvent
import app.hoshino.harness.core.Hoshino
import app.hoshino.harness.core.ModelInfo
import app.hoshino.harness.core.ModelRef
import app.hoshino.harness.core.PermissionTier
import app.hoshino.harness.core.Provider
import app.hoshino.harness.core.QueuedMessage
import app.hoshino.harness.core.ReasoningEffort
import app.hoshino.harness.core.SessionInfo
import app.hoshino.harness.core.SessionState
import app.hoshino.harness.core.TurnOptions
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 输入框草稿与本轮选项 */
data class ComposerState(
    val workspaceId: String = "ws-default",
    val presetId: String = "standard",
    val tier: PermissionTier = PermissionTier.WorkspaceWrite,
    val model: ModelRef? = null,
    val effort: ReasoningEffort = ReasoningEffort.Medium,
    val planMode: Boolean = false,
    val attachments: List<Attachment> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel : ViewModel() {
    private val client = Hoshino.client
    private val store = Hoshino.store

    val connection = client.connection
    val sessions = client.sessions
    val workspaces = client.workspaces
    val presets = client.presets
    val providers = client.providers
    val routing = client.routing
    val shizuku = client.shizuku
    val supportedPermissionTiers = client.supportedPermissionTiers
    private val submission=Mutex()
    val uiCustomization=client.uiCustomization
    val slashCommands = combine(client.slashCommands,uiCustomization) { commands,custom ->
        (custom.commands.map{app.hoshino.harness.core.SlashCommand(it.name,it.description,"用户覆盖")}+commands).distinctBy{it.name}
    }.stateIn(viewModelScope,SharingStarted.Eagerly,client.slashCommands.value)

    private val _currentId = MutableStateFlow<String?>(client.preferredSessionId)
    val currentId: StateFlow<String?> = _currentId.asStateFlow()

    val current: StateFlow<SessionInfo?> = combine(_currentId, client.sessions) { id, list -> list.firstOrNull { it.id == id } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val session: StateFlow<SessionState> = _currentId
        .flatMapLatest { id -> if (id == null) flowOf(SessionState()) else store.state(id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, client.preferredSessionId?.let{store.state(it).value}?:SessionState())

    private val _composer = MutableStateFlow(ComposerState())
    val composer: StateFlow<ComposerState> = _composer.asStateFlow()

    private val _fileSuggestions = MutableStateFlow<List<String>>(emptyList())
    val fileSuggestions: StateFlow<List<String>> = _fileSuggestions.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** 一次性提示（Toast） */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val errors=CoroutineExceptionHandler { _, failure ->
        _messages.tryEmit(client.describeFailure(failure))
    }
    private var startupRestored=false

    // ---------- 审批档（含无审批） ----------
    private val _approval = MutableStateFlow(ApprovalState())
    /** 当前会话（或尚未创建的新会话）实际生效的审批档。 */
    val approval: StateFlow<ApprovalState> = _approval.asStateFlow()
    /** 新会话还没创建时选的「仅本会话」档，创建后立即应用。 */
    private var pendingSessionMode: ApprovalMode? = null
    private val _exports = MutableSharedFlow<ExportedSession>(extraBufferCapacity = 4)
    /** 导出完成：界面据此打开系统分享。 */
    val exports: SharedFlow<ExportedSession> = _exports.asSharedFlow()
    private val uiPrefs get() = Hoshino.applicationContext?.getSharedPreferences("hoshino-ui", android.content.Context.MODE_PRIVATE)
    /** 用户是否已在首次开启无审批时勾选「不再提示」。 */
    val noApprovalAcknowledged: Boolean get() = uiPrefs?.getBoolean("no-approval-ack", false) == true
    fun acknowledgeNoApproval(neverAskAgain: Boolean) { if (neverAskAgain) uiPrefs?.edit()?.putBoolean("no-approval-ack", true)?.apply() }
    fun needsRiskConfirmation(mode: ApprovalMode) = ApprovalModeRules.needsRiskConfirmation(mode, noApprovalAcknowledged)

    /** 读与写审批档串行，避免较早发出的读取在写入之后返回、把界面盖回旧档。 */
    private val approvalLock = Mutex()
    fun refreshApproval() = viewModelScope.launch(errors) { approvalLock.withLock {
        val id = _currentId.value
        if (client.connection.value != app.hoshino.harness.core.ConnectionState.Connected) return@launch
        val state = client.approvalState(id)
        val pending = pendingSessionMode
        _approval.value = if (id == null && pending != null) ApprovalModeRules.apply(state, pending, ApprovalScope.Session) else state
    } }

    /** 切换审批档；[scope] 为本会话 / 设为默认 / 跟随默认。开启无审批前的风险确认由界面先做。 */
    fun setApprovalMode(mode: ApprovalMode, scope: ApprovalScope) = viewModelScope.launch(errors) { approvalLock.withLock {
        val id = _currentId.value
        if (id == null && scope != ApprovalScope.Default) {
            // 新会话还没创建：先记下，发送第一条消息创建会话后再写入内核。
            pendingSessionMode = if (scope == ApprovalScope.FollowDefault) null else mode
            _approval.value = ApprovalModeRules.apply(_approval.value, mode, scope)
        } else {
            _approval.value = client.setApprovalMode(id, mode, scope)
        }
        _messages.tryEmit(
            if (_approval.value.noApproval) "已开启无审批：所有操作自动执行，权限档仍生效"
            else "审批：${_approval.value.effective.label}" + if (scope == ApprovalScope.Default) "（已设为默认）" else "",
        )
    } }

    init {
        viewModelScope.launch(errors) { combine(_currentId, client.connection) { id, c -> id to c }.collect { refreshApproval() } }
        viewModelScope.launch(errors){client.sessions.collect{rows->if(!startupRestored&&rows.isNotEmpty()){startupRestored=true;client.preferredSessionId?.takeIf{id->rows.any{it.id==id}}?.let{openSession(it)}}}}
        // 本轮结束后自动发送排队消息
        viewModelScope.launch(errors) {
            client.events.filterIsInstance<HarnessEvent.TurnFinished>().collect { e ->
                if (!e.cancelled) {
                    delay(250)
                    val next = store.state(e.sessionId).value.queued.firstOrNull{!it.id.startsWith("waiting:")}
                    if (next != null){store.removeQueued(e.sessionId,next.id);client.send(e.sessionId, next.text, next.attachments, options())}
                }
            }
        }
        // 首次出现模型时自动选中主对话模型
        viewModelScope.launch(errors) {
            combine(client.providers, client.routing) { p, r -> p to r }.collect { (p, r) ->
                val cur = _composer.value.model
                val valid = cur != null && p.any { pr -> pr.id == cur.providerId && pr.models.any { it.id == cur.modelId } }
                if (!valid) {
                    val ref = r.main ?: p.firstOrNull { it.enabled && it.models.isNotEmpty() }?.let { ModelRef(it.id, it.models.first().id) }
                    val info = ref?.let { findModel(p, it) }
                    _composer.update { it.copy(model = ref, effort = info?.defaultEffort ?: it.effort) }
                }
            }
        }
    }

    private fun findModel(list: List<Provider>, ref: ModelRef): ModelInfo? =
        list.firstOrNull { it.id == ref.providerId }?.models?.firstOrNull { it.id == ref.modelId }

    fun modelInfo(ref: ModelRef?): ModelInfo? = ref?.let { findModel(providers.value, it) }
    suspend fun searchSessions(query:String):app.hoshino.harness.core.SessionSearchResults = try {client.searchSessions(query)} catch(cancelled:kotlinx.coroutines.CancellationException){throw cancelled} catch(failure:Exception){throw IllegalStateException(client.describeFailure(failure))}

    private fun options(): TurnOptions {
        val c = _composer.value
        return TurnOptions(c.presetId, supportedTier(current.value?.tier ?: c.tier), c.model, c.effort, c.planMode)
    }
    private fun supportedTier(tier: PermissionTier)=tier.takeIf { it in supportedPermissionTiers } ?: PermissionTier.WorkspaceWrite

    // ---------- 会话 ----------

    fun newSession() {
        startupRestored=true
        pendingSessionMode=null
        _currentId.value = null
        _composer.update { it.copy(attachments = emptyList(), planMode = false) }
    }

    fun openSession(id: String) {
        client.activateSession(id)
        _currentId.value = id
        val s = client.sessions.value.firstOrNull { it.id == id } ?: return
        _composer.update { it.copy(presetId = s.presetId, tier = supportedTier(s.tier), workspaceId = s.workspaceId, attachments = emptyList()) }
    }

    fun selectWorkspace(id: String) = _composer.update { it.copy(workspaceId = id) }

    fun addWorkspace(name: String, path: String) = viewModelScope.launch(errors) {
        val ws = client.addWorkspace(name, path)
        selectWorkspace(ws.id)
    }

    fun rename(id: String, title: String) = viewModelScope.launch(errors) { client.renameSession(id, title) }

    fun fork(id: String) = viewModelScope.launch(errors) {
        val s = client.forkSession(id)
        openSession(s.id)
        _messages.tryEmit("已分叉为新会话")
    }

    fun export(id: String, format: SessionExportFormat = SessionExportFormat.Markdown) = viewModelScope.launch(errors) {
        val result = runCatching { client.exportSessionFile(id, format) }
        result.onSuccess { _messages.tryEmit("已导出到 ${it.displayPath}"); _exports.tryEmit(it) }
            .onFailure { failure ->
                // 旧实现（如演示客户端）只支持 ZIP 路径导出。
                if (failure is UnsupportedOperationException) _messages.tryEmit("已导出：${client.exportSession(id)}") else throw failure
            }
    }

    fun setPinned(id: String, pinned: Boolean) = viewModelScope.launch(errors) {
        client.setPinned(id, pinned)
        _messages.tryEmit(if (pinned) "已置顶" else "已取消置顶")
    }

    fun setArchived(id: String, archived: Boolean) = viewModelScope.launch(errors) {
        client.setArchived(id, archived)
        if (archived && _currentId.value == id) newSession()
        _messages.tryEmit(if (archived) "已归档" else "已取消归档")
    }

    fun delete(id: String) = delete(listOf(id))

    /** 删除一个或多个会话（确认后调用）。当前会话在其中时先切到新会话，界面不再跟它的任务。 */
    fun delete(ids: List<String>) = viewModelScope.launch(errors) {
        if (ids.isEmpty()) return@launch
        if (_currentId.value in ids) newSession()
        client.deleteSessions(ids)
        ids.forEach { store.clear(it) }
        _messages.tryEmit(if (ids.size == 1) "已删除会话" else "已删除 ${ids.size} 个会话")
    }

    // ---------- 发送 ----------

    @Volatile var lastSendClickEpoch:Long=0;private set
    fun send(text: String, onComplete: (Boolean) -> Unit = {}) {
        lastSendClickEpoch=System.currentTimeMillis()
        val trimmed = text.trim()
        if (trimmed.isEmpty() && _composer.value.attachments.isEmpty()) { onComplete(false);return }
        if (_composer.value.model==null) { _messages.tryEmit("请先在设置 → 供应商中添加模型");onComplete(false);return }
        if(!submission.tryLock()){onComplete(false);return}
        val attachments = _composer.value.attachments
        viewModelScope.launch(errors) {
            var accepted=false
            try {
            if(_composer.value.tier !in supportedPermissionTiers) {
                _composer.update { it.copy(tier=PermissionTier.WorkspaceWrite) }
                _messages.tryEmit("系统完全访问即将支持，当前使用工作区内修改")
            }
            var id = _currentId.value
            if (id == null) {
                val c = _composer.value
                val s = client.createSession(c.workspaceId, c.presetId, c.tier)
                _currentId.value = s.id
                id = s.id
                pendingSessionMode?.let { mode -> pendingSessionMode = null; approvalLock.withLock { runCatching { _approval.value = client.setApprovalMode(s.id, mode, ApprovalScope.Session) }.onFailure { _messages.tryEmit("审批档未能应用到新会话：" + client.describeFailure(it)) } } }
            }
            if(client.connection.value!=app.hoshino.harness.core.ConnectionState.Connected){
                client.send(id,trimmed,attachments,options())
            }else if (session.value.running || store.state(id).value.running) {
                // 插话：任务进行中继续发的消息进入当前任务（下一次模型请求前读到），不另开任务。
                try { client.steer(id, trimmed, attachments) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    store.enqueue(id, QueuedMessage("q${System.nanoTime()}", trimmed, attachments))
                    _messages.tryEmit("当前任务已不接收插话，这句排在下一条发送")
                }
            } else {
                client.send(id, trimmed, attachments, options())
            }
            accepted=true
            _composer.update { c -> c.copy(attachments=c.attachments.filterNot { attachment -> attachments.any { it.id==attachment.id } }) }
            } finally { submission.unlock();onComplete(accepted) }
        }
    }

    fun stop() {
        val id = _currentId.value ?: return
        viewModelScope.launch(errors) { client.stop(id) }
    }

    fun steer(queuedId: String) {
        val id = _currentId.value ?: return
        val q = session.value.queued.firstOrNull { it.id == queuedId } ?: return
        store.removeQueued(id, queuedId)
        viewModelScope.launch(errors) { client.steer(id, q.text, q.attachments) }
    }

    fun removeQueued(queuedId: String) {
        val id = _currentId.value ?: return
        (client as? app.hoshino.harness.core.DshHarnessClient)?.cancelWaiting(queuedId)
        store.removeQueued(id, queuedId)
    }

    fun respondApproval(approvalId: String, d: ApprovalDecision) {
        val id = _currentId.value ?: return
        viewModelScope.launch(errors) { client.respondApproval(id, approvalId, d) }
    }

    fun answer(questionId: String, answer: String) {
        val id = _currentId.value ?: return
        viewModelScope.launch(errors) { client.answerQuestion(id, questionId, answer) }
    }

    fun resolveDiff(diffId: String, accept: Boolean) {
        val id = _currentId.value ?: return
        viewModelScope.launch(errors) { client.resolveDiff(id, diffId, accept) }
    }

    fun compact() {
        val id = _currentId.value
        if (id == null) {
            _messages.tryEmit("当前没有会话")
            return
        }
        viewModelScope.launch(errors) { client.compact(id) }
    }

    // ---------- 输入框选项 ----------

    fun setTier(t: PermissionTier) {
        if(t !in supportedPermissionTiers) { _messages.tryEmit("系统完全访问即将支持；Shizuku 授权已单独保存，可继续使用工作区内修改");return }
        if(t==PermissionTier.FullAccess && shizuku.value!=app.hoshino.harness.core.ShizukuStatus.Ready) { _messages.tryEmit("请先启动 Shizuku 并授权 Hoshino Harness");return }
        _composer.update { it.copy(tier = t) }
        val id = _currentId.value ?: return
        viewModelScope.launch(errors) { client.setSessionTier(id, t) }
    }

    fun setPreset(presetId: String) {
        _composer.update { it.copy(presetId = presetId) }
        val id = _currentId.value ?: return
        viewModelScope.launch(errors) { client.setSessionPreset(id, presetId) }
    }

    fun setPlanMode(on: Boolean) = _composer.update { it.copy(planMode = on) }

    fun setModel(ref: ModelRef) {
        val info = findModel(providers.value, ref)
        _composer.update { it.copy(model = ref, effort = info?.defaultEffort ?: it.effort) }
    }

    fun setEffort(e: ReasoningEffort) = _composer.update { it.copy(effort = e) }

    fun addAttachment(kind: AttachmentKind, name: String? = null) {
        if(kind!=AttachmentKind.WorkspaceFile || name.isNullOrBlank()){toast("请通过附件菜单选择真实文件");return}
        _composer.update {it.copy(attachments=it.attachments+Attachment("workspace:${System.nanoTime()}",name,kind,"工作区"))}
    }
    fun attach(value:Attachment)=_composer.update{it.copy(attachments=it.attachments+value)}

    fun removeAttachment(id: String) = _composer.update { c -> c.copy(attachments = c.attachments.filterNot { it.id == id }) }

    fun searchFiles(query: String) = viewModelScope.launch(errors) {
        _fileSuggestions.value = client.searchWorkspaceFiles(_composer.value.workspaceId, query)
    }

    fun toast(msg: String) {
        _messages.tryEmit(msg)
    }
}
