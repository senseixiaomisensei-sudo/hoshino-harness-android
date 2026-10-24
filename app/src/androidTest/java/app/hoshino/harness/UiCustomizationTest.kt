package app.hoshino.harness

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.hoshino.harness.core.*
import app.hoshino.harness.ui.ConfigViewModel
import app.hoshino.harness.ui.HoshinoApp
import app.hoshino.harness.ui.settings.KernelScreen
import app.hoshino.harness.ui.theme.HoshinoTheme
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Real Cordis configuration -> original ZIP Compose UI -> native rollback UI. */
class UiCustomizationTest {
    @get:Rule val ui=createEmptyComposeRule()
    @Test fun declarativeCustomizationRendersAndNativeRollbackRestoresSnapshot() {
        val client=Hoshino.client as DshHarnessClient
        var snapshot:String?=null
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            ui.waitUntil(300000){client.runtime.state.value.phase in setOf("已连接","崩溃")}
            assertEquals("已连接",client.runtime.state.value.phase)
            try {
                runBlocking{client.upsertProvider(Provider("ui-customization-fixture","UI fixture","custom",ProviderProtocol.OpenAICompatible,"http://127.0.0.1:8099/v1","sk-ui-fixture-"+java.util.UUID.randomUUID(),emptyList(),true,listOf(ModelInfo("mock-model","UI fixture",128000,4096,false,false,ReasoningEffort.Off))))}
                fun composer():SemanticsNodeInteraction {
                    val nodes=ui.onAllNodes(hasSetTextAction())
                    return nodes[(0 until nodes.fetchSemanticsNodes().size).first{runCatching{nodes[it].assertIsDisplayed()}.isSuccess}]
                }
                val custom=JSONObject("""{"accent":"#D34567","bodyScale":1.1,"codeScale":1.05,"tagline":"Native customization fixture","capsules":[{"label":"Quick fixture","prompt":"native capsule prompt"}],"commands":[{"name":"local-check","description":"Native command fixture","prompt":"native command prompt"}],"sidebar":[{"route":"skills","visible":true},{"route":"plugins","visible":false}]}""")
                snapshot=runBlocking{client.control("ui-override",JSONObject().put("content",custom.toString())).getString("snapshot")}
                runBlocking{client.refreshControl()}
                ui.waitUntil(10000){client.uiCustomization.value.tagline=="Native customization fixture"}
                ui.onNodeWithText("Native customization fixture").assertIsDisplayed()
                ui.onNodeWithText("Quick fixture").performScrollTo().performClick()
                composer().assertTextContains("native capsule prompt")
                val bitmap=runBlocking{AppSurface.capture()}
                try {
                    val pixels=IntArray(bitmap.width*bitmap.height);bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
                    assertTrue("Custom accent was absent from the actual native window",pixels.count{it and 0xffffff==0xd34567}>20)
                } finally {bitmap.recycle()}
                composer().performTextReplacement("/local-check")
                ui.onNodeWithText("Native command fixture").performClick()
                composer().assertTextContains("native command prompt")
                // Open the real drawer; hidden routes disappear while matching order remains native.
                ui.onNodeWithContentDescription(InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.cd_sidebar)).performClick()
                ui.onNodeWithText(InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.nav_plugins)).assertDoesNotExist()
                ui.onNodeWithText(InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.nav_skills)).assertIsDisplayed()

                scenario.onActivity{it.setContent{HoshinoTheme{KernelScreen(ConfigViewModel(),{})}}}
                ui.onNodeWithText("配置快照与回滚").performScrollTo().performClick()
                ui.waitUntil(10000){ui.onAllNodesWithText(snapshot!!).fetchSemanticsNodes().isNotEmpty()}
                ui.onNodeWithText(snapshot!!).performScrollTo().performClick()
                ui.onNodeWithText("恢复",substring=false).performClick()
                ui.waitUntil(10000){client.uiCustomization.value.tagline!="Native customization fixture"}
                // A malformed external file must be ignored, rather than becoming executable UI.
                File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,"hoshino/ui/overrides.json").writeText("{\"accent\":\"javascript:fixture\"}")
                runBlocking{client.refreshControl()}
                assertEquals(UiCustomization(),client.uiCustomization.value)
                assertNotNull(client.uiCustomizationError.value)
                runBlocking{client.rollbackConfiguration(snapshot!!)}
                assertNull(client.uiCustomizationError.value)
                scenario.onActivity{it.setContent{HoshinoApp()}}
                ui.onNodeWithText("Native customization fixture").assertDoesNotExist()
            } finally {snapshot?.let{runBlocking{client.rollbackConfiguration(it)}}}
        }
    }
}
