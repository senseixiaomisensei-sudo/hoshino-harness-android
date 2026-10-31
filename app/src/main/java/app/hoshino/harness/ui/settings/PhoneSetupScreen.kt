package app.hoshino.harness.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
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
 * 「设置手机操作」向导 (PH22). One page, steps in order, each ticks itself from what the system reports; the
 * consent step lists every system change Hoshino could make and nothing is done before the user ticks it
 * (mika-gap §2 PL3, "自动打开无线调试" unticked by default). "稍后再说" is always possible: leaving the page.
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
    var draft by remember(saved) { mutableStateOf(saved.copy(confirmed = true)) }
    var userSaysDeveloperOn by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var tick by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { while (true) { delay(1500); tick++ } }

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
        )
    }
    val marks = PhoneSetupFlow.marks(input)
    val current = PhoneSetupFlow.current(input)

    PageScaffold("设置手机操作", onBack, subtitle = "让 Hoshino 在后台屏上替你操作 App") {
        Text(
            "Hoshino 在一块你看不见的后台屏上打开和操作 App，你照常用手机。发消息、删除、下单、提交前会单独问你；付款、密码、验证码永远交给你自己。",
            style = t.caption, color = c.text2, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        PhoneSetupFlow.likelyProblem(input)?.let { Text("最可能卡住的一步：$it", style = t.caption, color = c.warning, modifier = Modifier.padding(horizontal = 20.dp)) }

        Step("1 · 系统版本", marks[PhoneSetupFlow.Step.Version]) {
            Hint(if (Build.VERSION.SDK_INT >= 34) "Android ${Build.VERSION.RELEASE}：后台屏有独立焦点，你打字不会被打断。" else if (Build.VERSION.SDK_INT == 33) "Android 13：可以用，但后台屏输入时可能抢走你正在用的输入框焦点，推荐 14+。" else "需要 Android 13 或更高版本。")
        }

        Step("2 · 你同意 Hoshino 做哪些系统改动", marks[PhoneSetupFlow.Step.Consent]) {
            Hint("下面每一项都不会在你勾选前发生。可以随时回来改；取消勾选后不再自动执行（已经打开的系统开关请在系统设置里关）。")
            ConsentRow("配对后自动开启 Hoshino 的无障碍服务", "用助手直接写入无障碍开关，省去「受限制的设置」的几步。推荐。", draft.enableAccessibility) { draft = draft.copy(enableAccessibility = it) }
            ConsentRow("授予 Hoshino「修改安全设置」权限（WRITE_SECURE_SETTINGS）", "只用于下面「自动打开无线调试」。", draft.writeSecureSettings) { draft = draft.copy(writeSecureSettings = it, autoWirelessDebugging = it && draft.autoWirelessDebugging) }
            ConsentRow("连上 Wi-Fi 后自动打开无线调试", "重启或换网后系统会关掉无线调试；勾选后 Hoshino 会在每个网络上尝试打开一次（每次都会通知你并记审计）。打开的调试端口只对本机配对过的设备可用。默认不勾。", draft.autoWirelessDebugging, enabled = draft.writeSecureSettings, danger = true) { draft = draft.copy(autoWirelessDebugging = it) }
            ConsentRow("把 Hoshino 加入电池优化白名单", "长任务不会被省电策略中断。", draft.batteryExemption) { draft = draft.copy(batteryExemption = it) }
            ConsentRow("任务期间把后台屏上的 App 静音", "改该 App 的播放声音权限，任务结束或崩溃后恢复原样。", draft.muteAgentApps) { draft = draft.copy(muteAgentApps = it) }
            ConsentRow("常驻悬浮胶囊", "没有任务时也显示，双击胶囊把此刻屏幕发给 Hoshino。", draft.capsuleAlways) { draft = draft.copy(capsuleAlways = it) }
            ConsentRow("允许 Agent 操作 Hoshino 自己", "默认拒绝：任何模式下 Agent 都不能点 Hoshino 的界面。", draft.allowSelf, danger = true) { draft = draft.copy(allowSelf = it) }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HButton("保存并应用", { consent.save(draft); scope.launch { services.applyConsent() }; message = "已保存" }, style = BtnStyle.Primary)
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
            Hint(if (a11y != null) "已开启。" else if (saved.enableAccessibility && input.helperRunning) "已同意自动开启，助手运行后会自动打开；没生效就手动开。" else "在系统「无障碍」里找到「Hoshino 手机操作」打开。")
            if (a11y == null) HButton("打开无障碍设置", { open(context, PhoneSetupFlow.accessibilityPage()) }, compact = true, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }

        Step("6 · 文字识别（可选，开源离线）", marks[PhoneSetupFlow.Step.Ocr]) {
            Hint("有的 App 自己画界面，无障碍读不到字时用开源 OCR（PaddleOCR PP-OCRv4 + ONNX Runtime）补读，完全在本机运行。模型约 15.6 MB，只在你点下载时联网一次并校验 SHA-256。")
            when (ocrState) {
                OcrModels.State.Ready -> Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) { Tag("已就绪", color = c.success); HButton("删除模型", { OcrModels.delete(context) }, style = BtnStyle.Ghost, compact = true) }
                OcrModels.State.Downloading -> ThinProgress(ocrProgress, Modifier.fillMaxWidth().padding(16.dp))
                else -> HButton("下载文字识别模型（15.6 MB）", { scope.launch { message = OcrModels.download(context)?.let { "下载失败：$it" } ?: "文字识别已就绪" } }, compact = true, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
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
