package dev.hoshino.app

import android.content.Intent
import android.os.Debug
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.hoshino.bridge.Provider
import dev.hoshino.bridge.ProviderStore
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class M0AcceptanceTest {
    @get:Rule val ui = createEmptyComposeRule()
    @Test fun coldStartBothProtocolsToolsFilesBackgroundSwitchAndSecrets() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val canary="sk-ci-canary-"+java.util.UUID.randomUUID()
        val store=ProviderStore(context)
        store.save(Provider("claude","anthropic","http://127.0.0.1:8099",listOf("mock-model")),canary)
        store.save(Provider("compatible","openai-compatible","http://127.0.0.1:8099/v1",listOf("mock-model")),canary)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            ui.waitUntil(300000) { KernelRuntime.state.value.status!="启动中" }
            assertEquals(KernelRuntime.state.value.diagnostics.joinToString(),"已连接",KernelRuntime.state.value.status)
            ui.onNodeWithTag("kernel-status").assertTextContains("已连接",substring=true)
            fun prompt(text: String) {
                val before=KernelRuntime.state.value.completedTurns
                ui.onNodeWithTag("message-input").performTextInput(text);ui.onNodeWithTag("send").performClick()
                val deadline=System.currentTimeMillis()+60000
                while(System.currentTimeMillis()<deadline && KernelRuntime.state.value.completedTurns==before) {
                    if(KernelRuntime.approval.value!=null) ui.onNodeWithTag("approve").performClick()
                    if(KernelRuntime.state.value.status=="崩溃") break
                    Thread.sleep(100)
                }
                val current=KernelRuntime.state.value
                assertTrue("Turn did not complete: ${current.status}; ${current.error}; roles=${current.messages.map { it.role }}; diagnostics=${current.diagnostics.joinToString()}",current.completedTurns>before)
                assertEquals(current.diagnostics.joinToString(),"",current.error)
            }
            for(provider in listOf("claude","compatible")) {
                if(provider!="claude") {
                    ui.onNodeWithTag("model-picker").performClick();ui.onNodeWithText("$provider / mock-model").performClick()
                    ui.waitUntil(10000) { KernelRuntime.state.value.provider==provider }
                }
                val textBefore=KernelRuntime.textDeltas;val thinkingBefore=KernelRuntime.reasoningDeltas
                prompt("你好")
                val answer=KernelRuntime.state.value.messages.last { it.role=="assistant" }
                assertTrue(answer.text.contains("你好"));assertTrue(answer.thinking.isNotEmpty())
                assertTrue("$provider must stream text deltas",KernelRuntime.textDeltas>textBefore)
                assertTrue("$provider must stream thinking deltas",KernelRuntime.reasoningDeltas>thinkingBefore)
                ui.onAllNodesWithTag("thinking").onLast().performClick()
                ui.onAllNodesWithTag("thinking-content").onLast().assertExists()
                prompt("读取设备 device 信息")
                val card=KernelRuntime.state.value.messages.last { it.tool=="get_device_info" }
                assertTrue(card.result.contains(android.os.Build.MODEL))
                assertTrue(KernelRuntime.state.value.messages.last { it.role=="assistant" }.text.contains(android.os.Build.MODEL))
                ui.onAllNodesWithTag("tool-get_device_info").onLast().assertExists()
                prompt("写入 hello.txt")
                assertEquals("Hello from dsh\n",File(context.filesDir,"workspace/hello.txt").readText())
            }
            val session=KernelRuntime.sessionId
            UiDevice.getInstance(instrumentation).pressHome()
            Thread.sleep(300000)
            assertEquals("已连接",KernelRuntime.state.value.status);assertEquals(session,KernelRuntime.sessionId)
            context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            prompt("你好，继续刚才的会话")
            assertEquals(canary,store.resolve("KEY_CLAUDE"))
            val secret=canary.toByteArray()
            fun contains(file: File): Boolean {
                file.inputStream().buffered().use { input ->
                    val buffer=ByteArray(65536);var tail=byteArrayOf()
                    // Latin-1 maps each byte one-to-one; String.indexOf avoids a Kotlin iterator per byte.
                    while(true) { val n=input.read(buffer);if(n<0)return false;val chunk=tail+buffer.copyOf(n);if(String(chunk,Charsets.ISO_8859_1).contains(canary))return true;tail=chunk.takeLast(secret.size).toByteArray() }
                }
            }
            File(context.applicationInfo.dataDir).walkTopDown().filter { it.isFile }.forEach { assertFalse("Plaintext credential in app storage: ${it.name}",contains(it)) }
            val logcat=instrumentation.uiAutomation.executeShellCommand("logcat -d --pid=${android.os.Process.myPid()}")
            android.os.ParcelFileDescriptor.AutoCloseInputStream(logcat).use { assertFalse(it.readBytes().toString(Charsets.UTF_8).contains(canary)) }
            val memory=Debug.MemoryInfo();Debug.getMemoryInfo(memory)
            val metrics=JSONObject().put("coldStartMs",KernelRuntime.connectedAt-KernelRuntime.startedAt).put("appPssKb",memory.totalPss).put("sessionPreserved",true).put("backgroundSeconds",300).put("standardsPassed",8)
            fun shell(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes().toString(Charsets.UTF_8) }
            // Capture before instrumentation exits; its app and guest processes can then be stopped by Android.
            val processRows=shell("ps -A -o PID,PPID,NAME").lineSequence().drop(1).map { it.trim().split(Regex("\\s+")) }.filter { it.size>=3 && it[0].toIntOrNull()!=null && it[1].toIntOrNull()!=null }.toList()
            val owned=mutableSetOf(android.os.Process.myPid())
            do { val count=owned.size;processRows.filter { it[1].toInt() in owned }.forEach { owned+=it[0].toInt() } } while(owned.size>count)
            val processMemory=owned.sorted().map { pid -> pid to shell("dumpsys meminfo $pid") }
            val processPss=JSONObject()
            processMemory.forEach { (pid, report) -> Regex("TOTAL PSS:\\s*(\\d+)").find(report)?.groupValues?.get(1)?.toLongOrNull()?.let { processPss.put(pid.toString(),it) } }
            metrics.put("processPssKb",processPss)
            File(context.getExternalFilesDir(null),"m0-memory.txt").writeText(processMemory.joinToString("\n") { (pid, report) -> "PID $pid\n$report" })
            File(context.getExternalFilesDir(null),"m0-metrics.json").writeText(metrics.toString(2))
        }
    }
}
