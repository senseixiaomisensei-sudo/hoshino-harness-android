package app.hoshino.harness.phone.setup

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import app.hoshino.harness.phone.PhoneConsent
import dev.hoshino.app.KernelService

/**
 * Keeps the process in the foreground-service state while a phone task runs (WP-9). The kernel already lives in
 * [KernelService] (a foreground service), so normally this does nothing; it covers the case where the kernel
 * service was stopped (notification "停止", or the process was restarted by the self-heal path without UI) while a
 * phone task is still being driven. Never starts anything when no task runs.
 */
object PhoneKeepAlive {
    /** Pure rule: only while a task is busy and the foreground service is not already running. */
    fun needsStart(taskBusy: Boolean, serviceRunning: Boolean) = taskBusy && !serviceRunning

    fun ensure(context: Context, taskBusy: Boolean) {
        val app = context.applicationContext
        if (!needsStart(taskBusy, running(app))) return
        runCatching { app.startForegroundService(Intent(app, KernelService::class.java)) }
            .onSuccess { PhoneConsent.of(app).audit("keepalive started foreground service for a phone task") }
            .onFailure { PhoneConsent.of(app).audit("keepalive could not start foreground service: ${it.javaClass.simpleName}") }
    }

    @Suppress("DEPRECATION")
    private fun running(context: Context): Boolean = runCatching {
        // Still returns the caller's own services on current Android.
        context.getSystemService(ActivityManager::class.java).getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == KernelService::class.java.name && it.foreground }
    }.getOrDefault(true)
}
