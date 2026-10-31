// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package dev.hoshino.bridge.phone.shell

import dev.hoshino.bridge.phone.shell.ShellClip.Now
import dev.hoshino.bridge.phone.shell.ShellClip.Read
import dev.hoshino.bridge.phone.shell.ShellClip.Restore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** WP-2: borrowing the clipboard for a paste never treats "unreadable" as "empty" and only restores over our own clip. */
class ShellClipTest {
    @Test fun readDistinguishesEmptyFromUnreadable() {
        assertEquals(Read.Has, ShellClip.read(clipPresent = true, hasPrimaryClip = true, descriptionPresent = true))
        assertEquals(Read.Empty, ShellClip.read(clipPresent = false, hasPrimaryClip = false, descriptionPresent = false))
        // Null clip while the system says something is there: unreadable, not empty.
        assertEquals(Read.Unreadable, ShellClip.read(clipPresent = false, hasPrimaryClip = true, descriptionPresent = false))
        assertEquals(Read.Unreadable, ShellClip.read(clipPresent = false, hasPrimaryClip = false, descriptionPresent = true))
    }

    @Test fun restoreOnlyTouchesOursOrEmpty() {
        assertEquals(Restore.PutBack, ShellClip.restore(priorEmpty = false, now = Now.Ours))
        assertEquals(Restore.Clear, ShellClip.restore(priorEmpty = true, now = Now.Ours))
        for (prior in listOf(true, false)) {
            assertEquals(Restore.Leave, ShellClip.restore(prior, Now.Other))
            assertEquals(Restore.Leave, ShellClip.restore(prior, Now.Unreadable))
        }
    }

    @Test fun clearedByOthersGetsUsersClipBack() {
        assertEquals(Restore.PutBack, ShellClip.restore(priorEmpty = false, now = Now.Empty))
        assertEquals(Restore.Leave, ShellClip.restore(priorEmpty = true, now = Now.Empty))
    }
}

/** In-memory clipboard as the shell would see it. [hiddenRead] simulates a read the system silently blocks. */
class FakeClipStore(var clip: FakeClip?) : ClipStore<FakeClip> {
    var readable = true
    var hiddenRead = false
    val writes = mutableListOf<String>()
    override fun canRead() = readable
    override fun current(): FakeClip? = if (hiddenRead) null else clip
    override fun hasClip() = clip != null
    override fun hasDescription() = clip != null
    override fun isOurs(clip: FakeClip, ours: String) = clip.label == "hoshino" && clip.text == ours
    override fun putOurs(text: String) { writes += "ours"; clip = FakeClip("hoshino", text) }
    override fun putBack(clip: FakeClip) { writes += "putBack"; this.clip = clip }
    override fun clear() { writes += "clear"; clip = null }
}

data class FakeClip(val label: String, val text: String)

class ClipSwapTest {
    private val user = FakeClip("notes", "用户的原剪贴板 ✓ secret-ish")

    @Test fun unreadableClipboardIsNeverChanged() {
        // No background-read permission / app-op denied.
        val noPerm = FakeClipStore(user).apply { readable = false }
        val a = ClipSwap(noPerm)
        assertEquals(ClipSwap.CLIP_UNREADABLE, a.set("hello"))
        assertSame(user, noPerm.clip)
        assertTrue(noPerm.writes.isEmpty())
        assertFalse(a.isHolding)
        assertNull(a.restore())
        assertTrue(noPerm.writes.isEmpty())

        // Read silently blocked: null clip although the clipboard says it has one.
        val blocked = FakeClipStore(user).apply { hiddenRead = true }
        val b = ClipSwap(blocked)
        assertEquals(ClipSwap.CLIP_UNREADABLE, b.set("hello"))
        assertSame(user, blocked.clip)
        assertTrue(blocked.writes.isEmpty())
        assertNull(b.restore())
        assertTrue(blocked.writes.isEmpty())
    }

    @Test fun usersClipComesBackUnchanged() {
        val store = FakeClipStore(user)
        val swap = ClipSwap(store)
        assertEquals(ClipSwap.CLIP_SET, swap.set("hello"))
        assertEquals(FakeClip("hoshino", "hello"), store.clip)
        assertEquals(ShellClip.Restore.PutBack, swap.restore())
        assertSame(user, store.clip)
        // Idempotent: a second restore (e.g. the watchdog) changes nothing.
        assertNull(swap.restore())
        assertEquals(listOf("ours", "putBack"), store.writes)
    }

    @Test fun emptyClipboardIsClearedAgain() {
        val store = FakeClipStore(null)
        val swap = ClipSwap(store)
        assertEquals(ClipSwap.CLIP_SET, swap.set("x"))
        assertEquals(ShellClip.Restore.Clear, swap.restore())
        assertNull(store.clip)
    }

    @Test fun userCopiedSomethingElseMeanwhileIsLeftAlone() {
        val store = FakeClipStore(user)
        val swap = ClipSwap(store)
        swap.set("hello")
        val fresh = FakeClip("browser", "user copied this during the paste")
        store.clip = fresh
        assertEquals(ShellClip.Restore.Leave, swap.restore())
        assertSame(fresh, store.clip)
    }

    @Test fun secondSetKeepsTheOriginalUsersClip() {
        val store = FakeClipStore(user)
        val swap = ClipSwap(store)
        swap.set("first")
        swap.set("second")
        assertEquals(FakeClip("hoshino", "second"), store.clip)
        assertEquals(ShellClip.Restore.PutBack, swap.restore())
        assertSame(user, store.clip)
    }

    @Test fun watchdogRestoresOnlyTheSwapItWasArmedFor() {
        val store = FakeClipStore(user)
        val swap = ClipSwap(store)
        swap.set("a")
        val first = swap.generation
        swap.restore()
        swap.set("b")
        // The watchdog of the first borrow fires late: it must not undo the second one.
        assertNull(swap.restoreIfStill(first))
        assertEquals(FakeClip("hoshino", "b"), store.clip)
        // The app crashed before restoring the second borrow: its own watchdog gives the user's clip back.
        assertEquals(ShellClip.Restore.PutBack, swap.restoreIfStill(swap.generation))
        assertSame(user, store.clip)
    }

    @Test fun resultCodesAreNeverZero() {
        // An older helper answers unknown transactions with an empty reply, which the proxy reads as 0.
        for (c in listOf(ClipSwap.CLIP_SET, ClipSwap.CLIP_UNREADABLE, ClipSwap.CLIP_FAILED, ShellText.TEXT_TYPED, ShellText.TEXT_UNMAPPABLE, ShellText.TEXT_FAILED)) {
            assertTrue(c != 0)
        }
    }

    @Test fun keyTypingOnlyAcceptsPrintableText() {
        assertTrue(ShellText.typeable("hello World 123 @#!"))
        assertTrue(ShellText.typeable("中文")) // decided by KeyCharacterMap on the device (refused there as a whole)
        assertFalse(ShellText.typeable(""))
        assertFalse(ShellText.typeable("line\nbreak"))
        assertFalse(ShellText.typeable("tab\there"))
        assertFalse(ShellText.typeable("emoji \uD83D\uDE00"))
        assertFalse(ShellText.typeable("x".repeat(ShellText.MAX_CHARS + 1)))
    }
}
