package app.hoshino.harness

import android.os.Bundle
import android.content.Intent
import dev.hoshino.app.KernelService
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import app.hoshino.harness.ui.HoshinoApp

class MainActivity : ComponentActivity() {
    override fun onNewIntent(intent:Intent){super.onNewIntent(intent);setIntent(intent);lifecycleScope.launch{runCatching{app.hoshino.harness.core.M2Intents.receive(this@MainActivity,intent)}}}
    override fun onKeyLongPress(keyCode:Int,event:android.view.KeyEvent?):Boolean {
        if(keyCode==android.view.KeyEvent.KEYCODE_VOLUME_DOWN || keyCode==android.view.KeyEvent.KEYCODE_VOLUME_UP){app.hoshino.harness.core.M2SafeMode.set(this,true);lifecycleScope.launch{app.hoshino.harness.core.Hoshino.client.restartKernel()};return true}
        return super.onKeyLongPress(keyCode,event)
    }
    override fun onKeyDown(keyCode:Int,event:android.view.KeyEvent?):Boolean {if(keyCode==android.view.KeyEvent.KEYCODE_VOLUME_DOWN || keyCode==android.view.KeyEvent.KEYCODE_VOLUME_UP){event?.startTracking();return true};return super.onKeyDown(keyCode,event)}
    override fun onResume(){super.onResume();app.hoshino.harness.core.AppSurface.resume(this);lifecycleScope.launch{app.hoshino.harness.core.Hoshino.client.refreshShizuku()}}
    override fun onPause(){app.hoshino.harness.core.AppSurface.pause(this);super.onPause()}
    override fun onCreate(savedInstanceState: Bundle?) {
        // Android 12+ 系统 SplashScreen（低版本由 core-splashscreen 兼容），主题见 Theme.Hoshino.Starting
        val activityStartedAt=android.os.SystemClock.elapsedRealtime()
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        startForegroundService(Intent(this,KernelService::class.java))
        setContent { HoshinoApp() }
        var inputRecorded=false
        val observer=object:android.view.ViewTreeObserver.OnDrawListener{override fun onDraw(){if(inputRecorded)return;inputRecorded=true;window.decorView.post{runCatching{java.io.File(filesDir,"m2-input-ready.json").writeText(org.json.JSONObject().put("coldStartToInputMs",android.os.SystemClock.elapsedRealtime()-app.hoshino.harness.core.M2SafeMode.processStartedAt).put("definition","Application.onCreate to first native Compose draw").put("activityToFirstDrawMs",android.os.SystemClock.elapsedRealtime()-activityStartedAt).put("sessionsAtFirstDraw",app.hoshino.harness.core.Hoshino.client.sessions.value.size).put("kernelAtFirstDraw",(app.hoshino.harness.core.Hoshino.client as? app.hoshino.harness.core.DshHarnessClient)?.runtime?.state?.value?.phase).toString())};window.decorView.viewTreeObserver.removeOnDrawListener(this)}}}
        window.decorView.viewTreeObserver.addOnDrawListener(observer)
        lifecycleScope.launch{runCatching{app.hoshino.harness.core.M2Intents.receive(this@MainActivity,intent)}}
    }
}
