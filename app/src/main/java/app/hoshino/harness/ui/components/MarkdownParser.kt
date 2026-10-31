package app.hoshino.harness.ui.components

import androidx.compose.runtime.Immutable
import app.hoshino.harness.core.StreamText
import java.util.concurrent.atomic.AtomicLong

/** 轻量 Markdown 块：标题、段落、有序/无序列表、引用、代码块、表格、分隔线。纯 Kotlin，可在 JVM 单测中使用。 */
@Immutable
sealed interface MdBlock {
    @Immutable data class Heading(val level: Int, val text: String) : MdBlock
    @Immutable data class Paragraph(val text: String) : MdBlock
    @Immutable data class ListItem(val ordered: Boolean, val marker: String, val text: String) : MdBlock
    @Immutable data class Quote(val text: String) : MdBlock
    @Immutable data class Code(val lang: String, val code: String) : MdBlock
    @Immutable data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
    @Immutable data object Rule : MdBlock
}

/** 解析量统计（测试/诊断用）：累计被解析的字符数与调用次数。 */
object MarkdownStats {
    val parsedChars = AtomicLong()
    val parseCalls = AtomicLong()
    fun reset() { parsedChars.set(0); parseCalls.set(0) }
}

private val TABLE_SEPARATOR = Regex("^\\|?\\s*:?-{2,}.*")
private val ORDERED_ITEM = Regex("^\\d+[.)] .*")

fun parseMarkdown(src: CharSequence): List<MdBlock> {
    MarkdownStats.parseCalls.incrementAndGet()
    MarkdownStats.parsedChars.addAndGet(src.length.toLong())
    val out = mutableListOf<MdBlock>()
    val lines = src.lines()
    var i = 0
    val para = StringBuilder()
    fun flush() {
        if (para.isNotBlank()) out += MdBlock.Paragraph(para.toString().trim())
        para.clear()
    }
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()
        when {
            trimmed.startsWith("```") -> {
                flush()
                val lang = trimmed.removePrefix("```").trim()
                val sb = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(lines[i])
                    i++
                }
                out += MdBlock.Code(lang, sb.toString())
            }
            trimmed.startsWith("#") -> {
                flush()
                val level = trimmed.takeWhile { it == '#' }.length.coerceIn(1, 4)
                out += MdBlock.Heading(level, trimmed.drop(level).trim())
            }
            trimmed == "---" || trimmed == "***" -> {
                flush(); out += MdBlock.Rule
            }
            trimmed.startsWith("|") && i + 1 < lines.size && lines[i + 1].trim().matches(TABLE_SEPARATOR) -> {
                flush()
                fun cells(l: String) = l.trim().trim('|').split('|').map { it.trim() }
                val header = cells(trimmed)
                i += 2
                val rows = mutableListOf<List<String>>()
                while (i < lines.size && lines[i].trim().startsWith("|")) {
                    rows += cells(lines[i]); i++
                }
                out += MdBlock.Table(header, rows)
                continue
            }
            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                flush(); out += MdBlock.ListItem(false, "•", trimmed.drop(2))
            }
            ORDERED_ITEM.matches(trimmed) -> {
                flush()
                val marker = trimmed.substringBefore(' ')
                out += MdBlock.ListItem(true, marker, trimmed.substringAfter(' '))
            }
            trimmed.startsWith(">") -> {
                flush(); out += MdBlock.Quote(trimmed.removePrefix(">").trim())
            }
            trimmed.isEmpty() -> flush()
            else -> {
                if (para.isNotEmpty()) para.append(' ')
                para.append(trimmed)
            }
        }
        i++
    }
    flush()
    return out
}

/**
 * 按块缓存的增量 Markdown 解析，给流式回答用。
 *
 * 安全边界 = 代码块之外的空行之后。本解析器的所有块都在空行处结束（段落、表格被空行终止，其余块是单行），
 * 因此「边界之前的块」与后续追加的内容无关：只解析一次并缓存；每次更新只重解析最后一个边界之后的尾部。
 * 新内容只扫描一遍来寻找边界与代码块开闭状态，总扫描量 O(n)。
 */
class IncrementalMarkdown {
    @Immutable
    data class Result(val stable: List<MdBlock>, val tail: List<MdBlock>) {
        val blocks: List<MdBlock> get() = if (tail.isEmpty()) stable else stable + tail
    }

    private var previous: CharSequence? = null
    private var stable: List<MdBlock> = emptyList()
    private var stableEnd = 0
    private var scanPos = 0
    private var inFence = false
    private var lastBoundary = 0
    private var lastResult = Result(emptyList(), emptyList())

    fun update(src: CharSequence): Result {
        val prev = previous
        if (prev != null && prev === src) return lastResult
        if (prev == null || !isExtension(prev, src)) reset()
        previous = src
        scan(src)
        if (lastBoundary > stableEnd) {
            stable = stable + parseMarkdown(src.subSequence(stableEnd, lastBoundary))
            stableEnd = lastBoundary
        }
        val tail = if (stableEnd < src.length) parseMarkdown(src.subSequence(stableEnd, src.length)) else emptyList()
        lastResult = Result(stable, tail)
        return lastResult
    }

    private fun isExtension(prev: CharSequence, src: CharSequence): Boolean {
        if (src.length < prev.length) return false
        if (prev is StreamText && src is StreamText && src.extends(prev)) return true
        // 普通字符串：逐字符比较前缀（比解析便宜得多）。
        for (i in 0 until prev.length) if (prev[i] != src[i]) return false
        return true
    }

    private fun reset() { stable = emptyList(); stableEnd = 0; scanPos = 0; inFence = false; lastBoundary = 0 }

    /** 从上次停下的行首继续扫描完整的行（以 \n、\r 或 \r\n 结束）。 */
    private fun scan(src: CharSequence) {
        var lineStart = scanPos
        var i = scanPos
        val n = src.length
        while (i < n) {
            val ch = src[i]
            if (ch == '\n' || ch == '\r') {
                var next = i + 1
                if (ch == '\r') { if (next >= n) break; if (src[next] == '\n') next++ }  // \r 在末尾：可能是 \r\n 的前半，等下次
                onLine(src, lineStart, i, next)
                lineStart = next; i = next
            } else i++
        }
        scanPos = lineStart
    }

    private fun onLine(src: CharSequence, start: Int, end: Int, next: Int) {
        var a = start; var b = end
        while (a < b && src[a].isWhitespace()) a++
        while (b > a && src[b - 1].isWhitespace()) b--
        if (a == b) { if (!inFence) lastBoundary = next; return }
        if (b - a >= 3 && src[a] == '`' && src[a + 1] == '`' && src[a + 2] == '`') inFence = !inFence
    }
}
