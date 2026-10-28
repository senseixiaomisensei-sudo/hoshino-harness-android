package app.hoshino.harness

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.hoshino.harness.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

/** Runs against the old 200 APK before overwrite. Uses only its existing public shell API. */
class M21UpgradeSeedTest {
 @Test fun seedExistingM2Data(){
  val context=InstrumentationRegistry.getInstrumentation().targetContext;val client=Hoshino.client as DshHarnessClient
  val events=ConcurrentLinkedQueue<HarnessEvent>();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);scope.launch(start=CoroutineStart.UNDISPATCHED){client.events.collect{events.add(it)}}
  ActivityScenario.launch(MainActivity::class.java).use{
   runBlocking{withTimeout(300000){while(client.runtime.state.value.phase!="已连接")delay(50)}
    client.upsertProvider(Provider("m21-mock","M21 preserved mock","custom",ProviderProtocol.OpenAICompatible,"http://127.0.0.1:8099/v1/chat/completions","m21-fixture-key",emptyList(),true,listOf(ModelInfo("mock-model","M21 Mock",128000,4096,true,true,ReasoningEffort.Off))))
    client.m2Control("config",JSONObject().put("name","approval.json").put("value",JSONObject().put("mode","auto").put("highRiskAsk",false)))
    val ids=JSONArray()
    for(index in 1..3){val session=client.createSession("ws-default","standard",PermissionTier.WorkspaceWrite);client.activateSession(session.id);client.send(session.id,"你好 m21-history-$index",emptyList(),TurnOptions("standard",PermissionTier.WorkspaceWrite,ModelRef("m21-mock","mock-model"),ReasoningEffort.Off,false));withTimeout(60000){while(events.none{it is HarnessEvent.TurnFinished&&it.sessionId==session.id})delay(20)};ids.put(session.id)}
    File(context.filesDir,"m21-upgrade-seed.json").writeText(JSONObject().put("sessions",ids).put("provider","m21-mock").put("keyRef","KEY_M21_MOCK").put("versionCode",context.packageManager.getPackageInfo(context.packageName,0).longVersionCode).toString())
    assertEquals(200L,context.packageManager.getPackageInfo(context.packageName,0).longVersionCode)
   }
  };scope.cancel()
 }
}
