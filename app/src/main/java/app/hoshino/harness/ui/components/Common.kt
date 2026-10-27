package app.hoshino.harness.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.hoshino.harness.ui.theme.HIcons
import app.hoshino.harness.ui.theme.HoshinoShapes
import app.hoshino.harness.ui.theme.HoshinoTheme
import java.util.Locale

// ---------------------------------------------------------------- 品牌

private const val STAR_PATH = "M24 11.5Q25.3 22.7 35 24Q25.3 25.3 24 36.5Q22.7 25.3 13 24Q22.7 22.7 24 11.5Z"

/** H 字母标：两根圆头竖杆 + 四角星（viewBox 48）。 */
@Composable
fun HoshinoMark(
    size: Dp,
    modifier: Modifier = Modifier,
    barColor: Color = HoshinoTheme.colors.text,
    starColor: Color = HoshinoTheme.colors.accent,
) {
    val star = remember { PathParser().parsePathString(STAR_PATH).toPath() }
    Canvas(modifier.size(size)) {
        val s = this.size.width / 48f
        scale(s, s, pivot = Offset.Zero) {
            drawRoundRect(barColor, topLeft = Offset(9f, 7f), size = Size(7f, 34f), cornerRadius = CornerRadius(3.5f, 3.5f))
            drawRoundRect(barColor, topLeft = Offset(32f, 7f), size = Size(7f, 34f), cornerRadius = CornerRadius(3.5f, 3.5f))
            drawPath(star, starColor)
        }
    }
}

/** 标志 + 字标，间距 = 标志高 × 0.4 */
@Composable
fun HoshinoWordmark(markSize: Dp = 28.dp, modifier: Modifier = Modifier) {
    val c = HoshinoTheme.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        HoshinoMark(markSize)
        Spacer(Modifier.width(markSize * 0.4f))
        Text(
            "Hoshino Harness",
            style = HoshinoTheme.type.title.copy(fontWeight = FontWeight.SemiBold, letterSpacing = HoshinoTheme.type.headline.letterSpacing),
            color = c.text,
        )
    }
}

// ---------------------------------------------------------------- 基础件

@Composable
fun Hairline(modifier: Modifier = Modifier, color: Color = HoshinoTheme.colors.line) {
    val px = with(LocalDensity.current) { 1f.toDp() }
    Box(modifier.fillMaxWidth().height(px).background(color))
}

@Composable
fun VHairline(modifier: Modifier = Modifier, color: Color = HoshinoTheme.colors.line) {
    val px = with(LocalDensity.current) { 1f.toDp() }
    Box(modifier.fillMaxHeight().width(px).background(color))
}

/** 卡片：surface + 1px 发丝线 + 12 圆角，无投影 */
fun Modifier.hairlineCard(
    bg: Color,
    line: Color,
    shape: androidx.compose.ui.graphics.Shape = HoshinoShapes.card,
): Modifier = this.clip(shape).background(bg).border(1.dp, line, shape)

@Composable
fun HCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(14.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = HoshinoTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .hairlineCard(c.surface, c.line)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(contentPadding),
        content = content,
    )
}

@Composable
fun HIcon(icon: ImageVector, modifier: Modifier = Modifier, tint: Color = HoshinoTheme.colors.text2, size: Dp = 20.dp) {
    Icon(icon, contentDescription = null, modifier = modifier.size(size), tint = tint)
}

/** 44dp 触控目标的图标按钮 */
@Composable
fun IconBtn(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = HoshinoTheme.colors.text,
    enabled: Boolean = true,
    iconSize: Dp = 20.dp,
) {
    Box(
        modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, Modifier.size(iconSize), tint = if (enabled) tint else tint.copy(alpha = 0.35f))
    }
}

enum class BtnStyle { Primary, Accent, Secondary, Ghost, Danger }

@Composable
fun HButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: BtnStyle = BtnStyle.Secondary,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    compact: Boolean = false,
) {
    val c = HoshinoTheme.colors
    val (bg, fg, border) = when (style) {
        BtnStyle.Primary -> Triple(c.text, c.bg, Color.Transparent)
        BtnStyle.Accent -> Triple(c.accent, c.onAccent, Color.Transparent)
        BtnStyle.Secondary -> Triple(c.surface, c.text, c.line)
        BtnStyle.Ghost -> Triple(Color.Transparent, c.text, Color.Transparent)
        BtnStyle.Danger -> Triple(c.danger, c.onAccent, Color.Transparent)
    }
    Row(
        modifier
            .heightIn(min = if (compact) 36.dp else 44.dp)
            .clip(HoshinoShapes.pill)
            .background(if (enabled) bg else bg.copy(alpha = 0.4f))
            .border(1.dp, border, HoshinoShapes.pill)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = if (compact) 14.dp else 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(16.dp), tint = fg)
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text,
            style = if (compact) HoshinoTheme.type.caption.copy(fontWeight = FontWeight.Medium) else HoshinoTheme.type.bodyMedium,
            color = fg,
            maxLines = 1,
        )
    }
}

/** 胶囊（快捷入口、筛选、选择器） */
@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    icon: ImageVector? = null,
    trailing: ImageVector? = null,
    color: Color? = null,
    onClick: (() -> Unit)? = null,
) {
    val c = HoshinoTheme.colors
    val fg = color ?: if (selected) c.accent else c.text2
    Row(
        modifier
            .heightIn(min = 32.dp)
            .clip(HoshinoShapes.pill)
            .background(if (selected) c.accentSoft else c.surface)
            .border(1.dp, if (selected) Color.Transparent else c.line, HoshinoShapes.pill)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(15.dp), tint = fg)
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = HoshinoTheme.type.caption, color = if (selected) c.accent else color ?: c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (trailing != null) {
            Spacer(Modifier.width(4.dp))
            Icon(trailing, null, Modifier.size(14.dp), tint = c.text3)
        }
    }
}

/** 小标签（来源、状态） */
@Composable
fun Tag(text: String, color: Color = HoshinoTheme.colors.text2, bg: Color = HoshinoTheme.colors.surface2, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.clip(HoshinoShapes.small).background(bg).padding(horizontal = 6.dp, vertical = 2.dp),
        style = HoshinoTheme.type.codeSmall.copy(fontFamily = HoshinoTheme.type.caption.fontFamily),
        color = color,
        maxLines = 1,
    )
}

@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier, size: Dp = 7.dp) {
    Box(modifier.size(size).clip(CircleShape).background(color))
}

@Composable
fun HSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val c = HoshinoTheme.colors
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = c.onAccent,
            checkedTrackColor = c.accent,
            checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = c.text3,
            uncheckedTrackColor = c.surface2,
            uncheckedBorderColor = c.line,
        ),
    )
}

@Composable
fun HCheckbox(checked: Boolean, onCheckedChange: (Boolean) -> Unit, danger: Boolean = false) {
    val c = HoshinoTheme.colors
    Checkbox(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = CheckboxDefaults.colors(
            checkedColor = if (danger) c.danger else c.accent,
            uncheckedColor = c.text3,
            checkmarkColor = c.onAccent,
        ),
    )
}

@Composable
fun HSlider(value: Float, onValueChange: (Float) -> Unit, range: ClosedFloatingPointRange<Float>, steps: Int = 0) {
    val c = HoshinoTheme.colors
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = range,
        steps = steps,
        colors = SliderDefaults.colors(
            thumbColor = c.text,
            activeTrackColor = c.accent,
            inactiveTrackColor = c.surface2,
            activeTickColor = c.onAccent,
            inactiveTickColor = c.text3,
        ),
    )
}

/** 细进度条 */
@Composable
fun ThinProgress(progress: Float, modifier: Modifier = Modifier, color: Color = HoshinoTheme.colors.accent) {
    val c = HoshinoTheme.colors
    Box(modifier.fillMaxWidth().height(3.dp).clip(HoshinoShapes.pill).background(c.surface2)) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().clip(HoshinoShapes.pill).background(color))
    }
}

/** 环形用量 */
@Composable
fun UsageRing(fraction: Float, modifier: Modifier = Modifier, size: Dp = 20.dp, stroke: Dp = 2.5.dp) {
    val c = HoshinoTheme.colors
    val color = when {
        fraction > 0.85f -> c.danger
        fraction > 0.65f -> c.warning
        else -> c.accent
    }
    Canvas(modifier.size(size)) {
        val w = stroke.toPx()
        val inset = w / 2
        translate(inset, inset) {
            val arcSize = Size(this.size.width - w, this.size.height - w)
            drawArc(c.line, 0f, 360f, false, size = arcSize, style = Stroke(w))
            drawArc(color, -90f, 360f * fraction.coerceIn(0f, 1f), false, size = arcSize, style = Stroke(w, cap = StrokeCap.Round))
        }
    }
}

/** 分段选择（关/低/中/高/max、主题、协议……） */
@Composable
fun <T> Segmented(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = HoshinoTheme.colors
    Row(
        modifier
            .clip(HoshinoShapes.small)
            .background(c.surface2)
            .padding(3.dp),
    ) {
        options.forEach { o ->
            val sel = o == selected
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 34.dp)
                    .clip(RoundedSmall)
                    .background(if (sel) c.surface else Color.Transparent)
                    .then(if (sel) Modifier.border(1.dp, c.line, RoundedSmall) else Modifier)
                    .clickable(enabled = enabled) { onSelect(o) }
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(o),
                    style = HoshinoTheme.type.caption.copy(fontWeight = if (sel) FontWeight.Medium else FontWeight.Normal),
                    color = if (!enabled) c.text3 else if (sel) c.text else c.text2,
                    maxLines = 1,
                )
            }
        }
    }
}

private val RoundedSmall = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)

/** 输入框：surface + 发丝线 + 12 圆角 */
@Composable
fun HTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String = "",
    mono: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    password: Boolean = false,
    hint: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    leading: ImageVector? = null,
) {
    val c = HoshinoTheme.colors
    val t = HoshinoTheme.type
    var reveal by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        if (label != null) {
            Text(label, style = t.caption, color = c.text2)
            Spacer(Modifier.height(6.dp))
        }
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .hairlineCard(c.surface, c.line)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
        ) {
            if (leading != null) {
                HIcon(leading, size = 18.dp, tint = c.text3)
                Spacer(Modifier.width(8.dp))
            }
            Box(Modifier.weight(1f)) {
                if (value.isEmpty()) {
                    Text(placeholder, style = if (mono) t.code else t.body, color = c.text3, maxLines = if (singleLine) 1 else 4)
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = singleLine,
                    minLines = if (singleLine) 1 else minLines,
                    textStyle = (if (mono) t.code else t.body).copy(color = c.text),
                    cursorBrush = SolidColor(c.accent),
                    visualTransformation = if (password && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboardType),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (password) {
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(24.dp).clip(CircleShape).clickable { reveal = !reveal }, contentAlignment = Alignment.Center) {
                    HIcon(if (reveal) HIcons.EyeOff else HIcons.Eye, size = 18.dp, tint = c.text3)
                }
            }
        }
        if (hint != null) {
            Spacer(Modifier.height(6.dp))
            Text(hint, style = t.caption, color = c.text3)
        }
    }
}

// ---------------------------------------------------------------- 列表 / 页面

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: @Composable (RowScope.() -> Unit)? = null) {
    val c = HoshinoTheme.colors
    Row(
        modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = HoshinoTheme.type.caption.copy(fontWeight = FontWeight.Medium), color = c.text3, modifier = Modifier.weight(1f))
        trailing?.invoke(this)
    }
}

@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    titleColor: Color = HoshinoTheme.colors.text,
    value: String? = null,
    showChevron: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    val c = HoshinoTheme.colors
    val t = HoshinoTheme.type
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .then(
                if (onClick != null || onLongClick != null) {
                    Modifier.longPressClickable(onClick = onClick ?: {}, onLongClick = onLongClick)
                } else Modifier,
            )
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            HIcon(icon, tint = if (titleColor == c.text) c.text2 else titleColor)
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = t.body, color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, style = t.caption, color = c.text3, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (value != null) {
            Spacer(Modifier.width(8.dp))
            Text(value, style = t.caption, color = c.text2, maxLines = 1, modifier = Modifier.widthIn(max = 160.dp), overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing(this)
        }
        if (showChevron) {
            Spacer(Modifier.width(4.dp))
            HIcon(HIcons.ChevronRight, size = 18.dp, tint = c.text3)
        }
    }
}

/** 列表分组容器：卡片内多行，行间发丝线 */
@Composable
fun GroupCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = HoshinoTheme.colors
    Column(
        modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .hairlineCard(c.surface, c.line),
        content = content,
    )
}

/** 二级页面骨架：返回 + 标题 + 右侧操作 */
@Composable
fun PageScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    scrollable: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = HoshinoTheme.colors
    Column(modifier.fillMaxSize().background(c.bg).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBtn(HIcons.Back, "返回", onBack)
            Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(title, style = HoshinoTheme.type.title, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(subtitle, style = HoshinoTheme.type.caption, color = c.text3, maxLines = 1)
            }
            actions(this)
        }
        Hairline()
        val body = Modifier.fillMaxWidth().weight(1f)
        if (scrollable) {
            Column(body.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp)){app.hoshino.harness.ui.diy.DiySlot("page.$title"){content()}}
        } else {
            Column(body){app.hoshino.harness.ui.diy.DiySlot("page.$title"){content()}}
        }
    }
}

@Composable
fun EmptyState(title: String, modifier: Modifier = Modifier, subtitle: String? = null, icon: ImageVector? = null) {
    val c = HoshinoTheme.colors
    Column(modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (icon != null) {
            HIcon(icon, size = 28.dp, tint = c.text3)
            Spacer(Modifier.height(12.dp))
        }
        Text(title, style = HoshinoTheme.type.body, color = c.text2)
        if (subtitle != null) {
            Spacer(Modifier.height(4.dp))
            Text(subtitle, style = HoshinoTheme.type.caption, color = c.text3)
        }
    }
}

/** 底部弹层：从下滑入，20 圆角，无投影 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HSheet(
    onDismiss: () -> Unit,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = HoshinoTheme.colors
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        shape = HoshinoShapes.sheet,
        containerColor = c.surface,
        contentColor = c.text,
        tonalElevation = 0.dp,
        scrimColor = Color.Black.copy(alpha = if (c.isDark) 0.6f else 0.32f),
        dragHandle = {
            Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(width = 36.dp, height = 4.dp).clip(HoshinoShapes.pill).background(c.line))
        },
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            if (title != null) {
                Text(title, style = HoshinoTheme.type.title, color = c.text, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            }
            content()
        }
    }
}

@Composable
fun HDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmText: String = "确定",
    onConfirm: () -> Unit,
    dismissText: String = "取消",
    danger: Boolean = false,
    confirmEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val c = HoshinoTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        tonalElevation = 0.dp,
        shape = HoshinoShapes.large,
        title = { Text(title, style = HoshinoTheme.type.title, color = if (danger) c.danger else c.text) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp), content = content) },
        confirmButton = {
            HButton(confirmText, onConfirm, style = if (danger) BtnStyle.Danger else BtnStyle.Primary, enabled = confirmEnabled, compact = true)
        },
        dismissButton = if (dismissText.isEmpty()) null else {
            { HButton(dismissText, onDismiss, style = BtnStyle.Ghost, compact = true) }
        },
    )
}

@Composable
fun TextInputDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    placeholder: String = "",
    mono: Boolean = false,
    hint: String? = null,
) {
    var text by remember { mutableStateOf(initial) }
    HDialog(title = title, onDismiss = onDismiss, onConfirm = { onConfirm(text.trim()) }, confirmEnabled = text.isNotBlank()) {
        HTextField(text, { text = it }, placeholder = placeholder, mono = mono, hint = hint)
    }
}

/** 运行中文字微光（全局唯一允许的渐变） */
@Composable
fun ShimmerText(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    val c = HoshinoTheme.colors
    val transition = rememberInfiniteTransition(label = "shimmer")
    val x by transition.animateFloat(
        initialValue = -200f,
        targetValue = 600f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmerX",
    )
    val brush = Brush.linearGradient(
        colors = listOf(c.text3, c.text, c.text3),
        start = Offset(x, 0f),
        end = Offset(x + 200f, 0f),
    )
    Text(text, modifier = modifier, style = style.copy(brush = brush), maxLines = 1)
}

// ---------------------------------------------------------------- 工具函数

fun relativeTime(ts: Long, now: Long = System.currentTimeMillis()): String {
    val d = (now - ts).coerceAtLeast(0) / 1000
    return when {
        d < 60 -> "刚刚"
        d < 3600 -> "${d / 60} 分钟前"
        d < 86_400 -> "${d / 3600} 小时前"
        d < 7 * 86_400 -> "${d / 86_400} 天前"
        else -> java.text.SimpleDateFormat("M月d日", Locale.CHINA).format(java.util.Date(ts))
    }
}

fun futureTime(ts: Long?): String {
    if (ts == null) return "—"
    return java.text.SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(java.util.Date(ts))
}

fun formatTokens(n: Int): String = when {
    n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1_000_000f)
    n >= 1_000 -> String.format(Locale.US, "%.1fk", n / 1_000f)
    else -> n.toString()
}

fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return if (s < 60) "${s}s" else "${s / 60}m ${s % 60}s"
}
