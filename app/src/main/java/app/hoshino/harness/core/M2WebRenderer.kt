package app.hoshino.harness.core

import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import android.graphics.Bitmap
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume

object M2WebRenderer {
    @Suppress("SetJavaScriptEnabled")
    suspend fun render(context:Context,args:JSONObject):JSONObject=withContext(Dispatchers.Main){withTimeout(30000){suspendCancellableCoroutine{continuation->
        val url=args.getString("url");require(android.net.Uri.parse(url).scheme in setOf("http","https"))
        val view=WebView(context);var finished=false
        fun cleanup(){view.stopLoading();view.destroy()}
        view.settings.javaScriptEnabled=true;view.settings.allowFileAccess=false;view.settings.allowContentAccess=false;view.settings.domStorageEnabled=false;view.settings.mixedContentMode=android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        view.layout(0,0,1080,1600)
        view.webViewClient=object:WebViewClient(){override fun onPageFinished(v:WebView,u:String){
            if(finished)return;finished=true
            val script=args.optString("script").take(20000)
            v.evaluateJavascript(script.ifBlank{"void(0)"}){
                v.evaluateJavascript("JSON.stringify({url:location.href,title:document.title,text:document.body?document.body.innerText:'',html:document.documentElement.outerHTML.slice(0,1000000)})"){raw->
                    if(!continuation.isActive){cleanup();return@evaluateJavascript}
                    val decoded=runCatching{org.json.JSONTokener(raw).nextValue() as? String}.getOrNull()
                    val result=runCatching{JSONObject(decoded?:"{}")}.getOrDefault(JSONObject())
                    if(args.optBoolean("screenshot",false)){
                        val bitmap=Bitmap.createBitmap(1080,1600,Bitmap.Config.ARGB_8888);v.draw(android.graphics.Canvas(bitmap));val dir=File(context.filesDir,"workspace/web-captures").apply{mkdirs()};val file=File(dir,"page-${System.currentTimeMillis()}.png");file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle();result.put("screenshot","/root/workspace/web-captures/${file.name}")
                    }
                    cleanup();continuation.resume(result)
                }
            }
        }}
        continuation.invokeOnCancellation{android.os.Handler(android.os.Looper.getMainLooper()).post{cleanup()}}
        view.loadUrl(url)
    }}}
}
