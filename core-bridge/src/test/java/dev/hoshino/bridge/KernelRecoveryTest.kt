package dev.hoshino.bridge

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class KernelRecoveryTest {
    @Test fun classifiesDshSessionErrors() {
        assertEquals(KernelFailure.SessionOwned, KernelRecovery.classify("内核拒绝请求：session \"8d90e505-7220-48a6-9819-211e538f97ae\" is already owned by an active write handle"))
        assertEquals(KernelFailure.SessionBusy, KernelRecovery.classify("内核拒绝请求：session \"x\" is owned by subagent routing"))
        assertEquals(KernelFailure.SessionBusy, KernelRecovery.classify("session/agent-busy"))
        assertEquals(KernelFailure.SessionNotFound, KernelRecovery.classify("内核拒绝请求：session \"x\" is not open"))
        assertEquals(KernelFailure.KernelLink, KernelRecovery.classify("Failed to connect to /127.0.0.1:41234"))
        assertEquals(KernelFailure.KernelLink, KernelRecovery.classify("内核请求失败 (502)"))
        assertEquals(KernelFailure.ProviderTransport, KernelRecovery.classify("请求未完成（TRANSPORT）：Connection error."))
        assertEquals(KernelFailure.Other, KernelRecovery.classify("请先配置模型供应商"))
    }
    @Test fun actions() {
        assertEquals(KernelRecovery.Action.RestartKernel, KernelRecovery.actionFor(KernelFailure.SessionOwned))
        assertEquals(KernelRecovery.Action.RestartKernel, KernelRecovery.actionFor(KernelFailure.KernelLink))
        assertEquals(KernelRecovery.Action.CancelStaleTurn, KernelRecovery.actionFor(KernelFailure.SessionBusy))
        assertEquals(KernelRecovery.Action.ReloadSession, KernelRecovery.actionFor(KernelFailure.SessionNotFound))
        assertEquals(KernelRecovery.Action.None, KernelRecovery.actionFor(KernelFailure.ProviderTransport))
    }
    @Test fun retriesExactlyOnceAfterRecovery() = runBlocking {
        var calls = 0; var recovered = 0
        val value = KernelRecovery.runWithRecovery({ calls++; if (calls == 1) error("session \"s\" is already owned by an active write handle"); "ok" }, { a, _, _ -> assertEquals(KernelRecovery.Action.RestartKernel, a); recovered++ })
        assertEquals("ok", value); assertEquals(2, calls); assertEquals(1, recovered)
    }
    @Test fun secondFailureIsRethrownWithFullMessage() = runBlocking {
        var calls = 0
        val msg = "内核拒绝请求：session \"s\" is already owned by an active write handle"
        val failure = runCatching { KernelRecovery.runWithRecovery<Unit>({ calls++; error(msg) }, { _, _, _ -> }) }.exceptionOrNull()
        assertEquals(msg, failure?.message); assertEquals(2, calls)
    }
    @Test fun nonRecoverableIsNotRetried() = runBlocking {
        var calls = 0; var recovered = 0
        val failure = runCatching { KernelRecovery.runWithRecovery<Unit>({ calls++; error("请先配置模型供应商") }, { _, _, _ -> recovered++ }) }.exceptionOrNull()
        assertNotNull(failure); assertEquals(1, calls); assertEquals(0, recovered)
    }
    @Test fun busyStateMachine() {
        assertEquals(KernelRecovery.BusyState.Ready, KernelRecovery.nextBusyState(false, 0))
        assertEquals(KernelRecovery.BusyState.Cancel, KernelRecovery.nextBusyState(true, 0))
        assertEquals(KernelRecovery.BusyState.Wait, KernelRecovery.nextBusyState(true, 3))
        assertEquals(KernelRecovery.BusyState.GiveUp, KernelRecovery.nextBusyState(true, 20))
        assertEquals(KernelRecovery.BusyState.Ready, KernelRecovery.nextBusyState(false, 7))
    }
    @Test fun heapAndCrashReason() {
        assertEquals(512, KernelRecovery.nodeHeapMiB(1L shl 30))
        assertEquals(2048, KernelRecovery.nodeHeapMiB(16L shl 30))
        assertEquals(1536, KernelRecovery.nodeHeapMiB(6L shl 30))
        assertEquals("FATAL ERROR: Reached heap limit Allocation failed - JavaScript heap out of memory", KernelRecovery.crashReason(listOf("ok", "FATAL ERROR: Reached heap limit Allocation failed - JavaScript heap out of memory")))
        assertNull(KernelRecovery.crashReason(listOf("ready")))
        assertTrue(KernelRecovery.isKernelCmdline("/usr/bin/node\u0000/opt/hoshino/node-host/dist/node-host/src/main.js"))
    }
}
