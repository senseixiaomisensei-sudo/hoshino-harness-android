// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package app.hoshino.harness.about

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import app.hoshino.harness.BuildConfig
import app.hoshino.harness.core.M2InstallReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** 当前安装的版本：优先读 PackageManager（真正装在手机上的），读不到退回编译期 BuildConfig。 */
data class InstalledVersion(val name: String, val code: Long) {
    val label: String get() = "$name ($code)"

    companion object {
        fun of(context: Context): InstalledVersion = runCatching {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            InstalledVersion(info.versionName ?: BuildConfig.VERSION_NAME, info.longVersionCode)
        }.getOrElse { InstalledVersion(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong()) }
    }
}

sealed interface UpdateUi {
    data object Idle : UpdateUi
    data object Checking : UpdateUi
    data class UpToDate(val newestSeen: Long?) : UpdateUi
    data class Available(val release: ReleaseInfo) : UpdateUi
    data class Downloading(val release: ReleaseInfo, val bytes: Long) : UpdateUi {
        val percent: Int get() = ((bytes * 100) / release.apkSize.coerceAtLeast(1)).toInt().coerceIn(0, 100)
    }
    data class Verifying(val release: ReleaseInfo) : UpdateUi
    /** 已核对通过并交给系统安装器；系统会弹确认，装完由 [M2InstallReceiver] 发通知。 */
    data class HandedToInstaller(val release: ReleaseInfo) : UpdateUi
    data class Failed(val message: String, val release: ReleaseInfo? = null) : UpdateUi
}

/**
 * 关于页「检查更新」：只问本仓库 GitHub Releases（含预发布）→ 按 versionCode 比较 → 下载手机用 arm64 APK →
 * 安装前核对（[ApkIntegrity]：地址、大小、摘要、包名、versionCode 更高、签名证书与已装包一致）→ 交系统安装器。
 * 下载在进程级作用域里跑，离开关于页不会中断；同一时间只跑一个下载。不读任何 token，日志不记 URL 以外的内容。
 */
class AppUpdates private constructor(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<UpdateUi>(UpdateUi.Idle)
    val state: StateFlow<UpdateUi> = _state.asStateFlow()
    private var job: Job? = null
    private val dir: File get() = File(context.filesDir, "hoshino/updates/about")

    val installed: InstalledVersion get() = InstalledVersion.of(context)

    fun check() {
        if (job?.isActive == true) return
        job = scope.launch {
            _state.value = UpdateUi.Checking
            _state.value = runCatching<UpdateUi> {
                val releases = Releases.parseList(get(HoshinoRepo.RELEASES_API))
                Releases.newest(releases, installed.code)?.let { UpdateUi.Available(it) }
                    ?: UpdateUi.UpToDate(releases.maxOfOrNull { it.versionCode })
            }.getOrElse { UpdateUi.Failed("连不上 GitHub 或读不懂发布信息（${it.message ?: it.javaClass.simpleName}），稍后再试") }
        }
    }

    fun downloadAndInstall(release: ReleaseInfo) {
        if (job?.isActive == true) return
        job = scope.launch {
            val apk = try {
                download(release)
            } catch (e: ApkRejected) {
                _state.value = UpdateUi.Failed(e.message ?: e.reason.text, release); return@launch
            } catch (e: Exception) {
                cleanup(); _state.value = UpdateUi.Failed("下载失败（${e.message ?: e.javaClass.simpleName}），可重试", release); return@launch
            }
            _state.value = UpdateUi.Verifying(release)
            try {
                verify(apk, release)
                commit(apk)
                _state.value = UpdateUi.HandedToInstaller(release)
            } catch (e: ApkRejected) {
                apk.delete(); _state.value = UpdateUi.Failed(e.message ?: e.reason.text, release)
            } catch (e: Exception) {
                apk.delete(); _state.value = UpdateUi.Failed("无法交给系统安装器（${e.message ?: e.javaClass.simpleName}）", release)
            }
        }
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000; c.readTimeout = 15_000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            c.setRequestProperty("User-Agent", "Hoshino-Harness/${BuildConfig.VERSION_NAME}")
            val code = c.responseCode
            check(code in 200..299) { "HTTP $code" }
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    private fun download(release: ReleaseInfo): File {
        ApkIntegrity.requireOfficialUrl(release.apkUrl)
        cleanup()
        check(dir.mkdirs() || dir.isDirectory) { "无法创建下载目录" }
        val target = File(dir, "update-${UUID.randomUUID()}.apk")
        _state.value = UpdateUi.Downloading(release, 0)
        val c = URL(release.apkUrl).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 20_000; c.readTimeout = 60_000; c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "Hoshino-Harness/${BuildConfig.VERSION_NAME}")
            val code = c.responseCode
            check(code in 200..299) { "HTTP $code" }
            // 跳转后的最终地址必须仍是 HTTPS（HttpURLConnection 不会从 HTTPS 跳到 HTTP，这里再确认一次）。
            check(c.url.protocol == "https") { "下载被重定向到非 HTTPS 地址" }
            val declared = c.contentLengthLong
            if (declared > 0 && declared != release.apkSize) throw ApkRejected(ApkRejected.Reason.Corrupt)
            var bytes = 0L; var lastShown = 0L
            c.inputStream.use { input -> target.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                while (scope.isActive) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    bytes += n
                    if (bytes > release.apkSize) throw ApkRejected(ApkRejected.Reason.Corrupt)
                    out.write(buffer, 0, n)
                    if (bytes - lastShown >= 256 * 1024) { lastShown = bytes; _state.value = UpdateUi.Downloading(release, bytes) }
                }
            } }
            _state.value = UpdateUi.Downloading(release, bytes)
            return target
        } catch (e: Exception) {
            target.delete(); throw e
        } finally { c.disconnect() }
    }

    /** 安装前核对：大小/摘要 → 解析安装包 → 包名、versionCode、签名证书（与当前已装包现读比较）。 */
    private fun verify(apk: File, release: ReleaseInfo) {
        ApkIntegrity.verifyFile(apk, release.apkSize, release.apkDigest)
        @Suppress("DEPRECATION")
        val archive = context.packageManager.getPackageArchiveInfo(apk.path, SIGNING_FLAGS) ?: throw ApkRejected(ApkRejected.Reason.Corrupt)
        @Suppress("DEPRECATION")
        val current = context.packageManager.getPackageInfo(context.packageName, SIGNING_FLAGS)
        if (context.packageName != HoshinoRepo.PACKAGE_NAME) throw ApkRejected(ApkRejected.Reason.Package)
        ApkIntegrity.requireUpdate(archive.facts(), current.facts())
    }

    private fun commit(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply { setAppPackageName(context.packageName) }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out, 65536) }; session.fsync(out) }
                val result = PendingIntent.getBroadcast(context, sessionId, Intent(context, M2InstallReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                session.commit(result.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(sessionId) }; throw e
        }
        // 已整份写进安装会话，下载的文件不再需要。
        apk.delete()
    }

    private fun cleanup() { dir.listFiles()?.forEach { it.delete() } }

    companion object {
        @Suppress("DEPRECATION")
        private const val SIGNING_FLAGS = PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES

        @Volatile private var instance: AppUpdates? = null
        fun get(context: Context): AppUpdates = instance ?: synchronized(this) {
            instance ?: AppUpdates(context.applicationContext).also { instance = it }
        }
    }
}

/** 当前签名证书；个别系统读安装包时不给 signingInfo，退回旧字段 signatures。 */
@Suppress("DEPRECATION")
internal fun PackageInfo.facts(): PackageFacts {
    val info = signingInfo
    val signers = (info?.apkContentsSigners ?: signatures).orEmpty().map { ApkIntegrity.sha256(it.toByteArray()) }.toSet()
    val history = if (info == null || info.hasMultipleSigners()) emptySet()
        else info.signingCertificateHistory.orEmpty().map { ApkIntegrity.sha256(it.toByteArray()) }.toSet()
    return PackageFacts(packageName, longVersionCode, signers, history)
}
