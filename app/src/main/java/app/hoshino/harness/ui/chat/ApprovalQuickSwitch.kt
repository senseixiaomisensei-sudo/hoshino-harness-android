package app.hoshino.harness.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hoshino.harness.core.ApprovalMode
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import app.hoshino.harness.core.ApprovalModeRules
import app.hoshino.harness.core.ApprovalScope
import app.hoshino.harness.core.ApprovalState
import app.hoshino.harness.ui.components.HCheckbox
import app.hoshino.harness.ui.components.HDialog
import app.hoshino.harness.ui.components.HIcon
import app.hoshino.harness.ui.components.HSheet
import app.hoshino.harness.ui.components.Hairline
import app.hoshino.harness.ui.components.Pill
import app.hoshino.harness.ui.components.Segmented
import app.hoshino.harness.ui.theme.HIcons
import app.hoshino.harness.ui.theme.HoshinoShapes
import app.hoshino.harness.ui.theme.HoshinoTheme

/** 输入框工具行里的审批档胶囊：点开即可切换；无审批时变成红色。 */
@Composable
fun ApprovalPill(state: ApprovalState, onClick: () -> Unit) {
    val c = HoshinoTheme.colors
    Pill(
        if (state.noApproval) "无审批" else "审批 · ${state.effective.label}",
        icon = if (state.noApproval) HIcons.Warning else HIcons.Shield,
        color = if (state.noApproval) c.danger else null,
        trailing = HIcons.ChevronDown,
        onClick = onClick,
        modifier = Modifier.semantics { contentDescription = "审批档：${state.effective.label}，点按切换" },
    )
}

/** 无审批开启时输入框上方的醒目小标记。 */
@Composable
fun NoApprovalBanner(state: ApprovalState, onClick: () -> Unit) {
    if (!state.noApproval) return
    val c = HoshinoTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(HoshinoShapes.small).background(c.danger.copy(alpha = 0.10f))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HIcon(HIcons.Warning, size = 14.dp, tint = c.danger)
        Spacer(Modifier.width(6.dp))
        Text(
            "无审批" + if (state.followsDefault) "（默认）" else "（本会话）",
            style = HoshinoTheme.type.caption.copy(fontWeight = FontWeight.Medium), color = c.danger,
        )
        Spacer(Modifier.width(6.dp))
        Text("操作将自动执行，权限档仍生效", style = HoshinoTheme.type.caption, color = c.text2, modifier = Modifier.weight(1f), maxLines = 1)
        Text("切换", style = HoshinoTheme.type.caption, color = c.accent)
    }
}

/** 插话气泡下的去向：已收到，下一步处理 → 已加入当前任务（停止时作废的标为已停止）。 */
@Composable
fun SteerStatusLine(itemId: String) {
    // 只订阅这一条插话的状态：其它插话变化不让这个气泡重组（与 M2.7 稳定 key / @Immutable 行渲染配合）。
    val flow = remember(itemId) { app.hoshino.harness.core.Steering.states.map { it[itemId] }.distinctUntilChanged() }
    val steer = flow.collectAsStateWithLifecycle(app.hoshino.harness.core.Steering.states.value[itemId]).value ?: return
    val c = HoshinoTheme.colors
    Text(steer.label, style = HoshinoTheme.type.caption, color = if (steer == app.hoshino.harness.core.SteerState.Cancelled) c.text3 else c.accent, modifier = Modifier.padding(top = 4.dp, end = 4.dp))
}

/** 停止后正在整理交代时的一行提示。 */
@Composable
fun StopSummaryHint(active: Boolean) {
    if (!active) return
    Text("已停止 · 正在整理已做的事…", style = HoshinoTheme.type.caption, color = HoshinoTheme.colors.text3, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
}

/**
 * 审批档选择弹层。可只改本会话、设为默认，或让本会话回到跟随默认。
 * 选到「无审批」时由调用方先弹 [NoApprovalRiskDialog]（首次，除非勾过不再提示）。
 */
@Composable
fun ApprovalSheet(
    state: ApprovalState,
    hasSession: Boolean,
    onPick: (ApprovalMode, ApprovalScope) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = HoshinoTheme.colors
    var scope by remember { mutableStateOf(ApprovalScope.Session) }
    HSheet(onDismiss = onDismiss, title = "审批") {
        Text(
            "当前：${state.effective.label}" + when {
                state.sessionMode != null -> "（本会话单独设置，默认为${state.defaultMode.label}）"
                else -> "（跟随默认）"
            },
            style = HoshinoTheme.type.caption, color = c.text3, modifier = Modifier.padding(horizontal = 20.dp),
        )
        Segmented(
            options = listOf(ApprovalScope.Session, ApprovalScope.Default),
            selected = scope,
            onSelect = { scope = it },
            label = { if (it == ApprovalScope.Session) (if (hasSession) "仅本会话" else "仅新会话") else "设为默认" },
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp).fillMaxWidth(),
        )
        ApprovalMode.entries.forEach { mode ->
            val selected = if (scope == ApprovalScope.Default) mode == state.defaultMode else mode == state.effective
            val danger = mode == ApprovalMode.None
            Row(
                Modifier.fillMaxWidth().clickable { onPick(mode, scope) }.heightIn(min = 56.dp).padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HIcon(if (danger) HIcons.Warning else HIcons.Shield, tint = if (danger) c.danger else c.text2)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(mode.label + if (mode == ApprovalMode.Default) " · 默认出厂" else "", style = HoshinoTheme.type.body, color = if (danger) c.danger else c.text)
                    Text(mode.description, style = HoshinoTheme.type.caption, color = c.text3)
                }
                if (selected) HIcon(HIcons.Check, size = 18.dp, tint = c.accent)
            }
        }
        if (hasSession && state.sessionMode != null) {
            Hairline()
            Row(
                Modifier.fillMaxWidth().clickable { onPick(state.defaultMode, ApprovalScope.FollowDefault) }.heightIn(min = 48.dp).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) { Text("本会话改回跟随默认（${state.defaultMode.label}）", style = HoshinoTheme.type.body, color = c.accent) }
        }
        Hairline()
        Text(ApprovalModeRules.SYSTEM_LIMITS, style = HoshinoTheme.type.caption, color = c.text3, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
    }
}

/** 首次开启无审批的一次性风险说明（可勾选不再提示）。 */
@Composable
fun NoApprovalRiskDialog(scope: ApprovalScope, onConfirm: (neverAskAgain: Boolean) -> Unit, onDismiss: () -> Unit) {
    val c = HoshinoTheme.colors
    var never by remember { mutableStateOf(false) }
    HDialog(
        title = if (scope == ApprovalScope.Default) "把无审批设为默认？" else "为本会话开启无审批？",
        onDismiss = onDismiss,
        confirmText = "开启无审批",
        onConfirm = { onConfirm(never) },
        danger = true,
    ) {
        Text(ApprovalModeRules.RISK_TEXT, style = HoshinoTheme.type.body, color = c.text2)
        Row(Modifier.clickable { never = !never }, verticalAlignment = Alignment.CenterVertically) {
            HCheckbox(never, { never = it })
            Text("我了解风险，以后不再提示", style = HoshinoTheme.type.caption, color = c.text2)
        }
    }
}
