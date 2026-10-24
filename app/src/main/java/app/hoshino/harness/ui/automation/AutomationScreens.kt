package app.hoshino.harness.ui.automation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hoshino.harness.core.Goal
import app.hoshino.harness.core.GoalStatus
import app.hoshino.harness.core.Repeat
import app.hoshino.harness.core.Schedule
import app.hoshino.harness.ui.ConfigViewModel
import app.hoshino.harness.ui.components.BtnStyle
import app.hoshino.harness.ui.components.EmptyState
import app.hoshino.harness.ui.components.HButton
import app.hoshino.harness.ui.components.HDialog
import app.hoshino.harness.ui.components.HIcon
import app.hoshino.harness.ui.components.HSheet
import app.hoshino.harness.ui.components.HSlider
import app.hoshino.harness.ui.components.HTextField
import app.hoshino.harness.ui.components.Hairline
import app.hoshino.harness.ui.components.IconBtn
import app.hoshino.harness.ui.components.PageScaffold
import app.hoshino.harness.ui.components.SectionLabel
import app.hoshino.harness.ui.components.Segmented
import app.hoshino.harness.ui.components.StatusDot
import app.hoshino.harness.ui.components.Tag
import app.hoshino.harness.ui.components.ThinProgress
import app.hoshino.harness.ui.components.futureTime
import app.hoshino.harness.ui.components.hairlineCard
import app.hoshino.harness.ui.components.relativeTime
import app.hoshino.harness.ui.settings.CollectToasts
import app.hoshino.harness.ui.theme.HIcons
import app.hoshino.harness.ui.theme.HoshinoTheme

// ================================================================ 定时任务

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SchedulesScreen(vm: ConfigViewModel, onBack: () -> Unit) {
    val schedules by vm.schedules.collectAsStateWithLifecycle()
    val c = HoshinoTheme.colors
    val t = HoshinoTheme.type
    var editing by remember { mutableStateOf<Schedule?>(null) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var deleteFor by remember { mutableStateOf<Schedule?>(null) }
    CollectToasts(vm)

    PageScaffold("定时任务", onBack, actions = {
        IconBtn(HIcons.Plus, "新建", {
            editing = Schedule("sc${System.currentTimeMillis()}", "", "", Repeat.Daily, "每天 09:00", "Asia/Shanghai", null, true)
        })
    }) {
        Spacer(Modifier.height(12.dp))
        if (schedules.isEmpty()) EmptyState("暂无定时任务", subtitle = "也可以在对话中输入 /schedule 创建", icon = HIcons.Clock)
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            schedules.forEach { s ->
                val open = expanded == s.id
                Column(Modifier.fillMaxWidth().hairlineCard(c.surface, c.line)) {
                    Column(Modifier.fillMaxWidth().clickable { expanded = if (open) null else s.id }.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(s.title, style = t.bodyMedium, color = c.text, modifier = Modifier.weight(1f))
                            StatusDot(if (s.enabled) c.success else c.text3, size = 6.dp)
                            Spacer(Modifier.width(6.dp))
                            Text(if (s.enabled) "启用" else "已暂停", style = t.caption, color = c.text2)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text("${s.repeat.label} · ${s.rule} · ${s.timezone}", style = t.caption, color = c.text2)
                        Text("下次运行：${if (s.enabled) futureTime(s.nextRun) else "—"}", style = t.caption, color = c.text3)
                    }
                    AnimatedVisibility(open) {
                        Column {
                            Hairline()
                            Text(s.prompt, style = t.caption, color = c.text2, modifier = Modifier.padding(14.dp))
                            FlowRow(Modifier.padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                HButton(if (s.enabled) "暂停" else "恢复", { vm.run { setScheduleEnabled(s.id, !s.enabled) } }, style = BtnStyle.Secondary, compact = true, icon = if (s.enabled) HIcons.Pause else HIcons.Play)
                                HButton("立即运行", { vm.run("已运行") { runScheduleNow(s.id) } }, style = BtnStyle.Secondary, compact = true, icon = HIcons.Play)
                                HButton("编辑", { editing = s }, style = BtnStyle.Secondary, compact = true, icon = HIcons.Edit)
                                HButton("删除", { deleteFor = s }, style = BtnStyle.Ghost, compact = true, icon = HIcons.Trash)
                            }
                            SectionLabel("运行记录")
                            if (s.runs.isEmpty()) {
                                Text("暂无", style = t.caption, color = c.text3, modifier = Modifier.padding(start = 20.dp, bottom = 14.dp))
                            }
                            s.runs.forEach { r ->
                                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    StatusDot(if (r.ok) c.success else c.danger, size = 6.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text(relativeTime(r.at), style = t.codeSmall, color = c.text3, modifier = Modifier.width(80.dp))
                                    Text(r.summary, style = t.caption, color = c.text)
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }
        }
    }
    editing?.let { s -> ScheduleEditSheet(s, onSave = { vm.run("已保存") { upsertSchedule(it) }; editing = null }, onDismiss = { editing = null }) }
    deleteFor?.let { s ->
        HDialog("删除定时任务？", onDismiss = { deleteFor = null }, confirmText = "删除", danger = true, onConfirm = {
            deleteFor = null; vm.run { deleteSchedule(s.id) }
        }) { Text(s.title, style = t.body, color = c.text2) }
    }
}

@Composable
private fun ScheduleEditSheet(initial: Schedule, onSave: (Schedule) -> Unit, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf(initial.title) }
    var prompt by remember { mutableStateOf(initial.prompt) }
    var repeat by remember { mutableStateOf(initial.repeat) }
    var rule by remember { mutableStateOf(initial.rule) }
    var tz by remember { mutableStateOf(initial.timezone) }
    HSheet(onDismiss = onDismiss, title = if (initial.title.isEmpty()) "新建定时任务" else "编辑定时任务") {
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            HTextField(title, { title = it }, label = "标题", placeholder = "每日摘要")
            HTextField(prompt, { prompt = it }, label = "提示词", placeholder = "到点后 Agent 要做什么", singleLine = false, minLines = 3)
            Segmented(Repeat.entries, repeat, { it.label }, {
                repeat = it
                rule = when (it) {
                    Repeat.Once -> "明天 09:00"
                    Repeat.Daily -> "每天 09:00"
                    Repeat.Weekly -> "每周一 09:00"
                    Repeat.Cron -> "0 9 * * 1-5"
                }
            }, Modifier.fillMaxWidth())
            HTextField(rule, { rule = it }, label = if (repeat == Repeat.Cron) "cron 表达式" else "时间", mono = repeat == Repeat.Cron)
            HTextField(tz, { tz = it }, label = "时区", mono = true, hint = "IANA 时区，如 Asia/Shanghai")
            HButton("保存", {
                onSave(initial.copy(title = title.trim(), prompt = prompt.trim(), repeat = repeat, rule = rule.trim(), timezone = tz.trim(), nextRun = System.currentTimeMillis() + 3_600_000L))
            }, Modifier.fillMaxWidth(), style = BtnStyle.Primary, enabled = title.isNotBlank() && prompt.isNotBlank())
            Spacer(Modifier.height(4.dp))
        }
    }
}

// ================================================================ 长期目标

@Composable
fun GoalsScreen(vm: ConfigViewModel, onBack: () -> Unit) {
    val goals by vm.goals.collectAsStateWithLifecycle()
    val c = HoshinoTheme.colors
    val t = HoshinoTheme.type
    var editing by remember { mutableStateOf<Goal?>(null) }
    CollectToasts(vm)
    PageScaffold("长期目标", onBack, actions = {
        IconBtn(HIcons.Plus, "新建目标", { editing = Goal("g${System.currentTimeMillis()}", "", "", GoalStatus.Active, 0f, System.currentTimeMillis()) })
    }) {
        if (goals.isEmpty()) EmptyState("暂无长期目标", subtitle = "在对话中输入 /goal 也可以设定", icon = HIcons.Target)
        GoalStatus.entries.forEach { status ->
            val list = goals.filter { it.status == status }
            if (list.isNotEmpty()) {
                SectionLabel("${status.label} · ${list.size}")
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    list.forEach { g ->
                        Column(Modifier.fillMaxWidth().hairlineCard(c.surface, c.line).clickable { editing = g }.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                HIcon(HIcons.Target, size = 16.dp, tint = if (g.status == GoalStatus.Active) c.accent else c.text3)
                                Spacer(Modifier.width(8.dp))
                                Text(g.title, style = t.bodyMedium, color = c.text, modifier = Modifier.weight(1f))
                                Tag(g.status.label, color = if (g.status == GoalStatus.Active) c.accent else c.text2, bg = if (g.status == GoalStatus.Active) c.accentSoft else c.surface2)
                            }
                            if (g.description.isNotEmpty()) Text(g.description, style = t.caption, color = c.text2, modifier = Modifier.padding(top = 4.dp))
                            Spacer(Modifier.height(10.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ThinProgress(g.progress, Modifier.weight(1f), color = if (g.status == GoalStatus.Done) c.success else c.accent)
                                Spacer(Modifier.width(10.dp))
                                Text("${(g.progress * 100).toInt()}%", style = t.codeSmall, color = c.text3)
                            }
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                when (g.status) {
                                    GoalStatus.Active -> {
                                        HButton("暂停", { vm.run { setGoalStatus(g.id, GoalStatus.Paused) } }, style = BtnStyle.Secondary, compact = true, icon = HIcons.Pause)
                                        HButton("标记完成", { vm.run { setGoalStatus(g.id, GoalStatus.Done) } }, style = BtnStyle.Ghost, compact = true, icon = HIcons.Check)
                                    }
                                    GoalStatus.Paused -> HButton("恢复", { vm.run { setGoalStatus(g.id, GoalStatus.Active) } }, style = BtnStyle.Secondary, compact = true, icon = HIcons.Play)
                                    GoalStatus.Done -> HButton("重新打开", { vm.run { setGoalStatus(g.id, GoalStatus.Active) } }, style = BtnStyle.Ghost, compact = true)
                                }
                                Spacer(Modifier.weight(1f))
                                Text("更新于 ${relativeTime(g.updatedAt)}", style = t.codeSmall, color = c.text3)
                            }
                        }
                    }
                }
            }
        }
    }
    editing?.let { g ->
        var title by remember(g.id) { mutableStateOf(g.title) }
        var desc by remember(g.id) { mutableStateOf(g.description) }
        var progress by remember(g.id) { mutableStateOf(g.progress) }
        HSheet(onDismiss = { editing = null }, title = if (g.title.isEmpty()) "新建长期目标" else "编辑目标") {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                HTextField(title, { title = it }, label = "目标", placeholder = "把测试覆盖率提升到 80%")
                HTextField(desc, { desc = it }, label = "说明", placeholder = "验收标准、节奏、注意事项", singleLine = false, minLines = 3)
                Text("进度 ${(progress * 100).toInt()}%", style = t.caption, color = c.text2)
                HSlider(progress, { progress = it }, 0f..1f)
                HButton("保存", {
                    vm.run("已保存") { upsertGoal(g.copy(title = title.trim(), description = desc.trim(), progress = progress, updatedAt = System.currentTimeMillis())) }
                    editing = null
                }, Modifier.fillMaxWidth(), style = BtnStyle.Primary, enabled = title.isNotBlank())
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}
