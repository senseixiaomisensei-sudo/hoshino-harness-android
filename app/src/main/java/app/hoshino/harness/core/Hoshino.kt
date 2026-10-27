package app.hoshino.harness.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * 极简服务定位器。接入 dsh 时只需把 [client] 换成真实实现（或改用 Hilt/Koin）。
 *
 *     Hoshino.client = DshHarnessClient(context, bridge)
 *
 * 必须在第一个 ViewModel 创建前（如 Application.onCreate）替换。
 */
object Hoshino {
    var applicationContext:android.content.Context?=null
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    lateinit var client: HarnessClient

    private var storeScope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    private var cachedStore:SessionStore?=null
    val store:SessionStore get()=cachedStore?:SessionStore(client,storeScope).also{cachedStore=it}
    fun resetStore(){storeScope.cancel();storeScope=CoroutineScope(SupervisorJob()+Dispatchers.Default);cachedStore=null}
}
