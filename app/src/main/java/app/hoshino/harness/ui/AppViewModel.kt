package app.hoshino.harness.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
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
    val customization=app.hoshino.harness.core.Hoshino.client.uiCustomization
    val customizationError=app.hoshino.harness.core.Hoshino.client.uiCustomizationError
    private val disk=app.hoshino.harness.core.Hoshino.applicationContext?.getSharedPreferences("hoshino-ui",android.content.Context.MODE_PRIVATE)
    private val _prefs = MutableStateFlow(runCatching{UiPrefs(themeMode=ThemeMode.valueOf(disk?.getString("theme","System")?:"System"),bodyScale=disk?.getFloat("bodyScale",1f)?:1f,codeScale=disk?.getFloat("codeScale",1f)?:1f,language=disk?.getString("language","简体中文")?:"简体中文",defaultTier=PermissionTier.valueOf(disk?.getString("tier","WorkspaceWrite")?:"WorkspaceWrite"),approvalPolicy=ApprovalPolicy.valueOf(disk?.getString("approval","Dangerous")?:"Dangerous"))}.getOrDefault(UiPrefs()))
    val prefs: StateFlow<UiPrefs> = _prefs.asStateFlow()
    init{viewModelScope.launch{_prefs.collect{p->disk?.edit()?.putString("theme",p.themeMode.name)?.putFloat("bodyScale",p.bodyScale)?.putFloat("codeScale",p.codeScale)?.putString("language",p.language)?.putString("tier",p.defaultTier.name)?.putString("approval",p.approvalPolicy.name)?.apply()}}}

    fun setTheme(mode: ThemeMode) = _prefs.update { it.copy(themeMode = mode) }
    fun cycleTheme(currentlyDark: Boolean) =
        _prefs.update { it.copy(themeMode = if (currentlyDark) ThemeMode.Light else ThemeMode.Dark) }
    fun setBodyScale(v: Float) = _prefs.update { it.copy(bodyScale = v) }
    fun setCodeScale(v: Float) = _prefs.update { it.copy(codeScale = v) }
    fun setLanguage(v: String) = _prefs.update { it.copy(language = v) }
    fun setDefaultTier(t: PermissionTier) { if(t in app.hoshino.harness.core.Hoshino.client.supportedPermissionTiers)_prefs.update { it.copy(defaultTier = t) } }
    fun setApprovalPolicy(p: ApprovalPolicy) = _prefs.update { it.copy(approvalPolicy = p) }
}
