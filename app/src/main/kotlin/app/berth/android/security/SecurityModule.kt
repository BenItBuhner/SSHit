package app.berth.android.security

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SecurityModule {
    @Provides @Singleton
    fun clock(): MonotonicClock = SystemMonotonicClock

    @Provides @Singleton
    fun authenticator(@ApplicationContext context: Context, foreground: ForegroundActivity): DeviceAuthenticator =
        FrameworkAuthenticator(context, foreground)
}
