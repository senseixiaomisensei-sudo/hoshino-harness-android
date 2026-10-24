package app.hoshino.harness

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import app.hoshino.harness.core.*
import app.hoshino.harness.ui.ConfigViewModel
import app.hoshino.harness.ui.chat.ChatItemView
import app.hoshino.harness.ui.chat.MessageActions
import app.hoshino.harness.ui.components.CodeBlock
import app.hoshino.harness.ui.settings.ProviderEditScreen
import app.hoshino.harness.ui.theme.HoshinoTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Focused native smoke evidence; this is deliberately separate from full M1 acceptance. */
class M1UiSmokeTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun actualKernelSecureScopeClipboardPixelCopyAndLongCapture() {
        val client=Hoshino.client as DshHarnessClient
        ui.waitUntil(300000){client.runtime.state.value.phase!="启动中"}
        assertEquals(client.runtime.state.value.logs.joinToString(),"已连接",client.runtime.state.value.phase)
        val terminal=runBlocking{client.openTerminal()}
        runBlocking{client.terminalInput(terminal,"printf 'HOSHINO_NATIVE_PTY_OK\\n'")}
        ui.waitUntil(20000){client.terminals.value.firstOrNull{it.id==terminal}?.lines?.joinToString(""){it.text}?.replace(Regex("\\u001B\\[[0-9;?]*[A-Za-z]"),"")?.lines()?.any{it.trim()=="HOSHINO_NATIVE_PTY_OK"}==true}
        runBlocking{client.closeTerminal(terminal)}
        fun secure()=ui.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE!=0
        assertFalse(secure())
        val screenshot=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        assertNotNull(screenshot)
        val bitmap=screenshot.copy(Bitmap.Config.ARGB_8888,false);val pixels=IntArray(bitmap.width*bitmap.height);bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
        assertTrue("Screenshot is entirely black",pixels.any{it and 0xffffff!=0});bitmap.recycle();screenshot.recycle()
        val capture=runBlocking{AppSurface.capture()};assertTrue(capture.width>0 && capture.height>0);capture.recycle()
        ui.runOnUiThread{ui.activity.setContent{HoshinoTheme{ProviderEditScreen(ConfigViewModel(),"new:custom",{})}}}
        ui.waitForIdle();assertTrue(secure())
        ui.runOnUiThread{ui.activity.setContent{HoshinoTheme{Column{ChatItemView(ChatItem.Assistant("fixture","copy-fixture",false),MessageActions());CodeBlock("echo code-only","bash")}}}}
        ui.waitForIdle();assertFalse(secure())
        ui.onNodeWithText("copy-fixture").performTouchInput{longClick()}
        ui.onNodeWithText("复制全文").performClick();ui.waitForIdle()
        val clipboard=ui.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals("copy-fixture",clipboard.primaryClip!!.getItemAt(0).text.toString())
        ui.onNodeWithText("复制",useUnmergedTree=true).performClick();ui.waitForIdle()
        assertEquals("echo code-only",clipboard.primaryClip!!.getItemAt(0).text.toString())
        val items=listOf(ChatItem.Assistant("one","Long capture first message",false),ChatItem.Assistant("two","Second message",false))
        val first=runBlocking{app.hoshino.harness.ui.tools.LongCapture.render(items.take(1),false,watermark=false)}
        val second=runBlocking{app.hoshino.harness.ui.tools.LongCapture.render(items.drop(1),false,watermark=false)}
        val long=runBlocking{app.hoshino.harness.ui.tools.LongCapture.render(items,false,watermark=false)}
        assertEquals(first.height+second.height,long.height)
        val uri=runBlocking{AppMedia(ui.activity).save(long)}
        val bounds=android.graphics.BitmapFactory.Options().apply{inJustDecodeBounds=true}
        ui.activity.contentResolver.openInputStream(uri)!!.use{android.graphics.BitmapFactory.decodeStream(it,null,bounds)}
        assertEquals(long.width,bounds.outWidth);assertEquals(long.height,bounds.outHeight)
        ui.activity.contentResolver.delete(uri,null,null);first.recycle();second.recycle();long.recycle()
    }
}
