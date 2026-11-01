// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
// (capsule/CapsuleVoice.kt：悬浮胶囊长按说话、拖到改一改/取消；改用普通 View 绘制，落点判定挪到 dev.hoshino.bridge.voice.CapsuleAim。)
package app.hoshino.harness.phone

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import app.hoshino.harness.voice.VoiceCopy
import app.hoshino.harness.voice.VoiceInput
import dev.hoshino.bridge.voice.CapsuleAim
import dev.hoshino.bridge.voice.HoldToTalk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 悬浮胶囊长按说话。由悬浮球的触摸监听驱动：[begin] 长按到点、[move] 手指移动、[end] 抬起、[abort] 被打断。
 * 听写卡片是一个不可触摸（FLAG_NOT_TOUCHABLE）的浮层：落点靠手指的屏幕坐标和两块目标的屏幕位置比（[CapsuleAim]），
 * 所以它不会挡住后台屏预览、接管时的画面操作，也不会拿走焦点；只在默认屏上画，不进入 Agent 的虚拟屏。
 * 松开：停在「取消」上丢掉；停在「改一改」上交给 [onEdit]（打开面板接着改）；否则交给 [onSend]（发给此刻对着的那件事）。
 * 胶囊被收起（进入 App、打开应用内虚拟屏、锁屏）或换成「你来处理」时调 [abort]：这段话直接丢掉、麦克风放开。
 */
@SuppressLint("SetTextI18n")
class CapsuleVoice(
    private val ctx: Context,
    private val wm: WindowManager,
    private val scope: CoroutineScope,
    private val onSend: (String) -> Unit,
    private val onEdit: (String) -> Unit,
    private val toast: (String) -> Unit,
) {
    private val voice = VoiceInput.of(ctx)
    private val hold = HoldToTalk(clock = { android.os.SystemClock.elapsedRealtime() })
    private val dp = ctx.resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()
    private val jobs = mutableListOf<Job>()
    private var added = false

    val active get() = hold.active

    private val status = TextView(ctx).apply { setTextColor(Color.parseColor("#B3FFFFFF")); textSize = 12f }
    private val text = TextView(ctx).apply { setTextColor(Color.WHITE); textSize = 15f; maxLines = 4 }
    private val hint = TextView(ctx).apply { setTextColor(Color.parseColor("#FFFFB74D")); textSize = 12f; visibility = View.GONE }
    private fun target(label: String) = TextView(ctx).apply {
        this.text = label; setTextColor(Color.WHITE); textSize = 14f; gravity = Gravity.CENTER
        setPadding(px(12), px(14), px(12), px(14))
        background = GradientDrawable().apply { cornerRadius = px(14).toFloat(); setColor(Color.parseColor("#33FFFFFF")) }
    }
    private val editTarget = target("改一改")
    private val cancelTarget = target("取消")
    private val card = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(14), px(10), px(14), px(12))
        background = GradientDrawable().apply { cornerRadius = px(18).toFloat(); setColor(Color.parseColor("#F2202027")) }
        addView(status); addView(text); addView(hint)
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, px(10), 0, 0)
            addView(editTarget, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = px(8) })
            addView(cancelTarget, LinearLayout.LayoutParams(0, -2, 1f))
        })
        contentDescription = "按住说话：拖到改一改或取消"
    }
    private val params = WindowManager.LayoutParams(
        -1, -2, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START; horizontalMargin = 0.04f }

    /** 长按到点：开会话。返回 false（没权限、引擎用不了、麦克风被输入栏占着）时提示原因，悬浮球照常。 */
    fun begin(ballY: Int, ballH: Int): Boolean {
        if (hold.active) return true
        if (!voice.hasPermission()) { toast("悬浮球说话需要麦克风权限：请先在 Hoshino 对话输入栏按住麦克风按钮授权"); return false }
        val session = voice.start(this)
        hold.press(session)
        jobs += scope.launch { session.events.collect { hold.onEvent(it) } }
        jobs += scope.launch { while (true) { delay(100); hold.tick(); if (!hold.active) break } }
        jobs += scope.launch { hold.state.collect { render(it) } }
        show(ballY, ballH)
        return true
    }

    fun move(rawX: Float, rawY: Float) {
        if (!hold.active) return
        hold.aim(CapsuleAim.target(rawX, rawY, rect(editTarget), rect(cancelTarget), slop = px(12)))
    }

    fun end() { if (hold.active) hold.release() }

    fun abort(reason: String? = null) {
        if (!hold.active && !added) return
        hold.dispose()
        close()
        reason?.let(toast)
    }

    private fun render(s: HoldToTalk.State) {
        when (s) {
            is HoldToTalk.State.Listening -> {
                status.text = (if (s.ended) "听完了" else "正在听") + " · " + VoiceCopy.elapsed(s.elapsedMs) + " · " + "▮".repeat(1 + (s.level * 5).toInt())
                text.text = (s.stable + s.tail).ifEmpty { "请说…" }
                hint.visibility = if (s.hint != null) View.VISIBLE else View.GONE
                hint.text = s.hint?.let(VoiceCopy::hint).orEmpty()
                highlight(editTarget, s.target == HoldToTalk.Target.Edit, "#CC2F5BEA")
                highlight(cancelTarget, s.target == HoldToTalk.Target.Cancel, "#CCD93636")
            }
            is HoldToTalk.State.Converting -> {
                status.text = if (s.slow) VoiceCopy.CONVERTING_SLOW else VoiceCopy.CONVERTING
                if (s.soFar.isNotEmpty()) text.text = s.soFar
            }
            is HoldToTalk.State.Done -> {
                hold.reset(); close()
                if (s.edit) onEdit(s.text) else onSend(s.text)
            }
            is HoldToTalk.State.Problem -> {
                hold.reset(); close()
                toast(VoiceCopy.failure(s.why))
            }
            HoldToTalk.State.Idle -> close()
        }
    }

    private fun highlight(v: TextView, on: Boolean, color: String) {
        (v.background as? GradientDrawable)?.setColor(Color.parseColor(if (on) color else "#33FFFFFF"))
    }

    private fun rect(v: View): IntArray? {
        if (!added || v.width == 0) return null
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return intArrayOf(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
    }

    /** 卡片放在悬浮球的另一半屏：球在上半屏就放下面，反之放上面，手指往卡片拖就能碰到两个目标。 */
    private fun show(ballY: Int, ballH: Int) {
        if (added) return
        val h = ctx.resources.displayMetrics.heightPixels
        params.y = if (ballY + ballH / 2 < h / 2) (ballY + ballH + px(24)).coerceAtMost(h - px(200)) else (ballY - px(200)).coerceAtLeast(px(24))
        runCatching { wm.addView(card, params); added = true }
    }

    private fun close() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        if (added) { runCatching { wm.removeView(card) }; added = false }
    }
}
