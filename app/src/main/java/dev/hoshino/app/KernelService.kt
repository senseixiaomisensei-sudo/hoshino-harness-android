package dev.hoshino.app

import android.app.*
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager

class KernelService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    override fun onCreate() {
        super.onCreate()
        val notifications=getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel("kernel","本地 Agent 内核",NotificationManager.IMPORTANCE_LOW))
        val intent=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        startForeground(1,Notification.Builder(this,"kernel").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Hoshino Harness").setContentText("本地 dsh 内核运行中").setContentIntent(intent).setOngoing(true).build())
        wakeLock=(getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"hoshino:kernel").apply { acquire(15*60*1000L) }
        KernelRuntime.start(applicationContext)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { KernelRuntime.stop(); if(wakeLock?.isHeld==true) wakeLock?.release();super.onDestroy() }
}
