package app.hoshino.harness.core

import android.app.*
import android.content.*
import kotlinx.coroutines.*

object M2Notifications {
    private fun manager(context:Context):NotificationManager=context.getSystemService(NotificationManager::class.java).also{it.createNotificationChannel(NotificationChannel("hoshino-actions","任务与审批",NotificationManager.IMPORTANCE_DEFAULT))}
    private fun open(context:Context)=PendingIntent.getActivity(context,0,Intent(context,app.hoshino.harness.MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    fun post(context:Context,title:String,text:String){runCatching{manager(context).notify(title.hashCode(),Notification.Builder(context,"hoshino-actions").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title.take(100)).setContentText(text.take(300)).setContentIntent(open(context)).setAutoCancel(true).build())}}
    fun approval(context:Context,session:String,id:String,title:String,oneShot:Boolean=false){
        val builder=Notification.Builder(context,"hoshino-actions").setSmallIcon(android.R.drawable.ic_dialog_alert).setContentTitle("等待审批 · $title").setContentText("在通知中选择或打开 Hoshino 查看详情").setContentIntent(open(context)).setAutoCancel(true)
        for(decision in approvalChoices(oneShot)){val label=decision.label
            val intent=Intent(context,M2ActionReceiver::class.java).setAction("approval.$id.${decision.name}").putExtra("session",session).putExtra("id",id).putExtra("decision",decision.name)
            builder.addAction(Notification.Action.Builder(null,label,PendingIntent.getBroadcast(context,(id+decision.name).hashCode(),intent,PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build())
        }
        runCatching{manager(context).notify(id.hashCode(),builder.build())}
    }
    fun dismiss(context:Context,id:String){manager(context).cancel(id.hashCode())}
    fun question(context:Context,session:String,id:String,text:String){val intent=Intent(context,M2ActionReceiver::class.java).setAction("question.$id").putExtra("session",session).putExtra("id",id);val reply=PendingIntent.getBroadcast(context,id.hashCode(),intent,PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT);val action=Notification.Action.Builder(null,"回复",reply).addRemoteInput(android.app.RemoteInput.Builder("answer").setLabel("输入回复").build()).build();runCatching{manager(context).notify(id.hashCode(),Notification.Builder(context,"hoshino-actions").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Hoshino 等待输入").setContentText(text.take(300)).setContentIntent(open(context)).addAction(action).build())}}
}
class M2ActionReceiver:BroadcastReceiver(){override fun onReceive(context:Context,intent:Intent){val pending=goAsync();CoroutineScope(Dispatchers.IO).launch{try{
    if(intent.action?.startsWith("question.")==true){val reply=android.app.RemoteInput.getResultsFromIntent(intent)?.getCharSequence("answer")?.toString();if(reply!=null)Hoshino.client.answerQuestion(intent.getStringExtra("session")?:"",intent.getStringExtra("id")?:"",reply);return@launch}
    val decision=runCatching{ApprovalDecision.valueOf(intent.getStringExtra("decision")?:"")}.getOrNull()?:return@launch
    Hoshino.client.respondApproval(intent.getStringExtra("session")?:"",intent.getStringExtra("id")?:"",decision)
}finally{pending.finish()}}}}

object M2SafeMode {
    var processStartedAt:Long=android.os.SystemClock.elapsedRealtime();private set
    fun initialize(context:Context){
        processStartedAt=android.os.SystemClock.elapsedRealtime()
        val p=context.getSharedPreferences("hoshino-startup",Context.MODE_PRIVATE)
        if(p.getBoolean("starting",false))p.edit().putInt("failures",p.getInt("failures",0)+1).commit()
        if(p.getInt("failures",0)>=3)p.edit().putBoolean("safe",true).commit()
        p.edit().putBoolean("starting",true).commit()
        val previous=Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler{t,e->p.edit().putBoolean("starting",false).putInt("failures",p.getInt("failures",0)+1).commit();previous?.uncaughtException(t,e)}
    }
    fun ready(context:Context){android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({context.getSharedPreferences("hoshino-startup",Context.MODE_PRIVATE).edit().putBoolean("starting",false).putInt("failures",0).apply()},30000)}
    fun enabled(context:Context)=context.getSharedPreferences("hoshino-startup",Context.MODE_PRIVATE).getBoolean("safe",false)
    fun set(context:Context,enabled:Boolean){context.getSharedPreferences("hoshino-startup",Context.MODE_PRIVATE).edit().putBoolean("safe",enabled).commit()}
    fun recordRunningMemory(context:Context){runCatching{val info=android.os.Debug.MemoryInfo().also{android.os.Debug.getMemoryInfo(it)};val record=org.json.JSONObject().put("at",System.currentTimeMillis()).put("appPssKiB",info.totalPss);val pidFile=java.io.File(context.filesDir,"hoshino/runtime-process.json");if(pidFile.isFile){val pid=org.json.JSONObject(pidFile.readText()).getInt("pid");val status=java.io.File("/proc/$pid/status").readText();record.put("nodeRssKiB",Regex("VmRSS:\\s+(\\d+)").find(status)?.groupValues?.get(1)?.toLongOrNull()?:org.json.JSONObject.NULL)};java.io.File(context.filesDir,"m2-running-memory.json").writeText(record.toString())}}
}
