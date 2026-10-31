package app.hoshino.harness

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.hoshino.harness.core.*
import app.hoshino.harness.testing.LegacyConstructors
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
 /*
  * This test APK is compiled from the current sources but runs inside the old 0.2.0-m2 (vc200) app process, so every app
  * class resolves to the OLD app's version. Direct Kotlin constructor calls on data classes link against whatever the
  * current source declares (e.g. the synthetic default-args constructor after M2.7 added defaulted ModelInfo fields), which
  * the old APK lacks: NoSuchMethodError. All app data classes here are therefore built through LegacyConstructors, which picks
  * the constructor the running (old) class actually has, so later constructor changes cannot break this seed again.
  */
 @Test fun seedExistingM2Data(){
  val context=InstrumentationRegistry.getInstrumentation().targetContext;val client=Hoshino.client as DshHarnessClient
  val events=ConcurrentLinkedQueue<HarnessEvent>();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);scope.launch(start=CoroutineStart.UNDISPATCHED){client.events.collect{events.add(it)}}
  ActivityScenario.launch(MainActivity::class.java).use{
   runBlocking{withTimeout(300000){while(client.runtime.state.value.phase!="已连接")delay(50)}
    val model=LegacyConstructors.new<ModelInfo>("mock-model","M21 Mock",128000,4096,true,true,ReasoningEffort.Off)
    client.upsertProvider(LegacyConstructors.new<Provider>("m21-mock","M21 preserved mock","custom",ProviderProtocol.OpenAICompatible,"http://127.0.0.1:8099/v1/chat/completions","m21-fixture-key",emptyList<Pair<String,String>>(),true,listOf(model)))
    val turn=LegacyConstructors.new<TurnOptions>("standard",PermissionTier.WorkspaceWrite,LegacyConstructors.new<ModelRef>("m21-mock","mock-model"),ReasoningEffort.Off,false)
    client.m2Control("config",JSONObject().put("name","approval.json").put("value",JSONObject().put("mode","auto").put("highRiskAsk",false)))
    val ids=JSONArray()
    for(index in 1..3){val session=client.createSession("ws-default","standard",PermissionTier.WorkspaceWrite);client.activateSession(session.id);client.send(session.id,"你好 m21-history-$index",emptyList(),turn);withTimeout(60000){while(events.none{it is HarnessEvent.TurnFinished&&it.sessionId==session.id})delay(20)};ids.put(session.id)}
    File(context.filesDir,"m21-upgrade-seed.json").writeText(JSONObject().put("sessions",ids).put("provider","m21-mock").put("keyRef","KEY_M21_MOCK").put("versionCode",context.packageManager.getPackageInfo(context.packageName,0).longVersionCode).toString())
    assertEquals(200L,context.packageManager.getPackageInfo(context.packageName,0).longVersionCode)
   }
  };scope.cancel()
 }
}
