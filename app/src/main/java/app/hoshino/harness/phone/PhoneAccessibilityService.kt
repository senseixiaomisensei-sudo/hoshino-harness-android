// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package app.hoshino.harness.phone

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Hoshino's accessibility service: reads windows per display, performs gestures on a given display,
 * reports change signals per display, takes main-screen screenshots and hosts the floating capsule
 * (accessibility overlay windows need no "draw over other apps" permission).
 * The event channel is process-wide ([A11yEventBus], Mika a8c3aa6): after the system disconnects and reconnects the
 * service, waits that subscribed earlier keep receiving events from the new instance; operations (windows, gestures,
 * screenshots) go to the currently connected instance even when a caller still holds an old one.
 * Enabled by the user (system settings), or by the helper only after the user ticked that item in the setup wizard (PhoneConsent).
 */
@SuppressLint("NewApi")
class PhoneAccessibilityService : AccessibilityService() {
    private val _foreground = MutableStateFlow<String?>(null)
    /** Package of the app the user is looking at on the main screen. */
    val foreground: StateFlow<String?> = _foreground.asStateFlow()

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo?.let { info ->
            if (info.flags and AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS == 0) {
                info.flags = info.flags or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                serviceInfo = info
            }
        }
        _instance.value = this
        PhoneCapsule.attach(this)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        clear()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        clear()
        super.onDestroy()
    }

    private fun clear() {
        _instance.compareAndSet(this, null)
        PhoneCapsule.detach()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // A disconnected instance may still get a late callback: only the connected one reports.
        if (_instance.value !== this) return
        val displayId = displayOf(event)
        if (displayId == Display.DEFAULT_DISPLAY && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()
            if (CapsuleVisibility.countsAsForeground(pkg, windowIsApplication(event.windowId)) && pkg != _foreground.value) {
                _foreground.value = pkg
                PhoneCapsule.foregroundChanged(pkg!!)
            }
        }
        if (displayId == Display.INVALID_DISPLAY || !events.hasSubscribers) return
        events.publish(this, DisplayEvent(displayId, event.eventType, event.packageName?.toString()))
    }

    /** Type of the event's window on the main screen: true = app window, false = overlay/IME/system, null = unknown. */
    private fun windowIsApplication(windowId: Int): Boolean? {
        if (windowId == -1) return null
        val w = runCatching { windows.orEmpty().firstOrNull { it.id == windowId } }.getOrNull() ?: return null
        return w.type == AccessibilityWindowInfo.TYPE_APPLICATION
    }

    override fun onInterrupt() = Unit

    /** Windows on [displayId], read from the currently connected instance (empty when none is connected). */
    fun windowsOn(displayId: Int): List<AccessibilityWindowInfo> = (_instance.value ?: return emptyList()).ownWindowsOn(displayId)

    private fun ownWindowsOn(displayId: Int): List<AccessibilityWindowInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) windowsOnAllDisplays.get(displayId).orEmpty()
        else if (displayId == Display.DEFAULT_DISPLAY) windows.orEmpty() else emptyList()

    /** Any UI change on [displayId] (window/content changes), as a signal. Process-wide: survives reconnects. */
    fun changesOn(displayId: Int): Flow<Int> = Companion.changesOn(displayId)

    /** All events on [displayId] with the package (pairing-code reader). Process-wide: survives reconnects. */
    fun packagesOn(displayId: Int): Flow<String?> = Companion.packagesOn(displayId)

    suspend fun gesture(displayId: Int, strokes: List<GestureDescription.StrokeDescription>): Boolean {
        val builder = GestureDescription.Builder()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) builder.setDisplayId(displayId)
        strokes.forEach(builder::addStroke)
        val gesture = builder.build()
        return suspendCancellableCoroutine { cont ->
            val callback = object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) { if (cont.isActive) cont.resume(true) }
                override fun onCancelled(gestureDescription: GestureDescription) { if (cont.isActive) cont.resume(false) }
            }
            val service = _instance.value
            if ((service == null || !service.dispatchGesture(gesture, callback, null)) && cont.isActive) cont.resume(false)
        }
    }

    suspend fun tap(displayId: Int, x: Float, y: Float, holdMs: Long): Boolean =
        gesture(displayId, listOf(GestureDescription.StrokeDescription(Path().apply { moveTo(x, y) }, 0, holdMs.coerceAtLeast(1))))

    suspend fun swipe(displayId: Int, x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean =
        gesture(displayId, listOf(GestureDescription.StrokeDescription(Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, 0, durationMs.coerceAtLeast(1))))

    sealed interface Shot { class Ok(val bitmap: Bitmap) : Shot; data object Secure : Shot; data class Failed(val code: Int) : Shot }

    /** Screenshot of a display (Android 11+). Secure windows report [Shot.Secure]. */
    suspend fun screenshot(displayId: Int): Shot {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return Shot.Failed(-1)
        val first = shotOnce(displayId)
        if (first !is Shot.Failed || first.code != ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) return first
        kotlinx.coroutines.delay(1_000)
        return shotOnce(displayId)
    }

    private suspend fun shotOnce(displayId: Int): Shot = suspendCancellableCoroutine { cont ->
        val service = _instance.value
        if (service == null) {
            cont.resume(Shot.Failed(ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS))
            return@suspendCancellableCoroutine
        }
        service.takeScreenshot(displayId, service.mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val shot = runCatching {
                    result.hardwareBuffer.use { buffer ->
                        val hw = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) ?: return@runCatching null
                        hw.copy(Bitmap.Config.ARGB_8888, false).also { hw.recycle() }
                    }
                }.getOrNull()
                if (cont.isActive) cont.resume(shot?.let(Shot::Ok) ?: Shot.Failed(ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR))
            }
            override fun onFailure(errorCode: Int) {
                val secure = ScreenshotErrors.isSecureWindow(Build.VERSION.SDK_INT, errorCode)
                if (cont.isActive) cont.resume(if (secure) Shot.Secure else Shot.Failed(errorCode))
            }
        })
    }

    private fun displayOf(event: AccessibilityEvent): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return event.displayId
        val windowId = event.windowId
        if (windowId == -1 || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return Display.DEFAULT_DISPLAY
        val all = windowsOnAllDisplays
        for (i in 0 until all.size()) if (all.valueAt(i).any { it.id == windowId }) return all.keyAt(i)
        return Display.INVALID_DISPLAY
    }

    private class DisplayEvent(val displayId: Int, val type: Int, val packageName: String?)

    companion object {
        private val _instance = MutableStateFlow<PhoneAccessibilityService?>(null)
        val instance: StateFlow<PhoneAccessibilityService?> = _instance.asStateFlow()
        private val events = A11yEventBus<PhoneAccessibilityService, DisplayEvent>({ _instance.value })

        /** Any UI change on [displayId] from whichever instance is connected now or later. */
        fun changesOn(displayId: Int): Flow<Int> = events.events.filter { it.displayId == displayId }.map { it.type }

        /** All events on [displayId] with the package, from whichever instance is connected now or later. */
        fun packagesOn(displayId: Int): Flow<String?> = events.events.filter { it.displayId == displayId }.map { it.packageName }
    }
}
