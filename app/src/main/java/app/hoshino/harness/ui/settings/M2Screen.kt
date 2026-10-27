package app.hoshino.harness.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import app.hoshino.harness.core.*
import app.hoshino.harness.ui.components.*
import app.hoshino.harness.ui.theme.HoshinoTheme
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun M2Screen(onBack:()->Unit){
    val context=LocalContext.current;val scope=rememberCoroutineScope();val client=Hoshino.client as? DshHarnessClient
    var section by remember{mutableStateOf("审批")};var content by remember{mutableStateOf("{}")};var status by remember{mutableStateOf("")};var busy by remember{mutableStateOf(false)};var token by remember{mutableStateOf("")}
    val files=mapOf("审批" to "approval.json","模式" to "modes.json","提示词" to "prompts.json","搜索" to "search.json","模型路由" to "routing.json","源码构建" to "build.json","折叠" to "preferences.json")
    fun load(){scope.launch{busy=true;runCatching{if(section=="UI DSL")client?.control("config-read",JSONObject().put("path","ui/overrides.json"))?.getString("content")?:"{}" else if(section in files)client?.m2Control("config",JSONObject().put("name",files[section]))?.toString(2)?:"{}"else client?.m2Control("doctor")?.toString(2)?:"{}"}.onSuccess{content=it;status=""}.onFailure{status=client?.describeFailure(it)?:"内核未连接"};busy=false}}
    LaunchedEffect(section){load()}
    PageScaffold("M2 · 自由配置",onBack,subtitle=if(M2SafeMode.enabled(context))"安全模式 · 用户插件与覆盖已禁用"else"普通文件配置 · 保存后热重载"){
        Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            listOf(listOf("审批","模式","提示词"),listOf("UI DSL","搜索","模型路由"),listOf("源码构建","折叠","诊断与备份")).forEach{row->Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){row.forEach{label->HButton(label,{section=label},compact=true)}}}
            if(section=="审批"){
                Text("支持全局、工作区、会话、模式覆盖；规则按顺序匹配。模型失败/超时默认询问。",style=HoshinoTheme.type.caption,color=HoshinoTheme.colors.text2)
                Row(horizontalArrangement=Arrangement.spacedBy(4.dp)){listOf("manual" to "手动","rules" to "规则","model" to "模型","auto" to "全自动").forEach{(id,label)->HButton(label,{val json=runCatching{JSONObject(content)}.getOrDefault(JSONObject());json.put("mode",id);content=json.toString(2)},compact=true)}}
                val json=runCatching{JSONObject(content)}.getOrDefault(JSONObject())
                Row{Text("高危动作仍问我",modifier=Modifier.weight(1f),color=HoshinoTheme.colors.text);HSwitch(json.optBoolean("highRiskAsk",true),{json.put("highRiskAsk",it);content=json.toString(2)})}
            }
            if(section=="源码构建"){
                Text("填写自己的 owner/repo 和已包含 m2.yml 的分支。Token 仅保存在 Android Keystore，构建签名由仓库 Secrets 提供。",style=HoshinoTheme.type.caption,color=HoshinoTheme.colors.text2)
                SecureKeyScope()
                Column {
                    HTextField(token,{token=it},placeholder="GitHub token",password=true,modifier=Modifier.fillMaxWidth())
                    HButton("保存 GitHub 凭证",{if(token.isNotBlank()){dev.hoshino.bridge.ProviderStore(context).putSecret("GITHUB_TOKEN",token.trim());token="";status="凭证已加密保存"}})
                }
                HButton("推送源码并构建",{scope.launch{busy=true;runCatching{client?.m2Control("build")}.onSuccess{status=it?.toString(2).orEmpty()}.onFailure{status=client?.describeFailure(it).orEmpty()};busy=false}},enabled=!busy)
                HButton("查询构建",{scope.launch{runCatching{client?.m2Control("build-status")}.onSuccess{status=it?.toString(2).orEmpty()}.onFailure{status=client?.describeFailure(it).orEmpty()}}})
            }
            if(section=="诊断与备份"){
                HButton("导出完整配置与会话备份",{scope.launch{runCatching{val result=client?.m2Control("backup")?:error("内核未连接");client.m2Control("deliver",JSONObject().put("path",result.getString("path")));status=result.toString(2)}.onFailure{status=client?.describeFailure(it).orEmpty()}}})
                HButton(if(M2SafeMode.enabled(context))"退出安全模式并重载"else"进入安全模式并重载",{M2SafeMode.set(context,!M2SafeMode.enabled(context));scope.launch{runCatching{client?.restartKernel()};load()}})
                Text("安全模式入口：启动时长按音量键；连续三次启动失败也会进入。旧 APK 保留在应用 updates 目录。",style=HoshinoTheme.type.caption,color=HoshinoTheme.colors.text2)
            }
            HTextField(content,{content=it},modifier=Modifier.fillMaxWidth().heightIn(min=260.dp),singleLine=false)
            if(section in files || section=="UI DSL")Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                HButton("保存并热重载",{scope.launch{busy=true;runCatching{val parsed=JSONObject(content);if(section=="UI DSL")client?.control("ui-override",JSONObject().put("content",content))else client?.m2Control("config",JSONObject().put("name",files[section]).put("value",parsed));client?.m2Control("reload");client?.refreshControl()}.onSuccess{status="已保存并应用"}.onFailure{status=client?.describeFailure(it)?:"保存失败"};busy=false}},enabled=!busy)
                HButton("重新读取",{load()},enabled=!busy)
            }
            if(status.isNotBlank())Text(status,style=HoshinoTheme.type.code,color=HoshinoTheme.colors.text2)
        }
    }
}
