package app.hoshino.harness

import app.hoshino.harness.core.Attachment
import app.hoshino.harness.core.AttachmentKind
import app.hoshino.harness.core.ChatItem
import app.hoshino.harness.core.HarnessEvent
import app.hoshino.harness.core.InboxEffect
import app.hoshino.harness.core.InboxMessage
import app.hoshino.harness.core.SessionStore
import app.hoshino.harness.core.SteerState
import app.hoshino.harness.core.SteerTracker
import app.hoshino.harness.core.SteerWithdrawal
import app.hoshino.harness.core.StreamRetry
import app.hoshino.harness.core.UnavailableHarnessClient
import app.hoshino.harness.ui.chat.steerEditable
import app.hoshino.harness.ui.chat.steerWithdrawalMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** m2.10 T1：插话撤回 / 编辑（对照 Mika 0.1.27 9bd29de）。撤回走 dsh `session/updateQueue remove`，竞态由内核裁决。 */
class SteerWithdrawTest {
    private fun user(id: String, text: String = id) = InboxMessage(id, "user", "r-$id", text)

    @Test fun withdrawnSteerIsWithdrawnNotStopped() {
        val t = SteerTracker()
        t.onSplice("next-step", 0, 0, listOf(user("a"), user("b")), null)
        assertTrue(t.canWithdraw("a"))
        assertTrue(t.beginWithdraw("a"))
        // dsh inbox.remove → splice(index, 1) with outcome=canceled
        val effects = t.onSplice("next-step", 0, 1, emptyList(), "canceled")
        assertEquals(listOf(InboxEffect.SteerWithdrawn("a")), effects)
        assertEquals(SteerState.Withdrawn, t.states["a"])
        assertFalse(t.canWithdraw("a")); assertFalse(t.beginWithdraw("a"))
        assertEquals(setOf("a"), t.withdrawnIds())
        // A later stop still cancels the other one as 「已停止」.
        assertEquals(listOf(InboxEffect.SteerCancelled("b")), t.onSplice("next-step", 0, 1, emptyList(), "canceled"))
        assertEquals(SteerState.Cancelled, t.states["b"])
    }

    @Test fun removalEventBeforeTheRpcResponseIsStillWithdrawn() {
        val t = SteerTracker()
        t.onSplice("next-step", 0, 0, listOf(user("a")), null)
        assertTrue(t.beginWithdraw("a"))
        t.onSplice("next-step", 0, 1, emptyList(), "canceled")
        // The RPC then fails (e.g. the socket dropped after the kernel applied it): the withdrawn state stays.
        t.withdrawFailed("a")
        assertEquals(SteerState.Withdrawn, t.states["a"])
    }

    @Test fun modelTookItFirstSoTheWithdrawLosesTheRace() {
        val t = SteerTracker()
        t.onSplice("next-step", 0, 0, listOf(user("a")), null)
        assertTrue(t.beginWithdraw("a"))
        // The claim (no outcome) and its user/message win in the kernel; updateQueue answers queue-item-not-found.
        assertTrue(t.onSplice("next-step", 0, 1, emptyList(), null).isEmpty())
        assertTrue(t.onUserMessage("a"))
        t.withdrawFailed("a")
        assertEquals(SteerState.Joined, t.states["a"])
        assertFalse(t.canWithdraw("a"))
        // A later canceled splice can never relabel a joined steer.
        assertTrue(t.withdrawnIds().isEmpty())
    }

    @Test fun onlyReceivedPendingSteersCanBeWithdrawn() {
        val t = SteerTracker()
        assertFalse(t.canWithdraw("ghost"))
        t.onSplice("next-turn", 0, 0, listOf(user("queued")), null)
        assertFalse("a next-turn prompt is not a steer", t.canWithdraw("queued"))
        t.onSplice("next-step", 0, 0, listOf(user("s")), null)
        t.onSplice("next-step", 0, 1, emptyList(), "canceled") // stopped
        assertFalse(t.canWithdraw("s"))
        assertTrue(SteerState.Received.withdrawable)
        for (s in SteerState.entries - SteerState.Received) assertFalse(s.name, s.withdrawable)
    }

    @Test fun replayAfterRestartKeepsTheWithdrawnState() {
        // Persisted withdrawn ids seed a fresh tracker; replaying insert + canceled removal gives 「已撤回」 again.
        val t = SteerTracker(withdrawn = setOf("a"))
        val added = t.onSplice("next-step", 0, 0, listOf(user("a"), user("b")), null)
        assertEquals(2, added.size)
        assertEquals(listOf(InboxEffect.SteerWithdrawn("a")), t.onSplice("next-step", 0, 1, emptyList(), "canceled"))
        assertEquals(listOf(InboxEffect.SteerCancelled("b")), t.onSplice("next-step", 0, 1, emptyList(), "canceled"))
    }

    @Test fun editIsOfferedOnlyWithAnEmptyComposerAndATextOnlySteer() {
        val steer = ChatItem.User("steer:a", "改成只看 README", emptyList(), steer = true)
        assertTrue(steerEditable("", false, steer))
        assertTrue(steerEditable("   ", false, steer))
        assertFalse("draft in the composer", steerEditable("草稿", false, steer))
        assertFalse("attachments waiting in the composer", steerEditable("", true, steer))
        val withFile = steer.copy(attachments = listOf(Attachment("f", "a.png", AttachmentKind.Image, "1 KB")))
        assertFalse("the original has an attachment", steerEditable("", false, withFile))
        assertFalse("not a steer", steerEditable("", false, steer.copy(steer = false)))
    }

    @Test fun withdrawOutcomesAreWordedForTheUser() {
        assertNull(steerWithdrawalMessage(SteerWithdrawal.Withdrawn))
        assertTrue(steerWithdrawalMessage(SteerWithdrawal.AlreadyTaken)!!.contains("交给模型"))
        assertTrue(steerWithdrawalMessage(SteerWithdrawal.Failed("内核未连接"))!!.contains("内核未连接"))
        assertTrue(SteerTracker.messageIdOf(SteerTracker.steerItemId("m")) == "m")
    }
}

/** ItemDiscarded：撤回的插话气泡从对话里去掉；断流重试前已流出的半句清掉、重试的回答不重复（m2.10 T1/T4）。 */
class ItemDiscardTest {
    private class TestClient : UnavailableHarnessClient()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun tearDown() = scope.cancel()
    private fun store() = SessionStore(TestClient(), scope, coalesceMs = 60_000)
    private fun text(cs: CharSequence) = StringBuilder().append(cs).toString()

    @Test fun retryDoesNotRepeatTheHalfReply() {
        val s = store(); val sid = "s"
        s.dispatch(HarnessEvent.TurnStarted(sid, "e:1"))
        s.dispatch(HarnessEvent.UserMessage(sid, "u", "讲一段", emptyList()))
        // attempt 1 streams half a sentence (and some thinking), then the upstream drops it
        s.dispatch(HarnessEvent.ThinkingDelta(sid, "r:a:1", "想一想"))
        s.dispatch(HarnessEvent.AssistantDelta(sid, "a:1", "这是一段被上"))
        s.dispatch(HarnessEvent.AssistantDelta(sid, "a:1", "游掐断的半句回")) // still buffered when the discard arrives
        for (id in StreamRetry.discardedItems("a:1", hasText = true, hasThinking = true)) s.dispatch(HarnessEvent.ItemDiscarded(sid, id))
        // attempt 2 completes
        s.dispatch(HarnessEvent.AssistantDelta(sid, "a:2", "断流重试后的完整回答。"))
        s.flushNow()
        s.dispatch(HarnessEvent.AssistantDone(sid, "a:2"))
        val items = s.state(sid).value.turns.flatMap { it.items }
        assertEquals(listOf("u", "a:2"), items.map { it.id })
        assertEquals("断流重试后的完整回答。", text((items[1] as ChatItem.Assistant).content))
        assertTrue(items.none { it is ChatItem.Assistant && text(it.content).contains("半句") })
    }

    @Test fun discardedIdsMatchWhatTheClientDrew() {
        assertEquals(listOf("a:7", "r:a:7", "ws:a:7:0", "ws:a:7:1"), StreamRetry.discardedItems("a:7", true, true, 2))
        assertTrue(StreamRetry.discardedItems("a:7", false, false).isEmpty())
    }

    @Test fun withdrawnSteerBubbleIsRemovedAndLaterItemsStayInTheTurn() {
        val s = store(); val sid = "s"
        s.dispatch(HarnessEvent.TurnStarted(sid, "e:1"))
        s.dispatch(HarnessEvent.UserMessage(sid, "u", "做个任务", emptyList()))
        s.dispatch(HarnessEvent.AssistantDelta(sid, "a:1", "开始"))
        s.flushNow()
        s.dispatch(HarnessEvent.UserMessage(sid, "steer:m", "这句要撤回", emptyList(), steer = true))
        s.dispatch(HarnessEvent.AssistantDelta(sid, "a:2", "继续干活"))
        s.flushNow()
        assertEquals(2, s.state(sid).value.turns.size) // the steer opened its own group
        s.dispatch(HarnessEvent.ItemDiscarded(sid, "steer:m"))
        val turns = s.state(sid).value.turns
        assertEquals(1, turns.size)
        assertEquals("u", turns[0].key)
        assertEquals(listOf("u", "a:1", "a:2"), turns[0].items.map { it.id })
        // Discarding something that is not there is a no-op.
        val before = s.state(sid).value
        s.dispatch(HarnessEvent.ItemDiscarded(sid, "steer:none"))
        assertEquals(before.turns.map { it.key }, s.state(sid).value.turns.map { it.key })
    }

    @Test fun aWithdrawnSteerAloneInItsGroupLeavesNothingBehind() {
        val s = store(); val sid = "s"
        s.dispatch(HarnessEvent.UserMessage(sid, "u", "任务", emptyList()))
        s.dispatch(HarnessEvent.UserMessage(sid, "steer:m", "插话", emptyList(), steer = true))
        s.dispatch(HarnessEvent.ItemDiscarded(sid, "steer:m"))
        assertEquals(listOf("u"), s.state(sid).value.turns.flatMap { it.items }.map { it.id })
    }
}
