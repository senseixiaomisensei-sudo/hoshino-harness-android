// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package app.hoshino.harness.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.Display
import dev.hoshino.bridge.phone.shell.PhoneServices
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Takeover (PH17): the agent hands the current app to the user. "我来处理" moves the app's task from the
 * agent display to the main screen; "处理好了" moves it back and the screen is re-read and diffed for the
 * model; "跳过" never counts as done. Requests queue (one at a time, in order: CU4). Offered from the
 * notification and from the capsule card.
 */
class PhoneTakeover private constructor(private val context: Context) {
    data class Pending(val sessionId: String, val reason: String, val taskId: Int, val movedToMain: Boolean = false)
    enum class Choice { Take, Done, Skip }

    private val queue = Mutex()
    private val _pending = MutableStateFlow<Pending?>(null)
    val pending: StateFlow<Pending?> = _pending.asStateFlow()
    @Volatile private var answer: CompletableDeferred<Choice>? = null
    private val _waiting = MutableStateFlow(0)
    /** Requests waiting behind the current one. */
    val waiting: StateFlow<Int> = _waiting.asStateFlow()

    suspend fun request(sessionId: String, reason: String, controller: PhoneController): JSONObject {
        _waiting.value++
        return queue.withLock {
            _waiting.value--
            val shell = PhoneServices.of(context).shell.value ?: throw PhoneUnavailable("helper", "手机助手没有运行")
            val display = controller.agentDisplayId() ?: throw PhoneUnavailable("display", "还没有在后台屏打开任何 App")
            val taskId = runCatching { shell.topTaskId(display) }.getOrDefault(-1)
            if (taskId < 0) return@withLock JSONObject().put("ok", false).put("error", "no app on the agent display")
            _pending.value = Pending(sessionId, reason, taskId)
            controller.setTakenOver(true, reason)
            PhoneNotifier.takeover(context, sessionId, reason, moved = false)
            var result = "timeout"
            try {
                while (true) {
                    val d = CompletableDeferred<Choice>().also { answer = it }
                    val choice = withTimeoutOrNull(TIMEOUT_MS) { d.await() } ?: break
                    val p = _pending.value ?: break
                    when (choice) {
                        Choice.Take -> if (!p.movedToMain) {
                            val moved = runCatching { shell.moveTask(taskId, Display.DEFAULT_DISPLAY) }.getOrDefault(false)
                            _pending.value = p.copy(movedToMain = moved)
                            PhoneNotifier.takeover(context, sessionId, reason, moved = moved)
                        }
                        Choice.Done, Choice.Skip -> {
                            if (p.movedToMain) runCatching { shell.moveTask(taskId, display) }
                            result = if (choice == Choice.Done) "done" else "skipped"
                            break
                        }
                    }
                }
            } finally {
                if (result == "timeout") _pending.value?.takeIf { it.movedToMain }?.let { runCatching { shell.moveTask(taskId, display) } }
                _pending.value = null
                answer = null
                PhoneNotifier.cancelTakeover(context)
                controller.setTakenOver(false)
            }
            val out = JSONObject().put("ok", result == "done").put("result", result)
            if (result == "skipped") out.put("note", "the user skipped this step: it is NOT done")
            controller.reread(sessionId)?.let { out.put("screen", it.optString("screen")).put("app", it.opt("app")) }
            out
        }
    }

    fun choose(choice: Choice) { answer?.complete(choice) }

    companion object {
        const val TIMEOUT_MS = 10 * 60_000L
        @Volatile private var instance: PhoneTakeover? = null
        fun of(context: Context): PhoneTakeover = instance ?: synchronized(this) { instance ?: PhoneTakeover(context.applicationContext).also { instance = it } }
    }
}

/**
 * Phone notifications in their own high-importance channel "需要你处理" (CU7). While the capsule is visible on a
 * lit screen they are posted silently (the capsule already shows it); otherwise they alert as usual.
 */
object PhoneNotifier {
    private const val CHANNEL = "hoshino-phone-needs-you"
    /** Same notices, posted without sound or heads-up while the capsule is visible on a lit screen. */
    private const val QUIET = "hoshino-phone-quiet"
    private const val TAKEOVER_ID = 4502
    private const val NEEDS_USER_ID = 4503
    private const val AUTO_ON_ID = 4504

    private fun manager(context: Context): NotificationManager = context.getSystemService(NotificationManager::class.java).also {
        if (it.getNotificationChannel(CHANNEL) == null) it.createNotificationChannel(NotificationChannel(CHANNEL, "手机操作 · 需要你处理", NotificationManager.IMPORTANCE_HIGH))
        if (it.getNotificationChannel(QUIET) == null) it.createNotificationChannel(NotificationChannel(QUIET, "手机操作 · 胶囊可见时（静默）", NotificationManager.IMPORTANCE_LOW))
    }

    private fun open(context: Context) = PendingIntent.getActivity(context, 0, Intent(context, app.hoshino.harness.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun action(context: Context, label: String, choice: PhoneTakeover.Choice) = Notification.Action.Builder(null, label,
        PendingIntent.getBroadcast(context, choice.ordinal + 10, Intent(context, PhoneActionReceiver::class.java).setAction("takeover.${choice.name}"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build()

    private fun builder(context: Context, title: String, text: String, quiet: Boolean = PhoneCapsule.visible.value) = Notification.Builder(context, if (quiet) QUIET else CHANNEL)
        .setSmallIcon(android.R.drawable.ic_dialog_alert).setContentTitle(title.take(100)).setContentText(text.take(300))
        .setStyle(Notification.BigTextStyle().bigText(text.take(600))).setContentIntent(open(context))
        .setCategory(Notification.CATEGORY_REMINDER)

    fun takeover(context: Context, sessionId: String, reason: String, moved: Boolean) {
        val b = builder(context, if (moved) "已把 App 挪到主屏 · 处理好后点「处理好了」" else "Hoshino 需要你处理一步", reason.ifBlank { "这一步需要你亲手完成" }).setOngoing(true)
        if (!moved) b.addAction(action(context, "我来处理", PhoneTakeover.Choice.Take))
        b.addAction(action(context, "处理好了", PhoneTakeover.Choice.Done)).addAction(action(context, "跳过", PhoneTakeover.Choice.Skip))
        runCatching { manager(context).notify(TAKEOVER_ID, b.build()) }
    }

    fun cancelTakeover(context: Context) = manager(context).cancel(TAKEOVER_ID)

    fun needsUser(context: Context, sessionId: String, reason: String) {
        val text = when (reason) {
            "captcha" -> "出现了人机验证，需要你亲手完成"
            "payment" -> "到了付款页，付款永远交给你自己操作"
            "password", "login" -> "需要登录或输入密码/验证码"
            else -> "这一步需要你处理"
        }
        val b = builder(context, "手机操作暂停 · 需要你", text).setAutoCancel(true)
        runCatching { manager(context).notify(NEEDS_USER_ID, b.build()) }
    }

    /** PL3: each automatic switch-on of wireless debugging is announced once. */
    fun wirelessDebuggingTurnedOn(context: Context) {
        manager(context)
        val b = builder(context, "已自动打开无线调试", "你在「设置手机操作」里允许过：连上 Wi-Fi 后自动打开无线调试以恢复手机助手。可随时在设置里关掉。", quiet = true).setAutoCancel(true)
        runCatching { manager(context).notify(AUTO_ON_ID, b.build()) }
    }
}

class PhoneActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action.startsWith("takeover.")) runCatching { PhoneTakeover.Choice.valueOf(action.removePrefix("takeover.")) }.getOrNull()?.let { PhoneTakeover.of(context).choose(it) }
    }
}
