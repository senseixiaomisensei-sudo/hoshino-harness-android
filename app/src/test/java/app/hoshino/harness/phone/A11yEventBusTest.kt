// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package app.hoshino.harness.phone

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mika a8c3aa6：无障碍服务被系统断开又重连后，任务早先订阅的事件等待照样收到新实例的事件；
 * 旧实例晚到的回调不混进来。用两个普通对象代替两个服务实例。
 */
class A11yEventBusTest {
    private class Service(val name: String)

    @Test fun waitStartedBeforeReconnectGetsEventsFromTheNewInstance() = runTest {
        val old = Service("old")
        val new = Service("new")
        var current: Service? = old
        val bus = A11yEventBus<Service, Int>({ current })
        // The task subscribed while the old instance was connected (UNDISPATCHED, like AndroidPhoneScreen.watch).
        val got = async(start = CoroutineStart.UNDISPATCHED) { withTimeoutOrNull(5_000) { bus.events.first() } }
        assertTrue(bus.hasSubscribers)
        current = null          // system unbinds the service
        current = new           // and connects a new instance
        assertTrue(bus.publish(new, 42))
        assertEquals(42, got.await())
    }

    @Test fun staleInstanceCannotPublish() = runTest {
        val old = Service("old")
        val new = Service("new")
        var current: Service? = new
        val bus = A11yEventBus<Service, Int>({ current })
        val seen = mutableListOf<Int>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { bus.events.collect { seen += it } }
        assertFalse(bus.publish(old, 1))
        assertTrue(bus.publish(new, 2))
        current = null
        assertFalse(bus.publish(new, 3)) // disconnected, nothing is current
        runCurrent()
        job.cancel()
        assertEquals(listOf(2), seen)
    }

    @Test fun subscriptionSurvivesSeveralReconnects() = runTest {
        val instances = List(3) { Service("s$it") }
        var current: Service? = instances[0]
        val bus = A11yEventBus<Service, Int>({ current })
        val got = async(start = CoroutineStart.UNDISPATCHED) { bus.events.take(3).toList() }
        instances.forEachIndexed { i, s -> current = s; bus.publish(s, i); runCurrent() }
        assertEquals(listOf(0, 1, 2), got.await())
    }

    @Test fun noSubscribersNoBacklog() = runTest {
        val s = Service("s")
        val bus = A11yEventBus<Service, Int>({ s })
        assertFalse(bus.hasSubscribers)
        bus.publish(s, 1) // dropped: no replay
        val got = async(start = CoroutineStart.UNDISPATCHED) { withTimeoutOrNull(1_000) { bus.events.first() } }
        bus.publish(s, 2)
        assertEquals(2, got.await())
    }
}
