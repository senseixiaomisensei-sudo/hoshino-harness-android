package app.hoshino.harness

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.hoshino.harness.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

class DshUpgradeAcceptanceTest {
 private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
 private val client get()=Hoshino.client as DshHarnessClient
 private fun ready()=runBlocking{withTimeout(300000){while(client.runtime.state.value.phase!="已连接"){check(client.runtime.state.value.phase!="崩溃"){client.runtime.state.value.logs.joinToString()};delay(40)}}}
 private fun record(key:String,value:JSONObject){val f=File(context.filesDir,"dsh-upgrade-acceptance.json");val result=runCatching{JSONObject(f.readText())}.getOrDefault(JSONObject());result.put(key,value.put("passed",true));f.writeText(result.toString(2))}
 @Test fun overwriteConnectsAndRetainsOldSessions(){
  ActivityScenario.launch(MainActivity::class.java).use{
   ready();assertEquals(208L,context.packageManager.getPackageInfo(context.packageName,0).longVersionCode)
   val actual=JSONObject(File(context.filesDir,"m2-runtime-health.json").readText()).getString("dshVersion");assertEquals("0.2.2-alpha.2",actual)
   val seed=JSONObject(File(context.filesDir,"m21-upgrade-seed.json").readText());val old=seed.getJSONArray("sessions")
   val live=runBlocking{client.api().rpc("session/list",JSONObject())}.getJSONArray("items");val ids=(0 until live.length()).map{i->live.getJSONObject(i).getString("sessionId")}
   for(i in 0 until old.length())assertTrue("Upstream failed to retain old session",old.getString(i) in ids)
   assertTrue(client.providers.value.any{it.id=="m21-mock"});assertEquals("m21-fixture-key",dev.hoshino.bridge.ProviderStore(context).resolve("KEY_M21_MOCK"))
   record("2",JSONObject().put("dshVersion",actual).put("oldSessionsRetained",old.length()).put("versionCode",208).put("originalConfigurationRetained",true))
  }
 }
 @Test fun realKernelChatsAndExecutesTools(){
  val received=ConcurrentLinkedQueue<HarnessEvent>();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);scope.launch(start=CoroutineStart.UNDISPATCHED){client.events.collect{received.add(it)}}
  ActivityScenario.launch(MainActivity::class.java).use{ready()
   runBlocking{
    val session=client.createSession("ws-default","standard",PermissionTier.WorkspaceWrite);client.activateSession(session.id)
    val options=TurnOptions("standard",PermissionTier.WorkspaceWrite,ModelRef("m21-mock","mock-model"),ReasoningEffort.Off,false)
    suspend fun turn(text:String):List<HarnessEvent>{val before=received.size;client.send(session.id,text,emptyList(),options);withTimeout(90000){while(received.toList().drop(before).none{it is HarnessEvent.TurnFinished&&it.sessionId==session.id})delay(20)};val events=received.toList().drop(before).filter{it.sessionId==session.id};assertFalse(events.filterIsInstance<HarnessEvent.Notice>().joinToString{it.text},events.filterIsInstance<HarnessEvent.Notice>().any{it.id.startsWith("error:")});return events}
    val chat=turn("你好");assertTrue(chat.any{it is HarnessEvent.AssistantDelta&&it.delta.isNotBlank()})
    val device=turn("读取设备 device 信息");assertTrue(device.any{it is HarnessEvent.ToolCallStarted&&it.name=="get_device_info"});assertTrue(device.any{it is HarnessEvent.ToolCallOutput&&it.delta.contains(android.os.Build.MODEL)})
    val marker="m1-write-fixture-upgrade-"+java.util.UUID.randomUUID().toString().replace("-","").take(8)
    val write=turn("写入 hello.txt $marker");assertTrue(write.any{it is HarnessEvent.ToolCallStarted&&it.name=="write"});assertEquals("Hello from dsh\n$marker\n",client.files.resolve("hello.txt").readText())
    record("3",JSONObject().put("chatSucceeded",true).put("completedTurns",3).put("tools",org.json.JSONArray(listOf("get_device_info","write"))).put("nativeDeviceCallback",true).put("actualFileWriteVerified",true))
   }
  };scope.cancel()
 }
}
