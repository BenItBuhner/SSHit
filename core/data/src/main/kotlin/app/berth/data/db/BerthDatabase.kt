package app.berth.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import app.berth.domain.model.TerminalTheme
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

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
    version = 9,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6, spec = KnownHostsCaseBlind::class),
        AutoMigration(from = 6, to = 7, spec = PersistenceInherits::class),
        // Version 8: a host's own scrollback cap and cipher list, two columns whose defaults leave every host as it was.
        AutoMigration(from = 7, to = 8),
        AutoMigration(from = 8, to = 9, spec = StockThemeIdsFreed::class),
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

/**
 * Version 7: a host's Keepalive and Reconnect inherit Settings › Connection's defaults unless the
 * host sets its own (spec C20). The tables are as they were, since the policy is a JSON column
 * whose fields may now be null; the rows change. Every build before this wrote the host editor's
 * starting values (15 s, 15 min) on a host that never changed them, so a 15 in either field becomes
 * null, inherit, and any other value stays the host's own, field by field
 * ([PersistencePolicy.foldLegacyDefaults][app.berth.domain.model.PersistencePolicy.foldLegacyDefaults]'s rule).
 * The defaults start at the same values, so every host connects as it did until they move. A tab's
 * host snapshot carries a policy too and is folded the same way, so a restored tab and its saved
 * host agree; a snapshot stored without one (a quick connect's) already reads as inherit.
 *
 * The rule runs on the JSON as stored rather than through the model, so a later change to the
 * model cannot change what this step did.
 */
class PersistenceInherits : AutoMigrationSpec {
    override fun onPostMigrate(connection: SQLiteConnection) {
        connection.rewriteJson("hosts", "persistenceJson") { it.foldPolicy() }
        connection.rewriteJson("sessions", "hostSnapshotJson") { host ->
            val policy = host["persistence"] as? JsonObject ?: return@rewriteJson host
            val folded = policy.foldPolicy()
            if (folded == policy) host else JsonObject(host + ("persistence" to folded))
        }
    }

    private fun JsonObject.foldPolicy(): JsonObject {
        val keep = mapValues { (field, value) ->
            val legacy = when (field) {
                "keepaliveSeconds" -> LEGACY_KEEPALIVE_SECONDS
                "reconnectMinutes" -> LEGACY_RECONNECT_MINUTES
                else -> return@mapValues value
            }
            if (value is JsonPrimitive && !value.isString && value.intOrNull == legacy) JsonNull else value
        }
        return if (keep == this) this else JsonObject(keep)
    }

    private companion object {
        const val LEGACY_KEEPALIVE_SECONDS = 15
        const val LEGACY_RECONNECT_MINUTES = 15
    }
}

/**
 * Version 9: no custom theme is shadowed by a stock one. Builds before the stock set grew to its
 * eighteen saved an imported scheme with no id under a slug of its name, so a Gogh "Dracula" sat
 * at `dracula`, now a stock id: the stock theme hid it, every host, group and default on it drew
 * the stock colours, and it could be neither opened nor deleted. Each custom under a stock id
 * moves to [TerminalTheme.freedId] with its name and colours as they were, and every reference to
 * it follows: the app default, each group's theme, each host's appearance and each tab's host
 * snapshot. A reference to a stock id no custom sat under still names the stock theme.
 *
 * The step runs on the JSON as stored rather than through the model, and against its own copy of
 * the eighteen ids stock at this version, [STOCK_IDS], so neither a later change to the model's
 * fields nor a stock theme added later changes what it did; a later addition needs a step of its
 * own. Where a theme moves to is [TerminalTheme.freedId], shared with bundle import so a bundle
 * made before the upgrade lands on the moved theme, which is why that derivation never changes.
 */
class StockThemeIdsFreed : AutoMigrationSpec {
    override fun onPostMigrate(connection: SQLiteConnection) {
        val stored = connection.preference(CUSTOM_THEMES)?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } as? JsonArray ?: return
        val moved = HashMap<String, String>()
        val themes = stored.map { element ->
            val theme = element as? JsonObject ?: return@map element
            val id = theme.stringField("id")?.takeIf { it in STOCK_IDS } ?: return@map element
            val to = TerminalTheme.freedId(id)
            moved[id] = to
            JsonObject(theme + ("id" to JsonPrimitive(to)))
        }
        if (moved.isEmpty()) return
        connection.setPreference(CUSTOM_THEMES, JsonArray(themes).toString())

        val default = connection.preference(DEFAULT_THEME)?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() as? JsonPrimitive }
        default?.takeIf { it.isString }?.content?.let(moved::get)?.let { connection.setPreference(DEFAULT_THEME, JsonPrimitive(it).toString()) }
        for ((from, to) in moved) {
            connection.prepare("UPDATE workspaces SET terminalThemeId = ? WHERE terminalThemeId = ?").use { update ->
                update.bindText(1, to)
                update.bindText(2, from)
                update.step()
            }
        }
        connection.rewriteJson("hosts", "appearanceJson") { it.repointed(moved) }
        connection.rewriteJson("sessions", "hostSnapshotJson") { host ->
            val appearance = host["appearance"] as? JsonObject ?: return@rewriteJson host
            val repointed = appearance.repointed(moved)
            if (repointed == appearance) host else JsonObject(host + ("appearance" to repointed))
        }
    }

    /** An appearance override whose theme moved, naming where it went. */
    private fun JsonObject.repointed(moved: Map<String, String>): JsonObject {
        val to = stringField("terminalThemeId")?.let(moved::get) ?: return this
        return JsonObject(this + ("terminalThemeId" to JsonPrimitive(to)))
    }

    private fun JsonObject.stringField(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun SQLiteConnection.preference(key: String): String? =
        prepare("SELECT value FROM preferences WHERE `key` = ?").use { row ->
            row.bindText(1, key)
            if (row.step()) row.getText(0) else null
        }

    private fun SQLiteConnection.setPreference(key: String, value: String) {
        prepare("UPDATE preferences SET value = ? WHERE `key` = ?").use { update ->
            update.bindText(1, value)
            update.bindText(2, key)
            update.step()
        }
    }

    internal companion object {
        private const val CUSTOM_THEMES = "terminal_themes_custom"
        private const val DEFAULT_THEME = "terminal_theme_default"

        /** The stock ids at version 9, as they were then; this list does not follow the gallery. */
        internal val STOCK_IDS: Set<String> = setOf(
            "berth-dark", "berth-light",
            "catppuccin-mocha", "catppuccin-latte",
            "gruvbox-dark", "gruvbox-light",
            "nord",
            "solarized-dark", "solarized-light",
            "rose-pine", "tokyo-night", "kanagawa", "everforest", "dracula", "one-dark", "ayu",
            "github-dark-high-contrast", "github-light-high-contrast",
        )
    }
}

/** [column] of every row of [table] through [change], written back where it changed; a value that is not a JSON object is left alone. */
private fun SQLiteConnection.rewriteJson(table: String, column: String, change: (JsonObject) -> JsonObject) {
    val changed = ArrayList<Pair<String, String>>()
    prepare("SELECT id, $column FROM $table").use { rows ->
        while (rows.step()) {
            if (rows.isNull(1)) continue
            val stored = rows.getText(1)
            val json = runCatching { Json.parseToJsonElement(stored) }.getOrNull() as? JsonObject ?: continue
            val folded = change(json)
            if (folded != json) changed += rows.getText(0) to folded.toString()
        }
    }
    for ((id, value) in changed) {
        prepare("UPDATE $table SET $column = ? WHERE id = ?").use { update ->
            update.bindText(1, value)
            update.bindText(2, id)
            update.step()
        }
    }
}
