package dev.hoshino.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

enum class ShizukuConnectionState { NotInstalled, NotRunning, NotAuthorized, Ready }

fun shizukuConnectionState(installed: Boolean, binderAlive: Boolean, authorized: Boolean) = when {
    binderAlive && authorized -> ShizukuConnectionState.Ready
    binderAlive -> ShizukuConnectionState.NotAuthorized
    installed -> ShizukuConnectionState.NotRunning
    else -> ShizukuConnectionState.NotInstalled
}

/** Observes the official Binder; installation alone never implies privilege. App-lifetime owner. */
class ShizukuConnection(private val context: Context) {
    private val mutableState=MutableStateFlow(ShizukuConnectionState.NotInstalled)
    val state=mutableState.asStateFlow()
    private val received=Shizuku.OnBinderReceivedListener { refresh() }
    private val died=Shizuku.OnBinderDeadListener { refresh() }
    private val permission=Shizuku.OnRequestPermissionResultListener { _,_ -> refresh() }
    private val packages=object: BroadcastReceiver() {
        override fun onReceive(context: Context,intent: Intent) {
            if(intent.data?.schemeSpecificPart==MANAGER_PACKAGE)refresh()
        }
    }
    init {
        Shizuku.addBinderReceivedListenerSticky(received)
        Shizuku.addBinderDeadListener(died)
        Shizuku.addRequestPermissionResultListener(permission)
        val filter=IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED);addAction(Intent.ACTION_PACKAGE_REMOVED);addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        if(Build.VERSION.SDK_INT>=33)context.registerReceiver(packages,filter,Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") context.registerReceiver(packages,filter)
        refresh()
    }
    fun refresh() {
        val installed=try {
            @Suppress("DEPRECATION") context.packageManager.getPackageInfo(MANAGER_PACKAGE,0)
            true
        } catch(_: PackageManager.NameNotFoundException) { false }
        val alive=Shizuku.pingBinder()
        val authorized=alive && runCatching { !Shizuku.isPreV11() && Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
        mutableState.value=shizukuConnectionState(installed,alive,authorized)
    }
    fun requestPermission() {
        refresh()
        check(Shizuku.pingBinder()) { if(state.value==ShizukuConnectionState.NotInstalled) "请先安装 Shizuku" else "已检测到 Shizuku，请先在 Shizuku 中启动服务" }
        check(!Shizuku.isPreV11()) { "请升级 Shizuku 到支持当前授权接口的版本" }
        if(Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED){refresh();return}
        check(!Shizuku.shouldShowRequestPermissionRationale()) { "授权已被拒绝，请到 Shizuku 的已授权应用中允许 Hoshino Harness" }
        Shizuku.requestPermission(REQUEST_CODE)
    }
    companion object {
        const val MANAGER_PACKAGE="moe.shizuku.privileged.api"
        private const val REQUEST_CODE=8701
    }
}
