// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
// (voice/HoldToTalk.kt；另加提示、慢转写、松开去向（填入 / 插话 / 发送）、拖到「改一改」。)
package dev.hoshino.bridge.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 按住说话的状态机：按下、上滑（或拖到）取消、拖到「改一改」、松开、到点收尾、收会话的事件，算出界面此刻该显示什么。
 * 不碰界面也不碰手势：对话输入栏和悬浮胶囊共用。会话由调用方开好再交进来（[press]），
 * 事件由调用方收了转进来（[onEvent]），[tick] 由调用方按住时定时调（计时、到 [maxMs] 收尾）。
 * 手还按着时会话自己先结束了：结果先留着，等手指离开再按那时的落点处理——停在取消上就丢掉，否则才交出去。
 * 只在一个线程（主线程）上调。
 */
class HoldToTalk(private val clock: () -> Long, private val maxMs: Long = MAX_HOLD_MS, private val minMs: Long = 500) {
    /** 手指此刻落在哪：松开时按它决定去向。 */
    enum class Target { Send, Cancel, Edit }

    sealed interface State {
        data object Idle : State

        /**
         * 按着（或刚松开、手机自带识别还在收尾）：[stable] 已经定下的字，[tail] 末尾还可能变的几个字。
         * [ended]：手还按着，会话却已经结束、结果留着等松开。[hint] 最近一条提示。
         */
        data class Listening(
            val stable: String,
            val tail: String,
            val level: Float,
            val elapsedMs: Long,
            val target: Target,
            val live: Boolean,
            val ended: Boolean = false,
            val hint: SpeechHint? = null,
        ) : State {
            val cancelArmed get() = target == Target.Cancel
            val text get() = stable + tail
        }

        /** 服务商：松开后在转最后一段。[soFar] 已经转出的字；[slow] 超过 3 秒还没转完。 */
        data class Converting(val soFar: String = "", val slow: Boolean = false) : State

        /** 界面拿去用（[edit] 为真时打开编辑而不是直接交出去），然后 [reset]。 */
        data class Done(val text: String, val edit: Boolean = false) : State

        /** null = 没听清（什么都没认出来，或按得太短）。 */
        data class Problem(val why: SpeechFailure?) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var session: SpeechSession? = null
    private var startedAt = 0L
    private var released = false
    /** 松开时手指在「改一改」上：结果到了标成编辑。 */
    private var editOnDone = false
    private var pending: State? = null

    val active get() = _state.value is State.Listening || _state.value is State.Converting

    fun press(session: SpeechSession) {
        this.session?.cancel()
        this.session = session
        startedAt = clock()
        released = false
        editOnDone = false
        pending = null
        _state.value = State.Listening("", "", 0f, 0, Target.Send, live = session.live)
    }

    /** 手指从按下处往上移了 [upPx]；超过 [thresholdPx] 就是「松开取消」，移回来又恢复。 */
    fun drag(upPx: Float, thresholdPx: Float) = aim(if (upPx > thresholdPx) Target.Cancel else Target.Send)

    /** 手指此刻在不在「取消」上（兼容 Mika 的写法）。 */
    fun armCancel(armed: Boolean) = aim(if (armed) Target.Cancel else Target.Send)

    /** 悬浮胶囊：落点由它自己判定后告诉这里。 */
    fun aim(target: Target) {
        val s = _state.value as? State.Listening ?: return
        if (released) return
        if (target != s.target) _state.value = s.copy(target = target)
    }

    fun release() {
        val s = _state.value as? State.Listening ?: return
        if (released) return
        val early = pending
        val current = session
        if (current == null && early == null) return
        when {
            s.target == Target.Cancel -> {
                current?.cancel()
                session = null
                pending = null
                _state.value = State.Idle
            }
            // 按得太短多半是误触：不去转（服务商的还要花额度），当没听清
            clock() - startedAt < minMs -> {
                current?.cancel()
                session = null
                pending = null
                _state.value = State.Problem(null)
            }
            early != null -> {
                pending = null
                _state.value = if (early is State.Done && s.target == Target.Edit) early.copy(edit = true) else early
            }
            current != null -> {
                released = true
                editOnDone = s.target == Target.Edit
                current.release()
                if (current.convertsAfterRelease) _state.value = State.Converting(s.text)
            }
        }
    }

    /** 按着时定时调：更新用时；到 [maxMs] 当松开处理。 */
    fun tick() {
        val s = _state.value as? State.Listening ?: return
        if (released || (session == null && pending == null)) return
        val elapsed = clock() - startedAt
        if (elapsed >= maxMs) {
            _state.value = s.copy(elapsedMs = maxMs)
            release()
        } else if (elapsed != s.elapsedMs) {
            _state.value = s.copy(elapsedMs = elapsed)
        }
    }

    fun onEvent(e: SpeechEvent) {
        if (session == null) return
        val now = _state.value
        when (e) {
            is SpeechEvent.Level -> if (now is State.Listening) _state.value = now.copy(level = e.value)
            is SpeechEvent.Partial -> when (now) {
                is State.Listening -> _state.value = now.copy(stable = e.stable, tail = e.tail)
                is State.Converting -> _state.value = now.copy(soFar = e.stable + e.tail)
                else -> Unit
            }
            is SpeechEvent.Hint -> when (now) {
                is State.Listening -> _state.value = now.copy(hint = e.kind)
                is State.Converting -> if (e.kind == SpeechHint.Slow) _state.value = now.copy(slow = true)
                else -> Unit
            }
            SpeechEvent.Converting -> if (now !is State.Converting) {
                _state.value = State.Converting((now as? State.Listening)?.text.orEmpty())
            }
            is SpeechEvent.Final -> {
                session = null
                val text = e.text.trim()
                val result = if (text.isEmpty()) State.Problem(null) else State.Done(text, edit = editOnDone)
                if (released || now !is State.Listening) {
                    _state.value = result
                } else {
                    pending = result
                    _state.value = now.copy(stable = text, tail = "", level = 0f, ended = true)
                }
            }
            is SpeechEvent.Failed -> {
                session = null
                val result = State.Problem(e.why)
                if (!released && now is State.Listening && now.cancelArmed) {
                    pending = result
                    _state.value = now.copy(level = 0f, ended = true)
                } else {
                    _state.value = result
                }
            }
        }
    }

    /** 离开页面、转屏、App 到后台、胶囊被收起：丢掉这一段，回到平时。可以重复调。 */
    fun dispose() {
        session?.cancel()
        session = null
        released = false
        pending = null
        editOnDone = false
        _state.value = State.Idle
    }

    /** 界面用完了 [State.Done]/[State.Problem] 之后回到平时；还在听、在转时不动。 */
    fun reset() {
        val s = _state.value
        if (s is State.Done || s is State.Problem) _state.value = State.Idle
    }

    companion object {
        /** 一次按住最长 2 分钟（服务商按 30 秒单段切开转写）。 */
        const val MAX_HOLD_MS = 120_000L

        /**
         * 说的话接在已有的字后面：两边挨着的都是中日韩文字或全角标点时直接接上，
         * 否则中间加一个空格（已有的字以空白结尾时不再加）。
         */
        fun join(existing: String, spoken: String): String {
            if (existing.isEmpty()) return spoken
            if (spoken.isEmpty()) return existing
            val left = existing.last()
            if (left.isWhitespace()) return existing + spoken
            return if (isCjk(left) && isCjk(spoken.first())) existing + spoken else "$existing $spoken"
        }

        private fun isCjk(ch: Char): Boolean {
            val block = Character.UnicodeBlock.of(ch)
            if (block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
                block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
            ) return true
            return when (Character.UnicodeScript.of(ch.code)) {
                Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA,
                Character.UnicodeScript.HANGUL, Character.UnicodeScript.BOPOMOFO -> true
                else -> false
            }
        }
    }
}

/**
 * 说完的话交到哪（对话输入栏用）：任务在跑时直接作为插话交给当前任务；不在跑时接在输入框后面，让用户再改。
 * 拖到「改一改」时无论在不在跑都填进输入框。纯逻辑。
 */
object VoiceRouting {
    sealed interface Route {
        data class Fill(val text: String) : Route
        data class Interject(val text: String) : Route
    }

    fun route(spoken: String, existing: String, running: Boolean, edit: Boolean): Route =
        if (running && !edit) Route.Interject(spoken) else Route.Fill(HoldToTalk.join(existing, spoken))
}

/** 麦克风同一时刻只给一处用（对话输入栏、悬浮胶囊）：后按的一处拿不到就报 [SpeechFailure.InUse]。 */
object MicGate {
    private var owner: Any? = null

    @Synchronized fun acquire(who: Any): Boolean {
        if (owner != null && owner !== who) return false
        owner = who
        return true
    }

    @Synchronized fun release(who: Any) { if (owner === who) owner = null }

    @Synchronized fun holder(): Any? = owner
}
