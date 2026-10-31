package app.hoshino.harness.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hoshino.harness.core.RunMetricsHub
import app.hoshino.harness.ui.components.BtnStyle
import app.hoshino.harness.ui.components.GroupCard
import app.hoshino.harness.ui.components.HButton
import app.hoshino.harness.ui.components.HDialog
import app.hoshino.harness.ui.components.HSheet
import app.hoshino.harness.ui.components.HTextField
import app.hoshino.harness.ui.components.Hairline
import app.hoshino.harness.ui.components.ListRow
import app.hoshino.harness.ui.components.Segmented
import app.hoshino.harness.ui.components.copyToClipboard
import app.hoshino.harness.ui.components.shareText
import app.hoshino.harness.ui.theme.HIcons
import app.hoshino.harness.ui.theme.HoshinoTheme
import dev.hoshino.bridge.metrics.RunMetricsStore
import dev.hoshino.bridge.metrics.RunStats
import dev.hoshino.bridge.metrics.RunSummaryFormat

/**
 * 「关于 › 运行统计」（OB5）：最近 10/30/100 次与全部的办成率、用时与第一次反馈的中位数、平均来回与 Token、缓存命中率、时间去向；
 * 复制或分享 JSON（汇总 + 每次小结，只有数字和目标前 80 字）；可填单价估算费用；可清空。口径与 EV2 评测报告的「运行小结」一致（[RunStats]）。
 * 独立组件：关于页里只加一行 [RunStatsEntry]，页面用弹层打开，不新增路由。
 */
@Composable
fun RunStatsEntry() {
    var open by remember { mutableStateOf(false) }
    Spacer(Modifier.height(12.dp))
    GroupCard { ListRow("运行统计", subtitle = "办成率、用时、首个反馈、来回次数、Token", icon = HIcons.Chart, showChevron = true, onClick = { open = true }) }
    if (open) HSheet(onDismiss = { open = false }, title = "运行统计") { RunStatsBody() }
}

@Composable
private fun RunStatsBody() {
    val c = HoshinoTheme.colors
    val t = HoshinoTheme.type
    val context = LocalContext.current
    val revision by RunMetricsHub.revision.collectAsStateWithLifecycle()
    val runs = remember(revision) { RunMetricsHub.runs() }
    var window by remember { mutableStateOf<Int?>(10) }
    var confirmClear by remember { mutableStateOf(false) }
    val stats = remember(runs, window) { RunStats.of(if (window == null) runs else runs.takeLast(window!!)) }
    val prices = RunMetricsHub.priceText()
    var priceIn by remember { mutableStateOf(prices.first) }
    var priceOut by remember { mutableStateOf(prices.second) }
    var priceCached by remember { mutableStateOf(prices.third) }

    Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Segmented(RunMetricsStore.WINDOWS, window, { if (it == null) "全部" else "最近 $it 次" }, { window = it }, Modifier.padding(horizontal = 16.dp).fillMaxWidth())
        GroupCard {
            RunSummaryFormat.stats(stats).forEachIndexed { i, (k, v) ->
                if (i > 0) Hairline()
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
                    Text(k, style = t.body, color = c.text2, modifier = Modifier.width(128.dp))
                    Text(v, style = t.body, color = c.text, modifier = Modifier.weight(1f))
                }
            }
        }
        Text(
            "只算这台设备上看着跑完的轮次；办成按内核的结束原因算（结局判定合并前），第一次反馈优先用界面实测。数字只存在本机，不上传。",
            style = t.caption, color = c.text3, modifier = Modifier.padding(horizontal = 20.dp),
        )
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HButton("复制 JSON", { copyToClipboard(context, RunMetricsHub.exportJson()) }, Modifier.weight(1f), icon = HIcons.Copy, enabled = runs.isNotEmpty())
            HButton("分享 JSON", { shareText(context, RunMetricsHub.exportJson()) }, Modifier.weight(1f), enabled = runs.isNotEmpty())
        }
        GroupCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("单价（每百万 Token，用于小结里的预估费用）", style = t.caption, color = c.text3)
                HTextField(priceIn, { priceIn = it }, label = "输入", keyboardType = KeyboardType.Decimal)
                HTextField(priceOut, { priceOut = it }, label = "输出", keyboardType = KeyboardType.Decimal)
                HTextField(priceCached, { priceCached = it }, label = "缓存命中（留空同输入）", keyboardType = KeyboardType.Decimal)
                HButton("保存单价", { RunMetricsHub.setPrices(priceIn, priceOut, priceCached) }, Modifier.fillMaxWidth(), style = BtnStyle.Secondary)
            }
        }
        HButton("清空运行统计", { confirmClear = true }, Modifier.padding(horizontal = 16.dp).fillMaxWidth(), style = BtnStyle.Danger, enabled = runs.isNotEmpty())
    }
    if (confirmClear) HDialog("清空运行统计？", { confirmClear = false }, confirmText = "清空", onConfirm = { RunMetricsHub.clear(); confirmClear = false }, danger = true) {
        Text("会删除这台设备上记下的全部运行小结，会话本身不受影响。", style = t.body, color = c.text2)
    }
}
