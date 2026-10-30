// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package dev.hoshino.bridge.arch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * EV4 架构依赖守卫（Kotlin 侧；插件侧见 node-host/test/architecture.test.mjs）。思路同 Mika `PackageDependencyTest`：
 * 按源码里的 import 和写全的类名查依赖方向，整行注释不算。规则：
 *
 * 1. 桥接层（core-bridge，`dev.hoshino.bridge`）不认识 App（`app.hoshino.harness`）：下层不引用上层。
 * 2. App 的内核适配层（`app.hoshino.harness.core`）不引用界面（`app.hoshino.harness.ui`）：AGENTS.md「UI 层不包含任何 Agent 业务逻辑」反过来也成立。
 * 3. 能力（手机、网页、记忆、知识……，`app.hoshino.harness.<能力>` 与 `dev.hoshino.bridge.<能力>`）不引用界面，能力之间互不引用。
 * 4. 纯逻辑包（`dev.hoshino.bridge.eval`、`dev.hoshino.bridge.metrics`）不碰 Android API，JVM 单测直接跑。
 * 5. 执行入口唯一：无障碍手势、全局动作、输入注入这些低层动作只能出现在登记过的驱动文件里（RT13：一切手机动作经执行入口 + 审批）。
 *    新增驱动要在 [INPUT_DRIVERS] 里登记，评审时一眼看得到。
 *
 * 现有代码里已有的违例记在 [BASELINE]（按文件计数）：只许减少不许增加；修掉一处就把基线改小，基线比实际多也会失败（防止基线过期替新违例打掩护）。
 */
class ArchitectureGuardTest {
    private val root = listOf(File("."), File("..")).first { File(it, "app/src/main").isDirectory && File(it, "core-bridge/src/main").isDirectory }
    private val bridgeRoot = File(root, "core-bridge/src/main/java/dev/hoshino/bridge")
    private val appRoot = File(root, "app/src/main/java/app/hoshino/harness")

    private class Use(val file: File, val line: Int, val text: String, val target: String)

    private val ref = Regex("""\b(app\.hoshino\.harness|dev\.hoshino\.bridge)((?:\.[a-z][a-z0-9_]*)+)""")

    private fun sources(dir: File) = dir.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.toList()

    private fun codeLines(f: File): Sequence<Pair<Int, String>> {
        var block = false
        return f.readLines().asSequence().mapIndexedNotNull { i, raw ->
            val t = raw.trim()
            when {
                block -> { if ("*/" in t) block = false; null }
                t.startsWith("/*") -> { if ("*/" !in t) block = true; null }
                t.startsWith("//") || t.startsWith("*") || t.startsWith("package ") -> null
                // 字符串里的字（Intent 动作名、提示文字）不是依赖
                else -> i + 1 to raw.replace(STRING, "\"\"").substringBefore(" //")
            }
        }
    }

    /** [dir] 下源码引用的本仓库包（`app.hoshino.harness.ui`、`dev.hoshino.bridge.phone` 这样取到第一段子包）。 */
    private fun uses(dir: File): List<Use> = sources(dir).flatMap { f ->
        codeLines(f).flatMap { (n, line) ->
            ref.findAll(line).map { m -> Use(f, n, line.trim(), m.groupValues[1] + "." + m.groupValues[2].removePrefix(".").substringBefore('.')) }
        }.toList()
    }

    /** [f] 在 [base] 下的第一段子目录（即所属子包）；直接在根下为 ""。 */
    private fun pkgOf(base: File, f: File) = f.relativeTo(base).invariantSeparatorsPath.let { if ('/' in it) it.substringBefore('/') else "" }

    private fun report(bad: List<Use>) = bad.joinToString("\n") { "${it.file.relativeTo(root).invariantSeparatorsPath}:${it.line}: ${it.text}" }

    /** 按文件计数后与基线比：超出的是新违例；基线比实际多说明修过了，要把基线改小。 */
    private fun checkAgainstBaseline(rule: String, bad: List<Use>) {
        val counts = bad.groupingBy { it.file.relativeTo(root).invariantSeparatorsPath }.eachCount()
        val allowed = BASELINE[rule].orEmpty()
        val over = counts.filter { (file, n) -> n > (allowed[file] ?: 0) }
        assertTrue("$rule:\n" + report(bad.filter { it.file.relativeTo(root).invariantSeparatorsPath in over }), over.isEmpty())
        val stale = allowed.filter { (file, n) -> (counts[file] ?: 0) < n }
        assertTrue("$rule 的基线过期了(违例已经减少),请把 ArchitectureGuardTest.BASELINE 改成实际数:$stale -> ${stale.keys.associateWith { counts[it] ?: 0 }}", stale.isEmpty())
    }

    @Test fun sourcesAreFound() {
        assertTrue("找不到源码目录", bridgeRoot.isDirectory && appRoot.isDirectory)
        assertTrue(sources(appRoot).size > 20 && sources(bridgeRoot).size > 10)
    }

    @Test fun bridgeDoesNotKnowTheApp() {
        val bad = uses(bridgeRoot).filter { it.target.startsWith("app.hoshino.harness.") }
        checkAgainstBaseline("桥接层引用了 App", bad)
    }

    @Test fun kernelAdapterDoesNotKnowTheUi() {
        val bad = uses(File(appRoot, "core")).filter { it.target == "app.hoshino.harness.ui" }
        checkAgainstBaseline("app/core 引用了界面", bad)
    }

    @Test fun capabilitiesDoNotKnowTheUiOrEachOther() {
        val bad = mutableListOf<Use>()
        for ((base, prefix) in listOf(appRoot to "app.hoshino.harness", bridgeRoot to "dev.hoshino.bridge")) {
            for (cap in CAPABILITIES) {
                val dir = File(base, cap)
                if (!dir.isDirectory) continue
                bad += uses(dir).filter { u ->
                    val target = u.target
                    target == "app.hoshino.harness.ui" ||
                        CAPABILITIES.any { other -> other != cap && (target == "app.hoshino.harness.$other" || target == "dev.hoshino.bridge.$other") }
                }
            }
        }
        checkAgainstBaseline("能力引用了界面或别的能力", bad)
    }

    @Test fun pureLogicHasNoAndroidDependency() {
        val bad = PURE.flatMap { pkg ->
            sources(File(bridgeRoot, pkg)).flatMap { f -> codeLines(f).filter { (_, l) -> ANDROID.containsMatchIn(l) }.map { (n, l) -> Use(f, n, l.trim(), "android") }.toList() }
        } + PURE.flatMap { pkg -> uses(File(bridgeRoot, pkg)).filter { it.target.startsWith("app.hoshino.harness") } }
        assertTrue("纯逻辑包引用了 Android 或 App:\n" + report(bad), bad.isEmpty())
        assertTrue("纯逻辑包不见了", PURE.all { File(bridgeRoot, it).isDirectory })
    }

    @Test fun lowLevelInputGoesThroughRegisteredDrivers() {
        val bad = (sources(appRoot) + sources(bridgeRoot)).flatMap { f ->
            val rel = f.relativeTo(root).invariantSeparatorsPath
            if (rel in INPUT_DRIVERS) emptyList() else codeLines(f).filter { (_, l) -> INPUT.containsMatchIn(l) }.map { (n, l) -> Use(f, n, l.trim(), "input") }.toList()
        }
        assertTrue("低层输入动作出现在执行入口之外(新驱动要登记到 INPUT_DRIVERS):\n" + report(bad), bad.isEmpty())
    }

    /** 守卫自己的自检：规则真的能抓到违例（防止正则写错导致永远通过）。 */
    @Test fun guardCatchesViolations() {
        val dir = createTempDir("arch")
        try {
            val f = File(dir, "x/Bad.kt").apply { parentFile.mkdirs(); writeText("package dev.hoshino.bridge.x\nimport app.hoshino.harness.ui.Foo\n// import app.hoshino.harness.core.Comment\nfun f() = app.hoshino.harness.phone.Tap()\nfun g(s: android.accessibilityservice.AccessibilityService) = s.dispatchGesture(null, null, null)\nval h = \"app.hoshino.harness.web in a string\"\n") }
            val found = uses(dir).map { it.target }
            assertEquals(listOf("app.hoshino.harness.ui", "app.hoshino.harness.phone"), found)
            assertTrue(codeLines(f).any { (_, l) -> INPUT.containsMatchIn(l) })
            assertEquals("x", pkgOf(dir, f))
        } finally { dir.deleteRecursively() }
    }

    private companion object {
        /** 能力包（App 与桥接层两边都按这个名单查）。新增能力时追加。 */
        val CAPABILITIES = listOf("phone", "web", "memory", "knowledge", "skills", "schedule")
        val PURE = listOf("eval", "metrics")
        val STRING = Regex(""""(?:[^"\\]|\\.)*"""")
        val ANDROID = Regex("""\b(import\s+)?(android|androidx)\.[a-z]""")
        val INPUT = Regex("""\b(dispatchGesture|performGlobalAction|injectInputEvent)\b""")

        /** 允许直接发低层输入动作的驱动文件（m2.8 手机执行入口下面的三条路：无障碍、全局键、shell 注入）。 */
        val INPUT_DRIVERS = setOf(
            "app/src/main/java/app/hoshino/harness/phone/PhoneAccessibilityService.kt",
            "app/src/main/java/app/hoshino/harness/phone/AndroidPhoneScreen.kt",
            "core-bridge/src/main/java/dev/hoshino/bridge/phone/shell/PhoneShellService.kt",
        )

        /** 现有违例（2026-10-31，codex/m2 bce6285）：只减不增。 */
        val BASELINE: Map<String, Map<String, Int>> = mapOf(
            // 剪贴板写入与系统分享的帮助函数放在 ui/components 里，DshHarnessClient 的回调直接调用；应挪到 core 或 bridge
            "app/core 引用了界面" to mapOf("app/src/main/java/app/hoshino/harness/core/DshHarnessClient.kt" to 2),
        )
    }
}
