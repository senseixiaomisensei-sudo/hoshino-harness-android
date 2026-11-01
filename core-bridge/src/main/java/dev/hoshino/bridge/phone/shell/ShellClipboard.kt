// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package dev.hoshino.bridge.phone.shell

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.Log
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * shell 进程（内置助手 / Shizuku UserService，uid 2000）直接访问系统剪贴板（移植 Mika e35865f + 7d2c48b）。
 *
 * 有的系统里 `ClipboardManager.getPrimaryClip` 读剪贴板前会先读 `Settings.Global`，而 app_process 起的进程没有普通 App
 * 的进程登记，拿不到 ContentProvider，报「calling package android does not match caller's uid 2000」，借剪贴板的中文输入随之失败。
 * 这里读内容、描述、有无三项直接调 `IClipboard`；Binder 仍按包名、uid、用户和设备检查权限，只是避开 Java 包装层额外的 Settings 查询。
 * 认不出的接口（如 Android 10/11 的两参签名）或拿不到剪贴板服务时退回 ClipboardManager，行为与以前一样；
 * 直接调用抛错时也退回一次。写入和清空仍走 ClipboardManager（框架原来的 ClipData 准备与清空流程）。
 */
@SuppressLint("PrivateApi")
internal class ShellClipboard(private val context: Context) {
    // 剪贴板要以 com.android.shell 访问:系统只对来自 shell 的剪贴板认「不弹已复制浮层」的标记,后台读写也只对 shell uid 放行。
    private val manager: ClipboardManager by lazy {
        ClipboardManager::class.java.getDeclaredConstructor(Context::class.java, Handler::class.java)
            .apply { isAccessible = true }.newInstance(context, Handler(Looper.getMainLooper() ?: Looper.myLooper()!!))
    }
    private val service: Any? by lazy {
        runCatching {
            val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "clipboard") as IBinder
            Class.forName("android.content.IClipboard\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        }.getOrNull()
    }
    private val type: Class<*>? by lazy { if (service == null) null else runCatching { Class.forName("android.content.IClipboard") }.getOrNull() }
    private val getClip by lazy { type?.let { ShellClipboardCalls.find(it, "getPrimaryClip") } }
    private val getDescription by lazy { type?.let { ShellClipboardCalls.find(it, "getPrimaryClipDescription") } }
    private val hasClip by lazy { type?.let { ShellClipboardCalls.find(it, "hasPrimaryClip") } }
    private val callerLabel by lazy {
        runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(ShellContext.SHELL_PACKAGE, 0)).toString()
        }.getOrDefault(ShellContext.SHELL_PACKAGE)
    }

    val primaryClip: ClipData? get() = read(getClip, { it as ClipData? }) { manager.primaryClip }
    val primaryClipDescription: ClipDescription? get() = read(getDescription, { it as ClipDescription? }) { manager.primaryClipDescription }
    fun hasPrimaryClip(): Boolean = read(hasClip, { it as Boolean? ?: false }) { manager.hasPrimaryClip() }
    fun setPrimaryClip(clip: ClipData) = manager.setPrimaryClip(clip)
    fun clearPrimaryClip() = manager.clearPrimaryClip()

    private inline fun <T> read(method: Method?, cast: (Any?) -> T, fallback: () -> T): T {
        if (method == null) return fallback()
        val args = ShellClipboardCalls.args(method, ShellContext.SHELL_PACKAGE, callerLabel, Process.myUid() / PER_USER_RANGE) ?: return fallback()
        return try {
            cast(method.invoke(service, *args))
        } catch (t: Throwable) {
            // 只记异常类名:剪贴板内容、包名以外的参数都不进日志
            val cause = (t as? InvocationTargetException)?.targetException ?: t
            Log.w(TAG, "IClipboard.${method.name} failed (${cause.javaClass.simpleName}); falling back to ClipboardManager")
            fallback()
        }
    }

    private companion object {
        const val TAG = "HoshinoShellClip"
        const val PER_USER_RANGE = 100_000
    }
}

/** 找 `IClipboard` 读取方法的签名并配参数（纯反射逻辑，JVM 单测直接跑）。 */
internal object ShellClipboardCalls {
    private val S = String::class.java
    private val I = Int::class.javaPrimitiveType!!
    private val Z = Boolean::class.javaPrimitiveType!!

    /** 按能力找，先认原版签名、再认已知的扩展签名；顺序即优先级。 */
    val SIGNATURES: List<Array<Class<*>>> = listOf(
        // Android 14+:包名、attributionTag、userId、deviceId
        arrayOf(S, S, I, I),
        // Android 12–13:包名、attributionTag、userId
        arrayOf(S, S, I),
        // 有的系统把读取扩成:包名、应用名、隐私栈、attributionTag、userId、deviceId、是否用户操作
        arrayOf(S, S, S, S, I, I, Z),
    )

    /** 都认不出（例如 Android 10/11 的「包名、userId」两参签名）返回 null，由调用方退回 ClipboardManager。 */
    fun find(type: Class<*>, name: String): Method? =
        SIGNATURES.firstNotNullOfOrNull { sig -> runCatching { type.getMethod(name, *sig) }.getOrNull() }

    fun args(method: Method, pkg: String, label: String, userId: Int): Array<Any?>? = when (method.parameterTypes.size) {
        // 后台保存/还原不是用户点「粘贴」,不伪造用户操作;隐私栈与框架默认值一样为空。
        7 -> arrayOf(pkg, label, "", null, userId, 0, false)
        // ShellContext 是默认设备的系统上下文,与原 ClipboardManager 使用的设备(0)一致。
        4 -> arrayOf(pkg, null, userId, 0)
        3 -> arrayOf(pkg, null, userId)
        else -> null
    }
}
