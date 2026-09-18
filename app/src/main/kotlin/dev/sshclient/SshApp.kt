package dev.sshclient

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import dev.sshclient.ssh.SshSecurity

@HiltAndroidApp
class SshApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SshSecurity.ensureProviders()
    }
}
