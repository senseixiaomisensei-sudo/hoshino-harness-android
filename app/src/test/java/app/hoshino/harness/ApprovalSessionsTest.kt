package app.hoshino.harness

import app.hoshino.harness.core.ApprovalBook
import app.hoshino.harness.core.ApprovalMode
import app.hoshino.harness.core.ApprovalModeRules
import app.hoshino.harness.core.ApprovalScope
import app.hoshino.harness.core.ApprovalState
import app.hoshino.harness.core.InboxEffect
import app.hoshino.harness.core.InboxMessage
import app.hoshino.harness.core.InboxMirror
import app.hoshino.harness.core.PermissionTier
import app.hoshino.harness.core.SessionInfo
import app.hoshino.harness.core.SessionExportPaths
import app.hoshino.harness.core.SessionPurge
import app.hoshino.harness.core.SteerState
import app.hoshino.harness.core.SteerTracker
import app.hoshino.harness.ui.drawer.SessionSelection
import app.hoshino.harness.ui.drawer.deleteConfirmation
import app.hoshino.harness.ui.drawer.sessionSections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ApprovalModeTest {
    @Test fun defaultStaysManualAndKeysMatchPlugin() {
        assertEquals(ApprovalMode.Manual, ApprovalMode.Default)
        assertEquals(ApprovalMode.Manual, ApprovalState().effective)
        // Same keys as plugins/hoshino-m2/config.ts APPROVAL_MODES.
        assertEquals(listOf("manual", "rules", "model", "auto", "none"), ApprovalMode.entries.map { it.key })
        assertEquals(ApprovalMode.None, ApprovalMode.of("none"))
        assertNull(ApprovalMode.of("yolo"))
    }

    @Test fun riskConfirmationOnlyWhenTurningNoApprovalOnAndNotAcknowledged() {
        assertTrue(ApprovalModeRules.needsRiskConfirmation(ApprovalMode.None, acknowledged = false))
        assertFalse(ApprovalModeRules.needsRiskConfirmation(ApprovalMode.None, acknowledged = true))
        for (mode in ApprovalMode.entries - ApprovalMode.None) assertFalse(ApprovalModeRules.needsRiskConfirmation(mode, acknowledged = false))
    }

    @Test fun switchingPerSessionDefaultAndBackToDefault() {
        val book = ApprovalBook()
        assertEquals(ApprovalMode.Manual, book.state("a").effective)
        val a = book.set("a", ApprovalMode.None, ApprovalScope.Session)
        assertTrue(a.noApproval); assertEquals(ApprovalMode.Manual, a.defaultMode); assertFalse(a.followsDefault)
        assertEquals(ApprovalMode.Manual, book.state("b").effective)
        // Global default: sessions without an override follow it; the session setting it drops its override.
        val b = book.set("b", ApprovalMode.None, ApprovalScope.Default)
        assertTrue(b.noApproval); assertTrue(b.followsDefault)
        assertTrue(book.state(null).noApproval)
        // An explicit per-session manual still wins over a no-approval default.
        assertEquals(ApprovalMode.Manual, book.set("c", ApprovalMode.Manual, ApprovalScope.Session).effective)
        assertEquals(ApprovalMode.None, book.set("c", ApprovalMode.Manual, ApprovalScope.FollowDefault).effective)
        book.forget(listOf("a"))
        assertTrue(book.state("a").followsDefault)
        // Optimistic local preview agrees with the book.
        val preview = ApprovalModeRules.apply(ApprovalState(), ApprovalMode.None, ApprovalScope.Session)
        assertEquals(ApprovalMode.None, preview.effective); assertEquals(ApprovalMode.Manual, preview.defaultMode)
        assertEquals(ApprovalMode.Rules, ApprovalModeRules.apply(preview.copy(defaultMode = ApprovalMode.Rules), ApprovalMode.Manual, ApprovalScope.FollowDefault).effective)
    }

    @Test fun riskTextExplainsTierBoundariesAndSystemPrompts() {
        assertTrue(ApprovalModeRules.RISK_TEXT.contains("只读"))
        assertTrue(ApprovalModeRules.RISK_TEXT.contains("工作区"))
        assertTrue(ApprovalModeRules.SYSTEM_LIMITS.contains("安装 APK"))
        assertTrue(ApprovalModeRules.SYSTEM_LIMITS.contains("Shizuku"))
    }
}

class SteeringTest {
    private fun user(id: String, text: String = id) = InboxMessage(id, "user", "r-$id", text)

    @Test fun steerIsReceivedThenJoinedWithoutADuplicateBubble() {
        val t = SteerTracker()
        val added = t.onSplice("next-step", 0, 0, listOf(user("m1", "顺便看 README")), null)
        assertEquals(1, added.size); assertTrue(added[0] is InboxEffect.SteerAdded)
        assertEquals(SteerState.Received, t.states["m1"])
        assertEquals(listOf("m1"), t.unconsumed())
        // Claim at the next step boundary: removed without outcome, then the durable user/message appears.
        assertTrue(t.onSplice("next-step", 0, 1, emptyList(), null).isEmpty())
        assertTrue(t.onUserMessage("m1"))
        assertEquals(SteerState.Joined, t.states["m1"])
        assertTrue(t.unconsumed().isEmpty())
        // An ordinary prompt is not a steer and is drawn normally.
        assertFalse(t.onUserMessage("other"))
    }

    @Test fun stopCancelsUnconsumedSteersOnly() {
        val t = SteerTracker()
        t.onSplice("next-step", 0, 0, listOf(user("a")), null)
        t.onSplice("next-step", 0, 1, emptyList(), null); t.onUserMessage("a")
        t.onSplice("next-step", 0, 0, listOf(user("b"), user("c")), null)
        val effects = t.onSplice("next-step", 0, 1, emptyList(), "canceled")
        assertEquals(listOf(InboxEffect.SteerCancelled("b")), effects)
        assertEquals(SteerState.Joined, t.states["a"]); assertEquals(SteerState.Cancelled, t.states["b"]); assertEquals(SteerState.Received, t.states["c"])
        assertEquals(listOf("c"), t.unconsumed())
    }

    @Test fun nextTurnPromptsAndStopNotesAreNotSteers() {
        val t = SteerTracker()
        assertTrue(t.onSplice("next-turn", 0, 0, listOf(user("q")), null).isEmpty())
        val note = InboxMessage("n", SteerTracker.STOP_NOTE_SOURCE, null, "model text", display = "做过：read")
        val effects = t.onSplice("next-step", 0, 0, listOf(note), null)
        assertEquals(listOf(InboxEffect.StopNoteAdded(note)), effects)
        assertTrue(t.unconsumed().isEmpty())
        assertEquals("steer:m", SteerTracker.steerItemId("m")); assertEquals("m", SteerTracker.messageIdOf("steer:m")); assertNull(SteerTracker.messageIdOf("e:3"))
    }

    @Test fun snapshotInboxProjectionSeedsSteersMissingFromTheLoadedPage() {
        // The loaded page started after "old" was inserted: the projection still lists it as pending.
        val t = SteerTracker()
        t.onSplice("next-step", 0, 0, listOf(user("seen")), null)
        val note = InboxMessage("n1", SteerTracker.STOP_NOTE_SOURCE, null, "model text", display = "交代")
        val effects = t.seed(listOf(user("queued")), listOf(user("old"), user("seen"), note))
        // Only what was not drawn yet: the old steer and the note; a next-turn prompt is never a steer.
        assertEquals(listOf(InboxEffect.SteerAdded(user("old")), InboxEffect.StopNoteAdded(note)), effects)
        assertEquals(listOf("old", "seen"), t.unconsumed())
        // The mirror now matches the projection, so a later claim of both is applied in place.
        t.onSplice("next-step", 0, 3, emptyList(), null)
        assertTrue(t.unconsumed().isEmpty())
        assertTrue(t.onUserMessage("old")); assertEquals(SteerState.Joined, t.states["old"])
        // Seeding again (reconnect) draws nothing twice.
        assertTrue(t.seed(emptyList(), listOf(note)).isEmpty())
    }

    @Test fun claimedStopNoteIsDrawnOnceEvenWhenItsSpliceWasNotLoaded() {
        val t = SteerTracker()
        val note = InboxMessage("n2", SteerTracker.STOP_NOTE_SOURCE, null, "model text", display = "交代")
        assertEquals(InboxEffect.StopNoteAdded(note), t.onNoteMessage(note))
        assertNull(t.onNoteMessage(note))
        // Seen through the inbox first, then claimed: still once.
        val other = InboxMessage("n3", SteerTracker.STOP_NOTE_SOURCE, null, "x", display = "y")
        assertEquals(listOf(InboxEffect.StopNoteAdded(other)), t.onSplice("next-step", 0, 0, listOf(other), null))
        assertNull(t.onNoteMessage(other))
        assertNull(t.onNoteMessage(user("plain")))
    }

    @Test fun mirrorClampsSplicesFromAPartialEventTail() {
        val m = InboxMirror()
        assertTrue(m.splice("next-step", 5, 3, emptyList()).isEmpty())
        m.splice("next-step", 9, 0, listOf(user("x"), user("y")))
        assertEquals(listOf("x", "y"), m.pending("next-step").map { it.id })
        assertEquals(listOf("y"), m.splice("next-step", 1, 99, emptyList()).map { it.id })
        assertTrue(m.splice("unknown", 0, 0, listOf(user("z"))).isEmpty())
    }
}

class SessionPurgeTest {
    private fun session(root: File, project: String, id: String, extra: String = ""): File {
        val dir = File(root, "$project/$id").apply { mkdirs() }
        File(dir, "session.v4.jsonl").writeText("{\"type\":\"session\",\"version\":4,\"id\":\"$id\",\"createdAt\":1,\"isSeeded\":false,\"delegationDepth\":0$extra}\n{\"seq\":0}\n")
        return dir
    }

    @Test fun purgeRemovesTargetAndItsSubagentsButKeepsForksAndOthers() {
        val home = Files.createTempDirectory("hh-purge").toFile()
        try {
            val sessions = File(home, "sessions")
            val target = session(sessions, "p1", "s-target")
            val child = session(sessions, "p1", "s-child", ",\"parentSession\":\"s-target\",\"origin\":\"subagent\"")
            val fork = session(sessions, "p1", "s-fork", ",\"parentSession\":\"s-target\",\"isSeededX\":1")
            val other = session(sessions, "p2", "s-other")
            val purge = SessionPurge(File(home, "deleted.txt"), sessions)
            purge.mark(listOf("s-target", "s-local-only", "../bad"))
            assertEquals(setOf("s-target", "s-local-only"), purge.pending())
            val done = purge.purge()
            assertTrue("s-target" in done && "s-child" in done && "s-local-only" in done)
            assertFalse(target.exists()); assertFalse(child.exists())
            assertTrue(fork.exists()); assertTrue(other.exists())
            assertTrue(purge.pending().isEmpty())
            assertTrue(purge.purge().isEmpty())
        } finally { home.deleteRecursively() }
    }

    @Test fun headerFieldsAreReadWithoutJsonLibrary() {
        val line = "{\"type\":\"session\",\"id\":\"abc-1\",\"parentSession\":\"p_2\",\"origin\":\"subagent\"}"
        assertEquals("abc-1", SessionPurge.field(line, "id"))
        assertEquals("p_2", SessionPurge.field(line, "parentSession"))
        assertNull(SessionPurge.field(line, "cwd"))
    }
}

class SessionSectionsTest {
    private fun s(id: String, pinnedAt: Long = 0, archived: Boolean = false, running: Boolean = false) =
        SessionInfo(id, "标题$id", "ws", "standard", PermissionTier.WorkspaceWrite, 1, 1, running = running, archived = archived, pinnedAt = pinnedAt)

    @Test fun pinnedFirstNewestPinFirstEachSessionInOneSection() {
        val rows = listOf(s("a"), s("b", pinnedAt = 10), s("c", pinnedAt = 20, running = true), s("d", archived = true, pinnedAt = 5))
        val sec = sessionSections(rows)
        assertEquals(listOf("c", "b"), sec.pinned.map { it.id })
        assertEquals(listOf("a"), sec.active.map { it.id })
        assertEquals(listOf("d"), sec.archived.map { it.id })
    }

    @Test fun multiSelectToggleSelectAllAndRetain() {
        var sel = SessionSelection().start("a")
        assertTrue(sel.active); assertEquals(setOf("a"), sel.ids)
        sel = sel.toggle("b"); assertEquals(setOf("a", "b"), sel.ids)
        sel = sel.selectAll(listOf("c", "d")); assertEquals(setOf("a", "b", "c", "d"), sel.ids)
        sel = sel.retain(setOf("b", "d")); assertEquals(setOf("b", "d"), sel.ids)
        sel = sel.toggle("b").toggle("d"); assertFalse(sel.active)
        assertFalse(SessionSelection().start("x").retain(emptySet()).active)
    }

    @Test fun deleteConfirmationNamesRunningAndCurrent() {
        val one = deleteConfirmation(listOf(s("a", running = true)), currentId = "a")
        assertTrue(one.contains("「标题a」")); assertTrue(one.contains("会先停止")); assertTrue(one.contains("切到新会话")); assertTrue(one.contains("工作区里的文件不会被删除"))
        val many = deleteConfirmation(listOf(s("a"), s("b", running = true), s("c")), currentId = "z")
        assertTrue(many.contains("3 个会话")); assertTrue(many.contains("其中 1 个正在运行")); assertFalse(many.contains("切到新会话"))
    }
}

class SessionExportPathsTest {
    @Test fun onlySingleFileNamesUnderExportsAreAccepted() {
        assertTrue(SessionExportPaths.safeRelative("exports/1730000000000-会话-abcd1234.md"))
        assertFalse(SessionExportPaths.safeRelative("exports/../secret"))
        assertFalse(SessionExportPaths.safeRelative("exports/.."))
        assertFalse(SessionExportPaths.safeRelative("exports/."))
        assertFalse(SessionExportPaths.safeRelative("exports/a\\b"))
        assertFalse(SessionExportPaths.safeRelative("other/a.md"))
        assertFalse(SessionExportPaths.safeRelative("exports/"))
        assertEquals("a_b.md", SessionExportPaths.safeName("a/b.md", "x.md"))
        assertEquals("x.md", SessionExportPaths.safeName("..", "x.md"))
        assertEquals("x.md", SessionExportPaths.safeName("  ", "x.md"))
    }
}
