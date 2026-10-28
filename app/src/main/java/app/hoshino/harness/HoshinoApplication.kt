package app.hoshino.harness

import android.app.Application
import app.hoshino.harness.core.DshHarnessClient
import app.hoshino.harness.core.Hoshino

class HoshinoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Hoshino.applicationContext=applicationContext
        app.hoshino.harness.core.M2SafeMode.initialize(this)
        Hoshino.client=DshHarnessClient(this)
        Hoshino.store
        (Hoshino.client as DshHarnessClient).restoreVisibleCache()
        (Hoshino.client as DshHarnessClient).restoreWaitingMessages()
    }
}
