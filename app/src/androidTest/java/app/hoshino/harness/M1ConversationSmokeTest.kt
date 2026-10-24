package app.hoshino.harness

import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.hoshino.harness.core.*
import app.hoshino.harness.ui.chat.ChatScreen
import app.hoshino.harness.ui.chat.ChatViewModel
import app.hoshino.harness.ui.theme.HoshinoTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

/** Real ZIP composer -> native adapter -> APK dsh -> both vendor protocols -> native event reducer. */
class M1ConversationSmokeTest {
    @get:Rule val ui=createEmptyComposeRule()
    @Test fun nativeComposerBothProtocolsThinkingDeviceWriteAndProviderSwitch() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val canary="sk-m1-instrument-"+java.util.UUID.randomUUID()
        val client=Hoshino.client as DshHarnessClient
        val events=ConcurrentLinkedQueue<HarnessEvent>()
        val collector=CoroutineScope(SupervisorJob()+Dispatchers.Default).launch(start=CoroutineStart.UNDISPATCHED){client.events.collect{events.add(it)}}
        ActivityScenario.launch(MainActivity::class.java).use{scenario->
            ui.waitUntil(300000){client.runtime.state.value.phase in setOf("已连接","崩溃")}
            assertEquals(client.runtime.state.value.logs.joinToString(),"已连接",client.runtime.state.value.phase)
            runBlocking {
                client.upsertProvider(Provider("claude","Mock Claude","custom",ProviderProtocol.Anthropic,"http://127.0.0.1:8099/v1/messages","••••••••Bearer $canary",emptyList(),true,listOf(ModelInfo("mock-model","M1 Claude",128000,4096,true,false,ReasoningEffort.Off))))
                client.upsertProvider(Provider("compatible","Mock Compatible","custom",ProviderProtocol.OpenAICompatible,"http://127.0.0.1:8099/v1/chat/completions",canary,emptyList(),true,listOf(ModelInfo("mock-model","M1 Compatible",128000,4096,true,false,ReasoningEffort.Off))))
            }
            val vm=ChatViewModel()
            vm.setModel(ModelRef("claude","mock-model"))
            scenario.onActivity{activity->activity.setContent{HoshinoTheme{ChatScreen(vm,false,{},{})}}}
            ui.waitUntil(10000){vm.composer.value.model?.providerId=="claude"}
            fun turn(text:String,expectFailure:Boolean=false):List<HarnessEvent> {
                val before=events.size
                val fields=ui.onAllNodes(hasSetTextAction())
                val visible=(0 until fields.fetchSemanticsNodes().size).firstOrNull{index->runCatching{fields[index].assertIsDisplayed()}.isSuccess}
                assertNotNull("No visible composer",visible)
                fields[visible!!].performTextReplacement(text)
                ui.onNode(hasContentDescription("发送",substring=true)).assertIsEnabled().performClick()
                ui.waitUntil(10000){vm.currentId.value!=null}
                val sid=vm.currentId.value!!;val deadline=System.currentTimeMillis()+90000
                fun complete():Boolean {
                    val current=events.toList().drop(before).filter{it.sessionId==sid}
                    return current.any{it is HarnessEvent.TurnFinished} && (!expectFailure || current.any{it is HarnessEvent.Notice && it.id.startsWith("error:")})
                }
                while(System.currentTimeMillis()<deadline && !complete()) {
                    runCatching{val choices=ui.onAllNodesWithText("允许一次");if(choices.fetchSemanticsNodes().isNotEmpty())choices.onLast().performClick()}
                    Thread.sleep(70)
                }
                val received=events.toList().drop(before).filter{it.sessionId==sid}
                assertTrue("Native UI turn did not finish: $text; ${client.runtime.state.value.phase}",received.any{it is HarnessEvent.TurnFinished})
                if(expectFailure) {
                    val failure=received.filterIsInstance<HarnessEvent.Notice>().firstOrNull{it.id.startsWith("error:")}
                    assertNotNull("Missing dsh failure details",failure)
                    assertTrue(failure!!.text,failure.text.contains("400") && failure.text.contains("fixture request rejected"))
                    assertFalse(failure.text.contains(canary))
                    return received
                }
                assertFalse("Native model request failed",received.filterIsInstance<HarnessEvent.Notice>().any{it.id.startsWith("error:")})
                assertTrue("Text was not streamed",received.any{it is HarnessEvent.AssistantDelta && it.delta.isNotEmpty()})
                assertTrue("Thinking was not streamed",received.any{it is HarnessEvent.ThinkingDelta && it.delta.isNotEmpty()})
                return received
            }
            for(provider in listOf("claude","compatible")) {
                if(provider=="compatible") {
                    ui.onNodeWithText("M1 Claude",substring=true).performScrollTo().performClick()
                    ui.waitUntil(10000){ui.onAllNodesWithText("M1 Compatible").fetchSemanticsNodes().isNotEmpty()}
                    ui.onNodeWithText("M1 Compatible").performClick()
                    ui.waitUntil(10000){vm.composer.value.model?.providerId=="compatible"}
                    androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
                }
                turn("你好")
                val device=turn("读取设备 device 信息")
                assertTrue(device.filterIsInstance<HarnessEvent.ToolCallStarted>().any{it.name=="get_device_info"})
                assertTrue(device.filterIsInstance<HarnessEvent.ToolCallOutput>().any{it.delta.contains(Build.MODEL)})
                assertTrue(device.filterIsInstance<HarnessEvent.AssistantDelta>().joinToString(""){it.delta}.contains(Build.MODEL))
                val write=turn("写入 hello.txt")
                assertTrue(write.filterIsInstance<HarnessEvent.ToolCallStarted>().any{it.name=="write"})
                assertTrue(write.any{it is HarnessEvent.FileDiff})
                assertEquals("Hello from dsh\n",client.files.resolve("hello.txt").readText())
            }
            turn("m1-error-fixture",true)
            fun hasCanary(file:File):Boolean {
                if(!file.isFile)return false
                file.inputStream().use{input->val buffer=ByteArray(65536);var tail="";while(true){val n=input.read(buffer);if(n<0)break;val text=tail+String(buffer,0,n,Charsets.ISO_8859_1);if(text.contains(canary))return true;tail=text.takeLast(canary.length)}}
                return false
            }
            val directories=listOf(context.filesDir,File(context.applicationInfo.dataDir,"shared_prefs"))
            for(directory in directories)directory.walkTopDown().filter{it.isFile && !java.nio.file.Files.isSymbolicLink(it.toPath())}.forEach{assertFalse("Credential appeared in app storage",hasCanary(it))}
        }
        collector.cancel()
    }
}
