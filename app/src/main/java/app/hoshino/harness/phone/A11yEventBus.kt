// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package app.hoshino.harness.phone

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 进程级的无障碍事件通道（移植 Mika a8c3aa6 的思路）：通道本身跨服务实例保留，只有 [current] 返回的那个实例能发布。
 * 无障碍被系统断开又重连后，任务里早先订阅的等待（`changesOn` / 读配对码）不用重新订阅就能收到新实例的事件；
 * 已断开的旧实例晚到的回调被丢掉，不会混进来。
 *
 * 事件只作观察用：订阅方处理不过来时丢最旧的，不阻塞系统回调。调用方用 UNDISPATCHED 先订阅再操作，保持热流。
 */
internal class A11yEventBus<S : Any, E>(private val current: () -> S?, capacity: Int = 64) {
    private val flow = MutableSharedFlow<E>(extraBufferCapacity = capacity, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<E> = flow.asSharedFlow()

    val hasSubscribers: Boolean get() = flow.subscriptionCount.value > 0

    /** [source] 是当前连着的实例才发布；返回是否发布了。 */
    fun publish(source: S, event: E): Boolean {
        if (current() !== source) return false
        return flow.tryEmit(event)
    }
}
