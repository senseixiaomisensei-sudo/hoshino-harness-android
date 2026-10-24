package app.hoshino.harness.ui

import androidx.lifecycle.ViewModel
import app.hoshino.harness.core.ApprovalPolicy
import app.hoshino.harness.core.PermissionTier
import app.hoshino.harness.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 本机 UI 偏好（主题、字号、语言、默认权限、审批策略）。持久化可替换为 DataStore。 */
data class UiPrefs(
    val themeMode: ThemeMode = ThemeMode.System,
    val bodyScale: Float = 1f,
    val codeScale: Float = 1f,
    val language: String = "简体中文",
    val defaultTier: PermissionTier = PermissionTier.WorkspaceWrite,
    val approvalPolicy: ApprovalPolicy = ApprovalPolicy.Dangerous,
)

class AppViewModel : ViewModel() {
    private val _prefs = MutableStateFlow(UiPrefs())
    val prefs: StateFlow<UiPrefs> = _prefs.asStateFlow()

    fun setTheme(mode: ThemeMode) = _prefs.update { it.copy(themeMode = mode) }
    fun cycleTheme(currentlyDark: Boolean) =
        _prefs.update { it.copy(themeMode = if (currentlyDark) ThemeMode.Light else ThemeMode.Dark) }
    fun setBodyScale(v: Float) = _prefs.update { it.copy(bodyScale = v) }
    fun setCodeScale(v: Float) = _prefs.update { it.copy(codeScale = v) }
    fun setLanguage(v: String) = _prefs.update { it.copy(language = v) }
    fun setDefaultTier(t: PermissionTier) { if(t in app.hoshino.harness.core.Hoshino.client.supportedPermissionTiers)_prefs.update { it.copy(defaultTier = t) } }
    fun setApprovalPolicy(p: ApprovalPolicy) = _prefs.update { it.copy(approvalPolicy = p) }
}
