package app.hoshino.harness.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.hoshino.harness.core.ApprovalDecision
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

    init {
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

    fun export(id: String) = viewModelScope.launch(errors) {
        val path = client.exportSession(id)
        _messages.tryEmit("已导出：$path")
    }

    fun setArchived(id: String, archived: Boolean) = viewModelScope.launch(errors) {
        client.setArchived(id, archived)
        if (archived && _currentId.value == id) newSession()
        _messages.tryEmit(if (archived) "已归档" else "已取消归档")
    }

    fun delete(id: String) = viewModelScope.launch(errors) {
        client.deleteSession(id)
        store.clear(id)
        if (_currentId.value == id) newSession()
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
            }
            if (session.value.running || store.state(id).value.running) {
                store.enqueue(id, QueuedMessage("q${System.nanoTime()}", trimmed, attachments))
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
        viewModelScope.launch(errors) { client.steer(id, q.text) }
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
