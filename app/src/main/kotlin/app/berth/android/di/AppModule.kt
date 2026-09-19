package app.berth.android.di

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier

/**
 * The whole process's lifecycle ([ProcessLifecycleOwner]): started while any activity is on
 * screen, stopped shortly after the last one leaves it. Injected rather than fetched so tests can
 * drive a registry of their own.
 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class ProcessLifecycle

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @ProcessLifecycle
    fun processLifecycle(): Lifecycle = ProcessLifecycleOwner.get().lifecycle
}
