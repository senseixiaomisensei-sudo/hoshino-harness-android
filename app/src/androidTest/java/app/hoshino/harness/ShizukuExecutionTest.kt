package app.hoshino.harness

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.hoshino.harness.core.*
import app.hoshino.harness.ui.chat.ChatScreen
import app.hoshino.harness.ui.chat.ChatViewModel
import app.hoshino.harness.ui.theme.HoshinoTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/** Requires the official Shizuku fixture server, started as adb shell by the runner. */
class ShizukuExecutionTest {
    @get:Rule val ui=createEmptyComposeRule()
    @Test fun fullAccessChatsAndRunsWithRealUidAfterApproval() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val client=Hoshino.client as DshHarnessClient
        val events=ConcurrentLinkedQueue<HarnessEvent>()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        scope.launch(start=CoroutineStart.UNDISPATCHED){client.events.collect{events.add(it)}}
        try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            ui.waitUntil(300000){client.runtime.state.value.phase in setOf("已连接","崩溃")}
            assertEquals(client.runtime.state.value.logs.joinToString(),"已连接",client.runtime.state.value.phase)
            ui.waitUntil(20000){client.shizuku.value in setOf(ShizukuStatus.NotAuthorized,ShizukuStatus.Ready)}
            if(client.shizuku.value!=ShizukuStatus.Ready) {
                runBlocking{client.requestShizukuPermission()}
                val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                val allow=device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile("(?i)^(allow( all the time| only this time)?|always allow|始终允许|允许)$"))),10000)
                assertNotNull("Official Shizuku permission dialog was not shown",allow);allow.click()
            }
            ui.waitUntil(10000){client.shizuku.value==ShizukuStatus.Ready}
            runBlocking {client.upsertProvider(Provider("system-fixture","System fixture","custom",ProviderProtocol.OpenAICompatible,"http://127.0.0.1:8099/v1","sk-system-fixture-"+UUID.randomUUID(),emptyList(),true,listOf(ModelInfo("mock-model","System fixture",128000,4096,false,false,ReasoningEffort.Off))))}
            val vm=ChatViewModel();vm.setModel(ModelRef("system-fixture","mock-model"));vm.setTier(PermissionTier.FullAccess)
            assertEquals(PermissionTier.FullAccess,vm.composer.value.tier)
            scenario.onActivity { activity -> activity.setContent{HoshinoTheme{ChatScreen(vm,false,{},{})}} }
            fun outputs()=java.io.File(context.filesDir,"workspace").listFiles().orEmpty().count{it.name.startsWith("system-shell-")}
            fun turn(denied:Boolean):List<HarnessEvent> {
                val before=events.size;val oldOutputs=outputs()
                val fields=ui.onAllNodes(hasSetTextAction())
                val visible=(0 until fields.fetchSemanticsNodes().size).first{runCatching{fields[it].assertIsDisplayed()}.isSuccess}
                fields[visible].performTextReplacement("system command shizuku id")
                ui.onNodeWithContentDescription("发送").performClick()
                ui.waitUntil(10000){vm.currentId.value!=null}
                val sid=vm.currentId.value!!;val deadline=System.currentTimeMillis()+120000
                while(System.currentTimeMillis()<deadline && events.toList().drop(before).none{it is HarnessEvent.TurnFinished && it.sessionId==sid}) {
                    val received=events.toList().drop(before).filter{it.sessionId==sid}
                    val resolved=received.filterIsInstance<HarnessEvent.ApprovalResolved>().map{it.id}.toSet()
                    val pending=received.filterIsInstance<HarnessEvent.ApprovalRequested>().lastOrNull{it.id !in resolved}
                    if(pending!=null)runCatching{
                        val choice=if(denied && pending.title=="工具执行审批")"拒绝" else "允许一次"
                        ui.onAllNodesWithText(choice).onLast().performClick()
                    }
                    Thread.sleep(60)
                }
                val result=events.toList().drop(before).filter{it.sessionId==sid}
                assertTrue("Full-access native chat did not finish",result.any{it is HarnessEvent.TurnFinished})
                val failures=result.filterIsInstance<HarnessEvent.Notice>().filter{it.id.startsWith("error:")}
                assertTrue(failures.joinToString{it.text},failures.isEmpty())
                assertTrue(result.filterIsInstance<HarnessEvent.ApprovalRequested>().any{it.title=="工具执行审批"})
                if(denied)assertEquals("Rejected command reached native execution",oldOutputs,outputs())
                return result
            }
            turn(true)
            val accepted=turn(false)
            val text=accepted.filterIsInstance<HarnessEvent.ToolCallOutput>().joinToString(""){it.delta}
            assertTrue("Missing actual Shizuku uid: $text",text.contains("\"uid\":2000") || text.contains("\"uid\":0"))
            assertTrue(text.contains("\"exitCode\":0"))
            assertTrue(accepted.filterIsInstance<HarnessEvent.AssistantDelta>().joinToString(""){it.delta}.let{it.contains("2000") || it.contains("\"uid\":0")})
            val shell=dev.hoshino.bridge.ShizukuShell(context)
            try {runBlocking {
                val timed=withTimeout(15000){shell.execute(UUID.randomUUID().toString(),"test-timeout","sleep 10",150)}
                assertTrue(timed.toString(),timed.getBoolean("timedOut"))
                val id=UUID.randomUUID().toString();val previous=outputs()
                val running=async(Dispatchers.IO){shell.execute(id,"test-cancel","sleep 30",40000)}
                withTimeout(10000){while(outputs()==previous)delay(20)}
                delay(150);shell.cancel(id,"test-cancel")
                val cancelled=withTimeout(5000){running.await()};assertTrue(cancelled.toString(),cancelled.getBoolean("cancelled"))
            }} finally {shell.close()}
        }} finally {scope.cancel()}
    }
}
