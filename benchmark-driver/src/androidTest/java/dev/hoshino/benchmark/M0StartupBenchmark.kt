package dev.hoshino.benchmark

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** A separate instrumentation process can force-stop Hoshino without killing the measurement driver. */
class M0StartupBenchmark {
    @Test fun fiveColdIconLaunchesAndMemory() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val device=UiDevice.getInstance(instrumentation)
        Configurator.getInstance().setWaitForIdleTimeout(0).setWaitForSelectorTimeout(0)
        val output=context.getExternalFilesDir(null)!!
        fun shell(command: String)=device.executeShellCommand(command)
        val acceptance=JSONObject(shell("cat /sdcard/Android/data/dev.hoshino.app/files/m0-metrics.json"))
        assertTrue(acceptance.getBoolean("backgroundContinuation"))
        fun waitFor(message: String, timeout: Long=60000, condition: () -> Boolean) {
            val deadline=SystemClock.elapsedRealtime()+timeout
            while(true) {
                try {if(condition())return} catch(_: StaleObjectException) { }
                assertTrue(message,SystemClock.elapsedRealtime()<deadline);Thread.sleep(25)
            }
        }
        fun memory(iteration: Int,phase: String): JSONArray {
            val rows=shell("ps -A -o PID,PPID,NAME").lineSequence().drop(1).map {it.trim().split(Regex("\\s+"))}.filter {it.size>=3 && it[0].toIntOrNull()!=null && it[1].toIntOrNull()!=null}.toList()
            val app=rows.first {it[2]=="dev.hoshino.app"}[0].toInt()
            val owned=mutableSetOf(app)
            do {val count=owned.size;rows.filter {it[1].toInt() in owned}.forEach {owned+=it[0].toInt()}} while(owned.size>count)
            val result=JSONArray()
            for(row in rows.filter {it[0].toInt() in owned}) {
                val pid=row[0].toInt();val raw=shell("dumpsys meminfo $pid")
                fun number(pattern: String)=Regex(pattern,RegexOption.MULTILINE).find(raw)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val pss=number("TOTAL PSS:\\s*(\\d+)").takeIf {it>0} ?: number("^\\s*TOTAL\\s+(\\d+)")
                assertTrue("Missing PSS for process $pid",pss>0)
                val name=row[2];val kind=if(pid==app) "java-app" else if(name.contains("proot")) "proot" else if(name=="node") "node-native" else "native-child"
                val file="m0-memory/$iteration-$phase-$kind-$pid.txt"
                File(output,file).apply {parentFile!!.mkdirs();writeText(raw)}
                result.put(JSONObject().put("pid",pid).put("kind",kind).put("pssKb",pss).put("javaHeapPssKb",number("^\\s*Java Heap:\\s*(\\d+)")).put("nativeHeapPssKb",number("^\\s*Native Heap:\\s*(\\d+)")).put("dump",file).put("capturedAtElapsedMs",SystemClock.elapsedRealtime()))
            }
            val kinds=(0 until result.length()).map {result.getJSONObject(it).getString("kind")}
            assertTrue("proot and Node must be observed",kinds.contains("proot") && kinds.contains("node-native"))
            return result
        }
        val samples=JSONArray();val readyTimes=mutableListOf<Long>();val tokenTimes=mutableListOf<Long>()
        val runMarker=java.util.UUID.randomUUID().toString().take(8)
        for(iteration in 1..5) {
            shell("am force-stop dev.hoshino.app")
            device.wakeUp();shell("wm dismiss-keyguard")
            val launcher=requireNotNull(device.launcherPackageName) {"No default launcher installed"}
            fun findIcon()=device.findObject(By.pkg(launcher).text("Hoshino Harness")) ?: device.findObject(By.pkg(launcher).descStartsWith("Hoshino Harness"))
            var icon: UiObject2?=null
            for(attempt in 1..4) {
                device.pressHome()
                waitFor("Launcher unavailable before iteration $iteration",10000) {device.currentPackageName==launcher}
                Thread.sleep(750)
                icon=findIcon()
                if(icon==null) {
                    // Start in the workspace, above both gesture and three-button navigation areas.
                    device.swipe(device.displayWidth/2,device.displayHeight*4/5,device.displayWidth/2,device.displayHeight/5,30)
                    val until=SystemClock.elapsedRealtime()+6000
                    while(icon==null && SystemClock.elapsedRealtime()<until) {Thread.sleep(100);icon=findIcon()}
                }
                if(icon==null && device.hasObject(By.pkg(launcher).scrollable(true))) {
                    try {UiScrollable(UiSelector().packageName(launcher).scrollable(true)).scrollTextIntoView("Hoshino Harness")} catch(_: UiObjectNotFoundException) { }
                    icon=findIcon()
                }
                if(icon!=null) {Thread.sleep(450);icon=findIcon();if(icon!=null)break}
            }
            assertNotNull("Launcher icon unavailable before iteration $iteration",icon)
            val clickedAt=SystemClock.elapsedRealtime()
            icon!!.click()
            waitFor("Kernel readiness timed out",300000) {device.findObject(By.res("kernel-status"))?.text?.contains("已连接")==true}
            val readyAt=SystemClock.elapsedRealtime()
            if(!device.hasObject(By.textContains("claude / mock-model"))) {
                requireNotNull(device.findObject(By.res("model-picker"))).click()
                requireNotNull(device.wait(Until.findObject(By.text("claude / mock-model")),5000)).click()
                waitFor("Fixture provider selection failed") {device.hasObject(By.textContains("claude / mock-model"))}
            }
            val marker="m0-benchmark-$runMarker-$iteration"
            // TextField's outer test-tag node is not necessarily its editable accessibility node.
            val input=By.pkg("dev.hoshino.app").clazz("android.widget.EditText")
            requireNotNull(device.findObject(input)).text=marker
            waitFor("Fixture input was not applied") {device.findObject(input)?.text?.contains(marker)==true}
            waitFor("Send button did not enable") {device.findObject(By.res("send"))?.isEnabled==true}
            requireNotNull(device.findObject(By.res("send"))).click()
            waitFor("First streamed token timed out") {
                // AlertDialog has a separate accessibility root from the Scaffold's test-tag setting.
                device.findObject(By.text("允许本次"))?.click()
                device.hasObject(By.textStartsWith("M0:$marker"))
            }
            val firstTokenAt=SystemClock.elapsedRealtime()
            assertTrue("Memory must be sampled during output",device.hasObject(By.text("✦ 思考中")))
            val streaming=memory(iteration,"streaming")
            assertTrue("All streaming memory dumps must precede stream completion",device.hasObject(By.text("✦ 思考中")))
            waitFor("Streaming did not finish") {!device.hasObject(By.text("✦ 思考中"))}
            val idle=memory(iteration,"idle")
            readyTimes+=readyAt-clickedAt;tokenTimes+=firstTokenAt-clickedAt
            samples.put(JSONObject().put("iteration",iteration).put("iconToReadyMs",readyAt-clickedAt).put("iconToFirstTokenMs",firstTokenAt-clickedAt).put("streaming",streaming).put("idle",idle))
        }
        acceptance.put("startup",JSONObject().put("mode","process-cold, runtime already installed").put("samples",samples).put("iterations",5).put("iconToReadyMedianMs",readyTimes.sorted()[2]).put("iconToFirstTokenMedianMs",tokenTimes.sorted()[2]).put("includesApprovalAndInput",true))
        acceptance.put("measurementAbi",android.os.Build.SUPPORTED_ABIS[0]).put("measurementApi",android.os.Build.VERSION.SDK_INT).put("measurementIsEmulator",shell("getprop ro.kernel.qemu").trim()=="1").put("modelFixture","claude / mock-model; loopback mock")
        File(output,"m0-metrics.json").writeText(acceptance.toString(2))
    }
}
