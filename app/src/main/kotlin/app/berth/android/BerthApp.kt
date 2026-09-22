package app.berth.android

import android.app.Application
import app.berth.android.diagnostics.BerthLog
import app.berth.android.diagnostics.CrashReporter
import app.berth.ssh.SshSecurity
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlin.concurrent.thread

@HiltAndroidApp
class BerthApp : Application() {
    @Inject lateinit var reports: CrashReporter

    override fun onCreate() {
        super.onCreate()
        // First, so a crash anywhere in what follows is written like any other.
        reports.install()
        BerthLog.i("App", "process started")
        // Building the provider is tens of milliseconds nothing on the way to the first frame needs;
        // every SSH path that does calls ensureProviders itself and waits for this one to finish.
        thread(name = "berth-providers") { SshSecurity.ensureProviders() }
    }
}
