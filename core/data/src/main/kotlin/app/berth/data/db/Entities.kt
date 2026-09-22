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
    /** Added in schema version 4: Connect opens the host's tunnels with no shell; every earlier host opens a terminal. */
    @ColumnInfo(defaultValue = "0") val tunnelsOnly: Boolean = false,
    /** Added in schema version 8: the host's own scrollback cap; null, as every earlier host has, follows Settings. */
    val scrollbackLines: Int? = null,
    /** Added in schema version 8: the host's cipher names as a JSON list; `[]`, as every earlier host has, offers the client's own. */
    @ColumnInfo(defaultValue = "'[]'") val ciphersJson: String = "[]",
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
    /** Added in schema version 3: the group's run is folded into its chip in the tab strip. */
    @ColumnInfo(defaultValue = "0") val collapsed: Boolean = false,
)

/** One tab (UX spec C3): [workspaceId] is its group and [sortOrder] its position within the group. */
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
    /** Added in schema version 3: the tab's runtime kind; every earlier row is an SSH session. */
    @ColumnInfo(defaultValue = "ssh") val kind: String = "ssh",
    /** Added in schema version 3: a title the user set; null shows the automatic one. */
    @ColumnInfo(defaultValue = "NULL") val customTitle: String? = null,
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

/**
 * Added in schema version 5: one command a host ran (spec C16), keyed by the host so every tab on
 * it shares one history. Before this the entries rode in each tab's frame; the session that
 * restores such a frame hands them over. [hostId] is the host's `commandHistoryKey`.
 */
@Entity(tableName = "command_history", indices = [Index("hostId", "at")])
data class CommandHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val hostId: String,
    val text: String,
    val at: Long,
)

/** Small settings, one JSON document per key. Observed as flows so the UI reacts to edits. */
@Entity(tableName = "preferences")
data class PreferenceEntity(
    @PrimaryKey val key: String,
    val value: String,
    val updatedAt: Long,
)
