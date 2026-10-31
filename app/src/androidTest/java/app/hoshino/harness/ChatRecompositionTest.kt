package app.hoshino.harness

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.hoshino.harness.core.*
import app.hoshino.harness.ui.chat.LocalRecomposeProbe
import app.hoshino.harness.ui.chat.MessageActions
import app.hoshino.harness.ui.chat.MessageList
import app.hoshino.harness.ui.theme.HoshinoTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentHashMap

/**
 * M2.7 重组计数：200 轮会话里流式输出最后一轮时，已完成轮次的行一次都不重组；
 * 计时文本局部刷新不牵动整行。
 */
@RunWith(AndroidJUnit4::class)
class ChatRecompositionTest {
    @get:Rule val compose = createComposeRule()

    private class StubClient : UnavailableHarnessClient()

    @Test fun streamingRecomposesOnlyTheLiveAnswerRow() {
        val store = SessionStore(StubClient(), CoroutineScope(SupervisorJob() + Dispatchers.Default), coalesceMs = 60_000)
        val sid = "perf"
        repeat(200) { n ->
            store.dispatch(HarnessEvent.TurnStarted(sid, "e:$n"))
            store.dispatch(HarnessEvent.UserMessage(sid, "u$n", "question $n", emptyList()))
            store.dispatch(HarnessEvent.ToolCallStarted(sid, "t$n", "read", "read", "{}", ToolKind.Generic))
            store.dispatch(HarnessEvent.ToolCallFinished(sid, "t$n", ToolStatus.Success))
            store.dispatch(HarnessEvent.AssistantDelta(sid, "a$n", "## Answer $n\n\nSome **markdown** text.\n\n- a\n- b\n"))
            store.flushNow()
            store.dispatch(HarnessEvent.AssistantDone(sid, "a$n"))
            store.dispatch(HarnessEvent.TurnFinished(sid, "e:$n", false))
        }
        store.dispatch(HarnessEvent.TurnStarted(sid, "e:live"))
        store.dispatch(HarnessEvent.UserMessage(sid, "u-live", "stream please", emptyList()))
        store.dispatch(HarnessEvent.AssistantDelta(sid, "a-live", "Start. "))
        store.flushNow()

        val counts = ConcurrentHashMap<String, Int>()
        val state = mutableStateOf(store.state(sid).value)
        // 回调与动作对象在组合外创建一次（与 ChatScreen 中 remember 的做法一致）。
        val probe: (String) -> Unit = { key -> counts.merge(key, 1, Int::plus) }
        val actions = MessageActions()
        compose.setContent {
            HoshinoTheme {
                CompositionLocalProvider(LocalRecomposeProbe provides probe) {
                    MessageList(state = state.value, actions = actions, sessionId = sid)
                }
            }
        }
        compose.waitForIdle()
        val initial = HashMap(counts)
        repeat(60) { i ->
            store.dispatch(HarnessEvent.AssistantDelta(sid, "a-live", "token $i "))
            store.flushNow()
            compose.runOnIdle { state.value = store.state(sid).value }
            compose.waitForIdle()
        }
        val composedOldRows = initial.keys.filter { it != "i:a-live" && it != "u:u-live" }
        assertTrue("some completed rows were on screen: $initial", composedOldRows.isNotEmpty())
        for (key in composedOldRows) assertEquals("completed row $key must not recompose while streaming", initial[key], counts[key])
        assertTrue("live answer row recomposed: ${counts["i:a-live"]}", (counts["i:a-live"] ?: 0) >= 30)
        assertEquals("live user row is unchanged", initial["u:u-live"], counts["u:u-live"])
    }
}
