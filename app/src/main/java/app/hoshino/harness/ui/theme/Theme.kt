package app.hoshino.harness.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/** 设计 Token（SPEC §1）。插件 UI 也应通过 HoshinoTheme.colors 取色。 */
@Immutable
data class HoshinoColors(
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val line: Color,
    val text: Color,
    val text2: Color,
    val text3: Color,
    val accent: Color,
    val accentSoft: Color,
    val onAccent: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val isDark: Boolean,
)

val LightHoshinoColors = HoshinoColors(
    bg = Color(0xFFFAFAF8),
    surface = Color(0xFFFFFFFF),
    surface2 = Color(0xFFF2F2EF),
    line = Color(0xFFE6E6E1),
    text = Color(0xFF111214),
    text2 = Color(0xFF5E6066),
    text3 = Color(0xFF9A9CA1),
    accent = Color(0xFF2F5BEA),
    accentSoft = Color(0x142F5BEA),
    onAccent = Color(0xFFFFFFFF),
    success = Color(0xFF1F9D55),
    warning = Color(0xFFB7791F),
    danger = Color(0xFFD93636),
    isDark = false,
)

val DarkHoshinoColors = HoshinoColors(
    bg = Color(0xFF0E0F11),
    surface = Color(0xFF16171A),
    surface2 = Color(0xFF1D1E22),
    line = Color(0xFF2A2B30),
    text = Color(0xFFF2F2EF),
    text2 = Color(0xFFA3A5AB),
    text3 = Color(0xFF6C6E74),
    accent = Color(0xFF4C7DFF),
    accentSoft = Color(0x1F4C7DFF),
    onAccent = Color(0xFFFFFFFF),
    success = Color(0xFF3DBE73),
    warning = Color(0xFFE0A43A),
    danger = Color(0xFFF06161),
    isDark = true,
)

/** 字号 13/15/17/22/28，字重 400/500/600。代码用等宽（可替换为 res/font 中的 JetBrains Mono）。 */
@Immutable
data class HoshinoType(
    val caption: TextStyle,
    val body: TextStyle,
    val bodyMedium: TextStyle,
    val title: TextStyle,
    val headline: TextStyle,
    val display: TextStyle,
    val code: TextStyle,
    val codeSmall: TextStyle,
)

/** 等宽字体。若在 res/font 放入 jetbrains_mono_regular.ttf，可替换为 FontFamily(Font(R.font.jetbrains_mono_regular))。 */
val MonoFamily: FontFamily = FontFamily.Monospace

fun hoshinoType(bodyScale: Float = 1f, codeScale: Float = 1f): HoshinoType {
    fun s(v: Float) = (v * bodyScale).sp
    fun c(v: Float) = (v * codeScale).sp
    return HoshinoType(
        caption = TextStyle(fontSize = s(13f), lineHeight = s(18f), fontWeight = FontWeight.Normal),
        body = TextStyle(fontSize = s(15f), lineHeight = s(23f), fontWeight = FontWeight.Normal),
        bodyMedium = TextStyle(fontSize = s(15f), lineHeight = s(22f), fontWeight = FontWeight.Medium),
        title = TextStyle(fontSize = s(17f), lineHeight = s(24f), fontWeight = FontWeight.SemiBold),
        headline = TextStyle(fontSize = s(22f), lineHeight = s(30f), fontWeight = FontWeight.SemiBold, letterSpacing = (-0.01f).em),
        display = TextStyle(fontSize = s(28f), lineHeight = s(36f), fontWeight = FontWeight.SemiBold),
        code = TextStyle(fontSize = c(13f), lineHeight = c(19f), fontFamily = MonoFamily),
        codeSmall = TextStyle(fontSize = c(12f), lineHeight = c(17f), fontFamily = MonoFamily),
    )
}

/** 圆角：小控件 8，卡片/输入框 12，底部弹层/大容器 20，胶囊 999。 */
object HoshinoShapes {
    val small = RoundedCornerShape(8.dp)
    val card = RoundedCornerShape(12.dp)
    val sheet = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    val large = RoundedCornerShape(20.dp)
    val pill = RoundedCornerShape(999.dp)
}

enum class ThemeMode(val label: String) { System("跟随系统"), Light("浅色"), Dark("深色") }

val LocalHoshinoColors = staticCompositionLocalOf { LightHoshinoColors }
val LocalHoshinoType = staticCompositionLocalOf { hoshinoType() }

object HoshinoTheme {
    val colors: HoshinoColors
        @Composable @ReadOnlyComposable get() = LocalHoshinoColors.current
    val type: HoshinoType
        @Composable @ReadOnlyComposable get() = LocalHoshinoType.current
}

@Composable
fun HoshinoTheme(
    mode: ThemeMode = ThemeMode.System,
    bodyScale: Float = 1f,
    codeScale: Float = 1f,
    accent: Color? = null,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val base = if (dark) DarkHoshinoColors else LightHoshinoColors
    val c = if(accent==null)base else base.copy(accent=accent,accentSoft=accent.copy(alpha=if(dark)0.12f else 0.08f),onAccent=if(accent.luminance()>0.5f)Color.Black else Color.White)
    val type = hoshinoType(bodyScale, codeScale)

    val scheme = if (dark) {
        darkColorScheme(
            primary = c.accent, onPrimary = c.onAccent,
            primaryContainer = c.accentSoft, onPrimaryContainer = c.accent,
            secondary = c.text2, onSecondary = c.bg,
            background = c.bg, onBackground = c.text,
            surface = c.surface, onSurface = c.text,
            surfaceVariant = c.surface2, onSurfaceVariant = c.text2,
            surfaceTint = Color.Transparent,
            surfaceContainerLowest = c.bg, surfaceContainerLow = c.surface,
            surfaceContainer = c.surface, surfaceContainerHigh = c.surface,
            surfaceContainerHighest = c.surface2,
            outline = c.line, outlineVariant = c.line,
            error = c.danger, onError = c.onAccent,
            scrim = Color(0x99000000),
        )
    } else {
        lightColorScheme(
            primary = c.accent, onPrimary = c.onAccent,
            primaryContainer = c.accentSoft, onPrimaryContainer = c.accent,
            secondary = c.text2, onSecondary = c.bg,
            background = c.bg, onBackground = c.text,
            surface = c.surface, onSurface = c.text,
            surfaceVariant = c.surface2, onSurfaceVariant = c.text2,
            surfaceTint = Color.Transparent,
            surfaceContainerLowest = c.surface, surfaceContainerLow = c.surface,
            surfaceContainer = c.surface, surfaceContainerHigh = c.surface,
            surfaceContainerHighest = c.surface2,
            outline = c.line, outlineVariant = c.line,
            error = c.danger, onError = c.onAccent,
            scrim = Color(0x66000000),
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
            @Suppress("DEPRECATION")
            window.navigationBarColor = c.bg.toArgb()
        }
    }

    CompositionLocalProvider(
        LocalHoshinoColors provides c,
        LocalHoshinoType provides type,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = Typography(
                bodyLarge = type.body, bodyMedium = type.body, bodySmall = type.caption,
                titleMedium = type.title, titleLarge = type.headline, labelLarge = type.bodyMedium,
                labelMedium = type.caption,
            ),
            shapes = Shapes(
                extraSmall = HoshinoShapes.small, small = HoshinoShapes.small,
                medium = HoshinoShapes.card, large = HoshinoShapes.large, extraLarge = HoshinoShapes.large,
            ),
            content = content,
        )
    }
}
