package app.hoshino.harness.core

import android.content.*

object KernelPrewarm {
    fun enabled(context:Context)=context.getSharedPreferences("hoshino-startup",Context.MODE_PRIVATE).getBoolean("prewarm",true)
    fun set(context:Context,value:Boolean){context.getSharedPreferences("hoshino-startup",Context.MODE_PRIVATE).edit().putBoolean("prewarm",value).apply()}
}
class KernelPrewarmReceiver:BroadcastReceiver(){override fun onReceive(context:Context,intent:Intent){if(intent.action in setOf(Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_MY_PACKAGE_REPLACED)&&KernelPrewarm.enabled(context))M2Scheduler.wake(context)}}
