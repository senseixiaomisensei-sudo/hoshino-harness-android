package dev.hoshino.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hoshino.bridge.Provider

class ChatViewModel : ViewModel() {
    val state = KernelRuntime.state
    val approval = KernelRuntime.approval
    fun send(text: String) = KernelRuntime.send(text)
    fun select(provider: String, model: String) = KernelRuntime.select(provider,model)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState);enableEdgeToEdge()
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        startForegroundService(Intent(this,KernelService::class.java))
        setContent { MaterialTheme(colorScheme=lightColorScheme(primary=Color(0xff0f1115),background=Color.White,surface=Color.White,secondary=Color(0xff617079))) { ChatScreen() } }
    }
}

@Composable fun ChatScreen(vm: ChatViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val approval by vm.approval.collectAsStateWithLifecycle()
    var settings by remember { mutableStateOf(false) }
    var showModels by remember { mutableStateOf(false) }
    var showLogs by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    val scroll=rememberLazyListState()
    LaunchedEffect(state.messages.size,state.messages.lastOrNull()?.text) { if(state.messages.isNotEmpty()) scroll.animateScrollToItem(state.messages.lastIndex) }
    Scaffold(modifier=Modifier.fillMaxSize().imePadding(), topBar={
        Column(Modifier.statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically) {
                Text("✦",color=Color(0xfff49ab8),fontSize=26.sp,modifier=Modifier.padding(8.dp))
                Column(Modifier.weight(1f),horizontalAlignment=Alignment.CenterHorizontally) {
                    Text("Hoshino Harness",fontSize=16.sp)
                    Text("● ${state.status}",fontSize=11.sp,color=if(state.status=="已连接") Color(0xff269b58) else Color(0xff997329),modifier=Modifier.testTag("kernel-status").clickable { showLogs=true })
                }
                IconButton(onClick={settings=true},modifier=Modifier.testTag("settings")) { Icon(Icons.Default.Settings,"模型设置") }
            }
            Box(Modifier.align(Alignment.CenterHorizontally)) {
                TextButton(onClick={showModels=true},modifier=Modifier.testTag("model-picker")) { Text(if(state.provider.isEmpty()) "选择模型 ▾" else "${state.provider} / ${state.model} ▾",fontSize=12.sp) }
                DropdownMenu(expanded=showModels,onDismissRequest={showModels=false}) {
                    state.providers.forEach { p -> p.models.forEach { m -> DropdownMenuItem(text={Text("${p.id} / $m")},onClick={vm.select(p.id,m);showModels=false},enabled=!state.running) } }
                    DropdownMenuItem(text={Text("添加 / 编辑提供商")},onClick={showModels=false;settings=true})
                }
            }
            HorizontalDivider(color=Color(0xffebedf0))
        }
    }, bottomBar={
        Column(Modifier.navigationBarsPadding().padding(12.dp)) {
            if(state.error.isNotEmpty()) Text(state.error,color=MaterialTheme.colorScheme.error,fontSize=12.sp)
            if(state.running) Text("✦ 思考中",color=Color(0xff617079),fontSize=12.sp,modifier=Modifier.padding(8.dp))
            Surface(shape=RoundedCornerShape(24.dp),shadowElevation=2.dp,tonalElevation=0.dp,border=androidx.compose.foundation.BorderStroke(1.dp,Color(0xffe1e5ee))) {
                Row(Modifier.padding(8.dp),verticalAlignment=Alignment.Bottom) {
                    TextField(value=draft,onValueChange={draft=it},placeholder={Text("向 Hoshino 发送消息",fontSize=14.sp)},modifier=Modifier.weight(1f).testTag("message-input"),maxLines=5,colors=TextFieldDefaults.colors(focusedContainerColor=Color.Transparent,unfocusedContainerColor=Color.Transparent,focusedIndicatorColor=Color.Transparent,unfocusedIndicatorColor=Color.Transparent))
                    FilledIconButton(onClick={vm.send(draft);draft=""},enabled=draft.isNotBlank() && state.status=="已连接" && state.provider.isNotEmpty() && !state.running,modifier=Modifier.testTag("send")) { Text("↑",fontSize=24.sp) }
                }
            }
            Text("本地工作区 · 修改与网络请求需审批",fontSize=10.sp,color=Color(0xff81858c),modifier=Modifier.align(Alignment.CenterHorizontally).padding(top=8.dp))
        }
    }) { padding ->
        if(state.messages.isEmpty()) Box(Modifier.fillMaxSize().padding(padding),contentAlignment=Alignment.Center) {
            Column(horizontalAlignment=Alignment.CenterHorizontally) { Text("✦",color=Color(0xfff49ab8),fontSize=46.sp);Spacer(Modifier.height(20.dp));Text("今天想做什么？",fontSize=23.sp);Text("先选择模型，再开始一个任务",color=Color(0xff81858c),fontSize=13.sp,modifier=Modifier.padding(12.dp)) }
        } else LazyColumn(state=scroll,modifier=Modifier.fillMaxSize().padding(padding).testTag("conversation"),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) { items(state.messages,key={it.id}) { MessageCard(it) } }
    }
    approval?.let { request -> AlertDialog(onDismissRequest={request.decision.complete(false)},title={Text("操作审批")},text={Text(request.description)},confirmButton={TextButton(onClick={request.decision.complete(true)},modifier=Modifier.testTag("approve")){Text("允许本次")}},dismissButton={TextButton(onClick={request.decision.complete(false)}){Text("拒绝")}}) }
    if(settings) ProviderSettings(state.providers) { settings=false }
    if(showLogs) AlertDialog(onDismissRequest={showLogs=false},title={Text("内核 ${state.status}")},text={Text((state.diagnostics.takeLast(50).joinToString("\n")).ifEmpty { "暂无诊断日志" },fontFamily=FontFamily.Monospace,fontSize=11.sp)},confirmButton={TextButton(onClick={showLogs=false}){Text("关闭")}})
}

@Composable private fun MessageCard(item: ChatItem) {
    if(item.role=="user") Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) { Surface(color=Color(0xffedf3fe),shape=RoundedCornerShape(18.dp)) { Text(item.text,modifier=Modifier.padding(14.dp),fontSize=14.sp) } }
    else if(item.role=="tool") {
        var expanded by remember(item.id) { mutableStateOf(true) }
        OutlinedCard(Modifier.fillMaxWidth().testTag("tool-${item.tool}"),shape=RoundedCornerShape(12.dp),colors=CardDefaults.outlinedCardColors(containerColor=Color(0xfff9fafb))) {
            Text("${if(item.result.isEmpty()) "◌" else "✓"}  ${item.tool}  ▾",modifier=Modifier.fillMaxWidth().clickable { expanded=!expanded }.padding(12.dp),fontSize=13.sp)
            AnimatedVisibility(expanded) { Column(Modifier.padding(horizontal=12.dp).padding(bottom=12.dp)) { Text(item.arguments,fontFamily=FontFamily.Monospace,fontSize=11.sp);if(item.result.isNotEmpty()) { Spacer(Modifier.height(8.dp));Text(item.result,fontFamily=FontFamily.Monospace,fontSize=11.sp) } } }
        }
    } else Column {
        if(item.thinking.isNotEmpty()) { var expanded by remember(item.id) { mutableStateOf(false) };Text("${if(expanded) "▾" else "▸"} 思考过程",color=Color(0xff617079),fontSize=12.sp,modifier=Modifier.testTag("thinking").clickable { expanded=!expanded }.padding(vertical=8.dp));AnimatedVisibility(expanded) { Text(item.thinking,fontSize=13.sp,color=Color(0xff81858c),modifier=Modifier.testTag("thinking-content").padding(bottom=12.dp)) } }
        if(item.text.isNotEmpty()) Text(item.text,fontSize=14.sp,lineHeight=24.sp,modifier=Modifier.testTag("assistant-text"))
    }
}

@Composable private fun ProviderSettings(providers: List<Provider>, dismiss: () -> Unit) {
    val context=LocalContext.current
    var id by remember { mutableStateOf("") };var type by remember { mutableStateOf("anthropic") }
    var base by remember { mutableStateOf("https://api.anthropic.com") };var model by remember { mutableStateOf("") };var key by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") };var saving by remember { mutableStateOf(false) }
    androidx.compose.ui.window.Dialog(onDismissRequest=dismiss,properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize(),color=Color.White) {
            Column(Modifier.systemBarsPadding().imePadding().padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) { Text("模型提供商",fontSize=20.sp,modifier=Modifier.weight(1f));TextButton(onClick=dismiss){Text("关闭")} }
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { FilterChip(selected=type=="anthropic",onClick={type="anthropic";base="https://api.anthropic.com"},label={Text("Anthropic")});FilterChip(selected=type=="openai-compatible",onClick={type="openai-compatible";base="https://api.openai.com/v1"},label={Text("OpenAI 兼容")}) }
                if(providers.isNotEmpty()) Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { providers.forEach { p -> SuggestionChip(onClick={id=p.id;type=p.type;base=p.baseURL;model=p.models.joinToString(",");key=""},label={Text(p.id)}) } }
                OutlinedTextField(id,{id=it},label={Text("Provider ID")},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("provider-id"))
                OutlinedTextField(base,{base=it},label={Text("Base URL")},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("provider-url"))
                OutlinedTextField(key,{key=it},label={Text("API Key（编辑时留空保留）")},visualTransformation=PasswordVisualTransformation(),singleLine=true,modifier=Modifier.fillMaxWidth().testTag("provider-key"))
                OutlinedTextField(model,{model=it},label={Text("Model ID（多个用逗号分隔）")},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("provider-model"))
                Text("凭据由 Android Keystore 加密保护",fontSize=11.sp,color=Color(0xff81858c))
                if(error.isNotEmpty()) Text(error,color=MaterialTheme.colorScheme.error)
                Button(onClick={saving=true;KernelRuntime.save(context,Provider(id.trim(),type,base.trim().trimEnd('/'),model.split(',').map { it.trim() }.filter { it.isNotEmpty() }),key) { message -> saving=false;if(message==null) { key="";dismiss() } else error=message }},enabled=!saving && !KernelRuntime.state.value.running,modifier=Modifier.fillMaxWidth().testTag("provider-save")){Text(if(saving) "保存中…" else "保存提供商")}
            }
        }
    }
}
