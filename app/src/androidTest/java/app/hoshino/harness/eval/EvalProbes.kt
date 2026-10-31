// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package app.hoshino.harness.eval

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.hoshino.bridge.eval.APP_CAPABILITIES
import dev.hoshino.bridge.eval.AppCapabilities
import dev.hoshino.bridge.eval.PROBE_ALLOWLIST
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 评测器自己对设备的只读查看（对应 Mika `EvalProbes`）：白名单探针、前置条件、任务结束后打开 App 找字、打开网页找字。
 * 命令只由 [PROBE_ALLOWLIST] 与 [APP_CAPABILITIES] 拼出，任务文件写不进任意命令。shell 身份来自测试框架（UiAutomation），
 * 不经过 Agent 的工具和审批，所以评测器的查看不会被算进 Agent 的步数。
 *
 * 和 Mika 的差别（如实写在 eval/README.md）：
 * - `screen_has` 在主屏上冷启动 App 读界面树（UiAutomator），Mika 在一块新的虚拟屏上读；
 * - `web_has` 直接取网页 HTML 去标签后找字，不执行 JavaScript（Hoshino 的内置浏览器是 m3.0），要脚本渲染的页面会判成找不到；
 * - 登录态只认人工标记（[loggedIn]，一行一个包名或网站），Hoshino 还没有「网站登录」登记表。
 */
class EvalProbes(private val loggedIn: File) {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    /** 以 shell 身份执行（stdout）。 */
    fun sh(command: String): String {
        val pfd = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    /** 只执行 [PROBE_ALLOWLIST] 里的命令。 */
    fun run(name: String): String {
        val cmd = PROBE_ALLOWLIST[name] ?: error("probe not in allowlist: $name")
        return sh(cmd).trim()
    }

    /** 这台手机上能处理某类事的 App（按标准 Intent 查，默认处理的排前面）；没有标准 Intent 的能力返回空。 */
    fun appCandidates(capability: String): List<String> = APP_CAPABILITIES[capability].orEmpty()
        .flatMap { i -> AppCapabilities.commands(i).flatMap { cmd -> AppCapabilities.packagesIn(runCatching { sh(cmd) }.getOrDefault("")) } }
        .distinct()

    /** `installed:<包名>`、`app:<能力>`、`signedIn:<包名或网站>`（旧写法 `login:`、`site:`）。返回（满足？, 不满足的原因）。 */
    fun requirementMet(req: String): Pair<Boolean, String> {
        val kind = req.substringBefore(':')
        val target = req.substringAfter(':', "")
        return when (kind) {
            "app" -> if (appCandidates(target).isNotEmpty()) true to "" else false to "这台手机上没有能处理「$target」的 App"
            "signedIn", "login", "site" -> if (marked(target)) true to "" else false to "未登录:$target 没有标记在 ${loggedIn.name} 里(Hoshino 还没有网站登录登记,App 与网站都靠人工标记)"
            "installed" -> when {
                !PACKAGE.matches(target) -> false to "前置条件写法不对:$req"
                sh("pm path $target").contains("package:") -> true to ""
                else -> false to "没装 $target"
            }
            else -> false to "不认识的前置条件:$req"
        }
    }

    private fun marked(target: String) = runCatching { loggedIn.readLines().map { it.trim() } }.getOrDefault(emptyList()).contains(target)

    /** 冷启动 [pkg]（或用它打开深链接），读界面树直到看到 [text]（忽略空白）或超时。 */
    fun screenHas(pkg: String, text: String, deepLink: String?): Boolean {
        require(PACKAGE.matches(pkg)) { "bad package: $pkg" }
        val device = UiDevice.getInstance(instrumentation)
        sh("am force-stop $pkg")
        if (deepLink == null) sh("monkey -p $pkg -c android.intent.category.LAUNCHER 1")
        else {
            require('\'' !in deepLink && '\n' !in deepLink && '"' !in deepLink) { "bad deep link" }
            sh("am start -a android.intent.action.VIEW -d '$deepLink' -p $pkg")
        }
        val want = text.replace(WS, "")
        val end = System.currentTimeMillis() + SCREEN_WAIT_MS
        try {
            while (true) {
                val dump = ByteArrayOutputStream().also { device.dumpWindowHierarchy(it) }.toString(Charsets.UTF_8.name())
                val seen = ATTR.findAll(dump).joinToString("") { it.groupValues[2] }.replace(WS, "")
                if (want in seen) return true
                if (System.currentTimeMillis() > end) return false
                Thread.sleep(500)
            }
        } finally {
            device.pressHome()
        }
    }

    /** 取 [url] 的 HTML（至多 [WEB_BYTES]），去掉脚本、样式与标签后找 [text]（忽略空白）。不执行 JavaScript。 */
    fun webHas(url: String, text: String): Boolean {
        val u = URL(url)
        require(u.protocol == "https" || u.protocol == "http") { "bad url: $url" }
        val conn = (u.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 30_000; instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) HoshinoEval/1")
        }
        return try {
            val body = conn.inputStream.use { input ->
                val out = ByteArrayOutputStream(); val buf = ByteArray(64 * 1024)
                while (out.size() < WEB_BYTES) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n) }
                out.toString(Charsets.UTF_8.name())
            }
            val plain = body.replace(SCRIPT, " ").replace(TAG, " ").replace("&nbsp;", " ").replace("&amp;", "&")
            text.replace(WS, "") in plain.replace(WS, "")
        } finally { conn.disconnect() }
    }

    private companion object {
        const val WEB_BYTES = 4 * 1024 * 1024
        const val SCREEN_WAIT_MS = 10_000L
        val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
        val WS = Regex("\\s+")
        val ATTR = Regex("""\b(text|content-desc)="([^"]*)"""")
        val SCRIPT = Regex("(?is)<(script|style)[^>]*>.*?</\\1>")
        val TAG = Regex("<[^>]+>")
    }
}
