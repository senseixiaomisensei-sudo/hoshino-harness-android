package app.hoshino.harness.ui.diy

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hoshino.harness.core.*
import app.hoshino.harness.ui.components.*
import app.hoshino.harness.ui.theme.HoshinoTheme
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import kotlinx.coroutines.launch

@Composable
fun DiySlot(name:String,onNavigate:(String)->Unit={},onPrompt:(String)->Unit={},fallback:@Composable ()->Unit){
    val custom by Hoshino.client.uiCustomization.collectAsStateWithLifecycle()
    val node=custom.raw.optJSONObject("slots")?.optJSONObject(name)
    val parsed=remember(node?.toString()){runCatching{node?.let{validateNode(it)}}.getOrNull()}
    if(parsed==null)fallback() else DiyNode(parsed,custom.raw,onNavigate,onPrompt)
}
private fun validateNode(node:JSONObject,depth:Int=0):JSONObject {
    require(depth<32);require(node.optString("type") in setOf("column","row","box","card","text","markdown","button","pill","tag","divider","spacer","input","switch","progress","icon","image","list","panel","slot","battery","status"))
    node.optJSONArray("children")?.let{a->require(a.length()<=100);for(i in 0 until a.length())validateNode(a.getJSONObject(i),depth+1)}
    return node
}
@Composable
fun DiyNode(node:JSONObject,document:JSONObject,onNavigate:(String)->Unit={},onPrompt:(String)->Unit={},depth:Int=0,sharedState:MutableMap<String,String>?=null){
    if(depth>32)return
    val context=LocalContext.current;val scope=rememberCoroutineScope();val c=HoshinoTheme.colors
    val ownState=remember(document.toString()){mutableStateMapOf<String,String>().apply{document.optJSONObject("state")?.let{o->o.keys().forEach{k->put(k,o.opt(k).toString())}}}}
    val state=sharedState?:ownState
    fun expand(value:String)=Regex("\\{\\{([\\w.-]+)}}").replace(value){state[it.groupValues[1]]?:document.optJSONObject("state")?.opt(it.groupValues[1])?.toString().orEmpty()}
    val condition=node.optString("when");if(condition.isNotEmpty()&&state[condition] !in setOf("true","1"))return
    val style=node.optJSONObject("style")?:JSONObject();val tokens=document.optJSONObject("themes")?.optJSONObject(document.optString("theme"))?:document.optJSONObject("tokens")?:JSONObject()
    fun color(key:String,fallback:Color):Color {val value=style.optString(key);val resolved=if(value.startsWith("$"))tokens.optJSONObject("colors")?.optString(value.drop(1)).orEmpty()else value;return runCatching{Color(android.graphics.Color.parseColor(resolved))}.getOrDefault(fallback)}
    val pad=style.optDouble("padding",0.0).toFloat().coerceIn(0f,96f);val space=style.optDouble("spacing",8.0).toFloat().coerceIn(0f,96f)
    val modifier=Modifier.fillMaxWidth().padding(pad.dp).then(if(node.has("animation"))Modifier.animateContentSize(androidx.compose.animation.core.tween(node.optJSONObject("animation")?.optInt("durationMs",200)?.coerceIn(0,3000)?:200))else Modifier)
    fun fire(){val event=node.optJSONObject("onClick")?:return;val command=event.optString("command");val args=event.optJSONObject("arguments")?:JSONObject();when(command){"navigate"->onNavigate(args.optString("route"));"prompt"->onPrompt(expand(args.optString("text")));"set"->state[args.getString("key")]=args.opt("value").toString();else->scope.launch{runCatching{(Hoshino.client as? DshHarnessClient)?.m2Control(command,args)}.onFailure{toast(context,it.message?:"命令失败")}}}}
    @Composable fun children(){val a=node.optJSONArray("children")?:JSONArray();for(i in 0 until a.length())DiyNode(a.getJSONObject(i),document,onNavigate,onPrompt,depth+1,state)}
    val text=expand(if(node.has("bind"))state[node.optString("bind")]?:node.optString("text")else node.optString("text"))
    when(node.getString("type")){
        "row"->Row(modifier,horizontalArrangement=Arrangement.spacedBy(space.dp)){children()}
        "column","list"->Column(modifier,verticalArrangement=Arrangement.spacedBy(space.dp)){children();node.optJSONArray("items")?.let{a->for(i in 0 until a.length())Text(a.opt(i).toString(),style=HoshinoTheme.type.body,color=c.text)}}
        "box","slot"->Box(modifier){children()}
        "card"->Column(modifier.background(color("background",c.surface),RoundedCornerShape(style.optDouble("radius",12.0).toFloat().coerceIn(0f,100f).dp)).padding(12.dp)){children()}
        "text","icon","status"->SelectionContainer{Text(text,style=HoshinoTheme.type.body.copy(fontSize=style.optDouble("fontSize",15.0).toFloat().coerceIn(8f,72f).sp),color=color("color",c.text),modifier=modifier)}
        "markdown"->MarkdownText(text,modifier=modifier)
        "button"->HButton(text,{fire()},modifier)
        "pill"->Pill(text,onClick={fire()})
        "tag"->Tag(text)
        "divider"->Hairline(modifier)
        "spacer"->Spacer(Modifier.height(style.optDouble("height",8.0).toFloat().coerceIn(0f,300f).dp))
        "input"->{val key=node.optString("bind",node.optString("id","input"));HTextField(state[key]?:text,{state[key]=it},modifier=modifier)}
        "switch"->{val key=node.optString("bind",node.optString("id","switch"));HSwitch(state[key]=="true",{state[key]=it.toString();fire()})}
        "progress"->ThinProgress((state[node.optString("bind")]?.toFloatOrNull()?:node.optDouble("value",0.0).toFloat()).coerceIn(0f,1f),modifier)
        "battery"->{var battery by remember{mutableIntStateOf(0)};LaunchedEffect(Unit){while(true){battery=context.getSystemService(android.os.BatteryManager::class.java).getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);kotlinx.coroutines.delay(30000)}};Text("电量 $battery%",style=HoshinoTheme.type.caption,color=c.text2)}
        "image"->{val path=node.optString("path");val file=runCatching{(Hoshino.client as? DshHarnessClient)?.m2?.workspaceFile(path)}.getOrNull();val bitmap=remember(file?.path){file?.let{android.graphics.BitmapFactory.decodeFile(it.path)}};if(bitmap!=null)androidx.compose.foundation.Image(bitmap=bitmap.asImageBitmap(),contentDescription=text,modifier=modifier.heightIn(max=400.dp))}
        "panel"->LocalPanel(node.optString("path"),modifier)
    }
}

@Suppress("SetJavaScriptEnabled")
@Composable private fun LocalPanel(path:String,modifier:Modifier){
    val context=LocalContext.current;val root=File(context.filesDir,"hoshino/ui").canonicalFile
    val file=remember(path){File(root,path).canonicalFile.takeIf{it.path.startsWith(root.path+File.separator)&&it.isFile}}
    if(file==null){Text("本地面板文件不存在",color=HoshinoTheme.colors.text3);return}
    val html=remember(file.lastModified()){file.readText().take(1024*1024)}
    AndroidView(modifier=modifier.heightIn(min=120.dp,max=600.dp),factory={WebView(it).apply{
        settings.javaScriptEnabled=true;settings.allowFileAccess=false;settings.allowContentAccess=false;settings.blockNetworkLoads=true
        webViewClient=object:WebViewClient(){override fun shouldOverrideUrlLoading(view:WebView,request:android.webkit.WebResourceRequest)=true}
        addJavascriptInterface(object{@android.webkit.JavascriptInterface fun call(action:String):String=when(action){"battery"->context.getSystemService(android.os.BatteryManager::class.java).getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY).toString();"version"->"0.2.0-m2";else->"{\"error\":\"API not allowed\"}"}},"Hoshino")
    }},update={it.loadDataWithBaseURL("https://local.hoshino.invalid/","<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data:; connect-src 'none'\">"+html,"text/html","UTF-8",null)})
}
