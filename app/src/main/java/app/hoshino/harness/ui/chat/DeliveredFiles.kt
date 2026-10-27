package app.hoshino.harness.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hoshino.harness.core.*
import app.hoshino.harness.ui.components.*
import app.hoshino.harness.ui.theme.HoshinoTheme
import kotlinx.coroutines.launch

@Composable fun DeliveredFiles(sessionId:String){
    val client=Hoshino.client as? DshHarnessClient?:return
    val files by client.m2.deliveries.collectAsStateWithLifecycle();val context=LocalContext.current;val scope=rememberCoroutineScope()
    files.filter{it.sessionId==sessionId}.takeLast(12).forEach{row->
        var preview by remember(row.id){mutableStateOf(false)}
        Column(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=4.dp).hairlineCard(HoshinoTheme.colors.surface,HoshinoTheme.colors.line).padding(12.dp)){
            Text(row.name,style=HoshinoTheme.type.bodyMedium,color=HoshinoTheme.colors.text)
            Text("${row.bytes/1024} KB · ${row.name.substringAfterLast('.',"文件").uppercase()}",style=HoshinoTheme.type.caption,color=HoshinoTheme.colors.text3)
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                HButton("预览",{preview=true},compact=true)
                HButton("保存",{scope.launch{runCatching{client.m2.save(client.m2.workspaceFile(row.path),row.name)}.onSuccess{toast(context,"已保存到下载/Hoshino")}.onFailure{toast(context,it.message?:"保存失败")}}},compact=true)
                HButton("分享/打开",{runCatching{AppMedia(context).shareFile(client.m2.workspaceFile(row.path))}.onFailure{toast(context,it.message?:"文件失效")}},compact=true)
            }
        }
        if(preview)HSheet(onDismiss={preview=false},title=row.name){
            val file=runCatching{client.m2.workspaceFile(row.path)}.getOrNull()
            if(file==null)Text("文件已移走")else when(file.extension.lowercase()){
                "png","jpg","jpeg","webp","gif"->{val options=android.graphics.BitmapFactory.Options().apply{inSampleSize=2};val bitmap=remember(file.path){android.graphics.BitmapFactory.decodeFile(file.path,options)};if(bitmap!=null)androidx.compose.foundation.Image(bitmap.asImageBitmap(),row.name,Modifier.fillMaxWidth().heightIn(max=500.dp))}
                "mp4","webm"->AndroidView(factory={android.widget.VideoView(it).apply{setVideoURI(AppMedia(context).uri(file));setMediaController(android.widget.MediaController(it).also{c->c.setAnchorView(this)});start()}},modifier=Modifier.fillMaxWidth().height(300.dp))
                "mp3","wav","m4a","flac"->{var player by remember{mutableStateOf<android.media.MediaPlayer?>(null)};DisposableEffect(Unit){onDispose{player?.release()}};HButton("播放 / 暂停",{if(player==null)player=android.media.MediaPlayer.create(context,AppMedia(context).uri(file));player?.let{if(it.isPlaying)it.pause()else it.start()}})}
                "zip"->{val entries=remember(file.path){runCatching{java.util.zip.ZipFile(file).use{it.entries().asSequence().take(2000).joinToString("\n"){e->"${e.name} · ${e.size} B"}}}.getOrDefault("压缩包无法读取")};SelectionContainer{Text(entries,style=HoshinoTheme.type.code,modifier=Modifier.heightIn(max=550.dp).verticalScroll(rememberScrollState()))}}
                "pdf"->{val bitmap=remember(file.path){runCatching{android.os.ParcelFileDescriptor.open(file,android.os.ParcelFileDescriptor.MODE_READ_ONLY).use{fd->android.graphics.pdf.PdfRenderer(fd).use{pdf->pdf.openPage(0).use{page->android.graphics.Bitmap.createBitmap(page.width*2,page.height*2,android.graphics.Bitmap.Config.ARGB_8888).also{page.render(it,null,null,android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)}}}}}.getOrNull()};if(bitmap!=null)androidx.compose.foundation.Image(bitmap.asImageBitmap(),row.name,Modifier.fillMaxWidth().heightIn(max=550.dp))}
                else->{val text=remember(file.path){if(file.length()>2*1024*1024)"文件较大，请使用其他应用打开"else runCatching{file.readText()}.getOrDefault("二进制文件，请使用其他应用打开")};SelectionContainer{Text(text,style=HoshinoTheme.type.code,modifier=Modifier.heightIn(max=550.dp).verticalScroll(rememberScrollState()))}}
            }
        }
    }
}
