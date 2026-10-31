package app.hoshino.harness

import app.hoshino.harness.core.StreamText
import app.hoshino.harness.ui.components.IncrementalMarkdown
import app.hoshino.harness.ui.components.MarkdownStats
import app.hoshino.harness.ui.components.parseMarkdown
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class MarkdownIncrementalTest {
    private val pieces = listOf(
        "# Title\n", "Some paragraph text ", "with **bold** and `code`.\n", "\n", "- item one\n", "- item two\n", "1. first\n",
        "> quote\n", "```kotlin\n", "val x = 1\n", "\n", "println(x)\n", "```\n", "| a | b |\n", "|---|---|\n", "| 1 | 2 |\n",
        "---\n", "\r\n", "trailing words", "\n\n", "```\n", "\r",
    )

    /** 任意切分方式下，增量解析结果与一次性全量解析完全一致。 */
    @Test fun incrementalEqualsFullParseForRandomStreams() {
        val rnd = Random(7)
        repeat(300) {
            val doc = buildString { repeat(rnd.nextInt(5, 60)) { append(pieces[rnd.nextInt(pieces.size)]) } }
            val parser = IncrementalMarkdown()
            var text = StreamText.of("")
            var i = 0
            while (i < doc.length) {
                val n = rnd.nextInt(1, 12).coerceAtMost(doc.length - i)
                text = text.append(doc.substring(i, i + n)); i += n
                assertEquals("prefix of: $doc", parseMarkdown(text.toString()), parser.update(text).blocks)
            }
        }
    }

    @Test fun nonPrefixUpdateResetsCache() {
        val parser = IncrementalMarkdown()
        parser.update("para one\n\npara two")
        assertEquals(parseMarkdown("# other\n\nx"), parser.update("# other\n\nx").blocks)
    }

    /** 流式 5 MB 回答：累计解析字符数是线性的（只重解析最后一个块），而不是每帧全量重解析。 */
    @Test fun streamingFiveMegabytesParsesLinearly() {
        val paragraph = "This is a streamed paragraph with `inline` code and **bold** words. ".repeat(4) + "\n\n"
        val parser = IncrementalMarkdown()
        var text = StreamText.of("")
        MarkdownStats.reset()
        var frames = 0
        val step = 4096
        val target = 5 * 1024 * 1024
        val doc = StringBuilder()
        while (doc.length < target) doc.append(paragraph)
        var i = 0
        while (i < doc.length) {
            val n = step.coerceAtMost(doc.length - i)
            text = text.append(doc.subSequence(i, i + n)); i += n
            parser.update(text); frames++
        }
        val parsed = MarkdownStats.parsedChars.get()
        // 全量重解析会是 Σ = frames * n / 2 ≈ 3 TB 字符级；增量应在 2n 量级。
        assertTrue("parsed $parsed chars over $frames frames for ${doc.length}", parsed < doc.length * 3L)
        assertEquals(parseMarkdown(doc).size, parser.update(text).blocks.size)
    }
}
