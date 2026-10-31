package dev.hoshino.bridge.eval

import dev.hoshino.bridge.metrics.Dsh
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DshRunFactsTest {
    @Test fun answerIsTheLastTextReply() {
        val e = Dsh().turnStart().user("q").stepStart(1).assistant(1, "我先查一下", toolCall = "web_fetch").toolCall("c", "web_fetch").toolResult("c", "Python 3.14.0")
            .stepEnd(1).stepStart(2).assistant(2, "最新版是 3.14.0").stepEnd(2).stepStart(3).assistant(3, "").stepEnd(3).turnEnd().events
        assertEquals("最新版是 3.14.0", DshRunFacts.answer(e))
        assertEquals("", DshRunFacts.answer(Dsh().turnStart().events))
    }

    /** 材料是读到的东西;Agent 自己写进文件的、命令里回显的不算(和 Mika 证据账本一样)。 */
    @Test fun evidenceExcludesAuthoredText() {
        val e = Dsh().turnStart().user("q")
            .toolCall("w", "write", """{"path":"a.txt","content":"编造的 999 元"}""").toolResult("w", "已写入 a.txt:编造的 999 元")
            .toolCall("b", "bash", """{"command":"echo 编造的 888 元 && python3 -c 'print(6*7)'"}""").toolResult("b", "编造的 888 元 && python3 -c 'print(6*7)'\n42")
            .toolCall("f", "web_fetch", """{"url":"https://example.com"}""").toolResult("f", "Example Domain 价格 128 元")
            .toolCall("x", "web_fetch", """{"url":"https://bad.example"}""").toolResult("x", "HTTP 404", error = true)
            .turnEnd().events
        val ev = DshRunFacts.evidence(e)
        assertEquals(listOf("42", "Example Domain 价格 128 元", "HTTP 404"), ev)
        assertTrue(Checks.grounded("价格 128 元", ev, 1))
        assertTrue(!Checks.grounded("价格 999 元", ev, 1))
        assertTrue(!Checks.grounded("价格 888 元", ev, 1))
    }

    @Test fun askedUserCountsAskUserCalls() {
        val e = Dsh().turnStart().toolCall("a", "ask_user", """{"question":"闹钟几点?"}""").toolResult("a", "7 点").toolCall("b", "ask_user").toolResult("b", "好").events
        assertEquals(2, DshRunFacts.askedUser(e))
    }

    @Test fun takeoverNeedsTheBarrierInWhatWasRead() {
        val login = Dsh().turnStart().toolCall("f", "web_fetch").toolResult("f", "Instagram\nLog in to see photos\nPhone number, username, or email")
            .toolCall("a", "ask_user", """{"question":"这个页面需要登录,请你先登录后告诉我"}""").toolResult("a", "取消").events
        assertEquals("login", DshRunFacts.takeoverBarrier(login))
        // Agent 说要登录,但读到的材料里没有登录框字样:不认
        val claimed = Dsh().turnStart().toolCall("f", "web_fetch").toolResult("f", "Instagram profile 1.2M followers")
            .toolCall("a", "ask_user", """{"question":"需要登录"}""").toolResult("a", "取消").events
        assertNull(DshRunFacts.takeoverBarrier(claimed))
        // 没交给用户:不算
        assertNull(DshRunFacts.takeoverBarrier(Dsh().turnStart().toolCall("f", "web_fetch").toolResult("f", "Log in to see").events))
    }

    @Test fun judgeFromTextOnlyRecognisesPlainImpossibility() {
        assertEquals("not_possible", DshRunFacts.judgeFromText("设置里没有这个菜单,做不到。"))
        assertEquals("not_possible", DshRunFacts.judgeFromText("2030 年的天气预报现在无法查到。"))
        assertEquals("not_possible", DshRunFacts.judgeFromText("That video ID does not exist."))
        assertNull(DshRunFacts.judgeFromText("最新版是 3.14.0。"))
        assertNull(DshRunFacts.judgeFromText(""))
        // 「依据」之后的话不看
        assertNull(DshRunFacts.judgeFromText("结果是 56,088。\n依据: 第一次没找到计算器,后来找到了"))
    }

    @Test fun failReasonFromTurnEnd() {
        assertEquals("模型出错:rate_limit · HTTP 429", DshRunFacts.failReason(JSONObject("""{"kind":"error","error":{"code":"rate_limit","status":429}}""")))
        assertEquals(null, DshRunFacts.failReason(null))
        val e = Dsh().turnStart().turnEnd("error", extra = JSONObject().put("error", JSONObject().put("code", "x"))).events
        assertEquals("error", DshRunFacts.endReason(e)?.optString("kind"))
    }
}
