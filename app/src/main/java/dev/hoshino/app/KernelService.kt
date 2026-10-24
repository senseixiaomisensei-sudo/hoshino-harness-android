package dev.hoshino.app

import android.app.*
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import app.hoshino.harness.MainActivity
import app.hoshino.harness.core.DshHarnessClient
import app.hoshino.harness.core.Hoshino

class KernelService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    override fun onCreate() {
        super.onCreate()
        val notifications=getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel("kernel","本地 Agent 内核",NotificationManager.IMPORTANCE_LOW))
        val intent=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        val stop=PendingIntent.getService(this,1,Intent(this,KernelService::class.java).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE)
        startForeground(1,Notification.Builder(this,"kernel").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Hoshino Harness").setContentText("本地 dsh 内核运行中").setContentIntent(intent).addAction(Notification.Action.Builder(null,"停止",stop).build()).setOngoing(true).build())
        wakeLock=(getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"hoshino:kernel").apply { acquire(15*60*1000L) }
        (Hoshino.client as? DshHarnessClient)?.startKernel()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int { if(intent?.action=="STOP")stopSelf();return START_NOT_STICKY }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { (Hoshino.client as? DshHarnessClient)?.stopKernel(); if(wakeLock?.isHeld==true) wakeLock?.release();super.onDestroy() }
}
