package app.hoshino.harness.phone

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * m2.9.3: 「自由操作」 for the in-app virtual screen (page and picture-in-picture). The same state machines as the floating
 * panel: [AutoTakeoverModel] (first touch takes over, 4 s without input hands back, 「接管」 makes it sticky) on top of the
 * controller's [ManualTakeover] (while active every agent phone action waits; the hand-back leaves the model one
 * sentence). Shared by every in-app surface so the page and its PiP never disagree. A takeover started or ended by the
 * floating panel, a stop, or the agent's own handoff is followed here.
 */
class InAppTakeover private constructor(private val controller: PhoneController, private val clock: () -> Long = System::currentTimeMillis) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val model = AutoTakeoverModel()
    private var autoJob: Job? = null
    private val _mode = MutableStateFlow(AutoTakeoverModel.Mode.Off)
    /** Off / Auto (hands back after 4 s idle) / Sticky (until 「交还」). */
    val mode: StateFlow<AutoTakeoverModel.Mode> = _mode.asStateFlow()
    private val _notice = MutableStateFlow<String?>(null)
    /** One-line notices for the page ("你停下了，AI 继续" …); the UI clears them with [consumeNotice]. */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    init {
        scope.launch {
            controller.manual.active.collect { s ->
                if (s == null) { model.ended(); autoJob?.cancel(); autoJob = null }
                else model.startedElsewhere(clock())
                _mode.value = model.mode
            }
        }
    }

    fun consumeNotice() { _notice.value = null }

    private fun refusal() = if (PhoneTakeover.of(controller.appContext).pending.value != null) "AI 正在请你处理一步，用对话里的按钮" else "虚拟屏还没打开"

    /** A finger went down on the picture. True = the touch may be injected (a takeover is active). */
    fun touchDown(): Boolean {
        val now = clock()
        if (model.touchDown(now) == AutoTakeoverModel.Action.Begin && !controller.beginManualTakeover()) {
            model.beginFailed(); _notice.value = refusal(); _mode.value = model.mode; return false
        }
        autoJob?.cancel(); autoJob = null
        _mode.value = model.mode
        val active = controller.manual.active.value != null
        if (!active) hint()
        return active
    }

    private var lastHint = 0L
    /** Not taken over: tell the user (at most every 3 s) that 「接管」 comes first. */
    private fun hint() {
        val now = clock()
        if (now - lastHint < 3_000) return
        lastHint = now
        _notice.value = if (PhoneTakeover.of(controller.appContext).pending.value != null) refusal() else "先点「接管」才能操作虚拟屏"
    }

    fun touchUp() { model.touchUp(clock()); scheduleAutoReturn() }

    /** Typing, a key button, a launch: keeps an automatic takeover alive. */
    fun activity() { model.activity(clock()); scheduleAutoReturn() }

    /** Before typing / a key / opening an app: takes over like a touch would. */
    fun ensure(): Boolean {
        if (controller.manual.active.value != null) { activity(); return true }
        val ok = touchDown()
        touchUp()
        return ok
    }

    /** 「接管」 locks the takeover (no automatic hand-back); 「交还」 ends it. */
    fun button() {
        when (model.button(clock())) {
            AutoTakeoverModel.Action.Begin -> if (!controller.beginManualTakeover()) { model.beginFailed(); _notice.value = refusal() } else _notice.value = "已接管：点「交还」前 AI 不会操作"
            AutoTakeoverModel.Action.End -> { autoJob?.cancel(); autoJob = null; controller.endManualTakeover(); _notice.value = "已交还，AI 继续（已告诉它你改了什么）" }
            AutoTakeoverModel.Action.None -> _notice.value = "已锁定接管：点「交还」前 AI 不会操作"
        }
        _mode.value = model.mode
    }

    /** The page is leaving the foreground: an automatic takeover is handed back at once (a locked one stays). */
    fun leaving() {
        if (model.mode == AutoTakeoverModel.Mode.Auto && controller.manual.active.value != null) {
            autoJob?.cancel(); autoJob = null
            model.ended(); controller.endManualTakeover()
            _mode.value = model.mode
        }
    }

    private fun scheduleAutoReturn() {
        autoJob?.cancel(); autoJob = null
        val at = model.nextCheckAt() ?: return
        autoJob = scope.launch {
            delay((at - clock()).coerceAtLeast(0) + 16)
            if (model.tick(clock()) == AutoTakeoverModel.Action.End) {
                controller.endManualTakeover()
                _notice.value = "你停下了，AI 继续（已告诉它你改了什么）"
                _mode.value = model.mode
            } else scheduleAutoReturn()
        }
    }

    companion object {
        @Volatile private var instance: InAppTakeover? = null
        fun of(context: Context): InAppTakeover = instance ?: synchronized(this) { instance ?: InAppTakeover(PhoneController.of(context)).also { instance = it } }
    }
}
