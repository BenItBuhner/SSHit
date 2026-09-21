package app.berth.data.di

import android.content.Context
import app.berth.data.crypto.HardwareKeys
import app.berth.data.crypto.KeystoreCrypto
import app.berth.data.crypto.KeystoreSigning
import app.berth.data.crypto.SecretCrypto
import app.berth.data.db.BerthDatabase
import app.berth.data.repo.EncryptedSecretStore
import app.berth.data.repo.RoomCommandHistoryRepository
import app.berth.data.repo.RoomHostRepository
import app.berth.data.repo.RoomIdentityRepository
import app.berth.data.repo.RoomKnownHostRepository
import app.berth.data.repo.RoomSessionRepository
import app.berth.data.repo.RoomSettingsRepository
import app.berth.data.repo.RoomSnippetRepository
import app.berth.data.repo.RoomTunnelRepository
import app.berth.data.repo.RoomWorkspaceRepository
import app.berth.domain.repository.CommandHistoryRepository
import app.berth.domain.repository.HostRepository
import app.berth.domain.repository.IdentityRepository
import app.berth.domain.repository.KnownHostRepository
import app.berth.domain.repository.SecretStore
import app.berth.domain.repository.SessionRepository
import app.berth.domain.repository.SettingsRepository
import app.berth.domain.repository.SnippetRepository
import app.berth.domain.repository.TunnelRepository
import app.berth.domain.repository.WorkspaceRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides @Singleton
    fun database(@ApplicationContext context: Context): BerthDatabase = BerthDatabase.create(context)

    @Provides @Singleton
    fun secretCrypto(): SecretCrypto = KeystoreCrypto()

    @Provides @Singleton
    fun hardwareKeys(@ApplicationContext context: Context): HardwareKeys = HardwareKeys(context)

    @Provides @Singleton
    fun keystoreSigning(hardwareKeys: HardwareKeys): KeystoreSigning = hardwareKeys

    @Provides @Singleton
    fun secretStore(db: BerthDatabase, crypto: SecretCrypto): SecretStore = EncryptedSecretStore(db, crypto)

    @Provides @Singleton
    fun hosts(db: BerthDatabase): HostRepository = RoomHostRepository(db)

    @Provides @Singleton
    fun identities(db: BerthDatabase, secrets: SecretStore, hardwareKeys: HardwareKeys): IdentityRepository =
        RoomIdentityRepository(db, secrets, hardwareKeys)

    @Provides @Singleton
    fun knownHosts(db: BerthDatabase): KnownHostRepository = RoomKnownHostRepository(db)

    @Provides @Singleton
    fun workspaces(db: BerthDatabase): WorkspaceRepository = RoomWorkspaceRepository(db)

    @Provides @Singleton
    fun sessions(db: BerthDatabase): SessionRepository = RoomSessionRepository(db)

    @Provides @Singleton
    fun tunnels(db: BerthDatabase): TunnelRepository = RoomTunnelRepository(db)

    @Provides @Singleton
    fun snippets(db: BerthDatabase): SnippetRepository = RoomSnippetRepository(db)

    @Provides @Singleton
    fun settings(db: BerthDatabase): SettingsRepository = RoomSettingsRepository(db)

    @Provides @Singleton
    fun commandHistory(db: BerthDatabase): CommandHistoryRepository = RoomCommandHistoryRepository(db)
}
