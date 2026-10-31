package org.rocks.geckodemo

import android.app.Application
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import java.io.File
import org.rocks.browser.AtomicSessionFile
import org.rocks.browser.SessionRepository

class DemoApplication : Application() {
    internal val storage by lazy { BrowserStorage(SessionRepository(AtomicSessionFile(File(filesDir, "gecko-browser-data.json")))) }
    // One runtime per process. Configuration changes must never create a second runtime.
    val runtime: GeckoRuntime by lazy {
        GeckoRuntime.create(this, GeckoRuntimeSettings.Builder()
            .remoteDebuggingEnabled(false)
            .consoleOutput(false)
            .loginAutofillEnabled(false)
            .build())
    }
}
