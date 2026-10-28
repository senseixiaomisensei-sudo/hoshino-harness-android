package app.hoshino.harness

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.hoshino.harness.core.*
import app.hoshino.harness.ui.chat.ChatScreen
import app.hoshino.harness.ui.chat.ChatViewModel
import app.hoshino.harness.ui.components.relativeTime
import app.hoshino.harness.ui.theme.HoshinoTheme
import dev.hoshino.bridge.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.net.Proxy
import java.net.URL
import java.util.concurrent.ConcurrentLinkedQueue

/** Exactly the requested acceptance cases 2–7. Case 1 is the signed dual-ABI CI build. */
class M21AcceptanceTest {
 @get:Rule val ui=createEmptyComposeRule()
 private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
 private val client get()=Hoshino.client as DshHarnessClient
 private fun seed()=JSONObject(File(context.filesDir,"m21-upgrade-seed.json").readText())
 private fun metrics(path:String="/m21/metrics"):JSONObject {val c=URL("http://127.0.0.1:8099$path").openConnection(Proxy.NO_PROXY) as java.net.HttpURLConnection;c.connectTimeout=2000;c.readTimeout=2000;return try{JSONObject(c.inputStream.bufferedReader().use{it.readText()})}finally{c.disconnect()}}
 private fun record(index:Int,value:JSONObject){val file=File(context.filesDir,"m21-acceptance.json");val data=runCatching{JSONObject(file.readText())}.getOrDefault(JSONObject());data.put(index.toString(),value.put("passed",true));file.writeText(data.toString(2))}
 private fun ready(){ui.waitUntil(300000){client.runtime.state.value.phase in setOf("已连接","崩溃")};assertEquals(client.runtime.state.value.logs.joinToString(),"已连接",client.runtime.state.value.phase)}
 private fun composer():SemanticsNodeInteraction{val fields=ui.onAllNodes(hasSetTextAction());val index=(0 until fields.fetchSemanticsNodes().size).first{runCatching{fields[it].assertIsDisplayed()}.isSuccess};return fields[index]}
 private fun chat(scenario:ActivityScenario<MainActivity>):ChatViewModel {val vm=ChatViewModel().also{it.newSession();it.setModel(ModelRef("m21-mock","mock-model"))};scenario.onActivity{it.setContent{HoshinoTheme{ChatScreen(vm,false,{},{})}}};ui.waitUntil(10000){vm.composer.value.model?.providerId=="m21-mock"};return vm}
 @Test fun case2OverwritePreservesConfigurationAndSessions(){
  assertEquals(201L,context.packageManager.getPackageInfo(context.packageName,0).longVersionCode)
  assertEquals(200L,seed().getLong("versionCode"));assertEquals("m21-fixture-key",ProviderStore(context).resolve(seed().getString("keyRef")))
  assertTrue(client.providers.value.any{it.id=="m21-mock"&&it.models.any{m->m.id=="mock-model"}})
  val ids=seed().getJSONArray("sessions");for(i in 0 until ids.length())assertTrue("Session absent before kernel start",client.sessions.value.any{it.id==ids.getString(i)})
  ActivityScenario.launch(MainActivity::class.java).use{ready();for(i in 0 until ids.length())assertTrue(client.sessions.value.any{it.id==ids.getString(i)})}
  record(2,JSONObject().put("preservedSessions",ids.length()).put("fromVersionCode",200).put("toVersionCode",201).put("providerAndKeystorePreserved",true))
 }
 @Test fun case3ResidentKernelReconnectsWithinOneSecond(){
  ActivityScenario.launch(MainActivity::class.java).use{ready()}
  val process=JSONObject(File(context.filesDir,"hoshino/runtime-process.json").readText()).getLong("pid")
  val start=android.os.SystemClock.elapsedRealtime()
  ActivityScenario.launch(MainActivity::class.java).use{assertEquals("已连接",client.runtime.state.value.phase);val elapsed=android.os.SystemClock.elapsedRealtime()-start;assertTrue("Warm reopen took $elapsed ms",elapsed<=1000);assertEquals(process,JSONObject(File(context.filesDir,"hoshino/runtime-process.json").readText()).getLong("pid"));record(3,JSONObject().put("warmReopenMs",elapsed).put("sameKernelPid",true))}
 }
 @Test fun case4ColdFrameAndDurableWaitingMessage(){
  val prefs=context.getSharedPreferences("hoshino-startup",android.content.Context.MODE_PRIVATE);prefs.edit().putLong("test-start-delay-ms",8000).commit()
  assertTrue("Cached sessions unavailable at process startup",client.sessions.value.size>=3)
  ActivityScenario.launch(MainActivity::class.java).use{scenario->
   val vm=chat(scenario);assertNotEquals("已连接",client.runtime.state.value.phase);composer().assertIsEnabled().performTextReplacement("你好 m21-waiting-for-kernel");ui.onNode(hasContentDescription("发送",substring=true)).assertIsEnabled().performClick()
   ui.waitUntil(3000){vm.currentId.value?.let{id->Hoshino.store.state(id).value.queued.any{it.id.startsWith("waiting:")}}==true};ui.onNodeWithText("等待内核").assertExists()
   val queuedFile=File(context.filesDir,"waiting-for-kernel.json");assertTrue("Queue was not durable",queuedFile.readText().contains("m21-waiting-for-kernel"))
   val frame=JSONObject(File(context.filesDir,"m2-input-ready.json").readText());assertTrue(frame.getInt("sessionsAtFirstDraw")>=3);assertNotEquals("已连接",frame.getString("kernelAtFirstDraw"))
   ready();ui.waitUntil(90000){vm.currentId.value?.let{id->Hoshino.store.state(id).value.items.any{it is ChatItem.Assistant&&it.markdown.isNotBlank()}}==true}
   assertTrue(Hoshino.store.state(vm.currentId.value!!).value.items.any{it is ChatItem.User&&it.text.contains("m21-waiting-for-kernel")});assertEquals(0,org.json.JSONArray(queuedFile.readText()).length())
   record(4,JSONObject().put("cachedSessionsAtFirstDraw",frame.getInt("sessionsAtFirstDraw")).put("activityFirstDrawMs",frame.getLong("activityToFirstDrawMs")).put("inputEnabledBeforeKernel",true).put("queuedMessageDelivered",true))
  };prefs.edit().remove("test-start-delay-ms").commit()
 }
 @Test fun case5ClickToFirstHttpWithin500ms(){
  ActivityScenario.launch(MainActivity::class.java).use{scenario->ready();val vm=chat(scenario)
   val before=System.currentTimeMillis();val server=metrics().getLong("now");val after=System.currentTimeMillis();val offset=server-(before+after)/2;val uncertainty=(after-before)/2+2
   metrics("/m21/reset");composer().performTextReplacement("你好 m21-latency");ui.onNode(hasContentDescription("发送",substring=true)).performClick()
   var sample=JSONObject();ui.waitUntil(5000){sample=metrics();sample.getLong("firstRequestAt")>0}
   val delta=sample.getLong("firstRequestAt")-offset-vm.lastSendClickEpoch;assertTrue("Click→HTTP $delta ms (clock uncertainty $uncertainty ms)",delta>=-uncertainty&&delta+uncertainty<=500)
   ui.waitUntil(60000){vm.currentId.value?.let{id->Hoshino.store.state(id).value.items.any{it is ChatItem.Assistant&&it.markdown.isNotEmpty()}}==true}
   assertEquals("An auxiliary model call preceded or accompanied the main request",1,metrics().getInt("mainRequests"))
   record(5,JSONObject().put("clickToFirstHttpMs",delta).put("clockUncertaintyMs",uncertainty).put("mainRequests",1))
  }
 }
 @Test fun case6OriginalHistoryTimesSurviveColdReconnect(){
  val expected=seed().getJSONObject("lastTimes");val before=client.sessions.value.associateBy{it.id}
  expected.keys().forEach{id->assertEquals("Cached original time changed",expected.getLong(id),before[id]?.updatedAt)}
  ActivityScenario.launch(MainActivity::class.java).use{ready();runBlocking{client.refreshSessions()};val rows=client.sessions.value.associateBy{it.id};expected.keys().forEach{id->assertEquals("Original time changed after kernel replay",expected.getLong(id),rows[id]?.updatedAt)}}
  val ids=seed().getJSONArray("sessions");val labels=(0 until ids.length()).map{relativeTime(client.sessions.value.first{r->r.id==ids.getString(it)}.updatedAt)}
  assertTrue(labels[0],labels[0].contains("小时"));assertTrue(labels[1],labels[1].contains("天"));assertTrue(labels[2],labels[2].contains("周"));assertEquals("未知",relativeTime(0))
  record(6,JSONObject().put("labels",org.json.JSONArray(labels)).put("originalTimestamps",expected).put("unknownHasNoNowFallback",true))
 }
 @Test fun case7MockChatGptOAuthStreamingRefreshAndQuota(){
  val auth=ChatGptAuth.installForTest(context,ChatGptOAuthEndpoints("http://127.0.0.1:8099",callbackPort=0));val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val events=ConcurrentLinkedQueue<HarnessEvent>();scope.launch(start=CoroutineStart.UNDISPATCHED){client.events.collect{events.add(it)}}
  ActivityScenario.launch(MainActivity::class.java).use{ready()
   val url=auth.beginLogin();val connection=URL(url).openConnection(Proxy.NO_PROXY) as java.net.HttpURLConnection;connection.connectTimeout=2000;connection.readTimeout=10000;connection.instanceFollowRedirects=true;connection.inputStream.use{it.readBytes()};connection.disconnect()
   ui.waitUntil(10000){auth.state.value.signedIn};val beforeToken=ProviderStore(context).resolve(ChatGptAuth.ACCESS)!!;runBlocking{auth.accessToken(true)};assertNotEquals(beforeToken,ProviderStore(context).resolve(ChatGptAuth.ACCESS));assertTrue(metrics().getInt("refreshes")>=1)
   val vault=ProviderStore(context);val catalog=org.json.JSONArray(context.assets.open("codex-models.json").bufferedReader().use{it.readText()});val names=(0 until catalog.length()).map{catalog.getJSONObject(it).getString("id")};vault.save(dev.hoshino.bridge.Provider("openai-codex","openai-codex","http://127.0.0.1:8099/backend-api",names,JSONObject()),"")
   runBlocking{client.setChatGptEnabled(true)};val model=client.providers.value.first{p->p.id=="openai-codex"}.models.first();val options=TurnOptions("standard",PermissionTier.WorkspaceWrite,ModelRef("openai-codex",model.id),ReasoningEffort.Medium,false)
   val session=runBlocking{client.createSession("ws-default","standard",PermissionTier.WorkspaceWrite)};runBlocking{client.send(session.id,"m21 subscription stream",emptyList(),options)}
   ui.waitUntil(60000){events.any{it is HarnessEvent.TurnFinished&&it.sessionId==session.id}}
   assertTrue(events.any{it is HarnessEvent.AssistantDelta&&it.sessionId==session.id&&it.delta.contains("subscription reply")});assertTrue(events.any{it is HarnessEvent.ThinkingDelta&&it.sessionId==session.id&&it.delta.contains("reasoning")});assertTrue(events.any{it is HarnessEvent.UsageReported&&it.sessionId==session.id&&it.usage.inputTokens==42})
   metrics("/m21/limit");val failed=runBlocking{client.createSession("ws-default","standard",PermissionTier.WorkspaceWrite)};runBlocking{client.send(failed.id,"m21 quota fixture",emptyList(),options)};ui.waitUntil(60000){events.any{it is HarnessEvent.TurnFinished&&it.sessionId==failed.id}}
   val error=events.filterIsInstance<HarnessEvent.Notice>().firstOrNull{it.sessionId==failed.id&&it.id.startsWith("error:")};assertNotNull(error);assertTrue(error!!.text,error.text.contains("额度"))
   val secret=vault.resolve(ChatGptAuth.ACCESS)!!;val refresh=vault.resolve(ChatGptAuth.REFRESH)!!
   for(file in File(context.filesDir,"dsh-home/sessions").walkTopDown().filter{it.isFile&&it.extension=="jsonl"}){val text=file.readText();assertFalse(text.contains(secret));assertFalse(text.contains(refresh))};assertFalse(client.runtime.state.value.logs.joinToString().contains(secret))
   record(7,JSONObject().put("oauthPkceCallback",true).put("refreshes",metrics().getInt("refreshes")).put("codexRequests",metrics().getInt("codexRequests")).put("streamedTextAndReasoning",true).put("reportedInputTokens",42).put("quotaErrorSanitized",true).put("credentialsExcludedFromSessionLogs",true))
  };scope.cancel();auth.logout()
 }
}
