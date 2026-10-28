package dev.hoshino.app

import android.app.*
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import app.hoshino.harness.MainActivity
import app.hoshino.harness.core.DshHarnessClient
import app.hoshino.harness.core.Hoshino
import kotlinx.coroutines.*

class KernelService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
    override fun onCreate() {
        super.onCreate()
        val notifications=getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel("kernel","本地 Agent 内核",NotificationManager.IMPORTANCE_LOW))
        val intent=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        val stop=PendingIntent.getService(this,1,Intent(this,KernelService::class.java).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE)
        val pause=PendingIntent.getService(this,2,Intent(this,KernelService::class.java).setAction("PAUSE"),PendingIntent.FLAG_IMMUTABLE)
        fun notification(text:String)=Notification.Builder(this,"kernel").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Hoshino Harness").setContentText(text).setContentIntent(intent).addAction(Notification.Action.Builder(null,"暂停",pause).build()).addAction(Notification.Action.Builder(null,"停止",stop).build()).setOngoing(true).build()
        startForeground(1,notification("本地 dsh 内核运行中"))
        wakeLock=(getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"hoshino:kernel")
        scope.launch{while(isActive){val running=Hoshino.client.sessions.value.count{it.running};if(running>0 && wakeLock?.isHeld!=true)wakeLock?.acquire(10*60*1000L);if(running==0 && wakeLock?.isHeld==true)wakeLock?.release();notifications.notify(1,notification(if(running>0)"$running 个会话运行中 · 已启用事件日志与恢复"else if((Hoshino.client as? DshHarnessClient)?.runtime?.state?.value?.phase=="已连接")"本地内核就绪"else"正在启动或重连本地内核"));delay(5000)}}
        (Hoshino.client as? DshHarnessClient)?.startKernel()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int { if(intent?.action=="STOP"){stopSelf();return START_NOT_STICKY};if(intent?.action=="PAUSE")scope.launch{Hoshino.client.sessions.value.filter{it.running}.forEach{runCatching{Hoshino.client.stop(it.id)}}};return START_STICKY }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { scope.cancel();(Hoshino.client as? DshHarnessClient)?.stopKernel(); if(wakeLock?.isHeld==true) wakeLock?.release();super.onDestroy() }
}
