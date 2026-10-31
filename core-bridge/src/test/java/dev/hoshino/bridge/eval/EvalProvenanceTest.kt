package dev.hoshino.bridge.eval

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * EV1「保留出处」：移植的题与 Mika 原文逐字节一致，除非在 eval/provenance.json 的 modified 里登记过；
 * 出处（仓库、提交、许可）写全，许可证全文在 licenses/mika-LICENSE，THIRD_PARTY_NOTICES.md 有 Mika 一段。
 */
class EvalProvenanceTest {
    private val root = listOf(File("."), File("..")).first { File(it, "eval/provenance.json").isFile }
    private val provenance = JSONObject(File(root, "eval/provenance.json").readText())

    private fun sha256(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    @Test fun sourceAndLicenseAreRecorded() {
        assertEquals("https://github.com/wangzan101/mika", provenance.getString("source"))
        assertTrue(Regex("^[0-9a-f]{40}$").matches(provenance.getString("commit")))
        assertEquals("Apache-2.0", provenance.getString("license"))
        val license = File(root, provenance.getString("licenseFile"))
        assertTrue(license.isFile && "Apache License" in license.readText() && "Version 2.0" in license.readText())
        val notices = File(root, "THIRD_PARTY_NOTICES.md").readText()
        assertTrue("THIRD_PARTY_NOTICES.md 缺 Mika 段落", "wangzan101/mika" in notices && "eval/tasks" in notices)
    }

    @Test fun portedTasksAreByteIdenticalUnlessDeclaredModified() {
        val files = provenance.getJSONObject("files")
        val modified = provenance.getJSONArray("modified").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
        assertEquals(128, files.length())
        for (path in files.keys()) {
            val f = File(root, "eval/$path")
            assertTrue("$path 被删了:移植的题要保留(不想跑就在评测器里跳过)", f.isFile)
            if (path !in modified) assertEquals("$path 与 Mika 原文不一致;改动须登记到 provenance.json 的 modified", files.getString(path), sha256(f))
        }
        // 题集里不在清单上的题只能是 Hoshino 自己出的(hh- 开头)
        val listed = files.keys().asSequence().toSet()
        File(root, "eval/tasks").walkTopDown().filter { it.isFile && it.extension == "json" }.forEach { f ->
            val rel = f.relativeTo(File(root, "eval")).invariantSeparatorsPath
            assertTrue("$rel 不在出处清单里,也不是 hh- 开头的新题", rel in listed || f.name.startsWith("hh-"))
        }
    }
}
