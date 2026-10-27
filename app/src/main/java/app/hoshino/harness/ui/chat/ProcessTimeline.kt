package app.hoshino.harness.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.hoshino.harness.core.*
import app.hoshino.harness.ui.components.*
import app.hoshino.harness.ui.theme.HoshinoTheme
import kotlinx.coroutines.delay

internal data class TurnPresentation(val id:String,val user:ChatItem.User?,val process:List<ChatItem>,val finalItems:List<ChatItem>,val actions:List<ChatItem>)
internal fun presentTurns(items:List<ChatItem>):List<TurnPresentation>{
    val groups=mutableListOf<MutableList<ChatItem>>()
    for(item in items){if(item is ChatItem.User || groups.isEmpty())groups.add(mutableListOf());groups.last().add(item)}
    return groups.map{group->
        val user=group.firstOrNull() as? ChatItem.User
        val final=group.filterIsInstance<ChatItem.Assistant>().lastOrNull()
        val actions=group.filter{it is ChatItem.Approval || it is ChatItem.Question || it is ChatItem.Diff && it.state==DiffState.Pending}
        val finals=group.filter{it===final || it is ChatItem.UsageFooter}
        TurnPresentation(user?.id?:group.first().id,user,group.filter{it!==user && it !in finals && it !in actions},finals,actions)
    }
}

@Composable
internal fun ProcessTimeline(items:List<ChatItem>,actions:MessageActions,display:String="collapsed",types:org.json.JSONObject?=null){
    if(items.isEmpty())return
    val c=HoshinoTheme.colors
    val context=androidx.compose.ui.platform.LocalContext.current
    val running=items.any{it is ChatItem.Thinking && it.running || it is ChatItem.Tool && it.status==ToolStatus.Running || it is ChatItem.Terminal && it.status==ToolStatus.Running}
    val failed=items.any{it is ChatItem.Tool && it.status==ToolStatus.Failed || it is ChatItem.Terminal && it.status==ToolStatus.Failed}
    val started=items.filterIsInstance<ChatItem.Thinking>().minOfOrNull{it.startedAt}
    var now by remember{mutableLongStateOf(System.currentTimeMillis())}
    LaunchedEffect(running){while(running){now=System.currentTimeMillis();delay(1000)}}
    val duration=items.filterIsInstance<ChatItem.Thinking>().sumOf{if(it.running)(now-it.startedAt).coerceAtLeast(0)else it.durationMs}
    var expanded by rememberSaveable(items.first().id,display){mutableStateOf(display!="collapsed")}
    val tools=items.count{it is ChatItem.Tool || it is ChatItem.Terminal}
    val files=items.filterIsInstance<ChatItem.Diff>().map{it.path}.distinct().size
    val current=items.lastOrNull()
    val action=when(current){is ChatItem.Tool->current.name;is ChatItem.Terminal->current.command.lineSequence().firstOrNull().orEmpty().take(60);is ChatItem.Thinking->"思考中";else->"处理任务"}
    val status=if(running)"运行中"else if(failed)"失败"else "已完成"
    val title=if(running)"$action · $tools 步 · ${formatDuration(duration)} · $status" else "已运行 $tools 步 · ${formatDuration(duration)}${if(files>0)" · 改了 $files 个文件"else""} · $status"
    Column(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=6.dp).hairlineCard()){
        Row(Modifier.fillMaxWidth().clickable{expanded=!expanded}.padding(12.dp)){
            Text(if(expanded)"▾ 过程"else"▸ 过程",style=HoshinoTheme.type.bodyMedium,color=c.text,modifier=Modifier.width(64.dp))
            Text(title,style=HoshinoTheme.type.caption,color=if(failed)c.danger else c.text2)
        }
        if(expanded){
            items.forEachIndexed{index,item->
                val type=when(item){is ChatItem.Thinking->"thinking";is ChatItem.Terminal->"terminal";is ChatItem.Diff->"diff";else->"tool"}
                var open by rememberSaveable(item.id,display){mutableStateOf(types?.optBoolean(type,false)==true || display=="expanded" || display=="current"&&index==items.lastIndex)}
                var full by remember{mutableStateOf(false)}
                val label=when(item){is ChatItem.Thinking->"思考";is ChatItem.Tool->item.name;is ChatItem.Terminal->"终端 · "+item.command.take(70);is ChatItem.Diff->item.path;is ChatItem.Assistant->"中间回复";else->"事件 ${index+1}"}
                Column(Modifier.padding(start=10.dp,end=6.dp,bottom=4.dp)){
                    Text("${if(open)"▾"else"▸"} ${index+1}. $label",style=HoshinoTheme.type.caption,color=c.text2,modifier=Modifier.fillMaxWidth().clickable{open=!open}.padding(8.dp))
                    if(if(display=="current")index==items.lastIndex else open){
                        if(item.plainText().length>12000){
                            var output by rememberSaveable(item.id){mutableStateOf(false)}
                            Text("${if(output)"▾"else"▸"} 长输出 · ${item.plainText().length} 字符",color=c.text3,modifier=Modifier.clickable{output=!output}.padding(8.dp))
                            if(output)SelectionContainer{Text(item.plainText().take(12000),style=HoshinoTheme.type.code,color=c.text)}
                            HButton("查看全部",{full=true},compact=true)
                        }else ChatItemView(item,actions)
                    }
                }
                if(full)HSheet("查看全部",{full=false}){
                    HButton("复制全文",{copyToClipboard(context,item.plainText())})
                    SelectionContainer{Text(item.plainText(),style=HoshinoTheme.type.code,color=c.text,modifier=Modifier.heightIn(max=640.dp).verticalScroll(rememberScrollState()).padding(12.dp))}
                }
            }
        }
    }
}

