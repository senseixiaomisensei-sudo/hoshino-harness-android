// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package dev.hoshino.bridge.arch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * minSdk 29 守卫：JVM 单测跑在 JDK 17 上，`BigInteger.TWO`、`InputStream.readNBytes` 这类成员在这里都有，单测测不出来；
 * 到了 Android 10–12 却是类一加载 / 一调用就抛 `NoSuchFieldError` / `NoSuchMethodError`（不是 Exception，catch 不住，进程直接崩）。
 * Mika f28e8be 修的就是这一类（配对时 Ed25519 的 `BigInteger.TWO` 让整个进程崩掉、无障碍随之被系统停掉）。
 *
 * 这里按源码文本查 core-bridge 与 app 的 main 源码（整行注释、字符串内容不算），列的是 Kotlin 会解析成 JDK 成员、
 * 而 Android 13+（API 33+）才有的 API。lint 的 NewApi 也能查，但仓库里有 `@SuppressLint("NewApi")` 的整类压制、CI 也不跑 lint，
 * 所以用这个测试兜底。确实只在高版本分支里调用、且有 SDK_INT 判断的，可在同一行写 `// minSdk-ok: 原因` 豁免。
 *
 * 另：防截屏窗口的截图错误码 `ERROR_TAKE_SCREENSHOT_SECURE_WINDOW` 是 API 34 才有，用到它的行必须同时判断 `UPSIDE_DOWN_CAKE`。
 */
class MinSdkApiGuardTest {
    private val root = listOf(File("."), File("..")).first { File(it, "app/src/main").isDirectory && File(it, "core-bridge/src/main").isDirectory }
    private val dirs = listOf(File(root, "core-bridge/src/main/java"), File(root, "app/src/main/java"))

    data class Hit(val file: String, val line: Int, val rule: String, val text: String)

    @Test fun sourcesAreFound() {
        val n = dirs.sumOf { d -> d.walkTopDown().count { it.isFile && it.extension == "kt" } }
        assertTrue("没找到源码（工作目录不对？）", n > 50)
    }

    @Test fun noApi33PlusJdkMembersInMainSources() {
        val hits = dirs.flatMap { d -> d.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { scan(it.relativeTo(root).invariantSeparatorsPath, it.readText()) }.toList() }
        assertTrue("这些调用在 Android 10–12（minSdk 29）上不存在，会抛 NoSuchFieldError/NoSuchMethodError：\n" + hits.joinToString("\n") { "${it.file}:${it.line}: [${it.rule}] ${it.text}" }, hits.isEmpty())
    }

    @Test fun guardCatchesViolations() {
        val bad = """
            package x
            import java.math.BigInteger
            val two = BigInteger.TWO
            // val commented = BigInteger.TWO
            val s = "BigInteger.TWO in a string"
            fun a(i: java.io.InputStream) = i.readNBytes(8)
            fun b(i: java.io.InputStream) = i.readAllBytes()
            fun c(i: java.io.InputStream, o: java.io.OutputStream) = i.transferTo(o)
            val l = java.util.List.of(1, 2)
            fun d() { val out = java.io.ByteArrayOutputStream(); out.toString(Charsets.UTF_8) }
            fun e() { val ok = java.io.ByteArrayOutputStream(); ok.toString("UTF-8"); ok.toString() }
            fun f() { val list = mutableListOf(1); list.removeFirst() }
            fun g() { val q = ArrayDeque<Int>(); q.removeFirst(); val j = java.util.ArrayDeque<Int>(); j.removeLast() }
            fun h(code: Int) = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && code == ERROR_TAKE_SCREENSHOT_SECURE_WINDOW
            fun k(code: Int) = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && code == ERROR_TAKE_SCREENSHOT_SECURE_WINDOW
            fun m(i: java.io.InputStream) = i.readNBytes(8) // minSdk-ok: only on API 33+
            fun n(b: ByteArray) = b.toString(Charsets.UTF_8)
        """.trimIndent()
        val rules = scan("Bad.kt", bad).map { it.line to it.rule }
        assertEquals(
            listOf(
                3 to "BigInteger.TWO", 6 to "InputStream.readNBytes", 7 to "InputStream.readAllBytes", 8 to "InputStream.transferTo",
                9 to "List/Set/Map.of", 10 to "ByteArrayOutputStream.toString(Charset)", 12 to "MutableList.removeFirst/removeLast",
                14 to "ERROR_TAKE_SCREENSHOT_SECURE_WINDOW",
            ),
            rules,
        )
    }

    companion object {
        private val STRING = Regex(""""(?:[^"\\]|\\.)*"""")
        private val SIMPLE = listOf(
            "BigInteger.TWO" to Regex("""\bBigInteger\.TWO\b"""),
            "InputStream.readNBytes" to Regex("""\.readNBytes\("""),
            "InputStream.readAllBytes" to Regex("""\.readAllBytes\(\s*\)"""),
            "InputStream.transferTo" to Regex("""\.transferTo\(\s*[\w.]+\s*\)"""),
            "List/Set/Map.of" to Regex("""\b(?:java\.util\.)?(?:List|Set|Map)\.(?:of|copyOf|ofEntries)\("""),
            "Stream.toList" to Regex("""\.stream\(\).*\.toList\(\)"""),
            "Optional.orElseThrow()" to Regex("""\.orElseThrow\(\s*\)"""),
            "HexFormat" to Regex("""\bHexFormat\b"""),
            "Math.clamp" to Regex("""\bMath\.clamp\("""),
            "Thread.ofVirtual/ofPlatform" to Regex("""\bThread\.of(?:Virtual|Platform)\b"""),
        )
        private val BAOS_DECL = Regex("""\b(\w+)\s*(?::\s*(?:java\.io\.)?ByteArrayOutputStream\s*)?=\s*(?:java\.io\.)?ByteArrayOutputStream\(""")
        private val REMOVE_END = Regex("""\b(\w+)\s*\.\s*(?:removeFirst|removeLast)\(\s*\)""")

        /** Kotlin 的 `ArrayDeque`、`java.util.ArrayDeque` / `LinkedList`（Deque）上的 removeFirst/removeLast 各版本都有。 */
        private fun isDeque(text: String, name: String) =
            Regex("""\b${Regex.escape(name)}\b\s*(?::\s*(?:java\.util\.|kotlin\.collections\.)?(?:ArrayDeque|LinkedList|Deque)\b|=\s*(?:java\.util\.|kotlin\.collections\.)?(?:ArrayDeque|LinkedList)\b)""").containsMatchIn(text)

        fun scan(file: String, text: String): List<Hit> {
            val baos = BAOS_DECL.findAll(text).map { it.groupValues[1] }.toSet()
            val hits = mutableListOf<Hit>()
            var block = false
            text.lines().forEachIndexed { i, raw ->
                val t = raw.trim()
                when {
                    block -> { if ("*/" in t) block = false; return@forEachIndexed }
                    t.startsWith("/*") -> { if ("*/" !in t) block = true; return@forEachIndexed }
                    t.startsWith("//") || t.startsWith("*") -> return@forEachIndexed
                }
                if ("minSdk-ok:" in raw) return@forEachIndexed
                val line = raw.replace(STRING, "\"\"").substringBefore(" //")
                fun hit(rule: String) { hits += Hit(file, i + 1, rule, t) }
                SIMPLE.forEach { (rule, re) -> if (re.containsMatchIn(line)) hit(rule) }
                if (baos.any { Regex("""\b${Regex.escape(it)}\.toString\(\s*[^)"\s]""").containsMatchIn(line) }) hit("ByteArrayOutputStream.toString(Charset)")
                if (REMOVE_END.findAll(line).any { !isDeque(text, it.groupValues[1]) }) hit("MutableList.removeFirst/removeLast")
                if ("ERROR_TAKE_SCREENSHOT_SECURE_WINDOW" in line && "UPSIDE_DOWN_CAKE" !in line) hit("ERROR_TAKE_SCREENSHOT_SECURE_WINDOW")
            }
            return hits
        }
    }
}
