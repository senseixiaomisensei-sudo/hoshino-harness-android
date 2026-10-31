package app.hoshino.harness.ui.chat

import android.content.Context
import app.hoshino.harness.core.Hoshino
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 对话显示偏好。存放在现有的 `hoshino-ui` SharedPreferences（与主题、字号同一处，备份/恢复会一并带上）。
 * - collapseApprovals：「审批后折叠」，默认开。开：处理过的审批卡从对话中消失，只在该轮过程组里留一行小字；关：旧样式（卡片保留并显示处理结果）。
 */
object ChatDisplayPrefs {
    private const val FILE = "hoshino-ui"
    private const val KEY_COLLAPSE_APPROVALS = "collapseApprovals"

    private val prefs get() = Hoshino.applicationContext?.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val collapse by lazy { MutableStateFlow(prefs?.getBoolean(KEY_COLLAPSE_APPROVALS, true) ?: true) }

    val collapseApprovals: StateFlow<Boolean> get() = collapse

    fun setCollapseApprovals(value: Boolean) {
        collapse.value = value
        prefs?.edit()?.putBoolean(KEY_COLLAPSE_APPROVALS, value)?.apply()
    }
}
