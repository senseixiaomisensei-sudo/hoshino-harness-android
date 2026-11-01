// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package dev.hoshino.bridge.voice

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HoldToTalkTest {
    /** 假会话:只记松开、取消各被调了几次;事件由测试直接喂给 [HoldToTalk.onEvent]。 */
    private class FakeSession(override val live: Boolean = true) : SpeechSession {
        override val convertsAfterRelease get() = !live
        override val events: Flow<SpeechEvent> = emptyFlow()
        var released = 0
        var cancelled = 0
        override fun release() { released++ }
        override fun cancel() { cancelled++ }
    }

    private var now = 0L
    private val hold = HoldToTalk(clock = { now })

    private fun pressed(live: Boolean = true): FakeSession = FakeSession(live).also { hold.press(it) }

    @Test
    fun partialShowsLive() {
        pressed()
        hold.onEvent(SpeechEvent.Partial("帮我", "订票"))
        val s = hold.state.value as HoldToTalk.State.Listening
        assertEquals("帮我", s.stable)
        assertEquals("订票", s.tail)
        assertTrue(s.live)
    }

    @Test
    fun releaseGivesDone() {
        val session = pressed()
        now = 2_000
        hold.release()
        assertEquals(1, session.released)
        hold.onEvent(SpeechEvent.Final("帮我订票"))
        assertEquals(HoldToTalk.State.Done("帮我订票"), hold.state.value)
    }

    @Test
    fun cancelArmedDropsText() {
        val session = pressed()
        now = 2_000
        hold.onEvent(SpeechEvent.Partial("帮我", "订票"))
        hold.drag(100f, 80f)
        assertTrue((hold.state.value as HoldToTalk.State.Listening).cancelArmed)
        hold.release()
        assertEquals(HoldToTalk.State.Idle, hold.state.value)
        assertEquals(1, session.cancelled)
        assertEquals(0, session.released)
        // 取消以后迟到的结果不再冒出来
        hold.onEvent(SpeechEvent.Final("帮我订票"))
        assertEquals(HoldToTalk.State.Idle, hold.state.value)
    }

    @Test
    fun dragBackDisarms() {
        pressed()
        hold.drag(100f, 80f)
        hold.drag(10f, 80f)
        assertFalse((hold.state.value as HoldToTalk.State.Listening).cancelArmed)
    }

    @Test
    fun tooShortIsNoMatch() {
        val session = pressed()
        now = 300
        hold.release()
        // 太短不交给会话去转(服务商的还要花额度):直接丢掉,当没听清
        assertEquals(0, session.released)
        assertEquals(1, session.cancelled)
        assertEquals(HoldToTalk.State.Problem(null), hold.state.value)
        // 会话迟到的结果不覆盖「没听清」
        hold.onEvent(SpeechEvent.Final("帮我"))
        assertEquals(HoldToTalk.State.Problem(null), hold.state.value)
    }

    @Test
    fun blankFinalIsNoMatch() {
        val session = pressed()
        now = 2_000
        hold.release()
        assertEquals(1, session.released)
        assertTrue(hold.state.value is HoldToTalk.State.Listening)
        hold.onEvent(SpeechEvent.Final("  "))
        assertEquals(HoldToTalk.State.Problem(null), hold.state.value)
    }

    @Test
    fun minimumIsConfigurable() {
        // 输入栏开始听时手指已经按了 300ms,只再数 200ms:合起来不到半秒才算太短
        val short = HoldToTalk(clock = { now }, minMs = 200)
        val session = FakeSession().also { short.press(it) }
        now = 250
        short.release()
        assertEquals(1, session.released)
        assertEquals(0, session.cancelled)
    }

    @Test
    fun maxDurationReleases() {
        val session = pressed()
        now = 30_000
        hold.tick()
        assertEquals(0, session.released)
        assertEquals(30_000L, (hold.state.value as HoldToTalk.State.Listening).elapsedMs)
        now = HoldToTalk.MAX_HOLD_MS
        hold.tick()
        assertEquals(1, session.released)
        // 到点以后再 tick 不重复收尾
        now = HoldToTalk.MAX_HOLD_MS + 1_000
        hold.tick()
        assertEquals(1, session.released)
    }

    @Test
    fun providerShowsConverting() {
        pressed(live = false)
        now = 2_000
        hold.release()
        hold.onEvent(SpeechEvent.Converting)
        assertEquals(HoldToTalk.State.Converting(), hold.state.value)
        // 服务商的引擎可能晚到一个音量:转文字时不再回到在听
        hold.onEvent(SpeechEvent.Level(0.5f))
        assertEquals(HoldToTalk.State.Converting(), hold.state.value)
        hold.onEvent(SpeechEvent.Final("你好"))
        assertEquals(HoldToTalk.State.Done("你好"), hold.state.value)
    }

    @Test
    fun providerConvertsRightOnRelease() {
        val session = pressed(live = false)
        now = 2_000
        hold.release()
        // 录音器停下要一小会儿,Converting 事件晚到;松开那一刻就该显示转文字,不闪回占位
        assertEquals(HoldToTalk.State.Converting(), hold.state.value)
        assertEquals(1, session.released)
        hold.tick()
        assertEquals(HoldToTalk.State.Converting(), hold.state.value)
        hold.onEvent(SpeechEvent.Final(""))
        assertEquals(HoldToTalk.State.Problem(null), hold.state.value)
    }

    @Test
    fun failureSurfaces() {
        pressed()
        hold.onEvent(SpeechEvent.Failed(SpeechFailure.Busy))
        assertEquals(HoldToTalk.State.Problem(SpeechFailure.Busy), hold.state.value)
    }

    @Test
    fun disposeCancels() {
        val session = pressed()
        hold.dispose()
        hold.dispose()
        assertEquals(1, session.cancelled)
        assertEquals(HoldToTalk.State.Idle, hold.state.value)
    }

    @Test
    fun levelMovesWhileListening() {
        pressed()
        hold.onEvent(SpeechEvent.Level(0.7f))
        assertEquals(0.7f, (hold.state.value as HoldToTalk.State.Listening).level, 0.0001f)
    }

    @Test
    fun resetAfterDoneGoesIdle() {
        pressed()
        now = 2_000
        hold.release()
        hold.onEvent(SpeechEvent.Final("好"))
        hold.reset()
        assertEquals(HoldToTalk.State.Idle, hold.state.value)
    }

    @Test
    fun pressAgainDropsTheOldSession() {
        val first = pressed()
        val second = pressed()
        assertEquals(1, first.cancelled)
        assertEquals(0, second.cancelled)
        assertTrue(hold.state.value is HoldToTalk.State.Listening)
    }

    @Test
    fun earlyFinalWaitsForReleaseThenAppliesOnce() {
        // 手机自带识别在手还按着时自己结束了:先不交出去,手指离开才算数
        val session = pressed()
        now = 2_000
        hold.onEvent(SpeechEvent.Final("帮我订票"))
        val s = hold.state.value as HoldToTalk.State.Listening
        assertTrue(s.ended)
        assertEquals("帮我订票", s.stable)
        now = 3_000
        hold.tick()
        assertTrue(hold.state.value is HoldToTalk.State.Listening)
        hold.release()
        assertEquals(HoldToTalk.State.Done("帮我订票"), hold.state.value)
        // 会话已经结束,不再去收尾;再松开一次也不重复交出
        assertEquals(0, session.released)
        hold.reset()
        hold.release()
        assertEquals(HoldToTalk.State.Idle, hold.state.value)
    }

    @Test
    fun earlyFinalWithCancelArmedIsDropped() {
        val session = pressed()
        now = 2_000
        hold.drag(100f, 80f)
        hold.onEvent(SpeechEvent.Final("帮我订票"))
        assertTrue(hold.state.value is HoldToTalk.State.Listening)
        hold.release()
        assertEquals(HoldToTalk.State.Idle, hold.state.value)
        assertEquals(0, session.released)
    }

    @Test
    fun earlyFinalThenDragToCancelIsDropped() {
        pressed()
        now = 2_000
        hold.onEvent(SpeechEvent.Final("帮我订票"))
        hold.drag(100f, 80f)
        assertTrue((hold.state.value as HoldToTalk.State.Listening).cancelArmed)
        hold.release()
        assertEquals(HoldToTalk.State.Idle, hold.state.value)
    }

    @Test
    fun earlyFinalThenDragBackOffCancelIsApplied() {
        pressed()
        now = 2_000
        hold.drag(100f, 80f)
        hold.onEvent(SpeechEvent.Final("帮我订票"))
        hold.drag(10f, 80f)
        hold.release()
        assertEquals(HoldToTalk.State.Done("帮我订票"), hold.state.value)
    }

    @Test
    fun earlyFailureWithCancelArmedIsDropped() {
        pressed()
        now = 2_000
        hold.armCancel(true)
        hold.onEvent(SpeechEvent.Failed(SpeechFailure.Busy))
        assertTrue((hold.state.value as HoldToTalk.State.Listening).ended)
        hold.release()
        assertEquals(HoldToTalk.State.Idle, hold.state.value)
    }

    @Test
    fun earlyFinalAppliedWhenTimeRunsOut() {
        pressed()
        now = 2_000
        hold.onEvent(SpeechEvent.Final("好"))
        now = HoldToTalk.MAX_HOLD_MS
        hold.tick()
        assertEquals(HoldToTalk.State.Done("好"), hold.state.value)
    }

    @Test
    fun pressWhileConvertingDropsTheOldSession() {
        // 服务商转写卡住时用户重新按:旧的那段取消,新的一段照常听、照常出结果
        val first = pressed(live = false)
        now = 2_000
        hold.release()
        assertEquals(HoldToTalk.State.Converting(), hold.state.value)
        val second = pressed()
        assertEquals(1, first.cancelled)
        assertEquals(0, second.cancelled)
        assertTrue(hold.state.value is HoldToTalk.State.Listening)
        now = 4_000
        hold.release()
        assertEquals(1, second.released)
        hold.onEvent(SpeechEvent.Final("新的"))
        assertEquals(HoldToTalk.State.Done("新的"), hold.state.value)
    }

    @Test
    fun joinRules() {
        assertEquals("你好", HoldToTalk.join("", "你好"))
        assertEquals("帮我订票", HoldToTalk.join("帮我", "订票"))
        assertEquals("book a train", HoldToTalk.join("book", "a train"))
        assertEquals("帮我 book", HoldToTalk.join("帮我", "book"))
        assertEquals("好的，订票", HoldToTalk.join("好的，", "订票"))
        assertEquals("book ", HoldToTalk.join("book ", ""))
        assertEquals("book a", HoldToTalk.join("book ", "a"))
    }
}
