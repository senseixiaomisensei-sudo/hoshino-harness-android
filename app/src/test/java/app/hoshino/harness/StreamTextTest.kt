package app.hoshino.harness

import app.hoshino.harness.core.OutputTail
import app.hoshino.harness.core.StreamText
import app.hoshino.harness.core.TrajectorySummary
import org.junit.Assert.*
import org.junit.Test

class StreamTextTest {
    @Test fun appendSharesBufferAndOldSnapshotsStayValid() {
        val a = StreamText.of("hello")
        val b = a.append(" world")
        val c = b.append("!")
        assertEquals("hello", a.toString())
        assertEquals("hello world", b.toString())
        assertEquals("hello world!", c.toString())
        assertTrue(c.extends(a)); assertTrue(c.extends(b)); assertFalse(a.extends(c))
    }

    @Test fun appendingAnOldSnapshotForksInsteadOfCorrupting() {
        val base = StreamText.of("ab")
        val first = base.append("CD")
        val second = base.append("XY") // CAS 重试场景：同一个旧快照被追加两次
        assertEquals("abCD", first.toString())
        assertEquals("abXY", second.toString())
        assertFalse(second.extends(first))
    }

    @Test fun equalityIsByContent() {
        assertEquals(StreamText.of("same"), StreamText.of("sa").append("me"))
        assertNotEquals(StreamText.of("same"), StreamText.of("sane"))
        assertTrue(StreamText.of("  \n").isBlankText()); assertFalse(StreamText.of(" x").isBlankText())
    }

    /** 5 MB 逐 token 追加：线性时间（旧实现 text+delta 为 O(n²)，在这个规模需要数十秒）。 */
    @Test fun fiveMegabytesOfTokensAppendInLinearTime() {
        val token = "0123456789abcdef"
        var text = StreamText.of("")
        val start = System.nanoTime()
        repeat(5 * 1024 * 1024 / token.length) { text = text.append(token) }
        val ms = (System.nanoTime() - start) / 1_000_000
        assertEquals(5 * 1024 * 1024, text.length)
        assertTrue("5MB append took ${ms}ms", ms < 3000)
    }

    @Test fun outputTailKeepsOnlyTheLast64KiB() {
        var tail = ""
        var total = 0L
        val chunk = "x".repeat(1000) + "\n"
        repeat(5 * 1024) { tail = OutputTail.append(tail, chunk); total += chunk.length }
        assertEquals(OutputTail.TAIL_LIMIT, tail.length)
        assertTrue(total > 5_000_000)
        assertEquals("end", OutputTail.append(tail, "end").takeLast(3))
        assertEquals(OutputTail.TAIL_LIMIT, OutputTail.append("", "y".repeat(200_000)).length)
    }

    @Test fun trajectorySummaryIsBounded() {
        val s = TrajectorySummary.of("z".repeat(1_000_000))
        assertTrue(s.length < TrajectorySummary.LIMIT + 100)
        assertTrue(s.contains("1000000"))
        assertEquals("short", TrajectorySummary.of("short"))
    }
}
