package app.berth.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

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
        CommandHistoryEntity::class,
    ],
    version = 6,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6, spec = KnownHostsCaseBlind::class),
    ],
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
    abstract fun commandHistory(): CommandHistoryDao

    companion object {
        const val NAME = "berth.db"

        fun create(context: Context): BerthDatabase =
            Room.databaseBuilder(context, BerthDatabase::class.java, NAME).build()

        fun inMemory(context: Context): BerthDatabase =
            Room.inMemoryDatabaseBuilder(context, BerthDatabase::class.java).allowMainThreadQueries().build()
    }
}

/**
 * Version 6: known hosts are matched case-blind, OpenSSH's way (#19's nit 12). The tables are as
 * they were; the rows change. Every name is lowercased, as `ssh` writes a `known_hosts` name and
 * as [KnownHostKey.canonicalHost][app.berth.domain.model.KnownHostKey.canonicalHost] writes one from
 * here on. Where two rows named one endpoint and key type in different spellings (`NAS.local` and
 * `nas.local`: two hosts typed for one server, each trusted on first use), one stands and the rest
 * go, since a lookup that now found both would take whichever key the server presented and the
 * changed-key sheet would never fire for the stale one. The row kept is the pinned one when one is
 * pinned (the pin is the user's explicit word, and the refusal it causes is loud and undone under
 * Known hosts, where a pin dropped would be silent); else the most recently seen (`lastSeenAt`,
 * refreshed on every match, so the key the server has been presenting); then the most recently
 * saved; then the greater id, so the choice is the same on every phone. A user whose rows named
 * each endpoint and type once sees no change at all; one with a collision sees the changed-key
 * sheet where a stale key used to pass under its other spelling, never the other way about.
 */
class KnownHostsCaseBlind : AutoMigrationSpec {
    override fun onPostMigrate(connection: SQLiteConnection) {
        connection.execSQL(
            """
            DELETE FROM known_hosts WHERE id IN (
                SELECT loser.id FROM known_hosts AS loser JOIN known_hosts AS winner
                    ON winner.host = loser.host COLLATE NOCASE AND winner.port = loser.port
                    AND winner.keyType = loser.keyType AND winner.id != loser.id
                WHERE winner.pinned > loser.pinned
                    OR (winner.pinned = loser.pinned AND winner.lastSeenAt > loser.lastSeenAt)
                    OR (winner.pinned = loser.pinned AND winner.lastSeenAt = loser.lastSeenAt AND winner.firstSeenAt > loser.firstSeenAt)
                    OR (winner.pinned = loser.pinned AND winner.lastSeenAt = loser.lastSeenAt AND winner.firstSeenAt = loser.firstSeenAt AND winner.id > loser.id)
            )
            """.trimIndent(),
        )
        connection.execSQL("UPDATE known_hosts SET host = LOWER(host) WHERE host != LOWER(host)")
    }
}
