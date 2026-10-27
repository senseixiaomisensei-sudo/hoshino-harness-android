package app.hoshino.harness.ui.chat
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import app.hoshino.harness.core.*
import app.hoshino.harness.ui.components.*
import org.json.JSONObject
import kotlinx.coroutines.launch
@Composable fun ApprovalQuickSwitch(){
    val client=Hoshino.client as? DshHarnessClient?:return;val scope=rememberCoroutineScope();val context=LocalContext.current
    var policy by remember{mutableStateOf(JSONObject())};var busy by remember{mutableStateOf(false)}
    LaunchedEffect(Unit){runCatching{client.m2Control("config",JSONObject().put("name","approval.json"))}.onSuccess{policy=it}}
    val names=mapOf("manual" to "手动","rules" to "规则","model" to "审批模型","auto" to "自动")
    HButton(names[policy.optString("mode","manual")]?:"手动",{if(!busy){busy=true;scope.launch{val keys=names.keys.toList();val next=JSONObject(policy.toString()).put("mode",keys[(keys.indexOf(policy.optString("mode","manual"))+1)%keys.size]);runCatching{client.m2Control("config",JSONObject().put("name","approval.json").put("value",next))}.onSuccess{policy=next;toast(context,"审批：${names[next.getString("mode")]}；${if(next.optBoolean("highRiskAsk",true))"高危动作仍需确认"else"按所选策略执行"}")}.onFailure{toast(context,client.describeFailure(it))};busy=false}}},compact=true,enabled=!busy)
}
