package app.berth.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        HostEntity::class,
        IdentityEntity::class,
        SecretEntity::class,
        KnownHostEntity::class,
        WorkspaceEntity::class,
        SessionEntity::class,
        SessionFrameEntity::class,
        TunnelEntity::class,
        SnippetEntity::class,
        PreferenceEntity::class,
    ],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class BerthDatabase : RoomDatabase() {
    abstract fun hosts(): HostDao
    abstract fun identities(): IdentityDao
    abstract fun secrets(): SecretDao
    abstract fun knownHosts(): KnownHostDao
    abstract fun workspaces(): WorkspaceDao
    abstract fun sessions(): SessionDao
    abstract fun tunnels(): TunnelDao
    abstract fun snippets(): SnippetDao
    abstract fun preferences(): PreferenceDao

    companion object {
        const val NAME = "berth.db"

        fun create(context: Context): BerthDatabase =
            Room.databaseBuilder(context, BerthDatabase::class.java, NAME).build()

        fun inMemory(context: Context): BerthDatabase =
            Room.inMemoryDatabaseBuilder(context, BerthDatabase::class.java).allowMainThreadQueries().build()
    }
}
