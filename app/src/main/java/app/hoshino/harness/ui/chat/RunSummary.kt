package app.hoshino.harness.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hoshino.harness.core.Hoshino
import app.hoshino.harness.core.RunMetricsHub
import app.hoshino.harness.ui.components.HIcon
import app.hoshino.harness.ui.theme.HIcons
import app.hoshino.harness.ui.theme.HoshinoShapes
import app.hoshino.harness.ui.theme.HoshinoTheme
import dev.hoshino.bridge.metrics.RunMetrics
import dev.hoshino.bridge.metrics.RunSummaryFormat

/**
 * 每轮一行小结（OB3）：结局 · 用时 · 和模型来回 · Token · 首个反馈 · 缓存命中；点开看时间去向、最慢的工具、Token 明细与预估费用。
 * 数字来自 [RunMetricsHub]（dsh 原版会话日志 + 界面实测），这一轮还在跑时不显示。独立组件，ChatScreen 里只挂一行。
 */
@Composable
fun RunSummary(sessionId: String, modifier: Modifier = Modifier) {
    if (sessionId.isEmpty()) return
    val all by RunMetricsHub.latest.collectAsStateWithLifecycle()
    val sessions by Hoshino.client.sessions.collectAsStateWithLifecycle()
    LaunchedEffect(sessionId) { RunMetricsHub.request(sessionId) }
    val m = all[sessionId] ?: return
    if (sessions.firstOrNull { it.id == sessionId }?.running == true) return
    RunSummaryRow(m, modifier)
}

@Composable
private fun RunSummaryRow(m: RunMetrics, modifier: Modifier) {
    val c = HoshinoTheme.colors
    val t = HoshinoTheme.type
    var open by rememberSaveable(m.runId) { mutableStateOf(false) }
    val line = RunSummaryFormat.line(m)
    val outcome = RunSummaryFormat.outcome(m)
    val tone = when (m.outcome) { "succeeded" -> c.success; "failed" -> c.danger; else -> c.text3 }
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .clip(HoshinoShapes.small)
            .clickable { open = !open }
            .semantics { contentDescription = "运行小结：$outcome，$line" }
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).clip(HoshinoShapes.pill).background(tone))
            Spacer(Modifier.width(6.dp))
            Text(outcome, style = t.caption, color = c.text2)
            Spacer(Modifier.width(6.dp))
            Text(line, style = t.caption, color = c.text3, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            HIcon(if (open) HIcons.ChevronUp else HIcons.ChevronDown, size = 14.dp, tint = c.text3)
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TimeBar(m)
                RunSummaryFormat.details(m, RunMetricsHub.prices()).forEach { (k, v) ->
                    Row {
                        Text(k, style = t.caption, color = c.text3, modifier = Modifier.width(96.dp))
                        Text(v, style = t.caption, color = c.text2, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** 时间去向：模型思考 / 读 / 操作 / 其他，按占总时长的比例画成一条，下面是图例。 */
@Composable
private fun TimeBar(m: RunMetrics) {
    val c = HoshinoTheme.colors
    val shares = RunSummaryFormat.breakdown(m)
    val colors = listOf(c.accent, c.success, c.warning, c.text3)
    Row(Modifier.fillMaxWidth().height(6.dp).clip(HoshinoShapes.pill).background(c.surface2)) {
        shares.forEachIndexed { i, s -> if (s.fraction > 0f) Box(Modifier.weight(s.fraction).height(6.dp).background(colors[i])) }
        val rest = 1f - shares.sumOf { it.fraction.toDouble() }.toFloat()
        if (rest > 0.001f) Box(Modifier.weight(rest).height(6.dp).background(Color.Transparent))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 2.dp, bottom = 2.dp)) {
        shares.forEachIndexed { i, s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(HoshinoShapes.pill).background(colors[i]))
                Spacer(Modifier.width(3.dp))
                Text("${s.label} ${RunSummaryFormat.seconds(s.ms)}", style = HoshinoTheme.type.caption, color = c.text3)
            }
        }
    }
}
