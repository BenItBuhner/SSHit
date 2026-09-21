package app.berth.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface HostDao {
    @Query("SELECT * FROM hosts ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<HostEntity>>

    @Query("SELECT * FROM hosts WHERE id = :id")
    fun observe(id: String): Flow<HostEntity?>

    @Query("SELECT * FROM hosts WHERE id = :id")
    suspend fun get(id: String): HostEntity?

    @Query("SELECT * FROM hosts WHERE authJson LIKE :needle")
    suspend fun findByAuthContaining(needle: String): List<HostEntity>

    @Upsert
    suspend fun upsert(host: HostEntity)

    @Query("DELETE FROM hosts WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE hosts SET lastConnectedAt = :at WHERE id = :id")
    suspend fun markConnected(id: String, at: Long)
}

@Dao
interface IdentityDao {
    @Query("SELECT * FROM identities ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<IdentityEntity>>

    @Query("SELECT * FROM identities WHERE id = :id")
    suspend fun get(id: String): IdentityEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(identity: IdentityEntity)

    @Upsert
    suspend fun upsert(identity: IdentityEntity)

    @Query("DELETE FROM identities WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface SecretDao {
    @Query("SELECT * FROM secrets WHERE id = :id")
    suspend fun get(id: String): SecretEntity?

    @Upsert
    suspend fun upsert(secret: SecretEntity)

    @Query("DELETE FROM secrets WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface KnownHostDao {
    @Query("SELECT * FROM known_hosts ORDER BY host, port")
    fun observeAll(): Flow<List<KnownHostEntity>>

    @Query("SELECT * FROM known_hosts WHERE host = :host AND port = :port")
    suspend fun find(host: String, port: Int): List<KnownHostEntity>

    @Upsert
    suspend fun upsert(key: KnownHostEntity)

    @Query("DELETE FROM known_hosts WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE known_hosts SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean)
}

@Dao
interface WorkspaceDao {
    @Query("SELECT * FROM workspaces ORDER BY sortOrder, createdAt")
    fun observeAll(): Flow<List<WorkspaceEntity>>

    @Query("SELECT * FROM workspaces ORDER BY sortOrder, createdAt")
    suspend fun getAll(): List<WorkspaceEntity>

    @Query("SELECT * FROM workspaces WHERE id = :id")
    suspend fun get(id: String): WorkspaceEntity?

    @Upsert
    suspend fun upsert(workspace: WorkspaceEntity)

    @Upsert
    suspend fun upsertAll(workspaces: List<WorkspaceEntity>)

    @Query("DELETE FROM workspaces WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions ORDER BY sortOrder, createdAt")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions ORDER BY sortOrder, createdAt")
    suspend fun getAll(): List<SessionEntity>

    @Upsert
    suspend fun upsert(session: SessionEntity)

    /** One transaction for the tabs a reorder shifted, so an observer never sees a half-moved strip. */
    @Upsert
    suspend fun upsertAll(sessions: List<SessionEntity>)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM session_frames WHERE sessionId = :sessionId")
    suspend fun frame(sessionId: String): SessionFrameEntity?

    @Upsert
    suspend fun upsertFrame(frame: SessionFrameEntity)

    @Query("DELETE FROM session_frames WHERE sessionId = :sessionId")
    suspend fun deleteFrame(sessionId: String)
}

@Dao
interface TunnelDao {
    @Query("SELECT * FROM tunnels ORDER BY hostId, bindPort")
    fun observeAll(): Flow<List<TunnelEntity>>

    @Query("SELECT * FROM tunnels WHERE hostId = :hostId ORDER BY bindPort")
    fun observeForHost(hostId: String): Flow<List<TunnelEntity>>

    @Query("SELECT * FROM tunnels WHERE id = :id")
    suspend fun get(id: String): TunnelEntity?

    @Upsert
    suspend fun upsert(tunnel: TunnelEntity)

    @Query("DELETE FROM tunnels WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE tunnels SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)
}

@Dao
interface SnippetDao {
    @Query("SELECT * FROM snippets ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<SnippetEntity>>

    @Query("SELECT * FROM snippets WHERE id = :id")
    suspend fun get(id: String): SnippetEntity?

    @Upsert
    suspend fun upsert(snippet: SnippetEntity)

    @Query("DELETE FROM snippets WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface CommandHistoryDao {
    @Query("SELECT * FROM command_history WHERE hostId = :hostId ORDER BY at, id")
    fun observeForHost(hostId: String): Flow<List<CommandHistoryEntity>>

    @Query("SELECT * FROM command_history WHERE hostId = :hostId ORDER BY at, id")
    suspend fun forHost(hostId: String): List<CommandHistoryEntity>

    /** The newest [limit] rows across every host, oldest first once reversed by the caller. */
    @Query("SELECT * FROM command_history ORDER BY at DESC, id DESC LIMIT :limit")
    fun observeNewest(limit: Int): Flow<List<CommandHistoryEntity>>

    @Query("SELECT * FROM command_history WHERE hostId = :hostId ORDER BY at DESC, id DESC LIMIT 1")
    suspend fun latest(hostId: String): CommandHistoryEntity?

    @Insert
    suspend fun insert(entry: CommandHistoryEntity): Long

    @Insert
    suspend fun insertAll(entries: List<CommandHistoryEntity>)

    /** Drops the host's oldest rows past [keep], so a host never holds more than the cap. */
    @Query(
        "DELETE FROM command_history WHERE hostId = :hostId AND id NOT IN " +
            "(SELECT id FROM command_history WHERE hostId = :hostId ORDER BY at DESC, id DESC LIMIT :keep)",
    )
    suspend fun trim(hostId: String, keep: Int)

    /** Every row of [from] goes on under [to]; the rows keep their ids and times, so the merged order is by time as before. */
    @Query("UPDATE command_history SET hostId = :to WHERE hostId = :from")
    suspend fun rekey(from: String, to: String)

    @Query("DELETE FROM command_history WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM command_history WHERE hostId = :hostId")
    suspend fun clear(hostId: String)

    @Query("DELETE FROM command_history")
    suspend fun clearAll()
}

@Dao
interface PreferenceDao {
    @Query("SELECT value FROM preferences WHERE `key` = :key")
    fun observe(key: String): Flow<String?>

    @Query("SELECT value FROM preferences WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Upsert
    suspend fun upsert(preference: PreferenceEntity)

    @Query("DELETE FROM preferences WHERE `key` = :key")
    suspend fun delete(key: String)
}
