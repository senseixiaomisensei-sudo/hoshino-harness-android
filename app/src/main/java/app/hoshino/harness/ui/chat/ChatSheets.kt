package app.hoshino.harness.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.hoshino.harness.core.ContextUsage
import app.hoshino.harness.core.ModelInfo
import app.hoshino.harness.core.ModelRef
import app.hoshino.harness.core.Provider
import app.hoshino.harness.core.ReasoningEffort
import app.hoshino.harness.core.SessionStats
import app.hoshino.harness.ui.components.BtnStyle
import app.hoshino.harness.ui.components.EmptyState
import app.hoshino.harness.ui.components.HButton
import app.hoshino.harness.ui.components.HDialog
import app.hoshino.harness.ui.components.HIcon
import app.hoshino.harness.ui.components.HSheet
import app.hoshino.harness.ui.components.Hairline
import app.hoshino.harness.ui.components.Segmented
import app.hoshino.harness.ui.components.StatusDot
import app.hoshino.harness.ui.components.Tag
import app.hoshino.harness.ui.components.UsageRing
import app.hoshino.harness.ui.components.formatDuration
import app.hoshino.harness.ui.components.formatTokens
import app.hoshino.harness.ui.components.hairlineCard
import app.hoshino.harness.ui.theme.HIcons
import app.hoshino.harness.ui.theme.HoshinoTheme

/** 输入框模型菜单：按供应商分组 + 推理等级分段 */
@Composable
fun ModelPickerSheet(
    providers: List<Provider>,
    selected: ModelRef?,
    effort: ReasoningEffort,
    onPick: (ModelRef) -> Unit,
    onEffort: (ReasoningEffort) -> Unit,
    onAddProvider: () -> Unit,
    onDismiss: () -> Unit,
    title: String = "模型",
    showEffort: Boolean = true,
) {
    val c = HoshinoTheme.colors
    val t = HoshinoTheme.type
    val enabled = providers.filter { it.enabled && it.models.isNotEmpty() }
    HSheet(onDismiss = onDismiss, title = title) {
        if (enabled.isEmpty()) {
            EmptyState("还没有可用模型，先添加模型供应商", icon = HIcons.Cpu)
            HButton("添加模型供应商", onAddProvider, Modifier.fillMaxWidth().padding(horizontal = 20.dp), style = BtnStyle.Primary, icon = HIcons.Plus)
            Spacer(Modifier.height(12.dp))
        } else {
            ModelPickerBody(enabled, selected, effort, onPick, onEffort, showEffort)
        }
    }
}

@Composable
private fun ModelPickerBody(
    enabled: List<Provider>,
    selected: ModelRef?,
    effort: ReasoningEffort,
    onPick: (ModelRef) -> Unit,
    onEffort: (ReasoningEffort) -> Unit,
    showEffort: Boolean,
) {
    val c = HoshinoTheme.colors
    val t = HoshinoTheme.type
    Column(Modifier.fillMaxWidth()) {
        val info: ModelInfo? = selected?.let { s -> enabled.firstOrNull { it.id == s.providerId }?.models?.firstOrNull { it.id == s.modelId } }
        if (showEffort) {
            Text("推理等级", style = t.caption, color = c.text3, modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(6.dp))
            Segmented(
                options = ReasoningEffort.entries,
                selected = if (info?.reasoning == false) ReasoningEffort.Off else effort,
                label = { it.label },
                onSelect = onEffort,
                enabled = info?.reasoning != false,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            )
            if (info?.reasoning == false) {
                Text("当前模型不支持推理", style = t.caption, color = c.text3, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            }
            Spacer(Modifier.height(12.dp))
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
            enabled.forEach { p ->
                item(key = "h-${p.id}") {
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(p.name, style = t.caption, color = c.text3, modifier = Modifier.weight(1f))
                        Text(p.protocol.label, style = t.codeSmall, color = c.text3)
                    }
                }
                items(p.models, key = { "${p.id}/${it.id}" }) { m ->
                    val sel = selected != null && selected.providerId == p.id && selected.modelId == m.id
                    Row(
                        Modifier.fillMaxWidth().background(if (sel) c.accentSoft else c.surface).clickable { onPick(ModelRef(p.id, m.id)) }
                            .heightIn(min = 52.dp).padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(m.displayName, style = t.body, color = if (sel) c.accent else c.text)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(m.id, style = t.codeSmall, color = c.text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${formatTokens(m.contextLength)} 上下文", style = t.codeSmall, color = c.text3)
                            }
                        }
                        if (m.vision) Tag("图片")
                        if (m.reasoning) {
                            Spacer(Modifier.width(4.dp)); Tag("推理 · ${m.defaultEffort.label}")
                        }
                        if (sel) {
                            Spacer(Modifier.width(8.dp)); HIcon(HIcons.Check, size = 18.dp, tint = c.accent)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** 上下文用量弹层 */
@Composable
fun ContextUsageSheet(usage: ContextUsage, onCompact: () -> Unit, onDismiss: () -> Unit) {
    val c = HoshinoTheme.colors
    val t = HoshinoTheme.type
    HSheet(onDismiss = onDismiss, title = "上下文用量") {
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            UsageRing(usage.fraction, size = 56.dp, stroke = 5.dp)
            Spacer(Modifier.width(16.dp))
            Column {
                Text("${(usage.fraction * 100).toInt()}%", style = t.headline, color = c.text)
                Text("${formatTokens(usage.usedTokens)} / ${formatTokens(usage.limitTokens)} tokens", style = t.caption, color = c.text3)
            }
        }
        Spacer(Modifier.height(16.dp))
        if (usage.breakdown.isNotEmpty()) {
            Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().hairlineCard(c.surface, c.line)) {
                usage.breakdown.forEachIndexed { i, (k, v) ->
                    if (i > 0) Hairline()
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Text(k, style = t.body, color = c.text2, modifier = Modifier.weight(1f))
                        Text(formatTokens(v), style = t.code, color = c.text)
                    }
                }
            }
        } else {
            Text("开始对话后显示明细", style = t.caption, color = c.text3, modifier = Modifier.padding(horizontal = 20.dp))
        }
        Spacer(Modifier.height(16.dp))
        HButton("立即压缩 /compact", { onCompact(); onDismiss() }, Modifier.fillMaxWidth().padding(horizontal = 20.dp), style = BtnStyle.Primary)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun SessionStatsDialog(stats: SessionStats, onDismiss: () -> Unit) {
    HDialog(title = "会话统计", onDismiss = onDismiss, confirmText = "关闭", onConfirm = onDismiss, dismissText = "") {
        KeyValue("轮次", "${stats.turns}")
        KeyValue("输入", "%,d".format(stats.inputTokens))
        KeyValue("输出", "%,d".format(stats.outputTokens))
        KeyValue("缓存读取", "%,d".format(stats.cacheReadTokens))
        KeyValue("推理", "%,d".format(stats.reasoningTokens))
        KeyValue("工具调用", "${stats.toolCalls}")
        KeyValue("总耗时", formatDuration(stats.totalDurationMs))
    }
}

data class MenuEntry(val label: String, val icon: ImageVector, val danger: Boolean = false, val onClick: () -> Unit)

/** 会话操作弹层（侧栏长按） */
@Composable
fun ActionSheet(title: String, entries: List<MenuEntry>, onDismiss: () -> Unit) {
    val c = HoshinoTheme.colors
    HSheet(onDismiss = onDismiss, title = title) {
        entries.forEach { e ->
            Row(
                Modifier.fillMaxWidth().clickable { onDismiss(); e.onClick() }.heightIn(min = 52.dp).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HIcon(e.icon, tint = if (e.danger) c.danger else c.text2)
                Spacer(Modifier.width(14.dp))
                Text(e.label, style = HoshinoTheme.type.body, color = if (e.danger) c.danger else c.text)
            }
        }
    }
}

@Composable
fun ConnectionDot(connected: Boolean, connecting: Boolean) {
    val c = HoshinoTheme.colors
    StatusDot(if (connected) c.success else if (connecting) c.warning else c.danger, size = 6.dp)
}

@Composable
fun RoundIcon(icon: ImageVector, onClick: () -> Unit) {
    val c = HoshinoTheme.colors
    Box(Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        HIcon(icon, size = 18.dp, tint = c.text2)
    }
}
