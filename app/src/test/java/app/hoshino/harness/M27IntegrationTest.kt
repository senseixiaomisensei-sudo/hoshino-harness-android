package app.hoshino.harness

import app.hoshino.harness.core.*
import app.hoshino.harness.ui.chat.ChatRow
import app.hoshino.harness.ui.chat.ProcessSummary
import app.hoshino.harness.ui.chat.TurnPresenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/**
 * M2.7 三个分支合并后的交叉语义（integ/m2.7）：
 * - 插话（approval-sessions）与文件卡归轮（perf-files）共存；
 * - 无审批自动放行（approval-sessions）在过程组（perf-files 的审批折叠）里留一行审计；
 * - 删除会话后重写交付记录不丢其它会话的归轮字段。
 */
class M27IntegrationTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun tearDown() = scope.cancel()
    private fun store() = SessionStore(object : UnavailableHarnessClient() {}, scope, maxRetainedSessions = 4, coalesceMs = 60_000)

    @Test fun filesDeliveredAfterASteerFollowTheTurnsFinalAnswer() {
        val s = store()
        val sid = "steer"
        s.dispatch(HarnessEvent.TurnStarted(sid, "e:1", at = 1_000))
        s.dispatch(HarnessEvent.UserMessage(sid, "u1", "写报告", emptyList()))
        s.dispatch(HarnessEvent.ToolCallStarted(sid, "t1", "write", "write", "{}", ToolKind.Edit, callId = "c1"))
        s.dispatch(HarnessEvent.ToolCallFinished(sid, "t1", ToolStatus.Success))
        // 运行中插话：界面另起一组，但仍是 dsh 的同一轮 e:1
        s.dispatch(HarnessEvent.UserMessage(sid, "steer:m1", "顺便加个目录", emptyList(), steer = true))
        s.dispatch(HarnessEvent.ToolCallStarted(sid, "t2", "deliver_file", "deliver_file", "{}", ToolKind.Generic, callId = "c2"))
        s.dispatch(HarnessEvent.FileDelivered(sid, DeliveredFile("d1", sid, "/root/workspace/report.md", "report.md", 10, turnId = "e:1", deliveredAt = 1_500, callId = "c2")))
        s.dispatch(HarnessEvent.AssistantDelta(sid, "a1", "完成"))
        s.dispatch(HarnessEvent.AssistantDone(sid, "a1"))
        s.dispatch(HarnessEvent.TurnFinished(sid, "e:1", cancelled = false))
        // 下一轮（普通消息）不被上一轮的文件跨过去
        s.dispatch(HarnessEvent.TurnStarted(sid, "e:9", at = 9_000))
        s.dispatch(HarnessEvent.UserMessage(sid, "u2", "再看看", emptyList()))
        s.dispatch(HarnessEvent.AssistantDelta(sid, "a2", "好的"))
        s.dispatch(HarnessEvent.AssistantDone(sid, "a2"))
        s.flushNow()

        val turns = TurnPresenter().present(s.state(sid).value, collapseApprovals = true)
        assertEquals(listOf("u1", "steer:m1", "u2"), turns.map { it.user?.id })
        assertTrue(turns[0].files.isEmpty())
        assertEquals(listOf("d1"), turns[1].files.map { it.id })
        assertTrue(turns[2].files.isEmpty())
        // 文件卡紧跟这一轮的最终回答
        val rows = turns[1].rows
        val idx = rows.indexOfFirst { it is ChatRow.FilesRow }
        assertTrue(idx > 0)
        assertEquals("a1", (rows[idx - 1] as ChatRow.ItemRow).item.id)
        // 插话气泡保留 steer 标记（状态行由 Steering 按 id 显示）
        assertTrue(turns[1].user!!.steer)
    }

    @Test fun noApprovalAutoAllowsShowOneAuditLineInTheProcessGroup() {
        val s = store()
        val sid = "none"
        s.dispatch(HarnessEvent.TurnStarted(sid, "e:1"))
        s.dispatch(HarnessEvent.UserMessage(sid, "u", "清理构建目录", emptyList()))
        s.dispatch(HarnessEvent.ToolCallStarted(sid, "t1", "bash", "rm -rf build", "{\"command\":\"rm -rf build\"}", ToolKind.Bash, autoApproved = true, callId = "c1"))
        s.dispatch(HarnessEvent.ToolCallStarted(sid, "t2", "write", "write", "{}", ToolKind.Edit, autoApproved = true, callId = "c2"))
        s.dispatch(HarnessEvent.ToolCallStarted(sid, "t3", "read", "read", "{}", ToolKind.Generic, callId = "c3"))
        s.dispatch(HarnessEvent.AssistantDelta(sid, "a", "好了"))
        s.dispatch(HarnessEvent.AssistantDone(sid, "a"))
        s.flushNow()
        for (collapse in listOf(true, false)) {
            val turn = TurnPresenter().present(s.state(sid).value, collapse).single()
            // 无审批不产生审批卡：折叠开关两种取值下都没有待处理动作
            assertTrue(turn.actions.isEmpty())
            val process = (turn.rows.first { it is ChatRow.ProcessRow } as ChatRow.ProcessRow)
            assertFalse(process.waiting)
            val summary = ProcessSummary.of(process.items, process.waiting)
            assertEquals(2, summary.autoApproved)
            assertTrue(summary.title(0).contains("自动放行 2"))
        }
        assertEquals("无审批 · 自动放行 2 次工具调用（已记入审计日志）", ProcessSummary.autoApprovalLine(2))
        val tool = s.state(sid).value.items.filterIsInstance<ChatItem.Tool>().first { it.id == "t2" }
        assertTrue(tool.autoApproved)
    }

    @Test fun manualModeShowsNoAutoApprovalAudit() {
        val items = listOf<ChatItem>(
            ChatItem.User("u", "x", emptyList(), false),
            ChatItem.Tool("t", "read", "read", "{}", "", ToolStatus.Success),
            ChatItem.Terminal("b", "ls", "", ToolStatus.Success, 0, autoApproved = false),
        )
        val summary = ProcessSummary.of(items.drop(1), waiting = false)
        assertEquals(0, summary.autoApproved)
        assertFalse(summary.title(0).contains("自动放行"))
    }

    @Test fun rewritingDeliveriesAfterDeletingASessionKeepsTurnFieldsOfOthers() {
        val rows = listOf(
            DeliveredFile("a", "keep", "/root/workspace/a.md", "a.md", 1, turnId = "e:3", deliveredAt = 42, callId = "c"),
            DeliveredFile("b", "gone", "/root/workspace/b.md", "b.md", 2, turnId = "e:4", deliveredAt = 43),
        )
        // M2Runtime.forgetSessions 的写法：过滤后用 DeliveredFileCodec 编码
        val kept = DeliveredFileCodec.decode(DeliveredFileCodec.encode(rows.filterNot { it.sessionId in setOf("gone") }))
        assertEquals(listOf(rows[0]), kept)
    }
}
