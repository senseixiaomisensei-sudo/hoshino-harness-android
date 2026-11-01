// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package dev.hoshino.bridge.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class RestartsTest {
    @Test
    fun normalRestartIsImmediate() {
        val r = Restarts()
        assertEquals(Restarts.Decision.Restart(0), r.onEmpty(0))
        // 隔得够久的空段(没开口超时)不算连着
        assertEquals(Restarts.Decision.Restart(0), r.onEmpty(6_000))
        assertEquals(Restarts.Decision.Restart(0), r.onEmpty(12_000))
        assertEquals(Restarts.Decision.Restart(0), r.onEmpty(18_000))
    }

    @Test
    fun backsOffAfterThreeQuickEmptyRestarts() {
        val r = Restarts()
        repeat(3) { assertEquals(Restarts.Decision.Restart(0), r.onEmpty(it * 10L)) }
        assertEquals(Restarts.Decision.Restart(Restarts.BACKOFF_MS), r.onEmpty(40))
    }

    @Test
    fun givesUpAfterTooManyQuickEmptyRestarts() {
        val r = Restarts()
        val got = (0 until Restarts.MAX_QUICK).map { r.onEmpty(it * 300L) }
        assertEquals(Restarts.Decision.Restart(Restarts.BACKOFF_MS), got.last())
        assertEquals(Restarts.Decision.GiveUp, r.onEmpty(Restarts.MAX_QUICK * 300L))
    }

    @Test
    fun realSpeechResetsTheCount() {
        val r = Restarts()
        repeat(5) { r.onEmpty(it * 10L) }
        r.onSpeech()
        assertEquals(Restarts.Decision.Restart(0), r.onEmpty(60))
    }

    @Test
    fun errorOutcomes() {
        val no = SystemSpeechRules.ERROR_NO_MATCH
        val client = SystemSpeechRules.ERROR_CLIENT
        assertEquals(SystemSpeechRules.Outcome.Retry, SystemSpeechRules.afterError(no, holding = true, heard = false))
        assertEquals(SystemSpeechRules.Outcome.Finish, SystemSpeechRules.afterError(no, holding = false, heard = false))
        // 松开后有的服务报 CLIENT:什么都没听到就是没听清,不是麦克风坏了
        assertEquals(SystemSpeechRules.Outcome.Finish, SystemSpeechRules.afterError(client, holding = false, heard = false))
        assertEquals(SystemSpeechRules.Outcome.Fail(SpeechFailure.MicUnavailable), SystemSpeechRules.afterError(client, holding = true, heard = false))
        assertEquals(SystemSpeechRules.Outcome.Finish, SystemSpeechRules.afterError(SystemSpeechRules.ERROR_NETWORK, holding = true, heard = true))
        assertEquals(SystemSpeechRules.Outcome.Fail(SpeechFailure.Network), SystemSpeechRules.afterError(SystemSpeechRules.ERROR_NETWORK, holding = false, heard = false))
    }
}
