package app.hoshino.harness.phone.setup

import android.content.Context
import app.hoshino.harness.phone.PhoneConsent
import app.hoshino.harness.phone.PhoneTaskForeground
import app.hoshino.harness.phone.PhoneTaskService

/**
 * WP-9 keep-alive fallback, folded into the m2.9 [PhoneTaskService] (integ/m2.9): there is exactly one foreground
 * service for a phone task. PhoneController starts it when the agent display appears, but Android 12+ can refuse a
 * foreground-service start from the background (ForegroundServiceStartNotAllowedException; only audited there).
 * This retries that same start when the task state changes while a task is busy and the service is not running.
 * It never starts KernelService (a user who tapped 停止 on the kernel notification stays stopped) and does nothing
 * when no task runs.
 */
object PhoneKeepAlive {
    /** Pure rule: only while a task is busy and the foreground service is not already running. */
    fun needsStart(taskBusy: Boolean, serviceRunning: Boolean) = taskBusy && !serviceRunning

    fun ensure(context: Context, taskBusy: Boolean, displayExists: Boolean) {
        val app = context.applicationContext
        if (!displayExists || !needsStart(taskBusy, PhoneTaskService.running)) return
        val error = PhoneTaskForeground.sync(app, true)
        PhoneConsent.of(app).audit(if (error == null) "keepalive restarted phone task foreground service" else "keepalive could not start phone task foreground service: ${error.javaClass.simpleName}")
    }
}
