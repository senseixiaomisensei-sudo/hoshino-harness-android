package app.hoshino.harness.ui.tools

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.hoshino.harness.core.*
import app.hoshino.harness.ui.chat.ChatItemView
import app.hoshino.harness.ui.chat.MessageActions
import app.hoshino.harness.ui.components.*
import app.hoshino.harness.ui.theme.HoshinoTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.lifecycle.compose.collectAsStateWithLifecycle

data class CaptureSelection(val items:List<ChatItem> = emptyList(),val screen:Bitmap?=null)
object CaptureRequests {val selection=MutableStateFlow<CaptureSelection?>(null)}

/** Measure and paint each detached message once. Never scroll or stitch viewport screenshots. */
object LongCapture {
    suspend fun render(items:List<ChatItem>,dark:Boolean,hideThinking:Boolean=false,hideTools:Boolean=false,watermark:Boolean=true):Bitmap=withContext(Dispatchers.Main) {
        val activity=AppSurface.activity();check(activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE==0)
        val decor=activity.window.decorView as ViewGroup;val width=decor.width
        val host=FrameLayout(activity);decor.addView(host,ViewGroup.LayoutParams(width,1));host.translationX=-width.toFloat()*2
        val parts=mutableListOf<Bitmap>();var height=0
        suspend fun paint(content:@Composable ()->Unit) {
            val view=ComposeView(activity);host.addView(view)
            view.setContent {HoshinoTheme(mode=if(dark)app.hoshino.harness.ui.theme.ThemeMode.Dark else app.hoshino.harness.ui.theme.ThemeMode.Light){Box(Modifier.fillMaxWidth().background(HoshinoTheme.colors.bg)){content()}}}
            delay(32)
            view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
            val h=view.measuredHeight.coerceAtLeast(1);require(width.toLong()*(height+h)<=32_000_000){"所选内容过长，请缩小消息范围"}
            view.layout(0,0,width,h);val part=Bitmap.createBitmap(width,h,Bitmap.Config.ARGB_8888);view.draw(Canvas(part));parts+=part;height+=h
            view.disposeComposition();host.removeView(view)
        }
        try {
            for(item in items) {
                if(hideThinking && item is ChatItem.Thinking)continue
                val visible=if(hideTools)when(item){is ChatItem.Tool->ChatItem.Notice(item.id,"工具 · ${item.name} · ${item.status.label}");is ChatItem.Terminal->ChatItem.Notice(item.id,"终端 · ${item.status.label}");is ChatItem.Diff->ChatItem.Notice(item.id,"文件 · ${item.path}");else->item} else item
                paint {ChatItemView(visible,MessageActions())}
            }
            if(watermark)paint {Row(Modifier.padding(12.dp)){app.hoshino.harness.ui.components.HoshinoMark(18.dp);Spacer(Modifier.width(6.dp));Text("Hoshino Harness",style=HoshinoTheme.type.codeSmall,color=HoshinoTheme.colors.text3)}}
            require(height>0){"没有可截图的消息"}
            val result=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);val canvas=Canvas(result);var top=0
            for(part in parts){canvas.drawBitmap(part,0f,top.toFloat(),null);top+=part.height;part.recycle()};parts.clear();result
        } finally {parts.forEach{it.recycle()};decor.removeView(host)}
    }
}

@Composable
fun ScreenshotScreen(onBack:()->Unit,onAttach:(Attachment)->Unit) {
    val context=LocalContext.current;val c=HoshinoTheme.colors;val scope=rememberCoroutineScope();val media=remember{AppMedia(context)}
    val request by CaptureRequests.selection.collectAsStateWithLifecycle()
    var bitmap by remember{mutableStateOf<Bitmap?>(null)};var error by remember{mutableStateOf("")}
    var thinking by remember{mutableStateOf(false)};var tools by remember{mutableStateOf(false)};var watermark by remember{mutableStateOf(true)}
    var first by remember{mutableStateOf("1")};var last by remember{mutableStateOf(request?.items?.size?.toString() ?: "1")}
    var cropTop by remember{mutableStateOf(0f)};var cropBottom by remember{mutableStateOf(1f)}
    val dark=c.isDark
    LaunchedEffect(request,thinking,tools,watermark,first,last) {
        error=""
        runCatching {
            request?.screen ?: request?.items?.let{items->val from=(first.toIntOrNull() ?: 1).coerceIn(1,items.size.coerceAtLeast(1));val to=(last.toIntOrNull() ?: items.size).coerceIn(from,items.size.coerceAtLeast(from));LongCapture.render(items.subList(from-1,to),dark,thinking,tools,watermark)}
        }.onSuccess{bitmap=it}.onFailure{error=it.message ?: "截图失败"}
    }
    fun cropped():Bitmap {val source=bitmap ?: kotlin.error("尚未生成截图");val top=(source.height*cropTop).toInt();val bottom=(source.height*cropBottom).toInt().coerceAtLeast(top+1).coerceAtMost(source.height);return Bitmap.createBitmap(source,0,top,source.width,bottom-top)}
    fun action(block:suspend()->Unit){scope.launch{try{block()}catch(e:Exception){toast(context,e.message ?: "操作失败")}}}
    PageScaffold("截图预览",onBack,subtitle=bitmap?.let{"${it.width} × ${it.height}"} ?: "离屏渲染") {
        if(request?.screen==null) {
            Row(Modifier.padding(16.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){HTextField(first,{first=it},Modifier.weight(1f),label="首条消息");HTextField(last,{last=it},Modifier.weight(1f),label="末条消息")}
            GroupCard {ListRow("隐藏 thinking",trailing={HSwitch(thinking,{thinking=it})});ListRow("隐藏工具细节",trailing={HSwitch(tools,{tools=it})});ListRow("H 标志水印",trailing={HSwitch(watermark,{watermark=it})})}
        }
        if(error.isNotEmpty())Text(error,color=c.danger,modifier=Modifier.padding(16.dp))
        bitmap?.let{image->Image(image.asImageBitmap(),"截图预览",Modifier.fillMaxWidth().heightIn(max=500.dp));Text("裁剪顶部 / 底部",style=HoshinoTheme.type.caption,color=c.text2,modifier=Modifier.padding(16.dp));androidx.compose.material3.Slider(cropTop,{cropTop=it.coerceAtMost(cropBottom-0.01f)},valueRange=0f..0.99f);androidx.compose.material3.Slider(cropBottom,{cropBottom=it.coerceAtLeast(cropTop+0.01f)},valueRange=0.01f..1f)}
        Row(Modifier.padding(16.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            HButton("保存",{action{media.save(cropped());toast(context,"已保存到 Pictures/Hoshino Harness")}},enabled=bitmap!=null)
            HButton("分享",{action{media.shareImage(cropped())}},enabled=bitmap!=null)
            HButton("复制图片",{action{media.shareImage(cropped(),true);toast(context,"已复制图片")}},enabled=bitmap!=null)
        }
        HButton("附加到会话",{action{val uri=media.save(cropped());val attachment=media.import(uri,AttachmentKind.Image).attachment;onAttach(attachment);onBack()}},Modifier.fillMaxWidth().padding(16.dp),enabled=bitmap!=null)
    }
}
