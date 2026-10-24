package app.hoshino.harness

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.hoshino.harness.core.*
import app.hoshino.harness.ui.chat.*
import app.hoshino.harness.ui.theme.HoshinoTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

class ChatExperienceTest {
    @get:Rule val ui=createEmptyComposeRule()
    @Test fun maxIsSentWithBudgetContextUsageAndFullFoldableThoughts() {
        val client=Hoshino.client as DshHarnessClient
        val events=ConcurrentLinkedQueue<HarnessEvent>();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        scope.launch(start=CoroutineStart.UNDISPATCHED){client.events.collect{events.add(it)}}
        try {ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            ui.waitUntil(300000){client.runtime.state.value.phase in setOf("已连接","崩溃")}
            assertEquals(client.runtime.state.value.logs.joinToString(),"已连接",client.runtime.state.value.phase)
            val model="deepseek-v4.1-flash-1017"
            runBlocking{client.upsertProvider(Provider("native-max-fixture","Max fixture","custom",ProviderProtocol.OpenAICompatible,"http://127.0.0.1:8099/v1","sk-max-fixture-"+UUID.randomUUID(),emptyList(),true,listOf(ModelInfo(model,"Max fixture",131072,4096,false,false,ReasoningEffort.Off))))}
            val vm=ChatViewModel();vm.setModel(ModelRef("native-max-fixture",model))
            scenario.onActivity{it.setContent{HoshinoTheme{ChatScreen(vm,false,{},{})}}}
            ui.onNodeWithText("Max fixture").performScrollTo().performClick()
            try {ui.waitUntil(10000){ui.onAllNodesWithText("max",substring=false).fetchSemanticsNodes().isNotEmpty()}}
            catch(failure:Throwable){throw AssertionError("Model picker did not open: "+ui.onRoot().printToString(maxDepth=10),failure)}
            ui.onNodeWithText("max",substring=false).assertIsEnabled().performClick()
            assertEquals(ReasoningEffort.Max,vm.composer.value.effort)
            ui.onNodeWithText("当前模型不支持推理").assertDoesNotExist()
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
            val fields=ui.onAllNodes(hasSetTextAction())
            fields[(0 until fields.fetchSemanticsNodes().size).first{runCatching{fields[it].assertIsDisplayed()}.isSuccess}].performTextReplacement("你好")
            ui.onNodeWithContentDescription("发送").performClick()
            ui.waitUntil(10000){vm.currentId.value!=null};val sid=vm.currentId.value!!
            val deadline=System.currentTimeMillis()+90000
            while(System.currentTimeMillis()<deadline && events.none{it is HarnessEvent.TurnFinished && it.sessionId==sid}) {
                runCatching{val choices=ui.onAllNodesWithText("允许一次");if(choices.fetchSemanticsNodes().isNotEmpty())choices.onLast().performClick()};Thread.sleep(60)
            }
            assertTrue(events.any{it is HarnessEvent.TurnFinished && it.sessionId==sid})
            assertFalse(events.filterIsInstance<HarnessEvent.Notice>().any{it.sessionId==sid && it.id.startsWith("error:")})
            assertTrue(events.any{it is HarnessEvent.ThinkingDelta && it.sessionId==sid})
            ui.waitUntil(15000){events.any{it is HarnessEvent.ContextUsageChanged && it.sessionId==sid && it.usage.usedTokens>0 && it.usage.breakdown.isNotEmpty()}}
            val usage=events.filterIsInstance<HarnessEvent.ContextUsageChanged>().last{it.sessionId==sid}.usage
            assertEquals(131072,usage.limitTokens)
            assertTrue(events.any{it is HarnessEvent.UsageReported && it.sessionId==sid && it.usage.inputTokens>0})
            val health=runBlocking(Dispatchers.IO){JSONObject(java.net.URL("http://127.0.0.1:8099/health").readText())}.getJSONArray("requestOptions")
            val request=(0 until health.length()).map{health.getJSONObject(it)}.last{it.optString("model")==model && it.optString("effort")=="max"}
            assertEquals("enabled",request.getString("thinkingType"));assertEquals("max_tokens",request.getString("maxTokensField"));assertTrue(request.getInt("maxTokens")>=32768)
            scenario.onActivity{it.setContent{HoshinoTheme{ContextUsageSheet(usage,{}, {})}}}
            ui.onNodeWithText("最近实际输入").assertExists()

            val thoughts="完整思考过程\n".repeat(800)+"THOUGHT-END"
            scenario.onActivity{it.setContent{HoshinoTheme{Column(Modifier.verticalScroll(rememberScrollState())) {
                ChatItemView(ChatItem.Thinking("thinking-fixture",thoughts,false,System.currentTimeMillis(),30000),MessageActions())
                ChatItemView(ChatItem.Terminal("terminal-fixture","echo fixture","TERMINAL-RESULT",ToolStatus.Success,0,false),MessageActions())
            }}}}
            ui.onNode(hasText("THOUGHT-END",substring=true)).assertDoesNotExist()
            ui.onNodeWithText("TERMINAL-RESULT").assertDoesNotExist()
            ui.onNodeWithContentDescription("展开终端输出").performScrollTo().performClick()
            ui.onNodeWithText("TERMINAL-RESULT").assertExists()
            ui.onNodeWithContentDescription("收起终端输出").performClick()
            ui.onNodeWithText("TERMINAL-RESULT").assertDoesNotExist()
            ui.onNodeWithText("思考过程 · 30s").performScrollTo().performClick()
            ui.onNode(hasText("THOUGHT-END",substring=true)).assertExists()
        }} finally{scope.cancel()}
    }
}
