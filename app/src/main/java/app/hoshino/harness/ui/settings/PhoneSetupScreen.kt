package app.hoshino.harness.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.hoshino.harness.phone.PhoneAccessibilityService
import app.hoshino.harness.phone.PhoneConsent
import app.hoshino.harness.phone.PhoneConsentState
import app.hoshino.harness.phone.PhoneController
import app.hoshino.harness.phone.ocr.OcrModels
import app.hoshino.harness.phone.setup.PairingCoordinator
import app.hoshino.harness.phone.setup.PairingCopy
import app.hoshino.harness.phone.setup.PairingNotifier
import app.hoshino.harness.phone.setup.PhoneSetupFlow
import app.hoshino.harness.phone.setup.SelfHealSettings
import app.hoshino.harness.ui.components.BtnStyle
import app.hoshino.harness.ui.components.GroupCard
import app.hoshino.harness.ui.components.HButton
import app.hoshino.harness.ui.components.HCheckbox
import app.hoshino.harness.ui.components.HTextField
import app.hoshino.harness.ui.components.Hairline
import app.hoshino.harness.ui.components.ListRow
import app.hoshino.harness.ui.components.PageScaffold
import app.hoshino.harness.ui.components.SectionLabel
import app.hoshino.harness.ui.components.Tag
import app.hoshino.harness.ui.components.ThinProgress
import app.hoshino.harness.ui.theme.HoshinoTheme
import dev.hoshino.bridge.phone.shell.BuiltinShellStatus
import dev.hoshino.bridge.phone.shell.PairingDiscovery
import dev.hoshino.bridge.phone.shell.PhoneChannel
import dev.hoshino.bridge.phone.shell.PhoneServices
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 「设置手机操作」向导 (PH22, WP-9). The consent page is the first screen: every system change Hoshino could make
 * is listed, all unticked (mika-gap §2 PL3), and "按所选开始" saves the ticks and goes straight to the next system
 * page. After pairing, the helper switches the accessibility service on only when the user ticked that item, so
 * the user normally leaves Hoshino at most twice or three times (D-1: 首配中位 ≤ 120 s). Steps tick themselves from
 * what the system reports; "稍后再说" is always possible: leaving the page.
 */
@Composable
fun PhoneSetupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val c = HoshinoTheme.colors
    val t = HoshinoTheme.type
    val scope = rememberCoroutineScope()
    val services = remember { PhoneServices.of(context) }
    val consent = remember { PhoneConsent.of(context) }
    val pairing = remember { PairingCoordinator.of(context) }
    val status by services.builtin.engine.status.collectAsState()
    val discovery by services.builtin.engine.pairingDiscovery.collectAsState()
    val channel by services.channel.collectAsState()
    val a11y by PhoneAccessibilityService.instance.collectAsState()
    val saved by consent.state.collectAsState()
    val ocrState by remember { OcrModels.stateFlow(context) }.collectAsState()
    val ocrProgress by OcrModels.progress.collectAsState()
    val issues by services.setupIssues.collectAsState()
    val selfHeal = remember { SelfHealSettings.of(context) }
    val healSaved by selfHeal.wanted.collectAsState()
    val healPaused by selfHeal.paused.collectAsState()
    val observation by services.accessibilityObservation.collectAsState()
    val lastHeal by remember { services.lastHeal }.collectAsState()
    var draft by remember(saved) { mutableStateOf(saved.copy(confirmed = true)) }
    var healDraft by remember(healSaved) { mutableStateOf(healSaved) }
    var moreItems by remember { mutableStateOf(false) }
    var editConsent by remember { mutableStateOf(false) }
    var helperUpAt by remember { mutableStateOf<Long?>(null) }
    var userSaysDeveloperOn by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var tick by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { while (true) { delay(1000); tick++ } }
    val helperUp = status == BuiltinShellStatus.Running || channel != PhoneChannel.None
    LaunchedEffect(helperUp, a11y) { helperUpAt = if (helperUp && a11y == null) SystemClock.elapsedRealtime() else null }

    val input = remember(status, channel, a11y, saved, ocrState, userSaysDeveloperOn, tick) {
        PhoneSetupFlow.Input(
            sdk = Build.VERSION.SDK_INT,
            consentConfirmed = saved.confirmed,
            shizukuReady = channel == PhoneChannel.Shizuku,
            developerOptions = runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1 }.getOrNull(),
            wirelessDebugging = runCatching { Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) == 1 }.getOrNull(),
            paired = services.builtin.engine.paired,
            helperRunning = status == BuiltinShellStatus.Running || channel != PhoneChannel.None,
            accessibility = a11y != null,
            ocrReady = ocrState == OcrModels.State.Ready,
            userSaysDeveloperOn = userSaysDeveloperOn,
            autoAccessibility = saved.enableAccessibility,
            helperUpWithoutA11yMs = helperUpAt?.let { SystemClock.elapsedRealtime() - it },
        )
    }
    val marks = PhoneSetupFlow.marks(input)
    val current = PhoneSetupFlow.current(input)
    val primary = PhoneSetupFlow.primary(input)

    fun go(p: PhoneSetupFlow.Primary) {
        when (p) {
            PhoneSetupFlow.Primary.OpenDeveloperOptions -> open(context, PhoneSetupFlow.developerOptionsPage())
            PhoneSetupFlow.Primary.StartPairing -> { pairing.begin(); open(context, PhoneSetupFlow.wirelessDebuggingPage()) }
            PhoneSetupFlow.Primary.OpenAccessibility -> open(context, PhoneSetupFlow.accessibilityPage())
            else -> Unit
        }
    }

    fun confirm() {
        consent.save(draft)
        selfHeal.save(healDraft && draft.enableAccessibility)
        scope.launch { services.applyConsent() }
        message = "已保存"
        go(PhoneSetupFlow.afterConsent(input.copy(consentConfirmed = true, autoAccessibility = draft.enableAccessibility)))
    }

    PageScaffold("设置手机操作", onBack, subtitle = "让 Hoshino 在后台屏上替你操作 App") {
        Text(
            "Hoshino 在一块你看不见的后台屏上打开和操作 App，你照常用手机。发消息、删除、下单、提交前会单独问你；付款、密码、验证码永远交给你自己。",
            style = t.caption, color = c.text2, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        PhoneSetupFlow.likelyProblem(input)?.let { Text("最可能卡住的一步：$it", style = t.caption, color = c.warning, modifier = Modifier.padding(horizontal = 20.dp)) }

        Step("1 · 系统版本", marks[PhoneSetupFlow.Step.Version]) {
            Hint(if (Build.VERSION.SDK_INT >= 34) "Android ${Build.VERSION.RELEASE}：后台屏有独立焦点，你打字不会被打断。" else if (Build.VERSION.SDK_INT == 33) "Android 13：可以用，但后台屏输入时可能抢走你正在用的输入框焦点，推荐 14+。" else "需要 Android 13 或更高版本。")
        }

        if (current != PhoneSetupFlow.Step.Version && current != PhoneSetupFlow.Step.Done) {
            SectionLabel("下一步") { Text("还要离开 Hoshino 约 ${PhoneSetupFlow.remainingTrips(input)} 次", style = t.caption, color = c.text2) }
            GroupCard {
                Column(Modifier.padding(vertical = 6.dp)) {
                    Hint(primaryHint(primary))
                    when (primary) {
                        PhoneSetupFlow.Primary.Confirm -> Unit // the button sits under the consent items below
                        PhoneSetupFlow.Primary.WaitAutoAccessibility -> ThinProgress(((input.helperUpWithoutA11yMs ?: 0L).toFloat() / PhoneSetupFlow.AUTO_A11Y_WAIT_MS).coerceIn(0f, 1f), Modifier.fillMaxWidth().padding(16.dp))
                        PhoneSetupFlow.Primary.Done, PhoneSetupFlow.Primary.Unsupported -> Unit
                        else -> HButton(primaryLabel(primary), { go(primary) }, style = BtnStyle.Primary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                    }
                }
            }
        }

        Step("2 · 你同意 Hoshino 做哪些系统改动", marks[PhoneSetupFlow.Step.Consent]) {
          if (saved.confirmed && !editConsent) {
            val on = listOf(saved.enableAccessibility, selfHeal.consent(), saved.batteryExemption, saved.writeSecureSettings, saved.autoWirelessDebugging, saved.muteAgentApps, saved.capsuleAlways, saved.allowSelf).count { it }
            ListRow("已同意 $on 项", subtitle = "点这里查看或修改", onClick = { editConsent = true }, showChevron = true)
          } else {
            Hint("每一项默认不勾，勾选前都不会发生；付款、密码、验证码永远交给你自己。可以随时回来改；取消勾选后不再自动执行（已经打开的系统开关请在系统设置里关）。")
            ConsentRow("配对后自动开启 Hoshino 的无障碍服务（推荐）", "配对成功后由助手直接写入无障碍开关，你不用进系统无障碍设置，也不用处理「受限制的设置」。", draft.enableAccessibility) {
                draft = draft.copy(enableAccessibility = it); if (!it) healDraft = false
            }
            ConsentRow("清理后台或无障碍被系统关掉后自动恢复（推荐）", "HyperOS 一键清理会关掉 Hoshino 的无障碍：勾选后助手在约 10 秒内把它重新打开并重连（每次写审计；同一个 10 分钟内最多 3 次）。你自己在系统里关掉无障碍时不会被打开回去。", healDraft, enabled = draft.enableAccessibility) { healDraft = it }
            ConsentRow("把 Hoshino 加入电池优化白名单（推荐）", "长任务不会被省电策略中断。", draft.batteryExemption) { draft = draft.copy(batteryExemption = it) }
            ListRow(if (moreItems) "收起其它授权项" else "其它授权项（4 项，默认不勾）", onClick = { moreItems = !moreItems }, showChevron = true)
            if (moreItems) {
                ConsentRow("授予 Hoshino「修改安全设置」权限（WRITE_SECURE_SETTINGS）", "用于「自动打开无线调试」；助手不在时也能自己恢复无障碍。", draft.writeSecureSettings) { draft = draft.copy(writeSecureSettings = it, autoWirelessDebugging = it && draft.autoWirelessDebugging) }
                ConsentRow("连上 Wi-Fi 后自动打开无线调试", "重启或换网后系统会关掉无线调试；勾选后 Hoshino 会在每个网络上尝试打开一次（每次都会通知你并记审计）。打开的调试端口只对本机配对过的设备可用。默认不勾。", draft.autoWirelessDebugging, enabled = draft.writeSecureSettings, danger = true) { draft = draft.copy(autoWirelessDebugging = it) }
                ConsentRow("任务期间把后台屏上的 App 静音", "改该 App 的播放声音权限，任务结束或崩溃后恢复原样。", draft.muteAgentApps) { draft = draft.copy(muteAgentApps = it) }
                ConsentRow("常驻悬浮胶囊", "没有任务时也显示，双击胶囊把此刻屏幕发给 Hoshino。", draft.capsuleAlways) { draft = draft.copy(capsuleAlways = it) }
                ConsentRow("允许 Agent 操作 Hoshino 自己", "默认拒绝：任何模式下 Agent 都不能点 Hoshino 的界面。", draft.allowSelf, danger = true) { draft = draft.copy(allowSelf = it) }
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!saved.confirmed) HButton("按所选开始", { confirm() }, style = BtnStyle.Primary)
                else HButton("保存并应用", { consent.save(draft); selfHeal.save(healDraft && draft.enableAccessibility); scope.launch { services.applyConsent() }; message = "已保存"; editConsent = false }, style = BtnStyle.Primary)
            }
          }
            if (issues.isNotEmpty()) Hint("这些项目没能执行：" + issues.joinToString("、"), warn = true)
        }

        if (channel == PhoneChannel.Shizuku) {
            Step("3 · 手机助手", PhoneSetupFlow.Mark.Done) { Hint("正在通过 Shizuku 运行手机助手，不需要配对无线调试。") }
        } else {
            Step("3 · 打开开发者选项与无线调试", marks[PhoneSetupFlow.Step.Developer]) {
                Hint("需要连着 Wi-Fi（不能上网的热点也行）。")
                Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HButton("打开无线调试", { open(context, PhoneSetupFlow.wirelessDebuggingPage()) }, compact = true)
                    HButton("我已经打开了", { userSaysDeveloperOn = true }, style = BtnStyle.Ghost, compact = true)
                }
            }
            Step("4 · 配对（只需一次）", marks[PhoneSetupFlow.Step.Pairing]) {
                Hint("在「无线调试」里点「使用配对码配对设备」。无障碍开着时 Hoshino 会自己读出配对码；否则在通知里或下面填 6 位配对码。")
                Hint("状态：" + statusText(status) + when (val d = discovery) { is PairingDiscovery.Found -> " · 已找到配对窗口（端口 ${d.port}）"; PairingDiscovery.Searching -> " · 正在找配对窗口…"; else -> "" })
                if (PairingNotifier.channelLowered(context)) Hint("配对通知的提醒级别被调低了，填码通知可能不弹出。", warn = true)
                Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HButton("开始配对", { pairing.begin(); open(context, PhoneSetupFlow.wirelessDebuggingPage()) }, style = BtnStyle.Primary, compact = true)
                    HButton("重新连接", { scope.launch { services.builtin.engine.retry() } }, compact = true)
                    if (services.builtin.engine.paired) HButton("解除配对", { scope.launch { services.builtin.engine.unpair() } }, style = BtnStyle.Ghost, compact = true)
                }
                Column(Modifier.padding(horizontal = 16.dp)) {
                    HTextField(code, { code = it.take(12) }, label = "配对码", placeholder = "6 位数字", keyboardType = KeyboardType.Number)
                    HTextField(port, { port = it.take(22) }, label = "配对端口（可不填）", placeholder = "例如 192.168.1.5:37123")
                    HButton("用这个配对码配对", {
                        message = if (pairing.submit(code, dev.hoshino.bridge.phone.shell.PairingCode.port(port))) "正在配对…" else "要填 6 位数字配对码"
                    }, compact = true, modifier = Modifier.padding(vertical = 6.dp))
                }
                (status as? BuiltinShellStatus.Failed)?.let { Hint(PairingCopy.failure(it.reason), warn = true) }
            }
        }

        Step("5 · 无障碍服务", marks[PhoneSetupFlow.Step.Accessibility]) {
            Hint(when {
                a11y != null -> "已开启。"
                PhoneSetupFlow.autoPending(input) -> "助手正在为你打开无障碍服务…"
                saved.enableAccessibility && input.helperRunning -> "自动开启没有生效：请手动打开（HyperOS 可能要先在应用信息里「允许受限制的设置」）。"
                saved.enableAccessibility -> "已同意自动开启：配对成功后助手会自动打开。"
                else -> "在系统「无障碍」里找到「Hoshino 手机操作」打开。"
            })
            if (a11y == null && !PhoneSetupFlow.autoPending(input)) HButton("打开无障碍设置", { open(context, PhoneSetupFlow.accessibilityPage()) }, compact = true, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }

        Step("6 · 文字识别（可选，开源离线）", marks[PhoneSetupFlow.Step.Ocr]) {
            Hint("有的 App 自己画界面，无障碍读不到字时用开源 OCR（PaddleOCR PP-OCRv4 + ONNX Runtime）补读，完全在本机运行。模型约 15.6 MB，只在你点下载时联网一次并校验 SHA-256。")
            when (ocrState) {
                OcrModels.State.Ready -> Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) { Tag("已就绪", color = c.success); HButton("删除模型", { OcrModels.delete(context) }, style = BtnStyle.Ghost, compact = true) }
                OcrModels.State.Downloading -> ThinProgress(ocrProgress, Modifier.fillMaxWidth().padding(16.dp))
                else -> HButton("下载文字识别模型（15.6 MB）", { scope.launch { message = OcrModels.download(context)?.let { "下载失败：$it" } ?: "文字识别已就绪" } }, compact = true, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
        }

        SectionLabel("保活与自愈")
        GroupCard {
            Column(Modifier.padding(vertical = 6.dp)) {
                Hint(when {
                    !selfHeal.consent() -> "未开启自动恢复：清理后台后需要你手动重新打开无障碍。"
                    healPaused -> "你在系统里关掉了无障碍，自动恢复已暂停；重新打开无障碍或在上面重新保存后恢复。"
                    else -> "已开启：无障碍被清理后约 10 秒内自动恢复，助手断线会自动重连。"
                })
                Hint("无障碍：" + (if (observation.connected) "运行中" else if (observation.listed) "已开启但没在运行" else "未开启") + " · 助手：" + statusText(status))
                lastHeal?.let { h -> Hint("上次自动恢复：" + (if (h.restored) "成功" else "失败") + (if (h.byHelper) "（助手在 Hoshino 被清理后完成）" else "（尝试 ${h.attempts} 次）")) }
                Hint("任务进行中 Hoshino 保持前台服务；付款、密码、验证码仍然交给你。")
            }
        }

        SectionLabel("小米 / HyperOS 注意事项")
        GroupCard { PhoneSetupFlow.HYPEROS_TIPS.forEachIndexed { i, tip -> if (i > 0) Hairline(); Text(tip, style = t.caption, color = c.text2, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) } }

        SectionLabel("完成")
        GroupCard {
            ListRow(if (current == PhoneSetupFlow.Step.Done) "手机操作已就绪" else "还差：" + stepName(current), subtitle = message,
                trailing = { HButton("复制诊断", { copyDiagnosis(context, scope) }, compact = true) })
        }
    }
}

@Composable
private fun Step(title: String, mark: PhoneSetupFlow.Mark?, content: @Composable () -> Unit) {
    val c = HoshinoTheme.colors
    SectionLabel(title) {
        when (mark) {
            PhoneSetupFlow.Mark.Done -> Tag("完成", color = c.success)
            PhoneSetupFlow.Mark.Unsupported -> Tag("不支持", color = c.danger)
            PhoneSetupFlow.Mark.Optional -> Tag("可选")
            else -> Tag("待完成", color = c.warning)
        }
    }
    GroupCard { Column(Modifier.padding(vertical = 6.dp)) { content() } }
}

@Composable
private fun Hint(text: String, warn: Boolean = false) {
    val c = HoshinoTheme.colors
    Text(text, style = HoshinoTheme.type.caption, color = if (warn) c.warning else c.text2, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
}

@Composable
private fun ConsentRow(title: String, detail: String, checked: Boolean, enabled: Boolean = true, danger: Boolean = false, onChange: (Boolean) -> Unit) {
    ListRow(title, subtitle = detail, trailing = { if (enabled) HCheckbox(checked, onChange, danger = danger) else Tag("先勾上一项") })
}

private fun statusText(s: BuiltinShellStatus) = when (s) {
    BuiltinShellStatus.NotPaired -> "未配对"
    BuiltinShellStatus.WirelessDebuggingOff -> "无线调试已关闭"
    BuiltinShellStatus.Connecting -> "正在连接"
    BuiltinShellStatus.Running -> "助手运行中"
    is BuiltinShellStatus.Failed -> "失败"
    BuiltinShellStatus.Unsupported -> "系统版本不支持"
}

private fun primaryLabel(p: PhoneSetupFlow.Primary) = when (p) {
    PhoneSetupFlow.Primary.OpenDeveloperOptions -> "打开开发者选项"
    PhoneSetupFlow.Primary.StartPairing -> "去打开无线调试并配对"
    PhoneSetupFlow.Primary.OpenAccessibility -> "打开无障碍设置"
    else -> "继续"
}

private fun primaryHint(p: PhoneSetupFlow.Primary) = when (p) {
    PhoneSetupFlow.Primary.Confirm -> "先在下面勾选你同意的项目（都不勾也可以），再点「按所选开始」，会直接带你去下一步的系统页面。"
    PhoneSetupFlow.Primary.OpenDeveloperOptions -> "在「全部参数与信息」里连点 7 次「OS 版本」打开开发者选项，然后回来。"
    PhoneSetupFlow.Primary.StartPairing -> "打开「无线调试」，点「使用配对码配对设备」；配对码会自动读到，或在通知里填 6 位数字。"
    PhoneSetupFlow.Primary.WaitAutoAccessibility -> "配对成功，助手正在打开无障碍服务…"
    PhoneSetupFlow.Primary.OpenAccessibility -> "在系统「无障碍」里打开「Hoshino 手机操作」。"
    PhoneSetupFlow.Primary.Done -> "手机操作已就绪。"
    PhoneSetupFlow.Primary.Unsupported -> "需要 Android 13 或更高版本。"
}

private fun stepName(s: PhoneSetupFlow.Step) = when (s) {
    PhoneSetupFlow.Step.Version -> "系统版本"
    PhoneSetupFlow.Step.Consent -> "同意项"
    PhoneSetupFlow.Step.Developer -> "打开无线调试"
    PhoneSetupFlow.Step.Pairing -> "配对"
    PhoneSetupFlow.Step.Accessibility -> "无障碍服务"
    PhoneSetupFlow.Step.Ocr -> "文字识别"
    PhoneSetupFlow.Step.Done -> "无"
}

private fun open(context: Context, intents: List<Intent>) {
    for (i in intents) if (runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
}

private fun copyDiagnosis(context: Context, scope: kotlinx.coroutines.CoroutineScope) {
    scope.launch {
        val report = runCatching { PhoneController.of(context).handle(JSONObject().put("action", "diagnose")) }.getOrElse { JSONObject().put("error", it.message) }
        val text = report.toString(2) + "\n" + PhoneConsent.of(context).auditLines(20).joinToString("\n")
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Hoshino 手机诊断", text))
    }
}
