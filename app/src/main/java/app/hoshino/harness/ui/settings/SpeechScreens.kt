// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
// (ui/SpeechModelScreen.kt、voice/SpeechOptions.kt：设置 › 模型 › 语音转文字 的选择页；改写为本项目的组件。)
package app.hoshino.harness.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.hoshino.harness.ui.components.BtnStyle
import app.hoshino.harness.ui.components.GroupCard
import app.hoshino.harness.ui.components.HButton
import app.hoshino.harness.ui.components.HIcon
import app.hoshino.harness.ui.components.HTextField
import app.hoshino.harness.ui.components.Hairline
import app.hoshino.harness.ui.components.ListRow
import app.hoshino.harness.ui.components.PageScaffold
import app.hoshino.harness.ui.components.SectionLabel
import app.hoshino.harness.ui.components.Tag
import app.hoshino.harness.ui.theme.HIcons
import app.hoshino.harness.ui.theme.HoshinoTheme
import app.hoshino.harness.voice.VoiceInput
import dev.hoshino.bridge.voice.SpeechChoice
import dev.hoshino.bridge.voice.SttEndpoint
import dev.hoshino.bridge.voice.SttError
import dev.hoshino.bridge.voice.SystemCapability
import dev.hoshino.bridge.voice.TranscribeMethod
import dev.hoshino.bridge.voice.Transcriber
import kotlinx.coroutines.launch

/** 写法给人看的名字。 */
fun methodLabel(m: TranscribeMethod): String = when (m) {
    TranscribeMethod.AudioTranscriptions -> "转写接口 /audio/transcriptions"
    TranscribeMethod.ChatInputAudio -> "对话接口 · 音频 + 指令"
    TranscribeMethod.ChatInputAudioUri -> "对话接口 · data URI 音频 + 指令"
    TranscribeMethod.ChatInputAudioOnly -> "对话接口 · 只发音频"
    TranscribeMethod.GeminiInline -> "Gemini 原生 generateContent"
}

/** 「设置 › 模型」里语音转文字一行右边写什么。 */
fun speechSummary(choice: SpeechChoice, system: SystemCapability, endpoints: List<SttEndpoint>): String = when (choice) {
    SpeechChoice.Off -> "已关闭"
    SpeechChoice.Auto -> when {
        system.usable -> "自动 · 手机自带" + if (system.onDevice) "（离线）" else "（联网）"
        system.available -> "自动 · 手机自带不响应"
        else -> "未选择"
    }
    SpeechChoice.System -> when {
        system.usable -> "手机自带" + if (system.onDevice) "（离线）" else "（联网）"
        system.available -> "手机自带 · 不响应"
        else -> "手机自带 · 不可用"
    }
    is SpeechChoice.Provider -> endpoints.firstOrNull { it.providerId == choice.providerId }?.let { "${choice.model} · ${it.name}" } ?: "服务商已删除"
    is SpeechChoice.Offline -> "离线 · 未安装"
}

/**
 * 设置 › 模型 › 语音转文字：单独的第五种用途（不影响对话/子代理/压缩/视觉）。
 * 选服务商模型时先用内置短音频试转，依次探测几种写法，通了才选上并记住写法；失败把原因写在那一行下面。
 */
@Composable
fun SpeechModelScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val voice = remember { VoiceInput.of(ctx) }
    val choice by voice.settings.choice.collectAsState()
    val silent by voice.settings.systemSilent.collectAsState()
    val system = remember(silent) { voice.capability() }
    val endpoints = remember { voice.endpoints(keys = false).filter(Transcriber::supports) }
    val scope = rememberCoroutineScope()
    val c = HoshinoTheme.colors
    val t = HoshinoTheme.type
    /** key = providerId/model；值 = 正在试 / 失败原因。 */
    val testing = remember { mutableStateMapOf<String, String>() }
    val manual = remember { mutableStateMapOf<String, String>() }
    var lastOk by remember { mutableStateOf<String?>(null) }

    fun pick(ep: SttEndpoint, model: String) {
        val k = ep.providerId + "/" + model
        testing[k] = "试转中…"
        lastOk = null
        scope.launch {
            val full = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { voice.endpoints(keys = true).firstOrNull { it.providerId == ep.providerId } } ?: ep
            runCatching { voice.transcriber.detect(full, model) }
                .onSuccess { m -> testing.remove(k); voice.settings.set(SpeechChoice.Provider(ep.providerId, model, m)); lastOk = "已选 $model（${methodLabel(m)}），写法已记住" }
                .onFailure { e -> testing[k] = "试转没通过：" + ((e as? SttError)?.reason() ?: e.message.orEmpty()) }
        }
    }

    PageScaffold("语音转文字", onBack) {
        Text("按住说话（对话输入栏、悬浮胶囊）用这里选的模型，和对话模型分开。", style = t.caption, color = c.text3, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        lastOk?.let { Text(it, style = t.caption, color = c.accent, modifier = Modifier.padding(horizontal = 20.dp)) }
        SectionLabel("手机")
        GroupCard {
            ListRow("自动", subtitle = "有手机自带识别就用它，不会悄悄改用要花钱的模型", trailing = { if (choice == SpeechChoice.Auto) HIcon(HIcons.Check, tint = c.accent) }, onClick = { voice.settings.set(SpeechChoice.Auto) })
            Hairline()
            ListRow(
                "手机自带识别",
                subtitle = when {
                    !system.available -> "这台手机没有可用的识别服务"
                    !system.responsive -> "试过 3 秒没有响应；重新选它可以再试"
                    system.onDevice -> "本机离线识别，边说边出字"
                    else -> "联网识别，边说边出字"
                },
                trailing = { if (choice == SpeechChoice.System) HIcon(HIcons.Check, tint = c.accent) },
                onClick = { voice.settings.set(SpeechChoice.System) },
            )
            Hairline()
            ListRow("不用语音输入", trailing = { if (choice == SpeechChoice.Off) HIcon(HIcons.Check, tint = c.accent) }, onClick = { voice.settings.set(SpeechChoice.Off) })
        }
        if (endpoints.isEmpty()) {
            Text("还没有能转写的服务商（Anthropic 与 ChatGPT 订阅不提供转写接口）。到 设置 › 模型 添加 OpenAI 兼容服务商即可。", style = t.caption, color = c.text3, modifier = Modifier.padding(20.dp))
        }
        for (ep in endpoints) {
            SectionLabel(ep.name)
            GroupCard {
                val candidates = ep.models.filter(Transcriber::looksLikeSpeech)
                candidates.forEachIndexed { i, m ->
                    if (i > 0) Hairline()
                    ModelRow(ep, m, choice, testing[ep.providerId + "/" + m]) { pick(ep, m) }
                }
                if (candidates.isNotEmpty()) Hairline()
                val typed = manual[ep.providerId].orEmpty()
                Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        HTextField(typed, { manual[ep.providerId] = it }, Modifier.weight(1f), placeholder = "列表里没有？手填模型名，如 whisper-1", mono = true)
                        Spacer(Modifier.width(8.dp))
                        HButton("试转并选用", { if (typed.isNotBlank()) pick(ep, typed.trim()) }, style = BtnStyle.Secondary, compact = true, enabled = typed.isNotBlank())
                    }
                    testing[ep.providerId + "/" + typed.trim()]?.takeIf { typed.isNotBlank() }?.let { Text(it, style = t.caption, color = if (it.startsWith("试转中")) c.text3 else c.danger, modifier = Modifier.padding(top = 6.dp)) }
                    val cur = choice
                    if (cur is SpeechChoice.Provider && cur.providerId == ep.providerId && cur.model !in candidates) {
                        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Tag("手填")
                            Spacer(Modifier.width(6.dp))
                            Text("当前：${cur.model} · ${methodLabel(cur.method)}", style = t.caption, color = c.accent)
                        }
                    }
                }
            }
        }
        SectionLabel("离线转写（预留）")
        GroupCard {
            ListRow("本机开源模型", subtitle = "接口已预留（如 sherpa-onnx + SenseVoice，均为开源）；本版本不下载、不提供引擎。")
        }
        Text("选服务商模型时会用一段约 1 秒的内置合成音频试转，依次探测转写接口、对话接口的几种音频写法和 Gemini 原生接口，通了才选上并记住写法；以后那种写法失灵会自动重新探测。录音只在你按住时进行，不保存。", style = t.caption, color = c.text3, modifier = Modifier.fillMaxWidth().padding(20.dp))
    }
}

@Composable
private fun ModelRow(ep: SttEndpoint, model: String, choice: SpeechChoice, status: String?, onPick: () -> Unit) {
    val c = HoshinoTheme.colors
    val selected = choice is SpeechChoice.Provider && choice.providerId == ep.providerId && choice.model == model
    Column {
        ListRow(
            model,
            subtitle = if (selected) methodLabel((choice as SpeechChoice.Provider).method) else null,
            trailing = { if (selected) HIcon(HIcons.Check, tint = c.accent) },
            onClick = onPick,
        )
        status?.let { Text(it, style = HoshinoTheme.type.caption, color = if (it.startsWith("试转中")) c.text3 else c.danger, modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 10.dp)) }
    }
}
