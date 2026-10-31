package app.hoshino.harness.phone

import app.hoshino.harness.phone.TypeFlow.Check
import app.hoshino.harness.phone.TypeFlow.Keys
import app.hoshino.harness.phone.TypeFlow.Paste
import dev.hoshino.bridge.phone.shell.ClipStore
import dev.hoshino.bridge.phone.shell.ClipSwap
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** WP-2 typing: write paths, read-back with one retry, clipboard safety and error codes. */
class TypeFlowTest {
    /** A field: [accepts] lists which writes reach it; [readable] false = self-drawn (screen reacts, text unreadable). */
    private class FakeField(
        val accepts: Set<TypeFlow.Method>,
        val paste: Paste = Paste.Done,
        val keys: Keys = Keys.Done,
        val showAfterCalls: Int = 1,
        val readable: Boolean = true,
        val reacts: Boolean = true,
    ) : TypeFlow.Io {
        val calls = mutableListOf<String>()
        var shown: String? = null
        var writes = 0
        override suspend fun setText(text: String): Boolean { calls += "set_text"; return write(TypeFlow.Method.set_text, text, true) }
        override suspend fun paste(text: String): Paste {
            calls += "paste"
            if (paste != Paste.Done) return paste
            write(TypeFlow.Method.paste, text, true); return Paste.Done
        }
        override suspend fun keys(text: String): Keys {
            calls += "keys"
            if (keys != Keys.Done) return keys
            write(TypeFlow.Method.keys, text, true); return Keys.Done
        }
        private fun write(m: TypeFlow.Method, text: String, ok: Boolean): Boolean {
            writes++
            if (m in accepts && writes >= showAfterCalls) shown = text
            return ok
        }
        override suspend fun check(text: String): Check {
            calls += "check"
            return when {
                shown == text && readable -> Check.Shown
                shown == text && reacts -> Check.Unverified
                else -> Check.NotShown
            }
        }
    }

    @Test fun nativeFieldUsesSetTextFirst() = runTest {
        val f = FakeField(setOf(TypeFlow.Method.set_text))
        val r = TypeFlow.run(f, "hello")
        assertTrue(r.ok)
        assertEquals(TypeFlow.Method.set_text, r.method)
        assertEquals(listOf("set_text", "check"), f.calls)
    }

    @Test fun webFieldIsDetectedFirstAndPasted() = runTest {
        // SET_TEXT would "show" in a contenteditable without the page getting input: paste comes first.
        val f = FakeField(setOf(TypeFlow.Method.paste, TypeFlow.Method.set_text))
        val r = TypeFlow.run(f, "你好", web = true)
        assertTrue(r.ok)
        assertEquals(TypeFlow.Method.paste, r.method)
        assertEquals(listOf("paste", "check"), f.calls)
    }

    @Test fun webSetTextIsNeverReportedVerified() = runTest {
        val f = FakeField(setOf(TypeFlow.Method.set_text), paste = Paste.Unavailable, keys = Keys.Unmappable)
        val r = TypeFlow.run(f, "你好", web = true)
        assertTrue(r.ok)
        assertTrue(r.detail, r.detail.startsWith("set_text_unverified"))
    }

    @Test fun readBackFailureRetriesOnce() = runTest {
        // The field only takes the text on the 4th write (2nd round): one automatic retry, then success.
        val f = FakeField(setOf(TypeFlow.Method.set_text), showAfterCalls = 4)
        val r = TypeFlow.run(f, "abc")
        assertTrue(r.ok)
        assertEquals(2, r.attempts)
        assertTrue(r.detail.contains("retried"))
        // Never more than ATTEMPTS rounds.
        val never = FakeField(emptySet())
        val r2 = TypeFlow.run(never, "abc")
        assertFalse(r2.ok)
        assertEquals(TypeFlow.READBACK_MISMATCH, r2.code)
        assertEquals(TypeFlow.ATTEMPTS * 3, never.calls.count { it != "check" })
    }

    @Test fun unreadableClipboardFallsBackToKeysAndIsNotRetried() = runTest {
        val f = FakeField(setOf(TypeFlow.Method.keys), paste = Paste.Unavailable)
        val r = TypeFlow.run(f, "abc", web = true)
        assertTrue(r.ok)
        assertEquals(TypeFlow.Method.keys, r.method)
        assertEquals(1, f.calls.count { it == "paste" })
    }

    @Test fun needsImeWhenNeitherClipboardNorKeysCanBeUsed() = runTest {
        val f = FakeField(emptySet(), paste = Paste.Unavailable, keys = Keys.Unmappable)
        val r = TypeFlow.run(f, "中文", unseen = true, atPoint = true)
        assertFalse(r.ok)
        assertEquals(TypeFlow.NEEDS_IME, r.code)
        assertTrue(r.detail.startsWith("needs_ime:"))
        // Each unavailable path is asked once and not again on the retry.
        assertEquals(listOf("paste", "keys"), f.calls)
    }

    @Test fun pointNotTypedWhenNothingAtThePointReacts() = runTest {
        val f = FakeField(emptySet(), reacts = false)
        val r = TypeFlow.run(f, "hello", unseen = true, atPoint = true)
        assertFalse(r.ok)
        assertEquals(TypeFlow.POINT_NOT_TYPED, r.code)
        assertTrue(r.detail.startsWith("point_not_typed:"))
        assertFalse(f.calls.contains("set_text"))
    }

    @Test fun selfDrawnFieldThatReactsStopsWithoutTypingTwice() = runTest {
        val f = FakeField(setOf(TypeFlow.Method.paste), readable = false)
        val r = TypeFlow.run(f, "hello", unseen = true, atPoint = true)
        assertTrue(r.ok)
        assertTrue(r.detail.startsWith("paste_unverified"))
        assertEquals(1, f.writes)
    }

    @Test fun errorCodesAreStable() {
        assertEquals("no_focus", TypeFlow.NO_FOCUS)
        assertEquals("point_not_typed", TypeFlow.POINT_NOT_TYPED)
        assertEquals("needs_ime", TypeFlow.NEEDS_IME)
    }

    @Test fun appearedComparesNewScreenText() {
        assertTrue(TypeFlow.appeared("hello world", listOf("搜索"), listOf("搜索", "hello world")))
        assertFalse(TypeFlow.appeared("hello", listOf("hello"), listOf("hello")))
        assertFalse(TypeFlow.appeared("   ", emptyList(), listOf("x")))
    }

    // ---- end to end with the helper's clipboard logic ----

    private data class Clip(val label: String, val text: String)

    private class Board(var clip: Clip?, val hidden: Boolean) : ClipStore<Clip> {
        val writes = mutableListOf<String>()
        override fun canRead() = true
        override fun current() = if (hidden) null else clip
        override fun hasClip() = clip != null
        override fun hasDescription() = clip != null
        override fun isOurs(clip: Clip, ours: String) = clip.label == "hoshino" && clip.text == ours
        override fun putOurs(text: String) { writes += text; clip = Clip("hoshino", text) }
        override fun putBack(clip: Clip) { writes += "putBack"; this.clip = clip }
        override fun clear() { writes += "clear"; clip = null }
    }

    /** Io whose paste goes through [ClipSwap] exactly like AndroidPhoneScreen.withBorrowedClip. */
    private class ClipField(val swap: ClipSwap<Clip>, val board: Board) : TypeFlow.Io {
        var field = ""
        var pastes = 0
        override suspend fun setText(text: String) = false
        override suspend fun paste(text: String): Paste {
            val code = swap.set(text)
            if (code != ClipSwap.CLIP_SET) return if (code == ClipSwap.CLIP_FAILED) Paste.Failed else Paste.Unavailable
            try { pastes++; field = board.clip!!.text; return Paste.Done } finally { swap.restore() }
        }
        override suspend fun keys(text: String): Keys { field = text; return Keys.Done }
        override suspend fun check(text: String) = if (field == text) Check.Shown else Check.NotShown
    }

    @Test fun backgroundPasteWithUnreadableClipLeavesItByteForByte() = runTest {
        val user = Clip("notes", "原剪贴板内容 123")
        val board = Board(user, hidden = true)
        val io = ClipField(ClipSwap(board), board)
        repeat(100) {
            val r = TypeFlow.run(io, "abc$it", web = true)
            assertTrue(r.ok)
            assertEquals(TypeFlow.Method.keys, r.method)
        }
        assertSame(user, board.clip)
        assertTrue(board.writes.isEmpty())
        assertEquals(0, io.pastes)
    }

    @Test fun readablePasteRestoresUsersClip() = runTest {
        val user = Clip("notes", "原剪贴板内容 123")
        val board = Board(user, hidden = false)
        val io = ClipField(ClipSwap(board), board)
        repeat(100) { assertTrue(TypeFlow.run(io, "你好$it", web = true).ok) }
        assertSame(user, board.clip)
        assertEquals(100, io.pastes)
        assertNull(board.writes.firstOrNull { it == "clear" })
    }
}
