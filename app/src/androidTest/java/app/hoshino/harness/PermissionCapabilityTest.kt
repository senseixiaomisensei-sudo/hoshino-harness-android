package app.hoshino.harness

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import app.hoshino.harness.core.*
import app.hoshino.harness.ui.chat.ChatScreen
import app.hoshino.harness.ui.chat.ChatViewModel
import app.hoshino.harness.ui.permission.TierSheet
import app.hoshino.harness.ui.theme.HoshinoTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

class PermissionCapabilityTest {
    @get:Rule val ui=createEmptyComposeRule()
    @Test fun unsupportedFullAccessIsDisabledAndRejectedSubmissionsKeepDraft() {
        val actual=Hoshino.client
        Hoshino.store // Bind the unchanged reducer to the real client before injecting a UI failure fixture.
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { HoshinoTheme {
                TierSheet(PermissionTier.WorkspaceWrite,ShizukuStatus.Ready,{}, {},setOf(PermissionTier.ReadOnly,PermissionTier.WorkspaceWrite))
            } } }
            ui.onNodeWithText("完全访问").assertIsNotEnabled()
            ui.onNodeWithText("即将支持").assertIsDisplayed()
            val attempts=AtomicInteger()
            val fixture=object: UnavailableHarnessClient() {
                override val providers=MutableStateFlow(listOf(Provider("fixture","Fixture","custom",ProviderProtocol.OpenAICompatible,"http://127.0.0.1:8099/v1","",emptyList(),true,listOf(ModelInfo("mock-model","Fixture model",128000,4096,false,false,ReasoningEffort.Off)))))
                override suspend fun refreshShizuku() {}
                override suspend fun createSession(workspaceId:String,presetId:String,tier:PermissionTier):SessionInfo {
                    attempts.incrementAndGet();throw IllegalStateException("fixture session rejected")
                }
                override fun describeFailure(failure:Throwable)=failure.message.orEmpty()
            }
            val messages=ConcurrentLinkedQueue<String>()
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
            try {
                Hoshino.client=fixture
                val failing=ChatViewModel()
                failing.setTier(PermissionTier.FullAccess)
                assertEquals(PermissionTier.WorkspaceWrite,failing.composer.value.tier)
                scope.launch(start=CoroutineStart.UNDISPATCHED){failing.messages.collect{messages.add(it)}}
                scenario.onActivity { activity -> activity.setContent { HoshinoTheme { ChatScreen(failing,false,{},{}) } } }
                ui.waitUntil(10000){failing.composer.value.model?.providerId=="fixture"}
                val fields=ui.onAllNodes(hasSetTextAction())
                val visible=(0 until fields.fetchSemanticsNodes().size).first{runCatching{fields[it].assertIsDisplayed()}.isSuccess}
                fields[visible].performTextReplacement("keep-this-draft")
                ui.onNodeWithContentDescription("发送").performClick()
                ui.waitUntil(10000){attempts.get()>0 && messages.contains("fixture session rejected")}
                ui.onNode(hasSetTextAction() and hasText("keep-this-draft")).assertExists()
            } finally {Hoshino.client=actual;scope.cancel()}
        }
    }
}
