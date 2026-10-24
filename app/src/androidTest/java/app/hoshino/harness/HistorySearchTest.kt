package app.hoshino.harness

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.hoshino.harness.core.*
import app.hoshino.harness.ui.drawer.Sidebar
import app.hoshino.harness.ui.theme.HoshinoTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

class HistorySearchTest {
    @get:Rule val ui=createEmptyComposeRule()
    @Test fun coldOriginalLogSearchFindsArchivedMessageInRecoveredWorkspace() {
        val actual=Hoshino.client as DshHarnessClient
        val target=AtomicReference<String>();val events=ConcurrentLinkedQueue<HarnessEvent>()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        scope.launch(start=CoroutineStart.UNDISPATCHED){actual.events.collect{event->events.add(event);if(event is HarnessEvent.ApprovalRequested && event.sessionId==target.get())actual.respondApproval(event.sessionId,event.id,ApprovalDecision.AllowOnce)}}
        try {ActivityScenario.launch(MainActivity::class.java).use{scenario->
            ui.waitUntil(300000){actual.runtime.state.value.phase in setOf("已连接","崩溃")}
            assertEquals("已连接",actual.runtime.state.value.phase)
            val suffix=UUID.randomUUID().toString().take(8);val title="history-title-$suffix";val marker="message-needle-$suffix"
            val sid=runBlocking {
                actual.upsertProvider(Provider("history-fixture","History fixture","custom",ProviderProtocol.OpenAICompatible,"http://127.0.0.1:8099/v1","sk-history-fixture-"+UUID.randomUUID(),emptyList(),true,listOf(ModelInfo("mock-model","History fixture",128000,4096,false,false,ReasoningEffort.Off))))
                val workspace=actual.addWorkspace("history-$suffix","history-$suffix")
                val session=actual.createSession(workspace.id,"standard",PermissionTier.WorkspaceWrite)
                target.set(session.id);actual.renameSession(session.id,title)
                actual.send(session.id,marker,emptyList(),TurnOptions("standard",PermissionTier.WorkspaceWrite,ModelRef("history-fixture","mock-model"),ReasoningEffort.Off,false))
                session.id
            }
            ui.waitUntil(90000){events.any{it is HarnessEvent.TurnFinished && it.sessionId==sid}}
            assertFalse(events.filterIsInstance<HarnessEvent.Notice>().any{it.sessionId==sid && it.id.startsWith("error:")})
            runBlocking{actual.refreshSessions();actual.refreshSessions();delay(300)}
            assertEquals("Refreshing history must not duplicate a Mux follow stream","已连接",actual.runtime.state.value.phase)
            runBlocking{delay(500);actual.setArchived(sid,true);delay(500);actual.stopKernel();actual.runtime.stopAndWait()}
            val probe=DshHarnessClient(InstrumentationRegistry.getInstrumentation().targetContext)
            try {
                probe.startKernel()
                ui.waitUntil(300000){probe.runtime.state.value.phase in setOf("已连接","崩溃")}
                assertEquals("已连接",probe.runtime.state.value.phase)
                ui.waitUntil(20000){probe.sessions.value.any{it.id==sid && it.title==title}}
                val cold=probe.sessions.value.first{it.id==sid}
                assertTrue(cold.archived)
                assertTrue("Recovered history was hidden by a missing workspace",probe.workspaces.value.any{it.id==cold.workspaceId})
                assertTrue(runBlocking{probe.searchSessions(marker)}.items.any{it.sessionId==sid && it.snippet.contains(marker)})
                val searched=AtomicBoolean();var opened:String?=null
                scenario.onActivity{activity->activity.setContent{HoshinoTheme{
                    Sidebar(probe.sessions.value,probe.workspaces.value,null,false,{}, {opened=it}, {}, {_,_->}, {_,_->}, {},onSearchSessions={query->probe.searchSessions(query).also{searched.set(true)}})
                }}}
                ui.onNode(hasSetTextAction()).performScrollTo().performTextReplacement(marker)
                ui.waitUntil(10000){searched.get()}
                ui.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(title))
                ui.onNodeWithText(title).assertIsDisplayed().performClick()
                assertEquals(sid,opened)
            } finally {
                runBlocking{probe.stopKernel();probe.runtime.stopAndWait()};actual.startKernel()
                ui.waitUntil(300000){actual.runtime.state.value.phase in setOf("已连接","崩溃")}
            }
        }} finally{scope.cancel()}
    }
}
