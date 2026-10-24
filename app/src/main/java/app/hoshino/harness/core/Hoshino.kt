package app.hoshino.harness.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 极简服务定位器。接入 dsh 时只需把 [client] 换成真实实现（或改用 Hilt/Koin）。
 *
 *     Hoshino.client = DshHarnessClient(context, bridge)
 *
 * 必须在第一个 ViewModel 创建前（如 Application.onCreate）替换。
 */
object Hoshino {
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    lateinit var client: HarnessClient

    val store: SessionStore by lazy { SessionStore(client, appScope) }
}
