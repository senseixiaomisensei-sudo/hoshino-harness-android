// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package app.hoshino.harness.phone.setup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 移植 Mika dc7965b 的 SlidingDeadlineTest（同场景、同断言），外加「多次续期」一例。 */
class SlidingDeadlineTest {
    private fun TestScope.deadline() = SlidingDeadline(10_000L) { testScheduler.currentTime }

    @Test
    fun endsAfterTheLengthWhenNotExtended() = runTest {
        val d = deadline()
        val never = CompletableDeferred<Unit>()
        d.extend()
        val result = async { d.await(never) }
        testScheduler.advanceTimeBy(9_999L)
        testScheduler.runCurrent()
        assertFalse(result.isCompleted)
        testScheduler.advanceTimeBy(1L)
        testScheduler.runCurrent()
        assertFalse(result.await())
    }

    /** 引导页开着放了九分多钟才去系统设置：从这时起再看 10 分钟，不按第一次开始的时候收工。 */
    @Test
    fun extendingWhileWaitingCountsFromThen() = runTest {
        val d = deadline()
        val never = CompletableDeferred<Unit>()
        d.extend()
        val result = async { d.await(never) }
        testScheduler.advanceTimeBy(9_000L)
        d.extend()
        testScheduler.advanceTimeBy(9_000L)
        testScheduler.runCurrent()
        assertFalse(result.isCompleted)
        testScheduler.advanceTimeBy(1_000L)
        testScheduler.runCurrent()
        assertFalse(result.await())
        assertEquals(19_000L, testScheduler.currentTime)
    }

    /** 离开引导页、回来、再点「开始配对」：每次都从那一刻重新算。 */
    @Test
    fun repeatedExtensionsKeepItOpen() = runTest {
        val d = deadline()
        val never = CompletableDeferred<Unit>()
        d.extend()
        val result = async { d.await(never) }
        repeat(5) {
            testScheduler.advanceTimeBy(8_000L)
            d.extend()
        }
        testScheduler.runCurrent()
        assertFalse(result.isCompleted)
        testScheduler.advanceTimeBy(10_000L)
        testScheduler.runCurrent()
        assertFalse(result.await())
        assertEquals(50_000L, testScheduler.currentTime)
    }

    @Test
    fun finishingInTimeIsTrue() = runTest {
        val d = deadline()
        d.extend()
        val job = launch { delay(3_000L) }
        assertTrue(d.await(job))
        assertEquals(3_000L, testScheduler.currentTime)
    }

    @Test
    fun neverExtendedEndsAtOnce() = runTest {
        assertFalse(deadline().await(CompletableDeferred<Unit>()))
    }
}
