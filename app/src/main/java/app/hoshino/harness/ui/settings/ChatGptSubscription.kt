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

/** Sign in with ChatGPT (Mika's flow): use the ChatGPT Plus/Pro plan in Hoshino. Default off. */
@Composable fun ChatGptSubscription(){
    val client=Hoshino.client as? DshHarnessClient?:return
    val context=LocalContext.current;val scope=rememberCoroutineScope();val state by client.chatGptAuth.state.collectAsStateWithLifecycle();val providers by client.providers.collectAsStateWithLifecycle()
    val enabled=providers.any{it.id=="openai-codex"&&it.enabled};val preferences=remember{context.getSharedPreferences("hoshino-chatgpt",android.content.Context.MODE_PRIVATE)}
    var explain by remember{mutableStateOf(false)};var busy by remember{mutableStateOf(false)}
    fun open(url:String){context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
    fun login(){runCatching{client.beginChatGptLogin{url->runCatching{open(url)}.onFailure{toast(context,"没有找到可以登录 ChatGPT 的浏览器")}}}.onFailure{toast(context,"无法开启登录，请稍后重试")}}
    fun enable(){scope.launch{busy=true;runCatching{client.setChatGptEnabled(true)}.onSuccess{if(!state.signedIn)login()}.onFailure{toast(context,client.describeFailure(it))};busy=false}}
    SectionLabel("ChatGPT 账号")
    GroupCard {
        ListRow("用 ChatGPT 登录",subtitle=if(enabled)state.status else"默认关闭 · 使用你的 ChatGPT Plus / Pro 套餐额度",trailing={HSwitch(enabled,{value->if(value){if(preferences.getBoolean("consented-plan",false))enable()else explain=true}else scope.launch{runCatching{client.setChatGptEnabled(false)}}})})
        if(enabled){
            if(state.needsRelogin)Text("ChatGPT 登录方式已更新为与 Mika 相同的官方「用 ChatGPT 登录」，旧的登录已清除，请重新登录一次。",style=HoshinoTheme.type.caption,color=HoshinoTheme.colors.danger,modifier=Modifier.padding(horizontal=16.dp,vertical=4.dp))
            state.error?.takeIf{!state.signedIn&&!state.waiting}?.let{Text(it,style=HoshinoTheme.type.caption,color=HoshinoTheme.colors.danger,modifier=Modifier.padding(horizontal=16.dp,vertical=4.dp))}
            Row(Modifier.fillMaxWidth().padding(16.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                if(state.waiting)HButton("取消登录",{client.chatGptAuth.cancelLogin()},compact=true)
                else HButton(if(state.signedIn)"重新登录"else"使用 ChatGPT 继续",{login()},compact=true,enabled=!busy)
                if(state.signedIn)HButton("退出登录",{scope.launch{val revoked=runCatching{client.chatGptAuth.signOut()}.getOrDefault(false);if(!revoked)toast(context,"这台手机上已退出登录，但 ChatGPT 那边没有确认。也可以在 ChatGPT 设置里断开 Hoshino。")}},compact=true)
                if(state.signedIn)HButton("管理用量",{runCatching{open(dev.hoshino.bridge.chatgpt.ChatGptOAuth.USAGE_URL)}},compact=true)
            }
            Text(if(state.waiting)"请在浏览器里完成登录，再回到 Hoshino。" else "在系统浏览器完成登录，Hoshino 不接触密码；登录信息只加密保存在本机。Plus 每 5 小时的额度与其他使用你套餐的 App 共用；免费版和 Go 账号不能使用套餐额度。模型列表来自你的账号，默认优先使用模型自带的联网搜索。",style=HoshinoTheme.type.caption,color=HoshinoTheme.colors.text3,modifier=Modifier.padding(16.dp))
        }
    }
    if(explain)HDialog("用你的 ChatGPT 套餐",onDismiss={explain=false},confirmText="使用 ChatGPT 继续",onConfirm={preferences.edit().putBoolean("consented-plan",true).apply();explain=false;enable()}){Text("用你 ChatGPT 套餐里包含的用量（或额度余额）完成 Hoshino 里符合条件的 AI 请求。ChatGPT Plus 和 Pro 可用，免费版和 Go 不支持。Hoshino 本身免费、不另外收费；登录凭据只加密保存在这台设备，不进入会话、快照或备份。",style=HoshinoTheme.type.body)}
}
