// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package dev.hoshino.bridge.phone.shell

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.AppOpsManager
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.os.PersistableBundle
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import dev.hoshino.bridge.phone.IHoshinoPhoneShell
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Privileged phone service. It runs with the shell uid either inside the built-in helper process
 * ([HelperMain], started once through wireless debugging) or as a Shizuku UserService. It owns the
 * agent virtual displays: when this process dies the displays and the apps on them go with it.
 *
 * Hidden framework interfaces are always called through reflection, with an equivalent shell command
 * as fallback. Only [allowedUid] (Hoshino) may call it.
 */
@SuppressLint("NewApi")
class PhoneShellService(private val allowedUid: Int, private val hostName: String) : IHoshinoPhoneShell.Stub() {
    /** Shizuku UserService entry: Shizuku v13 passes the app's context. */
    constructor(context: Context) : this(context.applicationInfo.uid, HOST_SHIZUKU)

    private val context: Context = ShellContext.create()
    private val displays = HashMap<Int, VirtualDisplay>()
    private val launchLock = Any()

    // getSystemService would bind the system context, whose package does not match the shell uid.
    @SuppressLint("PrivateApi")
    private val displayManager: DisplayManager =
        DisplayManager::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }.newInstance(context)

    // Same reason: the clipboard must be used as com.android.shell. The system honours the "no copied overlay"
    // extra only for clips set by the shell, and reads/writes in the background are allowed for the shell uid.
    private val clipboard: ClipboardManager by lazy {
        ClipboardManager::class.java.getDeclaredConstructor(Context::class.java, Handler::class.java)
            .apply { isAccessible = true }.newInstance(context, Handler(Looper.getMainLooper() ?: Looper.myLooper()!!))
    }
    private val clipLock = Any()
    private val clip = ClipSwap(AndroidClipStore())
    private val clipWatchdog = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "hoshino-clip-watchdog").apply { isDaemon = true } }

    private val taskManager: Any by lazy { Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)!! }

    override fun host(): String = hostName

    override fun createDisplay(surface: Surface, width: Int, height: Int, dpi: Int): Int = try {
        val vd = displayManager.createVirtualDisplay("hoshino-agent", width, height, dpi, surface, displayFlags())
        val id = vd.display.displayId
        synchronized(displays) { displays[id] = vd }
        setImePolicy(id)
        id
    } catch (t: Throwable) {
        Log.e(TAG, "createDisplay failed", t)
        -1
    }

    private fun ownDisplay(displayId: Int): Boolean = synchronized(displays) { displayId in displays }

    override fun releaseDisplay(displayId: Int) {
        synchronized(displays) { displays.remove(displayId) }?.release()
    }

    /**
     * Starts [packageName] on our display. If the user has a visible task of it on the main screen we
     * do not start it (that would pull the user's screen away) and return [START_IN_USE_ON_MAIN].
     */
    override fun startApp(displayId: Int, packageName: String): Int = synchronized(launchLock) {
        if (!ownDisplay(displayId)) return START_ERROR
        try {
            if (inUseOnMainScreen(packageName)) return START_IN_USE_ON_MAIN
        } catch (t: Throwable) {
            Log.e(TAG, "cannot inspect main-screen tasks", t)
            return START_ERROR
        }
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return START_NOT_FOUND
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
        val ok = try {
            val start = taskManager.javaClass.methods.first { it.name == "startActivityAsUser" && it.parameterTypes.size == 12 }
            // (caller, callingPackage, callingFeatureId, intent, resolvedType, resultTo, resultWho, requestCode, flags, profilerInfo, options, userId)
            val result = start.invoke(taskManager, null, ShellContext.SHELL_PACKAGE, null, intent, null, null, null, 0, 0, null, options, USER_CURRENT) as Int
            result >= 0
        } catch (t: Throwable) {
            Log.w(TAG, "startActivityAsUser unavailable, falling back to am start", t)
            val component = intent.component?.flattenToShortString() ?: return START_NOT_FOUND
            sh("am", "start", "--display", "$displayId", "-n", component).first == 0
        }
        if (ok) START_OK else START_ERROR
    }

    /**
     * launch_app cold=true (A-5, WP-1). Force-stops the app only when every task of it is on this agent display
     * ([ColdStart]); otherwise starts it normally. Never touches the main screen or a display we do not own.
     */
    override fun restartApp(displayId: Int, packageName: String): Int = synchronized(launchLock) {
        if (!ownDisplay(displayId)) return START_ERROR
        val decision = try {
            val displayField = TASK_INFO.getField("displayId")
            val taskDisplays = allTasks().filter { packageName in packagesOf(it) }.map { displayField.getInt(it) }
            ColdStart.decide(taskDisplays, displayId, inUseOnMainScreen(packageName))
        } catch (t: Throwable) {
            Log.e(TAG, "cannot inspect tasks", t)
            return START_ERROR
        }
        if (context.packageManager.getLaunchIntentForPackage(packageName) == null) return START_NOT_FOUND
        when (decision) {
            ColdStart.IN_USE -> START_IN_USE_ON_MAIN
            ColdStart.WARM -> startApp(displayId, packageName).let { if (it == START_OK) RESTART_WARM else it }
            ColdStart.RESTART -> {
                if (sh("am", "force-stop", "--user", "current", packageName).first != 0) return START_ERROR
                startApp(displayId, packageName).let { if (it == START_OK) RESTART_COLD else it }
            }
        }
    }

    override fun topActivity(displayId: Int): String? = try {
        if (!ownDisplay(displayId)) null
        else tasksOn(displayId).firstOrNull()?.let { (TASK_INFO.getField("topActivity").get(it) as? ComponentName)?.className }
    } catch (t: Throwable) { null }

    private fun packagesOf(task: Any): List<String> = listOf("baseActivity", "topActivity").mapNotNull {
        (TASK_INFO.getField(it).get(task) as? ComponentName)?.packageName
    }

    private fun inUseOnMainScreen(packageName: String): Boolean {
        val visibleField = runCatching { TASK_INFO.getField("isVisible") }.getOrNull()
        return tasksOn(Display.DEFAULT_DISPLAY).withIndex().any { (index, task) ->
            packageName in packagesOf(task) && (visibleField?.getBoolean(task) ?: (index == 0))
        }
    }

    private fun allTasks(): List<Any> {
        val methods = taskManager.javaClass.methods.filter { it.name == "getTasks" }
        val withDisplay = methods.firstOrNull { it.parameterTypes.size == 4 }
        val tasks = if (withDisplay != null) withDisplay.invoke(taskManager, MAX_TASKS, false, false, Display.INVALID_DISPLAY)
        else methods.first { it.parameterTypes.size == 3 }.invoke(taskManager, MAX_TASKS, false, false)
        return (tasks as List<*>).filterNotNull()
    }

    private fun tasksOn(displayId: Int): List<Any> {
        val displayField = TASK_INFO.getField("displayId")
        return allTasks().filter { displayField.getInt(it) == displayId }
    }

    override fun topTaskId(displayId: Int): Int = try {
        tasksOn(displayId).firstOrNull()?.let { TASK_INFO.getField("taskId").getInt(it) } ?: -1
    } catch (t: Throwable) { -1 }

    override fun topPackage(displayId: Int): String? = try {
        tasksOn(displayId).firstOrNull()?.let { (TASK_INFO.getField("topActivity").get(it) as? ComponentName)?.packageName }
    } catch (t: Throwable) { null }

    override fun displayOfTask(taskId: Int): Int = try {
        val id = TASK_INFO.getField("taskId")
        allTasks().firstOrNull { id.getInt(it) == taskId }?.let { TASK_INFO.getField("displayId").getInt(it) } ?: -1
    } catch (t: Throwable) { -1 }

    /** IActivityTaskManager.moveRootTaskToDisplay, i.e. `am display move-stack`. Used for user takeover. */
    override fun moveTask(taskId: Int, displayId: Int): Boolean {
        // Only between our own displays and the main screen.
        if (displayId != Display.DEFAULT_DISPLAY && !ownDisplay(displayId)) return false
        return try {
            taskManager.javaClass.getMethod("moveRootTaskToDisplay", Int::class.java, Int::class.java).invoke(taskManager, taskId, displayId)
            true
        } catch (t: Throwable) {
            sh("am", "display", "move-stack", "$taskId", "$displayId").first == 0
        }
    }

    override fun injectKey(displayId: Int, keyCode: Int): Boolean {
        if (!ownDisplay(displayId) || keyCode !in ALLOWED_KEYS) return false
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0, VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
        val up = KeyEvent.changeAction(down, KeyEvent.ACTION_UP)
        return injectPair(down, up, displayId) { sh("input", "-d", "$displayId", "keyevent", "$keyCode").first == 0 }
    }

    override fun injectCtrlKey(displayId: Int, keyCode: Int): Boolean {
        if (!ownDisplay(displayId) || (keyCode != KeyEvent.KEYCODE_A && keyCode != KeyEvent.KEYCODE_V)) return false
        val now = SystemClock.uptimeMillis()
        val meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta, VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
        return injectPair(down, KeyEvent.changeAction(down, KeyEvent.ACTION_UP), displayId) { false }
    }

    override fun injectTap(displayId: Int, x: Int, y: Int): Boolean {
        if (!ownDisplay(displayId)) return false
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat(), 0)
        val up = MotionEvent.obtain(now, now + TAP_MS, MotionEvent.ACTION_UP, x.toFloat(), y.toFloat(), 0)
        return try {
            down.source = InputDevice.SOURCE_TOUCHSCREEN
            up.source = InputDevice.SOURCE_TOUCHSCREEN
            injectPair(down, up, displayId) { sh("input", "-d", "$displayId", "tap", "$x", "$y").first == 0 }
        } finally {
            down.recycle(); up.recycle()
        }
    }

    /** Down, timed moves, up — each injected at its own moment so apps see the planned speed. */
    override fun injectStroke(displayId: Int, xs: IntArray, ys: IntArray, times: LongArray): Boolean {
        if (!ownDisplay(displayId)) return false
        val n = times.size
        if (n < 2 || n > STROKE_MAX_POINTS || xs.size != n || ys.size != n || times[0] != 0L ||
            times.last() > STROKE_MAX_MS || (1 until n).any { times[it] < times[it - 1] }) return false
        val down = SystemClock.uptimeMillis()
        fun send(i: Int, action: Int): Boolean {
            val wait = down + times[i] - SystemClock.uptimeMillis()
            if (wait > 0) SystemClock.sleep(wait)
            val props = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
            val coords = MotionEvent.PointerCoords().apply { x = xs[i].toFloat(); y = ys[i].toFloat(); pressure = 1f; size = 1f }
            val e = MotionEvent.obtain(down, down + times[i], action, 1, arrayOf(props), arrayOf(coords), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            return try { inject(e, displayId) } finally { e.recycle() }
        }
        if (!send(0, MotionEvent.ACTION_DOWN)) return false
        for (i in 1 until n) send(i, MotionEvent.ACTION_MOVE)
        return send(n - 1, MotionEvent.ACTION_UP) || send(n - 1, MotionEvent.ACTION_UP)
    }

    /**
     * WP-2: borrows the clipboard for one paste. Never touches it unless the user's clip was read first
     * ([ClipSwap]); a watchdog gives it back if the app dies before [restoreClip]. Content is never logged.
     */
    override fun setClipText(text: String): Int = synchronized(clipLock) {
        if (text.isEmpty() || text.length > CLIP_MAX_CHARS) return ClipSwap.CLIP_FAILED
        try {
            val r = clip.set(text)
            if (r == ClipSwap.CLIP_SET) {
                val gen = clip.generation
                clipWatchdog.schedule(Runnable { synchronized(clipLock) { runCatching { clip.restoreIfStill(gen) } } }, CLIP_WATCHDOG_S, TimeUnit.SECONDS)
            } else {
                Log.i(TAG, "setClipText: user clip unreadable, clipboard left untouched")
            }
            r
        } catch (t: Throwable) {
            Log.w(TAG, "setClipText failed: ${t.javaClass.simpleName}")
            // Never leave ours behind half-way.
            runCatching { clip.restore() }
            ClipSwap.CLIP_FAILED
        }
    }

    override fun restoreClip() {
        synchronized(clipLock) {
            runCatching { clip.restore() }.onFailure { Log.w(TAG, "restoreClip failed: ${it.javaClass.simpleName}") }
        }
    }

    /**
     * WP-2: types [text] key by key (KeyCharacterMap of the virtual keyboard) into an own display, used when the
     * clipboard may not be borrowed. All-or-nothing: if any character has no key, nothing is sent.
     */
    override fun injectText(displayId: Int, text: String): Int {
        if (!ownDisplay(displayId)) return ShellText.TEXT_FAILED
        if (!ShellText.typeable(text)) return ShellText.TEXT_UNMAPPABLE
        val events = try {
            KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray())
        } catch (t: Throwable) { null } ?: return ShellText.TEXT_UNMAPPABLE
        // Only plain character keys (plus the shift/meta keys the map adds): never Enter, Tab, Home or app keys.
        if (events.any { !KeyEvent.isModifierKey(it.keyCode) && it.keyCode !in TEXT_KEYS }) return ShellText.TEXT_UNMAPPABLE
        for (e in events) {
            val now = SystemClock.uptimeMillis()
            val ev = KeyEvent(now, now, e.action, e.keyCode, 0, e.metaState, VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
            if (!inject(ev, displayId) && !inject(ev, displayId)) return ShellText.TEXT_FAILED
        }
        return ShellText.TEXT_TYPED
    }

    private inner class AndroidClipStore : ClipStore<ClipData> {
        override fun canRead(): Boolean = runCatching {
            val granted = context.checkPermission(READ_CLIPBOARD_IN_BACKGROUND, Process.myPid(), Process.myUid()) == PackageManager.PERMISSION_GRANTED
            val ops = context.getSystemService(AppOpsManager::class.java)
            granted && ops.unsafeCheckOpNoThrow(OPSTR_READ_CLIPBOARD, Process.myUid(), ShellContext.SHELL_PACKAGE) == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)
        override fun current(): ClipData? = clipboard.primaryClip
        override fun hasClip(): Boolean = clipboard.hasPrimaryClip()
        override fun hasDescription(): Boolean = clipboard.primaryClipDescription != null
        override fun isOurs(clip: ClipData, ours: String): Boolean =
            clip.description.label == CLIP_LABEL && clip.itemCount == 1 && clip.getItemAt(0).text?.toString() == ours
        override fun putOurs(text: String) {
            val c = ClipData.newPlainText(CLIP_LABEL, text)
            val extras = PersistableBundle()
            extras.putBoolean(EXTRA_SUPPRESS_CLIPBOARD_OVERLAY, true)
            // Typed text may be an address or phone number: keep it out of clipboard previews and keyboard history.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            c.description.extras = extras
            clipboard.setPrimaryClip(c)
        }
        override fun putBack(clip: ClipData) {
            val extras = clip.description.extras?.let { PersistableBundle(it) } ?: PersistableBundle()
            extras.putBoolean(EXTRA_SUPPRESS_CLIPBOARD_OVERLAY, true)
            clip.description.extras = extras
            clipboard.setPrimaryClip(clip)
        }
        override fun clear() = clipboard.clearPrimaryClip()
    }

    private inline fun injectPair(down: InputEvent, up: InputEvent, displayId: Int, fallback: () -> Boolean): Boolean {
        if (!inject(down, displayId)) return fallback()
        return inject(up, displayId) || inject(up, displayId)
    }

    @SuppressLint("PrivateApi")
    private fun inject(event: InputEvent, displayId: Int): Boolean = try {
        event.javaClass.getMethod("setDisplayId", Int::class.java).invoke(event, displayId)
        val manager = runCatching { Class.forName("android.hardware.input.InputManagerGlobal").getMethod("getInstance").invoke(null) }.getOrNull()
            ?: Class.forName("android.hardware.input.InputManager").getMethod("getInstance").invoke(null)
        manager!!.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.java).invoke(manager, event, INJECT_MODE_ASYNC) as Boolean
    } catch (t: Throwable) {
        Log.w(TAG, "injectInputEvent failed", t)
        false
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        // The helper outlives the app and its binder may leak: only Hoshino may call interface methods.
        if (code in FIRST_CALL_TRANSACTION..LAST_CALL_TRANSACTION && Binder.getCallingUid() != allowedUid) {
            throw SecurityException("caller ${Binder.getCallingUid()} is not allowed")
        }
        return super.onTransact(code, data, reply, flags)
    }

    override fun identity(): IntArray = intArrayOf(Process.myUid(), Process.myPid())

    /** Setup commands only (grant, settings, appops, deviceidle); output is capped and never logged. */
    override fun runCommand(command: String): Bundle {
        fun result(code: Int, output: String) = Bundle().apply { putInt("exitCode", code); putString("output", output) }
        if (command.isBlank() || command.length > 4_000) return result(-1, "Invalid command length.")
        val process = try {
            ProcessBuilder("/system/bin/sh", "-c", command).redirectErrorStream(true).start()
        } catch (t: Throwable) {
            return result(-1, "Command could not start.")
        }
        val bytes = ByteArrayOutputStream()
        val reader = Thread {
            runCatching {
                process.inputStream.use { input ->
                    val buffer = ByteArray(4096)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (bytes.size() < OUTPUT_MAX) bytes.write(buffer, 0, minOf(count, OUTPUT_MAX - bytes.size()))
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        val finished = process.waitFor(CMD_TIMEOUT_S, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        reader.join(1_000)
        return result(if (finished) process.exitValue() else -1, bytes.toString("UTF-8"))
    }

    override fun releaseAll() {
        restoreClip()
        synchronized(displays) { displays.values.toList().also { displays.clear() } }.forEach { runCatching { it.release() } }
    }

    override fun destroy() {
        releaseAll()
        exitProcess(0)
    }

    private fun displayFlags(): Int {
        var flags = FLAG_PUBLIC or FLAG_OWN_CONTENT_ONLY or FLAG_SUPPORTS_TOUCH or FLAG_ROTATES_WITH_CONTENT or FLAG_DESTROY_CONTENT_ON_REMOVAL
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            flags = flags or FLAG_TRUSTED or FLAG_OWN_DISPLAY_GROUP or FLAG_ALWAYS_UNLOCKED or FLAG_TOUCH_FEEDBACK_DISABLED
        }
        // Android 14+: own focus without stealing the top focus from the user's main screen.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) flags = flags or FLAG_OWN_FOCUS or FLAG_STEAL_TOP_FOCUS_DISABLED
        return flags
    }

    @SuppressLint("PrivateApi")
    private fun setImePolicy(displayId: Int) {
        runCatching {
            val wm = Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null)!!
            wm.javaClass.getMethod("setDisplayImePolicy", Int::class.java, Int::class.java).invoke(wm, displayId, IME_POLICY_LOCAL)
        }.onFailure { Log.w(TAG, "setDisplayImePolicy failed", it) }
    }

    private fun sh(vararg cmd: String): Pair<Int, String> {
        val p = try { ProcessBuilder(*cmd).redirectErrorStream(true).start() } catch (t: Throwable) { return -1 to "" }
        var out = ""
        val reader = Thread { out = runCatching { p.inputStream.bufferedReader().readText() }.getOrDefault("") }.apply { isDaemon = true; start() }
        if (!p.waitFor(CMD_TIMEOUT_S, TimeUnit.SECONDS)) { p.destroyForcibly(); return -1 to "" }
        reader.join(1_000)
        return p.exitValue() to out
    }

    companion object {
        private const val TAG = "HoshinoPhoneShell"
        const val HOST_BUILTIN = "builtin"
        const val HOST_SHIZUKU = "shizuku"
        private const val MAX_TASKS = 100
        private const val TAP_MS = 50L
        private const val STROKE_MAX_POINTS = 1_024
        private const val STROKE_MAX_MS = 10_000L
        private const val CMD_TIMEOUT_S = 15L
        private const val OUTPUT_MAX = 64 * 1024
        const val START_OK = 0
        const val START_IN_USE_ON_MAIN = 1
        const val START_NOT_FOUND = 2
        const val START_ERROR = 3
        /** restartApp results (never 0: an older helper answers 0). */
        const val RESTART_COLD = 10
        const val RESTART_WARM = 11
        /** Navigation/editing keys only: never volume, power or home from here. */
        val ALLOWED_KEYS = setOf(4, 19, 20, 21, 22, 23, 61, 62, 66, 67, 84, 92, 93, 111, 112, 122, 123, 160)
        private val TASK_INFO: Class<*> by lazy { Class.forName("android.app.TaskInfo") }
        private const val INJECT_MODE_ASYNC = 0
        private const val VIRTUAL_KEYBOARD = -1
        private const val USER_CURRENT = -2
        private const val FLAG_PUBLIC = 1 shl 0
        private const val FLAG_OWN_CONTENT_ONLY = 1 shl 3
        private const val FLAG_SUPPORTS_TOUCH = 1 shl 6
        private const val FLAG_ROTATES_WITH_CONTENT = 1 shl 7
        private const val FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
        private const val FLAG_TRUSTED = 1 shl 10
        private const val FLAG_OWN_DISPLAY_GROUP = 1 shl 11
        private const val FLAG_ALWAYS_UNLOCKED = 1 shl 12
        private const val FLAG_TOUCH_FEEDBACK_DISABLED = 1 shl 13
        private const val FLAG_OWN_FOCUS = 1 shl 14
        private const val FLAG_STEAL_TOP_FOCUS_DISABLED = 1 shl 16
        private const val IME_POLICY_LOCAL = 0
        private const val CLIP_LABEL = "hoshino"
        private const val CLIP_MAX_CHARS = 4_000
        private const val CLIP_WATCHDOG_S = 10L
        // Hidden names (AppOpsManager.OPSTR_READ_CLIPBOARD, ClipDescription.EXTRA_SUPPRESS_CLIPBOARD_OVERLAY).
        private const val OPSTR_READ_CLIPBOARD = "android:read_clipboard"
        private const val READ_CLIPBOARD_IN_BACKGROUND = "android.permission.READ_CLIPBOARD_IN_BACKGROUND"
        private const val EXTRA_SUPPRESS_CLIPBOARD_OVERLAY = "com.android.systemui.SUPPRESS_CLIPBOARD_OVERLAY"
        /** Letters, digits, space and punctuation keys that KeyCharacterMap produces for printable text. */
        private val TEXT_KEYS: Set<Int> = (KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9).toSet() + (KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) +
            setOf(KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_COMMA, KeyEvent.KEYCODE_PERIOD, KeyEvent.KEYCODE_GRAVE, KeyEvent.KEYCODE_MINUS,
                KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_LEFT_BRACKET, KeyEvent.KEYCODE_RIGHT_BRACKET, KeyEvent.KEYCODE_BACKSLASH,
                KeyEvent.KEYCODE_SEMICOLON, KeyEvent.KEYCODE_APOSTROPHE, KeyEvent.KEYCODE_SLASH, KeyEvent.KEYCODE_AT, KeyEvent.KEYCODE_PLUS,
                KeyEvent.KEYCODE_STAR, KeyEvent.KEYCODE_POUND)
    }
}
