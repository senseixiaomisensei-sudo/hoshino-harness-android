package app.hoshino.harness

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.hoshino.harness.core.*
import app.hoshino.harness.ui.diy.DiySlot
import app.hoshino.harness.ui.theme.HoshinoTheme
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class M2AcceptanceTest {
    @get:Rule val ui=createEmptyComposeRule()
    @Test fun nativeDslHotReloadSlotFallbackDeliveryAndRecoveryBoundaries(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val client=Hoshino.client as DshHarnessClient
        ActivityScenario.launch(MainActivity::class.java).use{scenario->
            ui.waitUntil(300000){client.runtime.state.value.phase in setOf("已连接","崩溃")};assertEquals(client.runtime.state.value.logs.joinToString(),"已连接",client.runtime.state.value.phase)
            val baseline=runBlocking{client.control("config-read",JSONObject().put("path","ui/overrides.json"))}.getString("content")
            try{
                val start=android.os.SystemClock.elapsedRealtime()
                runBlocking{client.control("ui-override",JSONObject().put("content","""{"version":1,"process":{"display":"collapsed"},"sidebarColumns":2,"slots":{"topbar":{"type":"battery"},"message":{"type":"text","text":"M2-VALID-SLOT"}}}"""));client.refreshControl()}
                ui.waitUntil(10000){client.uiCustomization.value.raw.optInt("sidebarColumns")==2}
                val reload=android.os.SystemClock.elapsedRealtime()-start
                scenario.onActivity{it.setContent{HoshinoTheme{Column{DiySlot("topbar"){Text("DEFAULT SLOT")};DiySlot("message"){Text("DEFAULT MESSAGE")}}}}}
                ui.onNodeWithText("电量",substring=true).assertExists();ui.onNodeWithText("M2-VALID-SLOT").assertExists()
                File(context.filesDir,"hoshino/ui/overrides.json").writeText("""{"version":1,"slots":{"topbar":{"type":"invalid-native-widget"},"message":{"type":"text","text":"M2-VALID-SLOT"}}}""")
                ui.waitUntil(15000){client.uiCustomization.value.raw.optJSONObject("slots")?.has("topbar")==false}
                ui.onNodeWithText("DEFAULT SLOT").assertExists();ui.onNodeWithText("M2-VALID-SLOT").assertExists()
                val file=File(context.filesDir,"workspace/m2-delivery.txt").apply{parentFile!!.mkdirs();writeText("M2 file delivery fixture")}
                val result=runBlocking{client.m2Control("deliver",JSONObject().put("sessionId","m2-fixture").put("path","/root/workspace/m2-delivery.txt"))}
                assertTrue(result.getBoolean("delivered"));assertEquals(file.length(),result.getLong("bytes"));assertTrue(client.m2.deliveries.value.any{it.name==file.name})
                val caps=runBlocking{client.m2Control("doctor")}.getJSONObject("bridge");assertEquals(1,caps.getInt("version"))
                val modes=runBlocking{client.m2Control("state")}.getJSONArray("modes");assertTrue(modes.length()>=9)
                val memory=android.os.Debug.MemoryInfo().also{android.os.Debug.getMemoryInfo(it)}
                File(context.filesDir,"m2-native-metrics.json").writeText(JSONObject().put("environment","x86_64 GitHub Actions AOSP emulator").put("device",android.os.Build.MODEL).put("sdk",android.os.Build.VERSION.SDK_INT).put("firstBootKernelReadyMs",client.runtime.state.value.readyAt-client.runtime.state.value.startedAt).put("uiDslReloadMs",reload).put("appIdlePssKiB",memory.totalPss).put("kernelMemoryIncluded",false).put("coldStartToInputMs",JSONObject.NULL).put("background30MinuteSurvivalRate",JSONObject.NULL).put("searchAverageMs",JSONObject.NULL).put("searchSuccessRate",JSONObject.NULL).put("source","M2AcceptanceTest").toString(2))
            }finally{runBlocking{client.control("ui-override",JSONObject().put("content",baseline));client.refreshControl()}}
        }
    }
    @Test fun onDemandToolsGenerateTenSecondMediaDocxAndZip(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext;val client=Hoshino.client as DshHarnessClient
        ActivityScenario.launch(MainActivity::class.java).use{
            ui.waitUntil(300000){client.runtime.state.value.phase in setOf("已连接","崩溃")};assertEquals("已连接",client.runtime.state.value.phase)
            runBlocking{for(group in listOf("media","documents")){val result=client.m2Control("ensure-tools",JSONObject().put("group",group));assertEquals(result.optString("output"),0,result.getInt("code"))}}
            val terminal=runBlocking{client.openTerminal()}
            try{
                val command="ffmpeg -hide_banner -loglevel error -y -f lavfi -i color=c=blue:s=64x64:r=5 -t 10 -c:v libx264 -pix_fmt yuv420p /root/workspace/m2-video.mp4 && ffmpeg -hide_banner -loglevel error -y -f lavfi -i sine=frequency=440:sample_rate=22050 -t 10 -c:a libmp3lame /root/workspace/m2-audio.mp3 && python3 -c \"from docx import Document; d=Document(); d.add_paragraph('Hoshino M2 document fixture'); d.save('/root/workspace/m2-document.docx')\" && python3 -c \"import zipfile; z=zipfile.ZipFile('/root/workspace/m2-files.zip','w'); [z.write('/root/workspace/'+n,n) for n in ['m2-video.mp4','m2-audio.mp3','m2-document.docx']]; z.close()\"\n"
                runBlocking{client.terminalInput(terminal,command)}
                val archive=File(context.filesDir,"workspace/m2-files.zip")
                ui.waitUntil(180000){archive.isFile&&runCatching{java.util.zip.ZipFile(archive).use{z->z.size()==3}}.getOrDefault(false)}
                for(name in listOf("m2-video.mp4","m2-audio.mp3","m2-document.docx","m2-files.zip")){
                    val file=File(context.filesDir,"workspace/$name");assertTrue(file.length()>0)
                    val delivered=runBlocking{client.m2Control("deliver",JSONObject().put("sessionId","m2-media-fixture").put("path","/root/workspace/$name"))};assertTrue(delivered.getBoolean("delivered"))
                    val saved=runBlocking{client.m2.save(file,name)};assertTrue(saved.getString("uri").startsWith("content://"))
                }
                for(name in listOf("m2-video.mp4","m2-audio.mp3")){val media=android.media.MediaMetadataRetriever();try{media.setDataSource(File(context.filesDir,"workspace/$name").path);val duration=media.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong();assertTrue("Expected ten-second media: $duration",duration in 9900..11000)}finally{media.release()}}
                java.util.zip.ZipFile(File(context.filesDir,"workspace/m2-document.docx")).use{assertNotNull(it.getEntry("word/document.xml"))}
            }finally{runBlocking{client.closeTerminal(terminal)}}
        }
    }
}
