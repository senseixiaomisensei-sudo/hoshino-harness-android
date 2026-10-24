package app.hoshino.harness.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.hoshino.harness.core.HarnessClient
import app.hoshino.harness.core.Hoshino
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * 设置 / 扩展 / 自动化 / 工具台页面共用的 ViewModel：直接暴露 [HarnessClient] 的 StateFlow，
 * 写操作统一经 [run] 在 viewModelScope 中执行并把异常转成提示。
 */
class ConfigViewModel : ViewModel() {
    val client: HarnessClient = Hoshino.client

    val providers = client.providers
    val routing = client.routing
    val presets = client.presets
    val plugins = client.plugins
    val restartRequired = client.restartRequired
    val skills = client.skills
    val mcpServers = client.mcpServers
    val hooks = client.hooks
    val webhooks = client.webhooks
    val schedules = client.schedules
    val goals = client.goals
    val shizuku = client.shizuku
    val kernel = client.kernel
    val phoneCapabilities = client.phoneCapabilities
    val lspServers = client.lspServers
    val inspector = client.inspector
    val terminals = client.terminals
    val sessions = client.sessions

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun toast(msg: String) {
        _messages.tryEmit(msg)
    }

    /** 执行写操作；[done] 中可返回提示文本 */
    fun run(done: String? = null, block: suspend HarnessClient.() -> Unit) {
        viewModelScope.launch {
            try {
                client.block()
                if (done != null) _messages.tryEmit(done)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.tryEmit(client.describeFailure(e))
            }
        }
    }
}
