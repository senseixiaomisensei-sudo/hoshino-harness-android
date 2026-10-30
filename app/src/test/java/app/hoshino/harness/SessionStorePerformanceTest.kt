package app.hoshino.harness

import app.hoshino.harness.core.*
import app.hoshino.harness.ui.chat.ChatRow
import app.hoshino.harness.ui.chat.TurnPresenter
import app.hoshino.harness.ui.chat.flattenRowsReversed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** 内存与重算次数的上限断言（M2.7）。全部是纯 JVM 测试，不依赖 Android/Compose 运行时。 */
class SessionStorePerformanceTest {
    private class TestClient(override val sessionRestoreSupported: Boolean = true) : UnavailableHarnessClient() {
        val released = mutableListOf<String>()
        val retained = mutableListOf<String>()
        override fun releaseSession(sessionId: String) { synchronized(released) { released += sessionId } }
        override fun retainSession(sessionId: String) { synchronized(retained) { retained += sessionId } }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun tearDown() = scope.cancel()

    /** coalesceMs 设得很大：由测试显式 flushNow() 模拟「每帧提交一次」。 */
    private fun store(client: HarnessClient = TestClient(), retained: Int = 4) = SessionStore(client, scope, maxRetainedSessions = retained, coalesceMs = 60_000)

    private fun SessionStore.turn(sid: String, n: Int, toolOutput: String = "ok", answer: String = "answer $n") {
        dispatch(HarnessEvent.TurnStarted(sid, "e:turn$n", at = 1_000L * (n + 1)))
        dispatch(HarnessEvent.UserMessage(sid, "u$n", "question $n", emptyList()))
        dispatch(HarnessEvent.ThinkingDelta(sid, "r$n", "thinking about $n"))
        dispatch(HarnessEvent.ThinkingDone(sid, "r$n", 1200))
        dispatch(HarnessEvent.ToolCallStarted(sid, "tool$n", "read", "read", "{\"path\":\"f$n\"}", ToolKind.Generic, callId = "call$n"))
        dispatch(HarnessEvent.ToolCallOutput(sid, "tool$n", toolOutput))
        flushNow()
        dispatch(HarnessEvent.ToolCallFinished(sid, "tool$n", ToolStatus.Success))
        dispatch(HarnessEvent.AssistantDelta(sid, "a$n", answer))
        flushNow()
        dispatch(HarnessEvent.AssistantDone(sid, "a$n"))
        dispatch(HarnessEvent.UsageReported(sid, "e:turn$n", Usage(10, 20, 0, 0, 100)))
        dispatch(HarnessEvent.TurnFinished(sid, "e:turn$n", cancelled = false))
    }

    @Test fun streamingFiveMegabyteToolOutputKeepsOnlyTheTail() {
        val s = store()
        val sid = "s"
        s.dispatch(HarnessEvent.TurnStarted(sid, "e:1"))
        s.dispatch(HarnessEvent.UserMessage(sid, "u", "run", emptyList()))
        s.dispatch(HarnessEvent.ToolCallStarted(sid, "t", "bash", "yes", "{}", ToolKind.Bash, callId = "c1"))
        val chunk = "line of terminal output 0123456789\n"
        var total = 0L
        val start = System.nanoTime()
        while (total < 5L * 1024 * 1024) {
            repeat(64) { s.dispatch(HarnessEvent.ToolCallOutput(sid, "t", chunk)); total += chunk.length }
            s.flushNow()
        }
        val ms = (System.nanoTime() - start) / 1_000_000
        s.dispatch(HarnessEvent.ToolCallFinished(sid, "t", ToolStatus.Success, 0))
        val term = s.state(sid).value.items.filterIsInstance<ChatItem.Terminal>().single()
        assertEquals(total, term.outputLength)
        assertTrue("tail ${term.output.length}", term.output.length <= OutputTail.TAIL_LIMIT)
        assertTrue(term.outputTruncated)
        assertEquals("c1", term.callId)
        assertTrue("5MB tool stream reduced in ${ms}ms", ms < 5000)
        // 轨迹只存摘要
        assertTrue(s.state(sid).value.trajectory.all { it.payload.length <= TrajectorySummary.LIMIT + 100 })
    }

    @Test fun streamingFiveMegabyteAnswerIsLinearAndOnlyReplacesTheCurrentTurn() {
        val s = store()
        val sid = "s"
        repeat(50) { s.turn(sid, it) }
        val before = s.state(sid).value.turns
        s.dispatch(HarnessEvent.TurnStarted(sid, "e:live"))
        s.dispatch(HarnessEvent.UserMessage(sid, "u-live", "write a lot", emptyList()))
        val token = "word ".repeat(20)
        var total = 0
        val start = System.nanoTime()
        var flushes = 0
        while (total < 5 * 1024 * 1024) {
            repeat(50) { s.dispatch(HarnessEvent.AssistantDelta(sid, "a-live", token)); total += token.length }
            s.flushNow(); flushes++
            if (flushes % 50 == 0) {
                val now = s.state(sid).value.turns
                // 已完成的 50 轮在每次提交后都还是同一实例：只有最后一组被替换
                for (i in before.indices) assertSame(before[i], now[i])
            }
        }
        val ms = (System.nanoTime() - start) / 1_000_000
        val answer = s.state(sid).value.turns.last().items.filterIsInstance<ChatItem.Assistant>().single()
        assertEquals(total, answer.content.length)
        assertTrue("5MB answer reduced in ${ms}ms over $flushes flushes", ms < 5000)
    }

    @Test fun twoHundredTurnSessionStaysBoundedAndPresenterOnlyRecomputesTheLiveTurn() {
        val s = store()
        val sid = "long"
        val bigOutput = "o".repeat(200_000)
        repeat(200) { s.turn(sid, it, toolOutput = bigOutput) }
        val state = s.state(sid).value
        assertEquals(200, state.turns.size)
        // 内存上限：每个工具条目只留 64 KiB 尾部；轨迹条数与每条长度都有上限。
        val retainedChars = state.items.sumOf { item ->
            when (item) {
                is ChatItem.Tool -> item.output.length + item.input.length
                is ChatItem.Terminal -> item.output.length
                is ChatItem.Assistant -> item.content.length
                is ChatItem.Thinking -> item.content.length
                is ChatItem.User -> item.text.length
                else -> 0
            }.toLong()
        } + state.trajectory.sumOf { it.payload.length.toLong() }
        assertTrue("retained $retainedChars chars", retainedChars <= 200L * (OutputTail.TAIL_LIMIT + 1_000) + 1000L * (TrajectorySummary.LIMIT + 100))
        assertTrue(state.trajectory.size <= 1000)
        assertTrue(state.items.filterIsInstance<ChatItem.Tool>().all { it.outputLength == 200_000L && it.output.length == OutputTail.TAIL_LIMIT })

        val presenter = TurnPresenter()
        presenter.present(s.state(sid).value, collapseApprovals = true)
        assertEquals(200L, presenter.turnsComputed)
        // 第 201 轮流式 300 帧
        s.dispatch(HarnessEvent.TurnStarted(sid, "e:live"))
        s.dispatch(HarnessEvent.UserMessage(sid, "u-live", "go", emptyList()))
        presenter.present(s.state(sid).value, collapseApprovals = true)
        val afterNewTurn = presenter.turnsComputed
        assertEquals(201L, afterNewTurn)
        repeat(300) {
            s.dispatch(HarnessEvent.AssistantDelta(sid, "a-live", "token $it "))
            s.flushNow()
            presenter.present(s.state(sid).value, collapseApprovals = true)
        }
        assertEquals("only the live turn is recomputed per frame", afterNewTurn + 300, presenter.turnsComputed)
        // 行对象：已完成轮次在两次展示之间是同一实例（Compose 跳过重组的前提）
        val rowsA = flattenRowsReversed(presenter.present(s.state(sid).value, true))
        s.dispatch(HarnessEvent.AssistantDelta(sid, "a-live", "more")); s.flushNow()
        val rowsB = flattenRowsReversed(presenter.present(s.state(sid).value, true))
        val changed = rowsA.zip(rowsB).count { (a, b) -> a !== b }
        assertEquals("only the streaming answer row changes", 1, changed)
        assertEquals(rowsA.size, rowsB.size)
        assertTrue(rowsA.map { it.key }.toSet().size == rowsA.size)
    }

    @Test fun lruKeepsRecentSessionsAndNeverEvictsPinnedOnes() {
        val client = TestClient()
        val s = store(client, retained = 4)
        // 会话 run 正在运行：不得淘汰
        s.dispatch(HarnessEvent.TurnStarted("run", "e:1"))
        s.dispatch(HarnessEvent.UserMessage("run", "u", "long task", emptyList()))
        for (i in 1..6) s.turn("s$i", 1)
        val kept = s.retainedSessions()
        assertTrue("kept $kept", kept.size <= 4)
        assertTrue("running session kept: $kept", "run" in kept)
        assertTrue("most recent kept: $kept", "s6" in kept)
        assertTrue(client.released.containsAll(listOf("s1", "s2")))
        assertFalse("run" in client.released)
        // 切回被淘汰的会话：请求客户端重新订阅以从持久化重放
        s.state("s1")
        assertTrue(client.retained.contains("s1"))
        // 排队消息也不得因淘汰丢失
        s.enqueue("q", QueuedMessage("m", "later", emptyList()))
        for (i in 7..12) s.turn("s$i", 1)
        assertEquals(1, s.state("q").value.queued.size)
    }

    @Test fun clientsWithoutRestoreKeepEverything() {
        val client = TestClient(sessionRestoreSupported = false)
        val s = store(client, retained = 2)
        for (i in 1..6) s.turn("s$i", 1)
        assertEquals(6, s.retainedSessions().size)
        assertTrue(client.released.isEmpty())
    }

    @Test fun deliveredFilesFollowTheirTurnNotTheBottom() {
        val s = store()
        val sid = "f"
        repeat(3) { s.turn(sid, it) }
        // 第 1 轮（e:turn0）交付的新记录：带 turnId
        s.dispatch(HarnessEvent.FileDelivered(sid, DeliveredFile("d1", sid, "/root/workspace/report.md", "report.md", 10, turnId = "e:turn0", deliveredAt = 99_999)))
        // 旧记录：没有 turnId，按时间归到开始时间不晚于它的最近一轮（各轮开始于 1000/2000/3000ms，2500 → 第 2 轮）
        s.dispatch(HarnessEvent.FileDelivered(sid, DeliveredFile("d2", sid, "/root/workspace/old.png", "old.png", 10, deliveredAt = 2_500)))
        // 完全没有时间信息的旧记录 → 最后一轮
        s.dispatch(HarnessEvent.FileDelivered(sid, DeliveredFile("d3", sid, "/root/workspace/x.txt", "x.txt", 10)))
        val turns = TurnPresenter().present(s.state(sid).value, collapseApprovals = true)
        assertEquals(listOf("d1"), turns[0].files.map { it.id })
        assertEquals(listOf("d2"), turns[1].files.map { it.id })
        assertEquals(listOf("d3"), turns[2].files.map { it.id })
        // 文件卡紧跟该轮回答、在用量之前；列表底部（反向列表首行）不是文件卡
        val firstTurnRows = turns[0].rows
        val filesIdx = firstTurnRows.indexOfFirst { it is ChatRow.FilesRow }
        assertTrue(filesIdx > 0)
        assertTrue((firstTurnRows[filesIdx - 1] as ChatRow.ItemRow).item is ChatItem.Assistant)
        assertTrue((firstTurnRows[filesIdx + 1] as ChatRow.ItemRow).item is ChatItem.UsageFooter)
        val reversed = flattenRowsReversed(turns)
        assertFalse(reversed.first() is ChatRow.FilesRow && (reversed.first() as ChatRow.FilesRow).files.any { it.id == "d1" })
        // 旧记录早于所有轮次 → 第一轮
        s.dispatch(HarnessEvent.FileDelivered(sid, DeliveredFile("d0", sid, "/root/workspace/a", "a", 1, deliveredAt = 1)))
        assertEquals(listOf("d1", "d0"), TurnPresenter().present(s.state(sid).value, true)[0].files.map { it.id })
        // 重复投递（重放）不重复
        s.dispatch(HarnessEvent.FileDelivered(sid, DeliveredFile("d1", sid, "/root/workspace/report.md", "report.md", 10, turnId = "e:turn0", deliveredAt = 99_999)))
        assertEquals(4, s.state(sid).value.deliveries.size)
    }

    @Test fun deliveriesPublishedBeforeTheirTurnReplaysArePlacedOnceTheTurnExists() {
        val s = store()
        val sid = "r"
        s.dispatch(HarnessEvent.FileDelivered(sid, DeliveredFile("d", sid, "/root/workspace/a.md", "a.md", 1, turnId = "e:turn1")))
        repeat(3) { s.turn(sid, it) }
        val turns = TurnPresenter().present(s.state(sid).value, true)
        assertEquals(listOf("d"), turns[1].files.map { it.id })
    }

    @Test fun resolvedApprovalsCollapseIntoTheProcessGroup() {
        val s = store()
        val sid = "a"
        s.dispatch(HarnessEvent.TurnStarted(sid, "e:1"))
        s.dispatch(HarnessEvent.UserMessage(sid, "u", "write the report", emptyList()))
        s.dispatch(HarnessEvent.ToolCallStarted(sid, "t", "write", "write", "{}", ToolKind.Edit))
        s.dispatch(HarnessEvent.ApprovalRequested(sid, "ap", "工具执行审批", "/root/workspace/report.md\nnew content", "write"))
        s.dispatch(HarnessEvent.ApprovalRequested(sid, "ap2", "工具执行审批", "{\"command\":\"rm\"}", "bash"))
        var turn = TurnPresenter().present(s.state(sid).value, true).single()
        assertEquals(listOf("ap", "ap2"), turn.actions.map { it.id })
        assertTrue((turn.rows.first { it is ChatRow.ProcessRow } as ChatRow.ProcessRow).waiting)
        s.dispatch(HarnessEvent.ApprovalResolved(sid, "ap", ApprovalDecision.AllowOnce))
        turn = TurnPresenter().present(s.state(sid).value, true).single()
        assertEquals("handled approval leaves the conversation", listOf("ap2"), turn.actions.map { it.id })
        val collapsed = turn.process.filterIsInstance<ChatItem.Approval>().single()
        assertEquals("已允许 · write report.md", TurnPresenter.approvalLine(collapsed))
        // 旧样式：卡片留在对话里
        val legacy = TurnPresenter().present(s.state(sid).value, false).single()
        assertEquals(listOf("ap", "ap2"), legacy.actions.map { it.id })
        assertTrue(legacy.process.none { it is ChatItem.Approval })
        s.dispatch(HarnessEvent.ApprovalResolved(sid, "ap2", ApprovalDecision.Deny))
        val denied = TurnPresenter().present(s.state(sid).value, true).single().process.filterIsInstance<ChatItem.Approval>().last()
        assertEquals("已拒绝 · bash", TurnPresenter.approvalLine(denied))
        assertFalse((TurnPresenter().present(s.state(sid).value, true).single().rows.first { it is ChatRow.ProcessRow } as ChatRow.ProcessRow).waiting)
    }

    @Test fun cancelledTurnClosesRunningItemsWithoutTouchingFinishedTurns() {
        val s = store()
        val sid = "c"
        repeat(3) { s.turn(sid, it) }
        val finished = s.state(sid).value.turns
        s.dispatch(HarnessEvent.TurnStarted(sid, "e:x"))
        s.dispatch(HarnessEvent.UserMessage(sid, "ux", "go", emptyList()))
        s.dispatch(HarnessEvent.ThinkingDelta(sid, "rx", "hmm"))
        s.dispatch(HarnessEvent.TurnFinished(sid, "e:x", cancelled = true))
        val now = s.state(sid).value
        for (i in finished.indices) assertSame(finished[i], now.turns[i])
        assertFalse(now.items.filterIsInstance<ChatItem.Thinking>().last().running)
        assertTrue(now.items.last() is ChatItem.Notice)
        assertFalse(now.running)
    }
}
