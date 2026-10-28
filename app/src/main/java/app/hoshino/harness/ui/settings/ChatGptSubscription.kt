package app.hoshino.harness.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hoshino.harness.core.*
import app.hoshino.harness.ui.components.*
import app.hoshino.harness.ui.theme.HoshinoTheme
import kotlinx.coroutines.launch

@Composable fun ChatGptSubscription(){
    val client=Hoshino.client as? DshHarnessClient?:return
    val context=LocalContext.current;val scope=rememberCoroutineScope();val state by client.chatGptAuth.state.collectAsStateWithLifecycle();val providers by client.providers.collectAsStateWithLifecycle()
    val enabled=providers.any{it.id=="openai-codex"&&it.enabled};val preferences=remember{context.getSharedPreferences("hoshino-chatgpt",android.content.Context.MODE_PRIVATE)}
    var warning by remember{mutableStateOf(false)};var busy by remember{mutableStateOf(false)}
    fun login(){runCatching{val url=client.chatGptAuth.beginLogin();context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)))}.onFailure{toast(context,"无法开启登录，请检查浏览器或稍后重试")}}
    fun enable(){scope.launch{busy=true;runCatching{client.setChatGptEnabled(true)}.onSuccess{login()}.onFailure{toast(context,client.describeFailure(it))};busy=false}}
    SectionLabel("ChatGPT 账号")
    GroupCard {
        ListRow("ChatGPT 订阅（实验）",subtitle=if(enabled)state.status else"默认关闭 · 使用你自己的订阅额度",trailing={HSwitch(enabled,{value->if(value){if(preferences.getBoolean("consented",false))enable()else warning=true}else scope.launch{runCatching{client.setChatGptEnabled(false)}}})})
        if(enabled){Row(Modifier.fillMaxWidth().padding(16.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){HButton(if(state.signedIn)"重新登录"else"登录 ChatGPT",{login()},compact=true,enabled=!busy);if(state.signedIn)HButton("退出登录",{client.chatGptAuth.logout()},compact=true)};Text("在系统浏览器完成登录，不收集密码。模型目录不代表账号权益；可用额度由你的订阅决定。",style=HoshinoTheme.type.caption,color=HoshinoTheme.colors.text3,modifier=Modifier.padding(16.dp))}
    }
    if(warning)HDialog("启用 ChatGPT 订阅（实验）",onDismiss={warning=false},confirmText="知悉并登录",onConfirm={preferences.edit().putBoolean("consented",true).apply();warning=false;enable()}){Text("此为非官方集成，可能违反 OpenAI 条款并导致账号受限；可用额度取决于你自己的订阅。Token 仅加密保存在此设备，不进入会话、快照或备份。",style=HoshinoTheme.type.body)}
}
