package app.berth.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persistence shapes. Scalar fields that the UI sorts or searches by are columns; nested,
 * evolving structures (auth, persistence policy, appearance) are stored as JSON so adding a knob
 * does not need a migration.
 */
@Entity(tableName = "hosts", indices = [Index("name"), Index("lastConnectedAt")])
data class HostEntity(
    @PrimaryKey val id: String,
    val name: String,
    val color: String,
    val monogram: String,
    val address: String,
    val port: Int,
    val user: String,
    val authJson: String,
    val jumpHostIdsJson: String,
    val persistenceJson: String,
    val startupCommand: String?,
    val environmentJson: String,
    val terminalType: String,
    val agentForwarding: Boolean,
    val compression: Boolean,
    val addressFamily: String,
    val appearanceJson: String,
    val tagsJson: String,
    val muteBell: Boolean,
    val lastConnectedAt: Long?,
    val createdAt: Long,
)

@Entity(tableName = "identities", indices = [Index("name")])
data class IdentityEntity(
    @PrimaryKey val id: String,
    val name: String,
    val algorithm: String,
    val storage: String,
    val protection: String,
    val publicKeyOpenSsh: String,
    val fingerprintSha256: String,
    val comment: String,
    val keystoreAlias: String?,
    val createdAt: Long,
)

/** Ciphertext only. [blob] is `version || iv || ciphertext` as produced by the Keystore crypto. */
@Entity(tableName = "secrets")
data class SecretEntity(
    @PrimaryKey val id: String,
    val blob: ByteArray,
    val updatedAt: Long,
)

@Entity(tableName = "known_hosts", indices = [Index("host", "port")])
data class KnownHostEntity(
    @PrimaryKey val id: String,
    val host: String,
    val port: Int,
    val keyType: String,
    val publicKeyBase64: String,
    val fingerprintSha256: String,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    @ColumnInfo(defaultValue = "0") val pinned: Boolean = false,
)

@Entity(tableName = "workspaces", indices = [Index("sortOrder")])
data class WorkspaceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val color: String,
    val monogram: String,
    val accentRgb: Int?,
    val sortOrder: Int,
    val reconnectAtLaunch: Boolean,
    val createdAt: Long,
    /** Added in schema version 2; null inherits the app default. */
    @ColumnInfo(defaultValue = "NULL") val terminalThemeId: String? = null,
)

@Entity(tableName = "sessions", indices = [Index("workspaceId"), Index("hostId")])
data class SessionEntity(
    @PrimaryKey val id: String,
    val workspaceId: String,
    val hostId: String?,
    val hostSnapshotJson: String,
    val state: String,
    val layer: String,
    val title: String,
    val cwd: String?,
    val lastCommand: String?,
    val needsAttention: Boolean,
    val attentionReason: String?,
    val sortOrder: Int,
    val createdAt: Long,
    val lastLiveAt: Long?,
    val frameKey: String?,
)

/** Frozen terminal frame (scrollback plus screen) for a detached or restored session. */
@Entity(tableName = "session_frames")
data class SessionFrameEntity(
    @PrimaryKey val sessionId: String,
    val frame: ByteArray,
    val savedAt: Long,
)

@Entity(tableName = "tunnels", indices = [Index("hostId")])
data class TunnelEntity(
    @PrimaryKey val id: String,
    val hostId: String,
    val type: String,
    val bindAddress: String,
    val bindPort: Int,
    val destinationHost: String,
    val destinationPort: Int,
    val enabled: Boolean,
)

@Entity(tableName = "snippets", indices = [Index("hostId"), Index("name")])
data class SnippetEntity(
    @PrimaryKey val id: String,
    val name: String,
    val body: String,
    val hostId: String?,
    val tagsJson: String,
    val defaultAction: String,
    val runOnConnect: Boolean,
    val pinnedToDeck: Boolean,
    val workspaceId: String? = null,
)

/** Small settings, one JSON document per key. Observed as flows so the UI reacts to edits. */
@Entity(tableName = "preferences")
data class PreferenceEntity(
    @PrimaryKey val key: String,
    val value: String,
    val updatedAt: Long,
)
