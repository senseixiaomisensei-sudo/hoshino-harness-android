package app.hoshino.harness.core
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID
data class SharedTask(val id:String=UUID.randomUUID().toString(),val text:String="",val attachment:Attachment?=null,val newTask:Boolean=false)
object M2Intents {
    val pending=MutableStateFlow<SharedTask?>(null)
    suspend fun receive(context:Context,intent:Intent){
        val text=intent.getStringExtra(Intent.EXTRA_TEXT)?:intent.getStringExtra("hoshino.prompt").orEmpty()
        @Suppress("DEPRECATION") val uri=intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
        val attachment=uri?.let{AppMedia(context).import(it,if(context.contentResolver.getType(it)?.startsWith("image/")==true)AttachmentKind.Image else AttachmentKind.File).attachment}
        if(text.isNotBlank()||attachment!=null||intent.getBooleanExtra("hoshino.new",false))pending.value=SharedTask(text=text,attachment=attachment,newTask=intent.action==Intent.ACTION_SEND||intent.getBooleanExtra("hoshino.new",false))
    }
}
class M2TileService:android.service.quicksettings.TileService(){override fun onClick(){super.onClick();val intent=Intent(this,app.hoshino.harness.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("hoshino.new",true);if(Build.VERSION.SDK_INT>=34)startActivityAndCollapse(android.app.PendingIntent.getActivity(this,84,intent,android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT))else{@Suppress("DEPRECATION") startActivityAndCollapse(intent)}}}
class M2Widget:android.appwidget.AppWidgetProvider(){override fun onUpdate(context:Context,manager:android.appwidget.AppWidgetManager,ids:IntArray){for(id in ids){val views=android.widget.RemoteViews(context.packageName,app.hoshino.harness.R.layout.m2_widget);views.setTextViewText(app.hoshino.harness.R.id.m2_widget_status,"Hoshino · ${Hoshino.client.sessions.value.count{it.running}} 个任务运行中");val intent=Intent(context,app.hoshino.harness.MainActivity::class.java).putExtra("hoshino.new",true);views.setOnClickPendingIntent(app.hoshino.harness.R.id.m2_widget_new,android.app.PendingIntent.getActivity(context,id,intent,android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT));manager.updateAppWidget(id,views)}}}
