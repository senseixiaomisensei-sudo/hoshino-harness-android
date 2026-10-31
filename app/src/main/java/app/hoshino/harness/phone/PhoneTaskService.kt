package app.hoshino.harness.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Foreground service for a running phone task (m2.9). It exists exactly while the agent display exists
 * ([PhoneController.display] non-null: from the first phone_* call that needs it until phone_finish / idle release), so
 * the task keeps running when Hoshino is in the background or the user is busy on the main screen, and the user always
 * sees an ongoing "Hoshino 正在后台操作手机" notice with a 停止 button. Nothing here depends on an Activity.
 * The kernel itself is kept alive by KernelService; this service is the phone task's own, visible lifetime.
 */
class PhoneTaskService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) manager.createNotificationChannel(NotificationChannel(CHANNEL, "手机操作 · 后台任务进行中", NotificationManager.IMPORTANCE_LOW))
        val first = notification(PhoneTaskState())
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(ID, first)
        val controller = PhoneController.of(this)
        scope.launch {
            combine(controller.display, controller.state) { d, s -> d to s }.distinctUntilChanged().collect { (display, state) ->
                if (display == null) { stopSelf(); return@collect }
                runCatching { manager.notify(ID, notification(state)) }
            }
        }
    }

    private fun notification(state: PhoneTaskState): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, app.hoshino.harness.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, PhoneTaskService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle(PhoneTaskForeground.title(state))
            .setContentText("在后台虚拟屏进行，不占用你的主屏")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "停止", stop).build())
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val controller = PhoneController.of(this)
        if (intent?.action == ACTION_STOP) { controller.stop(null, "stopped by user"); controller.release(null) }
        if (controller.display.value == null) stopSelf()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object {
        const val CHANNEL = "hoshino-phone-task"
        const val ID = 4510
        const val ACTION_STOP = "app.hoshino.harness.phone.STOP_TASK"
    }
}

/** Start/stop rules for [PhoneTaskService] (pure parts unit-tested). */
object PhoneTaskForeground {
    /** The service runs exactly while an agent display exists. */
    fun shouldRun(displayExists: Boolean): Boolean = displayExists

    /** Notification title: package names only, never screen content or typed text. */
    fun title(state: PhoneTaskState): String = when (state.status) {
        PhoneTaskState.Status.NeedsUser -> "Hoshino 手机操作暂停 · 需要你"
        PhoneTaskState.Status.Approval -> "Hoshino 手机操作 · 等待你确认"
        PhoneTaskState.Status.TakenOver -> "Hoshino 手机操作 · 你正在处理"
        else -> "Hoshino 正在后台操作手机" + (state.app?.let { " · $it" } ?: "")
    }

    /**
     * Android 12+ may refuse to start a foreground service from the background
     * (ForegroundServiceStartNotAllowedException). The task itself still runs (the kernel process is kept by
     * KernelService); the failure is returned so the caller can audit it.
     */
    fun sync(context: Context, displayExists: Boolean): Throwable? = runCatching {
        val intent = Intent(context, PhoneTaskService::class.java)
        if (shouldRun(displayExists)) context.startForegroundService(intent) else context.stopService(intent)
    }.exceptionOrNull()
}
