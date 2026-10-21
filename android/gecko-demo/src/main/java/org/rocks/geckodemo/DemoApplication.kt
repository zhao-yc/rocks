package org.rocks.geckodemo

import android.app.Application
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

class DemoApplication : Application() {
    // One runtime per process. Configuration changes must never create a second runtime.
    val runtime: GeckoRuntime by lazy {
        GeckoRuntime.create(this, GeckoRuntimeSettings.Builder()
            .remoteDebuggingEnabled(false)
            .consoleOutput(false)
            .loginAutofillEnabled(false)
            .build())
    }
}
