package app.hoshino.harness.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hoshino.harness.core.*
import app.hoshino.harness.ui.components.*
import app.hoshino.harness.ui.theme.HIcons
import app.hoshino.harness.ui.theme.HoshinoTheme
import kotlinx.coroutines.launch

/**
 * 设置 → 模型 最上面的「用账号登录 · 不用填 Key」：OpenRouter 官方 PKCE 授权（m2.10，参照 Mika 0.1.26 f9860a6）。
 * 点「OpenRouter ›」展开操作；登录在系统浏览器里完成，Hoshino 不接触密码。退出只清本机。
 */
@Composable fun AccountSignInSection(){
    val client=Hoshino.client as? DshHarnessClient?:return
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    val state by client.openRouterAccount.state.collectAsStateWithLifecycle()
    var open by remember{mutableStateOf(false)}
    fun browse(url:String){context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
    fun login(){runCatching{client.beginOpenRouterLogin{url->runCatching{browse(url)}.onFailure{toast(context,"没有找到可以打开授权页的浏览器")}}}.onFailure{toast(context,"无法开启登录，请稍后重试")}}
    SectionLabel("用账号登录 · 不用填 Key")
    GroupCard {
        ListRow("OpenRouter",subtitle=state.status,icon=HIcons.Link,showChevron=true,onClick={open=!open;if(!state.signedIn&&!state.waiting)login()})
        if(open||state.waiting||state.error!=null){
            state.error?.takeIf{!state.signedIn&&!state.waiting}?.let{Text(it,style=HoshinoTheme.type.caption,color=HoshinoTheme.colors.danger,modifier=Modifier.padding(horizontal=16.dp,vertical=4.dp))}
            Row(Modifier.fillMaxWidth().padding(16.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                if(state.waiting)HButton("取消登录",{client.openRouterAccount.cancelLogin()},compact=true)
                else HButton(if(state.signedIn)"重新登录" else "用 OpenRouter 登录",{login()},compact=true)
                if(state.signedIn)HButton("退出登录",{scope.launch{runCatching{client.signOutOpenRouter()}.onSuccess{toast(context,"已在这台手机上退出。Key 仍在你的 OpenRouter 账号里，可在 OpenRouter 的 Keys 页删除。")}.onFailure{toast(context,client.describeFailure(it))}}},compact=true)
                if(state.signedIn)HButton("管理 Key",{runCatching{browse(dev.hoshino.bridge.oauth.OpenRouterAuth.KEYS_PAGE)}},compact=true)
            }
            Text(if(state.waiting)"请在浏览器里完成授权，再回到 Hoshino。" else "在系统浏览器里用 OpenRouter 官方授权换一把属于你的 Key，只加密保存在本机，另存为「OpenRouter（账号登录）」，不影响手填 Key 的 OpenRouter。退出只清这台手机上的 Key。",style=HoshinoTheme.type.caption,color=HoshinoTheme.colors.text3,modifier=Modifier.padding(16.dp))
        }
    }
}
