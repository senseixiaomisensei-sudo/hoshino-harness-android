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
 /**
  * This test APK is compiled from the current sources but runs inside the old 0.2.0-m2 (vc200) app process, so it links
  * against the old classes. Since M2.7, ModelInfo has two trailing default parameters; a plain Kotlin call compiles to the
  * synthetic default-args constructor that the old APK does not have (NoSuchMethodError). Call the 7-parameter constructor
  * the old app actually declares; fall back to the source constructor when running against a build without it.
  */
 private fun legacyModelInfo(id:String,name:String,context:Int,maxOutput:Int,vision:Boolean,reasoning:Boolean,effort:ReasoningEffort):ModelInfo{
  val legacy=ModelInfo::class.java.constructors.firstOrNull{it.parameterCount==7&&!it.isSynthetic}
  return if(legacy!=null)legacy.newInstance(id,name,context,maxOutput,vision,reasoning,effort) as ModelInfo else ModelInfo(id,name,context,maxOutput,vision,reasoning,effort)
 }
 @Test fun seedExistingM2Data(){
  val context=InstrumentationRegistry.getInstrumentation().targetContext;val client=Hoshino.client as DshHarnessClient
  val events=ConcurrentLinkedQueue<HarnessEvent>();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);scope.launch(start=CoroutineStart.UNDISPATCHED){client.events.collect{events.add(it)}}
  ActivityScenario.launch(MainActivity::class.java).use{
   runBlocking{withTimeout(300000){while(client.runtime.state.value.phase!="已连接")delay(50)}
    client.upsertProvider(Provider("m21-mock","M21 preserved mock","custom",ProviderProtocol.OpenAICompatible,"http://127.0.0.1:8099/v1/chat/completions","m21-fixture-key",emptyList(),true,listOf(legacyModelInfo("mock-model","M21 Mock",128000,4096,true,true,ReasoningEffort.Off))))
    client.m2Control("config",JSONObject().put("name","approval.json").put("value",JSONObject().put("mode","auto").put("highRiskAsk",false)))
    val ids=JSONArray()
    for(index in 1..3){val session=client.createSession("ws-default","standard",PermissionTier.WorkspaceWrite);client.activateSession(session.id);client.send(session.id,"你好 m21-history-$index",emptyList(),TurnOptions("standard",PermissionTier.WorkspaceWrite,ModelRef("m21-mock","mock-model"),ReasoningEffort.Off,false));withTimeout(60000){while(events.none{it is HarnessEvent.TurnFinished&&it.sessionId==session.id})delay(20)};ids.put(session.id)}
    File(context.filesDir,"m21-upgrade-seed.json").writeText(JSONObject().put("sessions",ids).put("provider","m21-mock").put("keyRef","KEY_M21_MOCK").put("versionCode",context.packageManager.getPackageInfo(context.packageName,0).longVersionCode).toString())
    assertEquals(200L,context.packageManager.getPackageInfo(context.packageName,0).longVersionCode)
   }
  };scope.cancel()
 }
}
