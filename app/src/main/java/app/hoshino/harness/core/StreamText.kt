package app.hoshino.harness.core

import androidx.compose.runtime.Immutable

/**
 * 流式文本的不可变快照（追加式 rope）。
 *
 * 模型的每个 token 都是一次追加。旧实现每次 `text + delta` 都复制整段字符串，长回答（或 MB 级输出）
 * 整体变成 O(n²)。这里所有快照共享同一块只追加的字符缓冲：
 * - [append] 在缓冲尾部写入，只花 O(delta)，返回长度更长的新快照，旧快照仍然有效（它只读自己的 [length] 之内）；
 * - 若某个旧快照被再次追加（例如 StateFlow.update 的 CAS 重试），缓冲尾部已被别人占用，就复制出一块新缓冲（分叉），语义不变；
 * - [equals] 先比较「同一缓冲 + 同一长度」，O(1)，让 Compose/StateFlow 的相等判断不再逐字符扫描。
 *
 * 线程模型：写入只发生在 [SessionStore] 的归约锁内；读者拿到快照的途径是 StateFlow 发布，
 * 发布本身建立 happens-before，因此读者看到的 [0, length) 一定是完整写入的字符。
 */
@Immutable
class StreamText private constructor(private val buffer: Buffer, override val length: Int) : CharSequence {

    private class Buffer(initialCapacity: Int) {
        @Volatile var chars = CharArray(initialCapacity.coerceAtLeast(16))
        var size = 0
        fun ensure(capacity: Int) {
            if (capacity <= chars.size) return
            var next = chars.size.toLong() * 2
            while (next < capacity) next *= 2
            chars = chars.copyOf(next.coerceAtMost(Int.MAX_VALUE.toLong() - 8).toInt().coerceAtLeast(capacity))
        }
    }

    @Volatile private var cached: String? = null

    /** 追加 [delta]，返回新快照；O(delta)（缓冲扩容摊还为 O(1)）。 */
    fun append(delta: CharSequence): StreamText {
        if (delta.isEmpty()) return this
        val target = synchronized(buffer) {
            if (buffer.size == length) {
                buffer.ensure(length + delta.length)
                val chars = buffer.chars
                for (i in delta.indices) chars[length + i] = delta[i]
                buffer.size = length + delta.length
                null
            } else Unit
        }
        if (target == null) return StreamText(buffer, length + delta.length)
        // 分叉：这个快照不是缓冲尾部，复制自己的内容再追加。
        val forked = Buffer(length + delta.length)
        System.arraycopy(buffer.chars, 0, forked.chars, 0, length)
        for (i in delta.indices) forked.chars[length + i] = delta[i]
        forked.size = length + delta.length
        return StreamText(forked, forked.size)
    }

    /** 本快照是否是 [previous] 的追加延续（同一缓冲、不更短）：是则 [previous] 的内容必为本快照的前缀，O(1) 判断。 */
    fun extends(previous: StreamText): Boolean = previous.buffer === buffer && previous.length <= length

    override fun get(index: Int): Char {
        if (index < 0 || index >= length) throw IndexOutOfBoundsException("index $index, length $length")
        return buffer.chars[index]
    }

    override fun subSequence(startIndex: Int, endIndex: Int): String = substring(startIndex, endIndex)

    fun substring(startIndex: Int, endIndex: Int = length): String {
        require(startIndex in 0..endIndex && endIndex <= length) { "range $startIndex..$endIndex, length $length" }
        return String(buffer.chars, startIndex, endIndex - startIndex)
    }

    /** 物化为 String；同一快照只物化一次。 */
    override fun toString(): String = cached ?: String(buffer.chars, 0, length).also { cached = it }

    fun isBlankText(): Boolean { for (i in 0 until length) if (!buffer.chars[i].isWhitespace()) return false; return true }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StreamText || other.length != length) return false
        if (other.buffer === buffer) return true
        for (i in 0 until length) if (buffer.chars[i] != other.buffer.chars[i]) return false
        return true
    }

    override fun hashCode(): Int {
        var h = length
        // 只取首尾若干字符，避免长文本 hashCode 逐字符扫描。
        val step = if (length <= 64) 1 else length / 32
        var i = 0
        while (i < length) { h = 31 * h + buffer.chars[i].code; i += step }
        return h
    }

    companion object {
        /** 每次返回独立缓冲，避免不同条目抢同一个缓冲尾部。 */
        fun of(text: CharSequence): StreamText = StreamText(Buffer(text.length + 16), 0).append(text)
    }
}

/**
 * 只保留尾部的文本：工具/终端输出在内存里最多保留 [TAIL_LIMIT] 个字符（约 64 KiB），
 * 完整内容仍在 dsh 的 session JSONL 里，「查看完整输出」时按需读取。
 */
object OutputTail {
    const val TAIL_LIMIT = 64 * 1024

    /** 返回追加后的尾部；总长度由调用方另行累计。O(min(tail+delta, limit))。 */
    fun append(tail: String, delta: String, limit: Int = TAIL_LIMIT): String {
        if (delta.isEmpty()) return tail
        if (delta.length >= limit) return delta.substring(delta.length - limit)
        val keepOld = (limit - delta.length).coerceAtMost(tail.length)
        return if (keepOld == tail.length) tail + delta else tail.substring(tail.length - keepOld) + delta
    }

    /** 把一段输入/参数截成头部（参数的开头更有信息量）。 */
    fun head(text: String, limit: Int = TAIL_LIMIT): String = if (text.length <= limit) text else text.substring(0, limit)
}

/** 轨迹里只存摘要，避免每轮把系统提示、完整工具输出、整段回答再复制一份常驻内存。 */
object TrajectorySummary {
    const val LIMIT = 2000
    fun of(text: CharSequence, limit: Int = LIMIT): String =
        if (text.length <= limit) text.toString()
        else text.subSequence(0, limit).toString() + "\n…（共 ${text.length} 字符，完整内容保存在会话日志中）"
}
