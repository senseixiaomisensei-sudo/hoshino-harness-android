package app.hoshino.harness.ui.tools

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.hoshino.harness.core.*
import app.hoshino.harness.ui.components.*
import app.hoshino.harness.ui.theme.*
import dev.hoshino.bridge.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** SAF grants are used only to copy explicitly selected documents into/out of the private workspace. */
object WorkspaceDocuments {
    suspend fun importFile(context:Context,files:WorkspaceFiles,uri:Uri,directory:String,name:String?=null)=withContext(Dispatchers.IO) {
        val display=name ?: context.contentResolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use{c->if(c.moveToFirst())c.getString(0) else null} ?: "imported-file"
        val leaf=display.substringAfterLast('/').substringAfterLast('\\');require(leaf.isNotBlank() && leaf!=".." && leaf!=".")
        val target=files.resolve("$directory/$leaf");check(!target.exists()){"同名文件已存在，请先重命名"}
        val temp=File(target.parentFile,".import-${java.util.UUID.randomUUID()}")
        try{context.contentResolver.openInputStream(uri)!!.use{input->temp.outputStream().use{out->val buffer=ByteArray(65536);var count=0L;while(true){val n=input.read(buffer);if(n<0)break;count+=n;require(count<=50*1024*1024){"单个导入文件超过 50 MB"};out.write(buffer,0,n)}}};check(temp.renameTo(target))}finally{temp.delete()}
    }
    suspend fun importTree(context:Context,files:WorkspaceFiles,tree:Uri,directory:String)=withContext(Dispatchers.IO) {
        var entries=0
        suspend fun copy(id:String,parent:String) {
            require(++entries<=2000){"目录超过 2000 个条目，请分批导入"}
            val uri=DocumentsContract.buildDocumentUriUsingTree(tree,id)
            val pair=context.contentResolver.query(uri,arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE),null,null,null)!!.use{c->check(c.moveToFirst());c.getString(0) to c.getString(1)}
            val name=pair.first;require(name.isNotBlank() && !name.contains('/') && name!=".." && name!=".")
            if(pair.second==DocumentsContract.Document.MIME_TYPE_DIR) {
                val path="$parent/$name";files.createDirectory(path)
                val children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,id)
                val ids=context.contentResolver.query(children,arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),null,null,null)!!.use{c->buildList{while(c.moveToNext())add(c.getString(0))}}
                for(child in ids)copy(child,path)
            } else importFile(context,files,uri,parent,name)
        }
        copy(DocumentsContract.getTreeDocumentId(tree),directory)
    }
}

@Composable
fun FilesScreen(onBack:()->Unit) {
    val context=LocalContext.current;val client=Hoshino.client as? DshHarnessClient
    if(client==null){PageScaffold("文件",onBack){EmptyState("即将支持")};return}
    val files=client.files;val scope=rememberCoroutineScope();val c=HoshinoTheme.colors;val t=HoshinoTheme.type
    var directory by remember{mutableStateOf("/root/workspace")};var rows by remember{mutableStateOf<List<WorkspaceEntry>>(emptyList())}
    var revision by remember{mutableIntStateOf(0)};var selected by remember{mutableStateOf(setOf<String>())};var query by remember{mutableStateOf("")}
    var contentSearch by remember{mutableStateOf(false)};var editor by remember{mutableStateOf<String?>(null)};var creating by remember{mutableStateOf<String?>(null)}
    var action by remember{mutableStateOf<String?>(null)};var showTrash by remember{mutableStateOf(false)};var trash by remember{mutableStateOf<List<TrashEntry>>(emptyList())}
    var exportFile by remember{mutableStateOf<File?>(null)}
    fun run(block:suspend()->Unit){scope.launch{try{withContext(Dispatchers.IO){block()};revision++;selected=emptySet()}catch(e:Exception){toast(context,e.message ?: "文件操作失败")}}}
    val importer=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){uris->run{for(uri in uris)WorkspaceDocuments.importFile(context,files,uri,directory)}}
    val folder=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()){uri->if(uri!=null)run{WorkspaceDocuments.importTree(context,files,uri,directory)}}
    val exporter=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")){uri->if(uri!=null)run{context.contentResolver.openOutputStream(uri)!!.use{out->exportFile!!.inputStream().use{it.copyTo(out)}}}}
    LaunchedEffect(directory,revision,query,contentSearch){withContext(Dispatchers.IO){files.cleanTrash();rows=if(query.isBlank())files.list(directory) else files.search(query,contentSearch).map{path->val f=files.resolve(path);WorkspaceEntry(path,f.name,f.isDirectory,f.length(),f.lastModified())};trash=files.trashList()}}
    editor?.let{path->FileEditor(files,path,{editor=null;revision++});return}
    PageScaffold("文件",onBack,subtitle=directory,actions={IconBtn(HIcons.Refresh,"刷新",{revision++})}) {
        Row(Modifier.padding(12.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            HButton("上级",{directory=directory.substringBeforeLast('/').takeIf{it.startsWith("/root/workspace")} ?: "/root/workspace"},compact=true,enabled=directory!="/root/workspace")
            HButton("新文件",{creating="file"},compact=true);HButton("新目录",{creating="directory"},compact=true)
            HButton("回收站",{showTrash=true},compact=true)
        }
        Row(Modifier.padding(horizontal=12.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            HButton("导入文件",{importer.launch(arrayOf("*/*"))},compact=true);HButton("导入目录",{folder.launch(null)},compact=true)
            HButton("导出",{val file=selected.singleOrNull()?.let{files.resolve(it)};if(file?.isFile==true){exportFile=file;exporter.launch(file.name)}else toast(context,"请选择一个文件")},compact=true,enabled=selected.size==1)
        }
        HTextField(query,{query=it},Modifier.padding(12.dp),placeholder="按名称或内容搜索",leading=HIcons.Search)
        Row(Modifier.padding(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){Pill("名称",selected=!contentSearch,onClick={contentSearch=false});Pill("内容",selected=contentSearch,onClick={contentSearch=true})}
        if(selected.isNotEmpty()) {
            Row(Modifier.padding(12.dp).horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                HButton("重命名",{action="rename"},compact=true,enabled=selected.size==1);HButton("移动",{action="move"},compact=true);HButton("复制",{action="copy"},compact=true)
                HButton("删除",{action="delete"},compact=true);HButton("复制路径",{copyToClipboard(context,selected.joinToString("\n"))},compact=true)
                HButton("分享",{selected.forEach{AppMedia(context).shareFile(files.resolve(it))}},compact=true,enabled=selected.all{files.resolve(it).isFile})
                HButton("取消选择",{selected=emptySet()},compact=true)
            }
        }
        if(rows.isEmpty())EmptyState("这里没有文件",subtitle="通过 SAF 导入，或让 Agent 在工作区创建文件",icon=HIcons.File)
        else GroupCard {rows.forEach {entry->ListRow(entry.name,subtitle=if(entry.directory)"文件夹" else "${entry.bytes/1024} KB",icon=if(entry.directory)HIcons.Folder else HIcons.File,onClick={if(entry.directory){directory=entry.path;selected=emptySet()}else editor=entry.path},trailing={HCheckbox(entry.path in selected,{on->selected=if(on)selected+entry.path else selected-entry.path})})}}
    }
    creating?.let{kind->TextInputDialog(if(kind=="file")"新建文件" else "新建目录",initial="",placeholder="名称",onDismiss={creating=null},onConfirm={name->creating=null;run{val path="$directory/$name";if(kind=="file")files.write(path,"","absent")else files.createDirectory(path)}})}
    action?.let{op->
        if(op=="delete")HDialog("移到回收站？",onDismiss={action=null},confirmText="删除",danger=true,onConfirm={action=null;val paths=selected.toList();run{paths.forEach{files.delete(it)}}}){Text("7 天内可从回收站恢复。",style=t.body,color=c.text2)}
        else TextInputDialog(if(op=="rename")"重命名" else if(op=="move")"移动到目录" else "复制到目录",initial=if(op=="rename")files.resolve(selected.single()).name else directory,mono=true,onDismiss={action=null},onConfirm={value->action=null;val paths=selected.toList();run{for(path in paths){val source=files.resolve(path);val target=if(op=="rename")files.publicPath(File(source.parentFile,value)) else "$value/${source.name}";files.transfer(path,target,op!="copy")}}})
    }
    if(showTrash)HSheet({showTrash=false},"回收站 · 保留 7 天") {if(trash.isEmpty())EmptyState("回收站为空") else trash.forEach{entry->ListRow(entry.originalPath,subtitle=java.util.Date(entry.deletedAt).toString(),trailing={HButton("恢复",{run{files.restore(entry.id)}},compact=true)})}}
}

@Composable
fun FileEditor(files:WorkspaceFiles,path:String,onBack:()->Unit) {
    val context=LocalContext.current;val c=HoshinoTheme.colors;val t=HoshinoTheme.type;val scope=rememberCoroutineScope()
    val draft=remember(path){val dir=File(context.filesDir,"hoshino/editor-drafts").apply{mkdirs()};File(dir,java.security.MessageDigest.getInstance("SHA-256").digest(path.toByteArray()).joinToString(""){"%02x".format(it)}+".txt")}
    var text by remember(path){mutableStateOf("")};var version by remember(path){mutableStateOf("")};var large by remember(path){mutableStateOf(false)}
    var page by remember(path){mutableIntStateOf(1)};var eof by remember(path){mutableStateOf(true)};var error by remember{mutableStateOf("")};var conflict by remember{mutableStateOf(false)}
    var query by remember{mutableStateOf("")};var replacement by remember{mutableStateOf("")};var preview by remember{mutableStateOf(false)}
    val undo=remember(path){mutableStateListOf<String>()};val redo=remember(path){mutableStateListOf<String>()};var ready by remember(path){mutableStateOf(false)}
    var saved by remember(path){mutableStateOf("")}
    suspend fun load(useDraft:Boolean) {withContext(Dispatchers.IO){val file=files.resolve(path);large=file.length()>2*1024*1024;val result=if(large)files.read(path,page,500) else files.editable(path);text=if(useDraft && draft.isFile && !large)draft.readText() else result.text;saved=result.text;version=result.version;eof=result.eof;conflict=false;ready=true}}
    fun run(block:suspend()->Unit){scope.launch{try{block();error=""}catch(e:Exception){error=e.message ?: "操作失败"}}}
    LaunchedEffect(path,page){runCatching{load(page==1)}.onFailure{error=it.message ?: "读取失败"}}
    LaunchedEffect(text,ready){if(ready && !large){delay(500);withContext(Dispatchers.IO){draft.writeText(text)}}}
    LaunchedEffect(path,version){if(version.isNotEmpty())while(true){delay(1200);conflict=withContext(Dispatchers.IO){files.version(files.resolve(path))!=version}}}
    fun change(value:String){undo+=text;if(undo.size>50)undo.removeAt(0);redo.clear();text=value}
    val image=remember(path){path.substringAfterLast('.').lowercase() in setOf("png","jpg","jpeg","webp","gif")}
    val imageBitmap=remember(path,image){if(image)runCatching{android.graphics.BitmapFactory.decodeFile(files.resolve(path).path,android.graphics.BitmapFactory.Options().apply{inSampleSize=2})}.getOrNull() else null}
    PageScaffold(path.substringAfterLast('/'),onBack,subtitle=if(large)"超过 2 MB · 只读分页 · 第 $page 行" else if(text!=saved)"草稿已自动保存" else "工作区文件",actions={IconBtn(HIcons.Refresh,"重新读取",{run{load(false)}})}) {
        if(conflict)Text("外部修改冲突：保留了你的草稿。重新读取后才能保存。",color=c.warning,modifier=Modifier.padding(16.dp))
        if(error.isNotEmpty())Text(error,color=c.danger,modifier=Modifier.padding(16.dp))
        if(image && imageBitmap!=null){Image(imageBitmap.asImageBitmap(),path,Modifier.fillMaxWidth().heightIn(max=600.dp));return@PageScaffold}
        Row(Modifier.padding(12.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            HButton("保存",{run{withContext(Dispatchers.IO){files.write(path,text,version);version=files.version(files.resolve(path));saved=text;draft.delete()}}},compact=true,enabled=ready && !large && !conflict)
            HButton("撤销",{if(undo.isNotEmpty()){redo+=text;text=undo.removeAt(undo.lastIndex)}},compact=true,enabled=undo.isNotEmpty() && !large)
            HButton("重做",{if(redo.isNotEmpty()){undo+=text;text=redo.removeAt(redo.lastIndex)}},compact=true,enabled=redo.isNotEmpty() && !large)
            HButton("复制内容",{copyToClipboard(context,text)},compact=true);HButton("预览",{preview=!preview},compact=true,enabled=path.endsWith(".md",true))
        }
        Row(Modifier.padding(horizontal=12.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)){HTextField(query,{query=it},Modifier.weight(1f),placeholder="查找");HTextField(replacement,{replacement=it},Modifier.weight(1f),placeholder="替换");HButton("全部替换",{if(query.isNotEmpty())change(text.replace(query,replacement))},compact=true,enabled=query.isNotEmpty() && !large)}
        if(query.isNotEmpty())Text("匹配 ${Regex(Regex.escape(query)).findAll(text).count()} 处",color=c.text3,style=t.caption,modifier=Modifier.padding(12.dp))
        if(large)Row(Modifier.padding(12.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)){HButton("上一页",{page=(page-500).coerceAtLeast(1)},enabled=page>1);HButton("下一页",{page+=500},enabled=!eof)}
        if(preview)MarkdownText(text,Modifier.padding(16.dp)) else {
            Row(Modifier.padding(12.dp).horizontalScroll(rememberScrollState())) {
                Text(text.lines().indices.joinToString("\n"){(it+page).toString()},color=c.text3,style=t.code,modifier=Modifier.padding(end=12.dp))
                if(large)SelectionContainer{Text(text,style=t.code,color=c.text)}
                else BasicTextField(text,{change(it)},textStyle=t.code.copy(color=c.text),cursorBrush=SolidColor(c.accent),visualTransformation=VisualTransformation {value->val result=AnnotatedString.Builder(value);val pattern=Regex("\"(?:[^\"\\\\]|\\\\.)*\"|'[^']*'|\\b(?:fun|val|var|class|return|if|else|def|import|from|const|let|async|await|true|false|null|None)\\b|\\b[0-9]+\\b|//[^\\n]*|#[^\\n]*");for(match in pattern.findAll(value.text)){result.addStyle(SpanStyle(color=when{match.value.startsWith("\"")||match.value.startsWith("'")->c.success;match.value.first().isDigit()->c.warning;else->c.accent}),match.range.first,match.range.last+1)};TransformedText(result.toAnnotatedString(),OffsetMapping.Identity)})
            }
        }
    }
}
