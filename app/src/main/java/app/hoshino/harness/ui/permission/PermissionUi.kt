package app.hoshino.harness.ui.permission

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.hoshino.harness.core.PermissionTier
import app.hoshino.harness.core.ShizukuStatus
import app.hoshino.harness.ui.components.BtnStyle
import app.hoshino.harness.ui.components.HButton
import app.hoshino.harness.ui.components.HCheckbox
import app.hoshino.harness.ui.components.HDialog
import app.hoshino.harness.ui.components.HIcon
import app.hoshino.harness.ui.components.HSheet
import app.hoshino.harness.ui.components.StatusDot
import app.hoshino.harness.ui.components.Tag
import app.hoshino.harness.ui.theme.HIcons
import app.hoshino.harness.ui.theme.HoshinoShapes
import app.hoshino.harness.ui.theme.HoshinoTheme

@Composable
fun tierColor(t: PermissionTier): Color {
    val c = HoshinoTheme.colors
    return when (t) {
        PermissionTier.ReadOnly -> c.text2
        PermissionTier.WorkspaceWrite -> c.text2
        PermissionTier.FullAccess -> c.danger
    }
}

/** 权限三档选择弹层 */
@Composable
fun TierSheet(current: PermissionTier, shizuku: ShizukuStatus, onPick: (PermissionTier) -> Unit, onDismiss: () -> Unit) {
    val c = HoshinoTheme.colors
    val t = HoshinoTheme.type
    HSheet(onDismiss = onDismiss, title = "权限") {
        Text("每个会话固定一档，也可用 /permission 切换。", style = t.caption, color = c.text3, modifier = Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(12.dp))
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PermissionTier.entries.forEach { tier ->
                val sel = tier == current
                val danger = tier == PermissionTier.FullAccess
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(HoshinoShapes.card)
                        .background(if (sel) (if (danger) c.danger.copy(alpha = 0.08f) else c.accentSoft) else c.surface)
                        .border(1.dp, if (sel) (if (danger) c.danger else c.accent) else c.line, HoshinoShapes.card)
                        .clickable { onPick(tier) }
                        .padding(14.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    HIcon(
                        when (tier) {
                            PermissionTier.ReadOnly -> HIcons.Lock
                            PermissionTier.WorkspaceWrite -> HIcons.Shield
                            PermissionTier.FullAccess -> HIcons.Warning
                        },
                        tint = if (danger) c.danger else c.text2,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(tier.label, style = t.bodyMedium, color = if (danger) c.danger else c.text)
                            Spacer(Modifier.width(6.dp))
                            Text(tier.key, style = t.codeSmall, color = c.text3)
                            if (tier == PermissionTier.WorkspaceWrite) {
                                Spacer(Modifier.width(6.dp)); Tag("默认")
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(tier.description, style = t.caption, color = c.text2)
                        if (danger) {
                            Spacer(Modifier.height(6.dp))
                            ShizukuStatusPill(shizuku)
                        }
                    }
                    if (sel) HIcon(HIcons.Check, size = 18.dp, tint = if (danger) c.danger else c.accent)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
fun ShizukuStatusPill(status: ShizukuStatus) {
    val c = HoshinoTheme.colors
    val color = when (status) {
        ShizukuStatus.Ready -> c.success
        ShizukuStatus.NotAuthorized -> c.warning
        else -> c.text3
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(color, size = 6.dp)
        Spacer(Modifier.width(6.dp))
        Text("Shizuku · ${status.label}", style = HoshinoTheme.type.caption, color = color)
    }
}

/** 切到完全访问前的风险确认 */
@Composable
fun FullAccessRiskDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val c = HoshinoTheme.colors
    var ack by remember { mutableStateOf(false) }
    HDialog(
        title = "开启完全访问？",
        onDismiss = onDismiss,
        confirmText = "开启",
        onConfirm = onConfirm,
        danger = true,
        confirmEnabled = ack,
    ) {
        Column(
            Modifier.fillMaxWidth().clip(HoshinoShapes.card).background(c.danger.copy(alpha = 0.08f)).border(1.dp, c.danger.copy(alpha = 0.4f), HoshinoShapes.card).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf(
                "通过 Shizuku 获得系统级（ADB/Root）权限",
                "终端命令将自动执行，不再弹出审批卡",
                "可能修改或删除工作区以外的文件、安装/卸载应用、更改系统设置",
                "模型出错或被提示注入时，后果可能无法撤销",
            ).forEach {
                Row {
                    Text("•", color = c.danger, style = HoshinoTheme.type.caption)
                    Spacer(Modifier.width(6.dp))
                    Text(it, color = c.danger, style = HoshinoTheme.type.caption)
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().clip(HoshinoShapes.small).clickable { ack = !ack },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HCheckbox(ack, { ack = it }, danger = true)
            Text("我已了解风险", style = HoshinoTheme.type.body, color = c.text)
        }
    }
}

/** Shizuku 状态与三步引导（安装 → 启动 → 授权） */
@Composable
fun ShizukuGuide(
    status: ShizukuStatus,
    onRefresh: () -> Unit,
    onRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = HoshinoTheme.colors
    val t = HoshinoTheme.type
    val uri = LocalUriHandler.current
    val stepDone = listOf(
        status != ShizukuStatus.NotInstalled,
        status == ShizukuStatus.NotAuthorized || status == ShizukuStatus.Ready,
        status == ShizukuStatus.Ready,
    )
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("当前状态", style = t.caption, color = c.text3, modifier = Modifier.weight(1f))
            ShizukuStatusPill(status)
        }
        Spacer(Modifier.height(12.dp))
        GuideStep(1, "安装 Shizuku", "从 Google Play 或 GitHub 安装 Shizuku。", stepDone[0], active = !stepDone[0]) {
            HButton("获取 Shizuku", { uri.openUri("https://shizuku.rikka.app/download/") }, style = BtnStyle.Secondary, compact = true)
        }
        GuideStep(2, "启动 Shizuku", "Android 11+ 使用「无线调试」配对启动；已 Root 设备可直接启动。", stepDone[1], active = stepDone[0] && !stepDone[1]) {
            HButton("我已启动，刷新状态", onRefresh, style = BtnStyle.Secondary, compact = true, icon = HIcons.Refresh)
        }
        GuideStep(3, "授权 Hoshino Harness", "在弹出的 Shizuku 授权框中选择「始终允许」。", stepDone[2], active = stepDone[1] && !stepDone[2], last = true) {
            HButton("请求授权", onRequest, style = BtnStyle.Primary, compact = true)
        }
    }
}

@Composable
private fun GuideStep(
    index: Int,
    title: String,
    desc: String,
    done: Boolean,
    active: Boolean,
    last: Boolean = false,
    action: @Composable () -> Unit,
) {
    val c = HoshinoTheme.colors
    val t = HoshinoTheme.type
    Row(Modifier.fillMaxWidth().padding(bottom = if (last) 0.dp else 14.dp)) {
        Box(
            Modifier.size(24.dp).clip(CircleShape)
                .background(if (done) c.success else if (active) c.text else c.surface2),
            contentAlignment = Alignment.Center,
        ) {
            if (done) HIcon(HIcons.Check, size = 14.dp, tint = c.onAccent)
            else Text("$index", style = t.caption.copy(fontWeight = FontWeight.SemiBold), color = if (active) c.bg else c.text3)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = t.bodyMedium, color = if (done) c.text2 else c.text)
            Text(desc, style = t.caption, color = c.text3)
            if (active) {
                Spacer(Modifier.height(8.dp))
                action()
            }
        }
    }
}

@Composable
fun ShizukuGuideSheet(
    status: ShizukuStatus,
    pendingEnable: Boolean,
    onRefresh: () -> Unit,
    onRequest: () -> Unit,
    onEnable: () -> Unit,
    onDismiss: () -> Unit,
) {
    HSheet(onDismiss = onDismiss, title = "Shizuku 引导") {
        ShizukuGuide(status, onRefresh, onRequest, Modifier.padding(horizontal = 20.dp))
        if (pendingEnable) {
            Spacer(Modifier.height(16.dp))
            HButton(
                if (status == ShizukuStatus.Ready) "启用完全访问" else "完成以上步骤后可启用",
                onEnable,
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                style = BtnStyle.Danger,
                enabled = status == ShizukuStatus.Ready,
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}
