package app.hoshino.harness.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.hoshino.harness.ui.automation.GoalsScreen
import app.hoshino.harness.ui.automation.SchedulesScreen
import app.hoshino.harness.ui.chat.ChatScreen
import app.hoshino.harness.ui.chat.ChatViewModel
import app.hoshino.harness.ui.extensions.HooksScreen
import app.hoshino.harness.ui.extensions.McpScreen
import app.hoshino.harness.ui.extensions.PluginsScreen
import app.hoshino.harness.ui.extensions.SkillsScreen
import app.hoshino.harness.ui.nav.Routes
import app.hoshino.harness.ui.settings.AboutScreen
import app.hoshino.harness.ui.settings.AppearanceScreen
import app.hoshino.harness.ui.settings.CredentialsScreen
import app.hoshino.harness.ui.settings.PhoneSetupScreen
import app.hoshino.harness.ui.settings.DataPrivacyScreen
import app.hoshino.harness.ui.settings.FeedbackScreen
import app.hoshino.harness.ui.settings.KernelScreen
import app.hoshino.harness.ui.settings.ModelsScreen
import app.hoshino.harness.ui.settings.PhoneCapabilitiesScreen
import app.hoshino.harness.ui.settings.PresetEditScreen
import app.hoshino.harness.ui.settings.PresetsScreen
import app.hoshino.harness.ui.settings.ProviderEditScreen
import app.hoshino.harness.ui.settings.SecurityScreen
import app.hoshino.harness.ui.settings.SettingsHomeScreen
import app.hoshino.harness.ui.settings.ShizukuScreen
import app.hoshino.harness.ui.theme.HoshinoTheme
import app.hoshino.harness.ui.settings.M2Screen
import app.hoshino.harness.ui.components.PageScaffold
import app.hoshino.harness.ui.diy.DiyNode
import app.hoshino.harness.ui.theme.ThemeMode
import app.hoshino.harness.ui.tools.InspectorScreen
import app.hoshino.harness.ui.tools.LspScreen
import app.hoshino.harness.ui.tools.TerminalScreen
import app.hoshino.harness.ui.tools.TrajectoryScreen
import app.hoshino.harness.ui.tools.WebPreviewScreen
import app.hoshino.harness.ui.tools.ScreenshotScreen
import app.hoshino.harness.ui.tools.FilesScreen
import app.hoshino.harness.ui.components.toast

/** 应用根：主题 + 导航。ChatViewModel / AppViewModel 为 Activity 级，跨页面共享。 */
@Composable
fun HoshinoApp() {
    val app: AppViewModel = viewModel()
    val chat: ChatViewModel = viewModel()
    val prefs by app.prefs.collectAsStateWithLifecycle()
    val custom by app.customization.collectAsStateWithLifecycle()
    val customizationError by app.customizationError.collectAsStateWithLifecycle()
    val context=LocalContext.current
    LaunchedEffect(customizationError){customizationError?.let{toast(context,it)}}
    val systemDark = isSystemInDarkTheme()
    val isDark = when (prefs.themeMode) {
        ThemeMode.System -> systemDark
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }

    HoshinoTheme(mode = prefs.themeMode, bodyScale = custom.bodyScale ?: prefs.bodyScale, codeScale = custom.codeScale ?: prefs.codeScale,
        accent=custom.accent?.let{Color(android.graphics.Color.parseColor(it))},tokens=custom.raw.optJSONObject("themes")?.optJSONObject(custom.raw.optString("theme"))?:custom.raw.optJSONObject("tokens")) {
        val nav = rememberNavController()
        val back: () -> Unit = { nav.popBackStack() }
        val go: (String) -> Unit = { route -> nav.navigate(route) { launchSingleTop = true } }
        val dur = 200

        NavHost(
            navController = nav,
            startDestination = Routes.CHAT,
            modifier = Modifier.fillMaxSize().background(HoshinoTheme.colors.bg),
            enterTransition = { slideInHorizontally(tween(dur, easing = FastOutSlowInEasing)) { it / 6 } + fadeIn(tween(dur)) },
            exitTransition = { fadeOut(tween(150)) },
            popEnterTransition = { fadeIn(tween(dur)) },
            popExitTransition = { slideOutHorizontally(tween(dur, easing = FastOutSlowInEasing)) { it / 6 } + fadeOut(tween(150)) },
        ) {
            composable(Routes.CHAT) {
                ChatScreen(
                    vm = chat,
                    isDark = isDark,
                    onToggleTheme = { app.cycleTheme(isDark) },
                    onNavigate = go,
                )
            }
            composable(Routes.TRAJECTORY) { TrajectoryScreen(chat, back) }

            // ---------- 设置
            composable(Routes.SETTINGS) { SettingsHomeScreen(viewModel(), back, go) }
            composable(Routes.M2) { M2Screen(back) }
            custom.raw.optJSONArray("pages")?.let{pages->for(i in 0 until pages.length()){val page=pages.getJSONObject(i);composable(page.getString("route")){PageScaffold(page.getString("title"),back){DiyNode(page.getJSONObject("root"),custom.raw,go)}}}}
            composable(Routes.APPEARANCE) { AppearanceScreen(app, back) }
            composable(Routes.MODELS) { ModelsScreen(viewModel(), back, go) }
            composable(Routes.PROVIDER, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                ProviderEditScreen(viewModel(), entry.arguments?.getString("id") ?: "", back)
            }
            composable(Routes.PRESETS) { PresetsScreen(viewModel(), back, go) }
            composable(Routes.PRESET, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                PresetEditScreen(viewModel(), entry.arguments?.getString("id") ?: Routes.NEW_PRESET, back)
            }
            composable(Routes.SECURITY) { SecurityScreen(viewModel(), app, back, go) }
            composable(Routes.SHIZUKU) { ShizukuScreen(viewModel(), back) }
            composable(Routes.PHONE) { PhoneCapabilitiesScreen(viewModel(), back) }
            composable(Routes.PHONE_SETUP) { PhoneSetupScreen(back) }
            composable(Routes.KERNEL) { KernelScreen(viewModel(), back) }
            composable(Routes.DATA) { DataPrivacyScreen(viewModel(), back) }
            composable(Routes.CREDENTIALS) { CredentialsScreen(viewModel(), back) }
            composable(Routes.FEEDBACK) { FeedbackScreen(viewModel(), back) }
            composable(Routes.ABOUT) { AboutScreen(back) }

            // ---------- 扩展
            composable(Routes.PLUGINS) { PluginsScreen(viewModel(), back) }
            composable(Routes.SKILLS) { SkillsScreen(viewModel(), back) }
            composable(Routes.MCP) { McpScreen(viewModel(), back) }
            composable(Routes.HOOKS) { HooksScreen(viewModel(), back) }

            // ---------- 自动化
            composable(Routes.SCHEDULES) { SchedulesScreen(viewModel(), back) }
            composable(Routes.GOALS) { GoalsScreen(viewModel(), back) }

            // ---------- 工具台
            composable(Routes.TERMINAL) { TerminalScreen(viewModel(), back) }
            composable(Routes.SCREENSHOT) { ScreenshotScreen(back,{chat.attach(it)}) }
            composable(Routes.FILES) { FilesScreen(back) }
            composable(Routes.INSPECTOR) { InspectorScreen(viewModel(), back) }
            composable(Routes.WEB) { WebPreviewScreen(back) }
            composable(Routes.LSP) { LspScreen(viewModel(), back) }
        }
    }
}
