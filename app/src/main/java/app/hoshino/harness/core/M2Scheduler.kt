package app.hoshino.harness.core

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.*
import androidx.work.*
import org.json.JSONArray
import java.util.concurrent.TimeUnit

object M2Scheduler {
    fun sync(context:Context,entries:JSONArray){
        val now=System.currentTimeMillis();val times=(0 until entries.length()).mapNotNull{i->val item=entries.getJSONObject(i);val record=item.optJSONObject("record")?:item.optJSONObject("schedule")?:item;runCatching{java.time.Instant.parse(record.getString("scheduledAt")).toEpochMilli()}.getOrNull()}
        val next=times.minOrNull()?:0L
        context.getSharedPreferences("hoshino-scheduler",Context.MODE_PRIVATE).edit().putLong("next",next).putBoolean("active",entries.length()>0).apply()
        val alarm=context.getSystemService(AlarmManager::class.java);val pending=PendingIntent.getBroadcast(context,77,Intent(context,M2ScheduleReceiver::class.java).setAction("hoshino.SCHEDULE"),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarm.cancel(pending)
        if(next>0){alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,maxOf(now+1000,next),pending);WorkManager.getInstance(context).enqueueUniquePeriodicWork("hoshino-schedule-recovery",ExistingPeriodicWorkPolicy.UPDATE,PeriodicWorkRequestBuilder<M2RecoveryWorker>(15,TimeUnit.MINUTES).build())}
        else WorkManager.getInstance(context).cancelUniqueWork("hoshino-schedule-recovery")
    }
    fun wake(context:Context){runCatching{context.startForegroundService(Intent(context,dev.hoshino.app.KernelService::class.java))}.onFailure{M2Notifications.post(context,"Hoshino 定时任务待恢复","系统限制了后台启动，请点此打开应用继续")}}
}
class M2ScheduleReceiver:BroadcastReceiver(){override fun onReceive(context:Context,intent:Intent){val prefs=context.getSharedPreferences("hoshino-scheduler",Context.MODE_PRIVATE);if(prefs.getBoolean("active",false))M2Scheduler.wake(context)}}
class M2RecoveryWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params){override suspend fun doWork():Result {val p=applicationContext.getSharedPreferences("hoshino-scheduler",Context.MODE_PRIVATE);if(p.getBoolean("active",false)&&p.getLong("next",Long.MAX_VALUE)<=System.currentTimeMillis())M2Scheduler.wake(applicationContext);return Result.success()}}
