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
}
