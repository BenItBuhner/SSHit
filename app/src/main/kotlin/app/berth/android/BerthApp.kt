package app.berth.android

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import app.berth.ssh.SshSecurity

@HiltAndroidApp
class BerthApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SshSecurity.ensureProviders()
    }
}
