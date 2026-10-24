package app.hoshino.harness

import android.app.Application
import app.hoshino.harness.core.DshHarnessClient
import app.hoshino.harness.core.Hoshino

class HoshinoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Hoshino.client=DshHarnessClient(this)
    }
}
