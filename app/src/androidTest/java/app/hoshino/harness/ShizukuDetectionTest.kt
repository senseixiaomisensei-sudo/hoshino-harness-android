package app.hoshino.harness

import androidx.test.platform.app.InstrumentationRegistry
import app.hoshino.harness.core.Hoshino
import app.hoshino.harness.core.ShizukuStatus
import dev.hoshino.bridge.ShizukuConnection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Run after installing the official manager on the test emulator; no fake Binder. */
class ShizukuDetectionTest {
    @Test fun installedManagerDoesNotRequireInstallation() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        @Suppress("DEPRECATION")
        val manager=context.packageManager.getPackageInfo(ShizukuConnection.MANAGER_PACKAGE,0)
        assertEquals(ShizukuConnection.MANAGER_PACKAGE,manager.packageName)
        runBlocking{Hoshino.client.refreshShizuku()}
        val deadline=System.currentTimeMillis()+5000
        while(Hoshino.client.shizuku.value==ShizukuStatus.NotInstalled && System.currentTimeMillis()<deadline)Thread.sleep(50)
        assertNotEquals("Installed Shizuku was hidden by package visibility or a fixed placeholder",ShizukuStatus.NotInstalled,Hoshino.client.shizuku.value)
        println("SHIZUKU_ACTUAL_MANAGER_STATE ${Hoshino.client.shizuku.value}")
    }
}
