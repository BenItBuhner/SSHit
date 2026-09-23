package app.berth.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.platform.app.InstrumentationRegistry
import app.berth.data.crypto.HardwareKeys
import app.berth.data.crypto.SecretCrypto
import app.berth.data.db.BerthDatabase
import app.berth.data.db.BerthDatabase_Impl
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
import app.berth.domain.model.AppearanceOverride
import app.berth.domain.model.AuthMethod
import app.berth.domain.model.ConnectionSettings
import app.berth.domain.model.DeckAction
import app.berth.domain.model.DeckArrows
import app.berth.domain.model.DeckKey
import app.berth.domain.model.DeckKeyCode
import app.berth.domain.model.DeckLayer
import app.berth.domain.model.DeckLayout
import app.berth.domain.model.DeckReach
import app.berth.domain.model.Density
import app.berth.domain.model.FilesPrefs
import app.berth.domain.model.FilesSort
import app.berth.domain.model.HapticLevel
import app.berth.domain.model.Host
import app.berth.domain.model.Identity
import app.berth.domain.model.InterfaceContrast
import app.berth.domain.model.InterfaceTheme
import app.berth.domain.model.InterfaceVariant
import app.berth.domain.model.KeyAlgorithm
import app.berth.domain.model.KeyProtection
import app.berth.domain.model.KeyStorage
import app.berth.domain.model.KnownHostKey
import app.berth.domain.model.PersistencePolicy
import app.berth.domain.model.SessionRecord
import app.berth.domain.model.SessionState
import app.berth.domain.model.Snippet
import app.berth.domain.model.SnippetAction
import app.berth.domain.model.SwatchColor
import app.berth.domain.model.TabKind
import app.berth.domain.model.TabSwipeGesture
import app.berth.domain.model.TerminalFont
import app.berth.domain.model.TerminalTheme
import app.berth.domain.model.TmuxMode
import app.berth.domain.model.Tunnel
import app.berth.domain.model.TunnelType
import app.berth.domain.model.Workspace
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Installing this build over an older one has to keep the database that build wrote. Each test
 * creates the schema an older build shipped, from its exported JSON, and fills it the way a phone
 * in use is filled: a host on a key and one on a password, a software identity with its encrypted
 * private key and a hardware one, accepted host keys, a second group, a detached session with its
 * frame and a failed quick connect, tunnels, snippets and the settings documents, every document
 * written as that build encoded it. The migration to the current version then has to keep every
 * row, give the new columns their defaults, and hand the rows back through the repositories, which
 * is the path the app takes at launch. The two tab settings this build added are not in an older
 * settings document, so they read as their defaults until they are set.
 *
 * Version 6 is the one migration that changes rows rather than columns: the known hosts' names are
 * lowercased and two rows that named one endpoint and key type in different spellings become one
 * ([app.berth.data.db.KnownHostsCaseBlind]). The seed holds a capitalised name on its own, a pair that differ only
 * in case where the more recently seen row is the one to keep, a pair where the older row is the
 * pinned one (from version 2, which brought the pin) and so the one to keep, and two key types
 * under one endpoint that both stand; and a row that reads back case-blind through the repository.
 *
 * Version 7 changes rows too: a host's Keepalive and Reconnect may inherit Settings › Connection's
 * defaults, and the 15 s and 15 min an older build stored as the editor's starting values become
 * inherit ([app.berth.data.db.PersistenceInherits]). The seed's web host keeps its 30 s keepalive
 * and inherits its reconnect; the bastion inherits its keepalive and keeps Forever (0); the nas, a
 * host nobody changed, stored at the editor's 15 s and 15 min, inherits both, and so does its tab's
 * snapshot; the detached tab's snapshot of the web host folds as the host does; the quick
 * connect's, stored without a policy, is left as it was. A version 7 phone holds its rows folded
 * already, and a 15 set on it since is the host's own: the bastion's Keepalive, chosen as 15 there,
 * stays 15 through the steps after.
 *
 * Version 8 gives each host its own scrollback cap and cipher list, two columns whose defaults
 * (null, and an empty list) leave every host as it was: following Settings › Terminal ›
 * Scrollback and offering the client's own ciphers. A session's host snapshot, a JSON document
 * without either field, reads them as the same defaults.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MigrationTest {
    /** XORs with a fixed byte, like the repositories test; enough to prove the key is read back through the crypto. */
    private val crypto = object : SecretCrypto {
        override fun encrypt(plain: ByteArray) = byteArrayOf(9) + ByteArray(plain.size) { (plain[it].toInt() xor 0x5A).toByte() }
        override fun decrypt(blob: ByteArray) = ByteArray(blob.size - 1) { (blob[it + 1].toInt() xor 0x5A).toByte() }
    }

    @Test
    fun `a version 1 database migrates to the current version keeping every row`() = migrateAndCheck(from = 1)

    @Test
    fun `a version 2 database migrates to the current version keeping every row`() = migrateAndCheck(from = 2)

    @Test
    fun `a version 3 database migrates to the current version keeping every row`() = migrateAndCheck(from = 3)

    @Test
    fun `a version 4 database migrates to the current version keeping every row`() = migrateAndCheck(from = 4)

    @Test
    fun `a version 5 database migrates to the current version keeping every row`() = migrateAndCheck(from = 5)

    @Test
    fun `a version 6 database migrates to the current version keeping every row`() = migrateAndCheck(from = 6)

    @Test
    fun `a version 7 database migrates to the current version keeping every row`() = migrateAndCheck(from = 7)

    /** Version 7's row changes on the framework's connection, the path the app opens its database on. */
    @Test
    fun `a version 6 database opened the way the app opens it has the old starting values folded to inherit`() {
        val file = File.createTempFile("berth-v6-app", ".db").also { it.delete(); it.deleteOnExit() }
        val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), file, AndroidSQLiteDriver(), BerthDatabase::class, { BerthDatabase_Impl() }, emptyList())
        helper.createDatabase(6).use { seed(it, 6) }

        val db = Room.databaseBuilder(RuntimeEnvironment.getApplication(), BerthDatabase::class.java, file.absolutePath).allowMainThreadQueries().build()
        try {
            runTest {
                val hosts = RoomHostRepository(db)
                assertEquals(PersistencePolicy(keepaliveSeconds = 30, tmux = TmuxMode.ATTACH_OR_CREATE, tmuxSessionName = "web"), hosts.get("h1")?.persistence)
                assertEquals(PersistencePolicy(reconnectMinutes = 0), hosts.get("bastion")?.persistence)
                assertEquals(PersistencePolicy(), hosts.get("nas")?.persistence)
                assertEquals(listOf(prodWeb.persistence, PersistencePolicy(), PersistencePolicy()), RoomSessionRepository(db).getAll().map { it.hostSnapshot.persistence })
                // Settings › Connection starts where the editor did, so each host connects as it did before the upgrade.
                val defaults = RoomSettingsRepository(db).connectionSettings.first()
                assertEquals(30 to 15, hosts.get("h1")!!.persistence.let { it.effectiveKeepaliveSeconds(defaults) to it.effectiveReconnectMinutes(defaults) })
                assertEquals(15 to 0, hosts.get("bastion")!!.persistence.let { it.effectiveKeepaliveSeconds(defaults) to it.effectiveReconnectMinutes(defaults) })
                assertEquals(15 to 15, hosts.get("nas")!!.persistence.let { it.effectiveKeepaliveSeconds(defaults) to it.effectiveReconnectMinutes(defaults) })
            }
        } finally {
            db.close()
        }
    }

    /**
     * The app opens its database with no driver set ([BerthDatabase.create]), where Room runs a
     * migration over the framework's connection, not the test helper's [AndroidSQLiteDriver]:
     * the version 6 row changes have to run on that path too.
     */
    @Test
    fun `a version 5 database opened the way the app opens it has its known hosts folded`() {
        val file = File.createTempFile("berth-v5-app", ".db").also { it.delete(); it.deleteOnExit() }
        val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), file, AndroidSQLiteDriver(), BerthDatabase::class, { BerthDatabase_Impl() }, emptyList())
        helper.createDatabase(5).use { seed(it, 5) }

        val db = Room.databaseBuilder(RuntimeEnvironment.getApplication(), BerthDatabase::class.java, file.absolutePath).allowMainThreadQueries().build()
        try {
            runTest {
                val knownHosts = RoomKnownHostRepository(db)
                assertEquals(listOf("example.com", "example.com", "nas.local", "prod-api.example.com", "web.example.net"), knownHosts.observeAll().first().map { it.host })
                assertEquals(listOf("k5"), knownHosts.find("NAS.local", 22).map { it.id }, "the pinned row stands, found under the spelling it was saved in")
            }
        } finally {
            db.close()
        }
    }

    private fun migrateAndCheck(from: Int) {
        val file = File.createTempFile("berth-v$from", ".db").also { it.delete(); it.deleteOnExit() }
        val helper = MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            file,
            AndroidSQLiteDriver(),
            BerthDatabase::class,
            { BerthDatabase_Impl() },
            emptyList(),
        )
        helper.createDatabase(from).use { seed(it, from) }
        helper.runMigrationsAndValidate(CURRENT_VERSION, emptyList()).use { checkColumns(it, from) }

        // Open the migrated file the way the app opens its database and read it back through the repositories.
        val db = Room.databaseBuilder(RuntimeEnvironment.getApplication(), BerthDatabase::class.java, file.absolutePath)
            .allowMainThreadQueries()
            .build()
        try {
            runTest { checkRows(db, from) }
        } finally {
            db.close()
        }
    }

    // ---- what the phone holds ----------------------------------------------------------------------

    private val laptopKey = "-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n-----END OPENSSH PRIVATE KEY-----\n".toByteArray()

    private val prodWeb = Host(
        id = "h1", name = "prod web", color = SwatchColor.VERDIGRIS, monogram = "PW", address = "web.example.net", port = 2200, user = "deploy",
        auth = AuthMethod.Key("id-1"), jumpHostIds = listOf("bastion"),
        persistence = PersistencePolicy(keepaliveSeconds = 30, tmux = TmuxMode.ATTACH_OR_CREATE, tmuxSessionName = "web"),
        environment = mapOf("LANG" to "C.UTF-8"), appearance = AppearanceOverride(fontSizeSp = 15), tags = listOf("prod", "web"),
        lastConnectedAt = 5_000, createdAt = 1_000,
    )
    private val bastion = Host(
        id = "bastion", name = "bastion", color = SwatchColor.SLATE, monogram = "BA", address = "bastion.example.net", user = "ops",
        auth = AuthMethod.Password("pw-1"), persistence = PersistencePolicy(reconnectMinutes = 0), startupCommand = "tmux attach", muteBell = true, createdAt = 900,
    )
    /** A host nobody changed: an older build stored the editor's starting 15 s and 15 min, and it reads back inheriting both. */
    private val nas = Host(id = "nas", name = "nas", color = SwatchColor.MOSS, monogram = "NA", address = "nas.local", user = "admin", createdAt = 950)

    /** A quick connect's snapshot, stored without the optional fields; they decode as their defaults. */
    private val quick = Host(id = "quick-1", name = "10.0.0.7", color = SwatchColor.GRAPHITE, monogram = "10", address = "10.0.0.7", user = "root", createdAt = 11)

    private val laptop = Identity(
        id = "id-1", name = "laptop", algorithm = KeyAlgorithm.ED25519, storage = KeyStorage.SOFTWARE_ENCRYPTED, protection = KeyProtection.NONE,
        publicKeyOpenSsh = "ssh-ed25519 AAAA laptop", fingerprintSha256 = "SHA256:abc", createdAt = 1,
    )
    private val phone = Identity(
        id = "id-2", name = "phone", algorithm = KeyAlgorithm.ECDSA_P256, storage = KeyStorage.ANDROID_KEYSTORE, protection = KeyProtection.BIOMETRIC,
        publicKeyOpenSsh = "ecdsa-sha2-nistp256 BBBB phone", fingerprintSha256 = "SHA256:def", keystoreAlias = "berth.identity.id-2", createdAt = 2,
    )

    private val home = Workspace(id = Workspace.DEFAULT_ID, name = "Home", color = SwatchColor.OCHRE, monogram = "HO", sortOrder = 0, reconnectAtLaunch = true, createdAt = 1)
    private val work = Workspace(id = "w2", name = "Work", color = SwatchColor.SLATE, monogram = "WK", accentRgb = 0x4E6E9E, sortOrder = 1, createdAt = 2)

    private val deploySession = SessionRecord(
        id = "s1", workspaceId = Workspace.DEFAULT_ID, hostId = "h1", hostSnapshot = prodWeb, state = SessionState.DETACHED,
        title = "deploy@web: ~", cwd = "/home/deploy", lastCommand = "ls", sortOrder = 0, createdAt = 10, lastLiveAt = 20,
    )
    private val quickSession = SessionRecord(
        id = "s2", workspaceId = "w2", hostId = null, hostSnapshot = quick, state = SessionState.FAILED,
        title = "root@10.0.0.7", needsAttention = true, attentionReason = "Connection refused", sortOrder = 0, createdAt = 11,
    )
    private val nasSession = SessionRecord(
        id = "s3", workspaceId = Workspace.DEFAULT_ID, hostId = "nas", hostSnapshot = nas, state = SessionState.DETACHED,
        title = "admin@nas: ~", sortOrder = 1, createdAt = 12,
    )
    private val frame = byteArrayOf(1, 2, 3, 4)

    private val webTunnel = Tunnel(id = "t1", hostId = "h1", type = TunnelType.LOCAL, bindPort = 8080, destinationHost = "localhost", destinationPort = 80)
    private val socks = Tunnel(id = "t2", hostId = "bastion", type = TunnelType.DYNAMIC, bindAddress = "0.0.0.0", bindPort = 1080, enabled = false)

    private val disk = Snippet(id = "sn1", name = "disk", body = "df -h", tags = listOf("ops"), pinnedToDeck = true)
    private val tail = Snippet(id = "sn2", name = "tail", body = "tail -f {{file}}", hostId = "h1", defaultAction = SnippetAction.PASTE, runOnConnect = true)

    private val deck = DeckLayout(
        rows = 2, reach = DeckReach.LEFT, heightDp = 48, arrows = DeckArrows.BOTH,
        layers = listOf(
            DeckLayer(
                "Mine",
                listOf(DeckKey(tap = DeckAction.Key(DeckKeyCode.ESC)), DeckKey(tap = DeckAction.Text("ls -la\n"), display = "ls"), DeckKey(nub = true)),
            ),
        ),
    )
    private val slate = InterfaceTheme(
        variant = InterfaceVariant.LIGHT, tone = 0.85f, accent = 0x7A9CD6, contrast = InterfaceContrast.HIGH, density = Density.COMPACT,
        useSystemFont = true, radiusScale = 0.7f,
    )
    private val fira = TerminalFont(family = "Fira Code", sizeSp = 15, lineHeight = 1.3f, ligatures = false, boldAsBright = true, cursorShape = "bar", cursorBlink = true)
    private val filesPrefs = FilesPrefs(sort = FilesSort.SIZE, ascending = false, showHidden = true, recentPaths = mapOf("h1" to listOf("/var/log", "/home/deploy")))

    // ---- the rows as the older build wrote them ------------------------------------------------------

    /** Version 1's columns; version 2's additions are nullable or defaulted, so the same rows land in either schema. */
    private fun seed(connection: SQLiteConnection, version: Int) = with(connection) {
        insert(
            "hosts",
            "id" to "h1", "name" to "prod web", "color" to "VERDIGRIS", "monogram" to "PW", "address" to "web.example.net", "port" to 2200, "user" to "deploy",
            "authJson" to """{"type":"app.berth.domain.model.AuthMethod.Key","identityId":"id-1"}""",
            "jumpHostIdsJson" to """["bastion"]""",
            "persistenceJson" to """{"keepaliveSeconds":30,"reconnectMinutes":15,"tmux":"ATTACH_OR_CREATE","tmuxSessionName":"web","tmuxPrefix":"C-b","transport":"SSH"}""",
            "startupCommand" to null, "environmentJson" to """{"LANG":"C.UTF-8"}""", "terminalType" to "xterm-256color",
            "agentForwarding" to false, "compression" to false, "addressFamily" to "AUTO",
            "appearanceJson" to """{"terminalThemeId":null,"fontSizeSp":15,"fontFamily":null}""",
            "tagsJson" to """["prod","web"]""", "muteBell" to false, "lastConnectedAt" to 5_000, "createdAt" to 1_000,
        )
        insert(
            "hosts",
            "id" to "bastion", "name" to "bastion", "color" to "SLATE", "monogram" to "BA", "address" to "bastion.example.net", "port" to 22, "user" to "ops",
            "authJson" to """{"type":"app.berth.domain.model.AuthMethod.Password","secretId":"pw-1"}""",
            "jumpHostIdsJson" to "[]",
            "persistenceJson" to """{"keepaliveSeconds":15,"reconnectMinutes":0,"tmux":"OFF","tmuxSessionName":null,"tmuxPrefix":"C-b","transport":"SSH"}""",
            "startupCommand" to "tmux attach", "environmentJson" to "{}", "terminalType" to "xterm-256color",
            "agentForwarding" to false, "compression" to false, "addressFamily" to "AUTO",
            "appearanceJson" to """{"terminalThemeId":null,"fontSizeSp":null,"fontFamily":null}""",
            "tagsJson" to "[]", "muteBell" to true, "lastConnectedAt" to null, "createdAt" to 900,
        )
        insert(
            "hosts",
            "id" to "nas", "name" to "nas", "color" to "MOSS", "monogram" to "NA", "address" to "nas.local", "port" to 22, "user" to "admin",
            "authJson" to """{"type":"app.berth.domain.model.AuthMethod.AskEachTime"}""",
            "jumpHostIdsJson" to "[]",
            "persistenceJson" to """{"keepaliveSeconds":15,"reconnectMinutes":15,"tmux":"OFF","tmuxSessionName":null,"tmuxPrefix":"C-b","transport":"SSH"}""",
            "startupCommand" to null, "environmentJson" to "{}", "terminalType" to "xterm-256color",
            "agentForwarding" to false, "compression" to false, "addressFamily" to "AUTO",
            "appearanceJson" to """{"terminalThemeId":null,"fontSizeSp":null,"fontFamily":null}""",
            "tagsJson" to "[]", "muteBell" to false, "lastConnectedAt" to null, "createdAt" to 950,
        )

        insert(
            "identities",
            "id" to "id-1", "name" to "laptop", "algorithm" to "ED25519", "storage" to "SOFTWARE_ENCRYPTED", "protection" to "NONE",
            "publicKeyOpenSsh" to "ssh-ed25519 AAAA laptop", "fingerprintSha256" to "SHA256:abc", "comment" to "", "keystoreAlias" to null, "createdAt" to 1,
        )
        insert("secrets", "id" to "identity:id-1", "blob" to crypto.encrypt(laptopKey), "updatedAt" to 1)
        insert(
            "identities",
            "id" to "id-2", "name" to "phone", "algorithm" to "ECDSA_P256", "storage" to "ANDROID_KEYSTORE", "protection" to "BIOMETRIC",
            "publicKeyOpenSsh" to "ecdsa-sha2-nistp256 BBBB phone", "fingerprintSha256" to "SHA256:def", "comment" to "", "keystoreAlias" to "berth.identity.id-2", "createdAt" to 2,
        )

        insert(
            "known_hosts",
            "id" to "k1", "host" to "example.com", "port" to 22, "keyType" to "ssh-ed25519", "publicKeyBase64" to "AAAA1", "fingerprintSha256" to "SHA256:1", "firstSeenAt" to 1, "lastSeenAt" to 2,
        )
        insert(
            "known_hosts",
            "id" to "k2", "host" to "web.example.net", "port" to 2200, "keyType" to "ssh-rsa", "publicKeyBase64" to "AAAA2", "fingerprintSha256" to "SHA256:2", "firstSeenAt" to 3, "lastSeenAt" to 4,
        )
        // The names version 6 folds. k3 names k2's endpoint and type in capitals, saved later but seen less recently:
        // k2 stays (and from version 2 is the pinned one besides). k4 stands alone under a capitalised name. k5 and k6
        // are one server typed two ways and trusted twice: k6 is the more recently seen, k5 the pinned from version 2,
        // so which stands turns on the version. k7 is a second key type under k1's endpoint; both stand.
        insert(
            "known_hosts",
            "id" to "k3", "host" to "Web.Example.NET", "port" to 2200, "keyType" to "ssh-rsa", "publicKeyBase64" to "AAAA3", "fingerprintSha256" to "SHA256:3", "firstSeenAt" to 5, "lastSeenAt" to 3,
        )
        insert(
            "known_hosts",
            "id" to "k4", "host" to "Prod-API.example.com", "port" to 22, "keyType" to "ssh-ed25519", "publicKeyBase64" to "AAAA4", "fingerprintSha256" to "SHA256:4", "firstSeenAt" to 6, "lastSeenAt" to 7,
        )
        insert(
            "known_hosts",
            "id" to "k5", "host" to "NAS.local", "port" to 22, "keyType" to "ssh-ed25519", "publicKeyBase64" to "AAAA5", "fingerprintSha256" to "SHA256:5", "firstSeenAt" to 8, "lastSeenAt" to 10,
        )
        insert(
            "known_hosts",
            "id" to "k6", "host" to "nas.local", "port" to 22, "keyType" to "ssh-ed25519", "publicKeyBase64" to "AAAA6", "fingerprintSha256" to "SHA256:6", "firstSeenAt" to 9, "lastSeenAt" to 20,
        )
        insert(
            "known_hosts",
            "id" to "k7", "host" to "EXAMPLE.com", "port" to 22, "keyType" to "ssh-rsa", "publicKeyBase64" to "AAAA7", "fingerprintSha256" to "SHA256:7", "firstSeenAt" to 11, "lastSeenAt" to 12,
        )

        insert(
            "workspaces",
            "id" to Workspace.DEFAULT_ID, "name" to "Home", "color" to "OCHRE", "monogram" to "HO", "accentRgb" to null, "sortOrder" to 0, "reconnectAtLaunch" to true, "createdAt" to 1,
        )
        insert(
            "workspaces",
            "id" to "w2", "name" to "Work", "color" to "SLATE", "monogram" to "WK", "accentRgb" to 0x4E6E9E, "sortOrder" to 1, "reconnectAtLaunch" to false, "createdAt" to 2,
        )

        insert(
            "sessions",
            "id" to "s1", "workspaceId" to Workspace.DEFAULT_ID, "hostId" to "h1",
            "hostSnapshotJson" to """{"id":"h1","name":"prod web","color":"VERDIGRIS","monogram":"PW","address":"web.example.net","port":2200,"user":"deploy",""" +
                """"auth":{"type":"app.berth.domain.model.AuthMethod.Key","identityId":"id-1"},"jumpHostIds":["bastion"],""" +
                """"persistence":{"keepaliveSeconds":30,"reconnectMinutes":15,"tmux":"ATTACH_OR_CREATE","tmuxSessionName":"web","tmuxPrefix":"C-b","transport":"SSH"},""" +
                """"startupCommand":null,"environment":{"LANG":"C.UTF-8"},"terminalType":"xterm-256color","agentForwarding":false,"compression":false,"addressFamily":"AUTO",""" +
                """"appearance":{"terminalThemeId":null,"fontSizeSp":15,"fontFamily":null},"tags":["prod","web"],"muteBell":false,"lastConnectedAt":5000,"createdAt":1000}""",
            "state" to "DETACHED", "layer" to "LOCAL_FRAME", "title" to "deploy@web: ~", "cwd" to "/home/deploy", "lastCommand" to "ls",
            "needsAttention" to false, "attentionReason" to null, "sortOrder" to 0, "createdAt" to 10, "lastLiveAt" to 20, "frameKey" to null,
        )
        insert("session_frames", "sessionId" to "s1", "frame" to frame, "savedAt" to 21)
        insert(
            "sessions",
            "id" to "s2", "workspaceId" to "w2", "hostId" to null,
            "hostSnapshotJson" to """{"id":"quick-1","name":"10.0.0.7","color":"GRAPHITE","monogram":"10","address":"10.0.0.7","user":"root","auth":{"type":"app.berth.domain.model.AuthMethod.AskEachTime"},"createdAt":11}""",
            "state" to "FAILED", "layer" to "LOCAL_FRAME", "title" to "root@10.0.0.7", "cwd" to null, "lastCommand" to null,
            "needsAttention" to true, "attentionReason" to "Connection refused", "sortOrder" to 0, "createdAt" to 11, "lastLiveAt" to null, "frameKey" to null,
        )
        insert(
            "sessions",
            "id" to "s3", "workspaceId" to Workspace.DEFAULT_ID, "hostId" to "nas",
            "hostSnapshotJson" to """{"id":"nas","name":"nas","color":"MOSS","monogram":"NA","address":"nas.local","port":22,"user":"admin",""" +
                """"auth":{"type":"app.berth.domain.model.AuthMethod.AskEachTime"},"jumpHostIds":[],""" +
                """"persistence":{"keepaliveSeconds":15,"reconnectMinutes":15,"tmux":"OFF","tmuxSessionName":null,"tmuxPrefix":"C-b","transport":"SSH"},""" +
                """"startupCommand":null,"environment":{},"terminalType":"xterm-256color","agentForwarding":false,"compression":false,"addressFamily":"AUTO",""" +
                """"appearance":{"terminalThemeId":null,"fontSizeSp":null,"fontFamily":null},"tags":[],"muteBell":false,"lastConnectedAt":null,"createdAt":950}""",
            "state" to "DETACHED", "layer" to "LOCAL_FRAME", "title" to "admin@nas: ~", "cwd" to null, "lastCommand" to null,
            "needsAttention" to false, "attentionReason" to null, "sortOrder" to 1, "createdAt" to 12, "lastLiveAt" to null, "frameKey" to null,
        )

        insert(
            "tunnels",
            "id" to "t1", "hostId" to "h1", "type" to "LOCAL", "bindAddress" to "127.0.0.1", "bindPort" to 8080, "destinationHost" to "localhost", "destinationPort" to 80, "enabled" to true,
        )
        insert(
            "tunnels",
            "id" to "t2", "hostId" to "bastion", "type" to "DYNAMIC", "bindAddress" to "0.0.0.0", "bindPort" to 1080, "destinationHost" to "localhost", "destinationPort" to 0, "enabled" to false,
        )

        insert(
            "snippets",
            "id" to "sn1", "name" to "disk", "body" to "df -h", "hostId" to null, "tagsJson" to """["ops"]""", "defaultAction" to "RUN", "runOnConnect" to false, "pinnedToDeck" to true,
        )
        insert(
            "snippets",
            "id" to "sn2", "name" to "tail", "body" to "tail -f {{file}}", "hostId" to "h1", "tagsJson" to "[]", "defaultAction" to "PASTE", "runOnConnect" to true, "pinnedToDeck" to false,
        )

        // The settings an older build kept, as it encoded them: nothing about tabs yet.
        val settings = listOf(
            "deck_layout" to """{"version":1,"rows":2,"reach":"LEFT","height_dp":48,"arrows":"BOTH","layers":[{"name":"Mine","keys":[""" +
                """{"tap":{"type":"key","key":"ESC"},"up":null,"down":null,"hold":null,"display":null,"nub":false,"snippets":false},""" +
                """{"tap":{"type":"text","text":"ls -la\n"},"up":null,"down":null,"hold":null,"display":"ls","nub":false,"snippets":false},""" +
                """{"tap":null,"up":null,"down":null,"hold":null,"display":null,"nub":true,"snippets":false}],"prefix":null}]}""",
            "haptic_level" to "\"SUBTLE\"",
            "interface_theme" to """{"variant":"LIGHT","tone":0.85,"accent":"#7a9cd6","materialYou":false,"contrast":"HIGH","density":"COMPACT","useSystemFont":true,"radiusScale":0.7}""",
            "terminal_font" to """{"family":"Fira Code","sizeSp":15,"lineHeight":1.3,"ligatures":false,"nerdFontFallback":true,"boldAsBright":true,"cursorShape":"bar","cursorBlink":true}""",
            "terminal_theme_default" to "\"berth-light\"",
            "last_active_session" to "s2",
            "current_workspace" to "w2",
        )
        for ((key, value) in settings) insert("preferences", "key" to key, "value" to value, "updatedAt" to 3)

        if (version >= 2) {
            // Version 2's columns and the Files document, holding what a phone on that build could have set.
            execSQL("UPDATE known_hosts SET pinned = 1 WHERE id IN ('k2', 'k5')")
            execSQL("UPDATE workspaces SET terminalThemeId = 'berth-light' WHERE id = 'w2'")
            execSQL("UPDATE snippets SET workspaceId = 'w2' WHERE id = 'sn2'")
            insert(
                "preferences",
                "key" to "files_prefs", "value" to """{"sort":"size","ascending":false,"show_hidden":true,"recent_paths":{"h1":["/var/log","/home/deploy"]}}""", "updatedAt" to 4,
            )
        }

        if (version >= 3) {
            // Version 3's columns: a renamed tab and a collapsed group, as a phone on that build could have set them.
            execSQL("UPDATE sessions SET customTitle = 'web box' WHERE id = 's1'")
            execSQL("UPDATE workspaces SET collapsed = 1 WHERE id = 'w2'")
        }

        if (version >= 4) {
            // Version 4's column: the bastion marked tunnels only, as a phone on that build could have set it.
            execSQL("UPDATE hosts SET tunnelsOnly = 1 WHERE id = 'bastion'")
        }

        if (version >= 6) {
            // A version 6 phone holds its known hosts as its own migration left them: lowercase, one row per endpoint and key type.
            execSQL("DELETE FROM known_hosts WHERE id IN ('k3', 'k6')")
            execSQL("UPDATE known_hosts SET host = LOWER(host)")
        }

        if (version >= 7) {
            // A version 7 phone holds its policies as its own migration left them, a stored 15 in either field null; the
            // bastion's Keepalive was chosen as 15 on that build since, the host's own value and not the editor's start.
            for ((table, column) in listOf("hosts" to "persistenceJson", "sessions" to "hostSnapshotJson")) {
                execSQL(
                    "UPDATE $table SET $column = REPLACE(REPLACE($column, '\"keepaliveSeconds\":15,', '\"keepaliveSeconds\":null,'), " +
                        "'\"reconnectMinutes\":15,', '\"reconnectMinutes\":null,')",
                )
            }
            execSQL("UPDATE hosts SET persistenceJson = REPLACE(persistenceJson, '\"keepaliveSeconds\":null,', '\"keepaliveSeconds\":15,') WHERE id = 'bastion'")
        }
    }

    // ---- after the migration -----------------------------------------------------------------------

    /** The columns themselves: rows counted, the added columns holding their defaults or the values version 2 set. */
    private fun checkColumns(connection: SQLiteConnection, from: Int) = with(connection) {
        assertEquals(CURRENT_VERSION.toLong(), long("PRAGMA user_version"))
        val counts = mapOf(
            // Version 6 folds seven known hosts to five: k3 goes under k2, and one of k5 and k6 under the other.
            "hosts" to 3, "identities" to 2, "secrets" to 1, "known_hosts" to 5, "workspaces" to 2, "sessions" to 3,
            "session_frames" to 1, "tunnels" to 2, "snippets" to 2, "preferences" to if (from >= 2) 8 else 7,
            // Version 5: the history table arrives empty; the commands an older build kept in each tab's frame reach it as the frames are restored.
            "command_history" to 0,
        )
        for ((table, rows) in counts) assertEquals(rows.toLong(), long("SELECT COUNT(*) FROM $table"), "$table keeps its rows")

        // Version 7: a stored 15 in either field is inherit now (null); anything else stays the host's own, and the rest of the document is as it was.
        assertEquals(
            """{"keepaliveSeconds":30,"reconnectMinutes":null,"tmux":"ATTACH_OR_CREATE","tmuxSessionName":"web","tmuxPrefix":"C-b","transport":"SSH"}""",
            text("SELECT persistenceJson FROM hosts WHERE id = 'h1'"),
        )
        assertEquals(
            """{"keepaliveSeconds":${if (from >= 7) 15 else null},"reconnectMinutes":0,"tmux":"OFF","tmuxSessionName":null,"tmuxPrefix":"C-b","transport":"SSH"}""",
            text("SELECT persistenceJson FROM hosts WHERE id = 'bastion'"),
            "a 15 chosen on a version 7 phone is not folded a second time",
        )
        assertEquals(
            """{"keepaliveSeconds":null,"reconnectMinutes":null,"tmux":"OFF","tmuxSessionName":null,"tmuxPrefix":"C-b","transport":"SSH"}""",
            text("SELECT persistenceJson FROM hosts WHERE id = 'nas'"),
            "a host left at the editor's 15 s and 15 min inherits both",
        )
        assertTrue(
            text("SELECT hostSnapshotJson FROM sessions WHERE id = 's3'")!!.contains(
                """"persistence":{"keepaliveSeconds":null,"reconnectMinutes":null,"tmux":"OFF","tmuxSessionName":null,"tmuxPrefix":"C-b","transport":"SSH"},""",
            ),
            "and so does its tab's snapshot",
        )
        assertTrue(
            text("SELECT hostSnapshotJson FROM sessions WHERE id = 's1'")!!.contains(
                """"persistence":{"keepaliveSeconds":30,"reconnectMinutes":null,"tmux":"ATTACH_OR_CREATE","tmuxSessionName":"web","tmuxPrefix":"C-b","transport":"SSH"},""",
            ),
            "the detached tab's snapshot folds as its host does",
        )
        assertEquals(
            """{"id":"quick-1","name":"10.0.0.7","color":"GRAPHITE","monogram":"10","address":"10.0.0.7","user":"root","auth":{"type":"app.berth.domain.model.AuthMethod.AskEachTime"},"createdAt":11}""",
            text("SELECT hostSnapshotJson FROM sessions WHERE id = 's2'"),
            "a snapshot with no policy already inherits and is not rewritten",
        )

        // Version 8: every old host follows Settings' scrollback and offers the client's own ciphers until its editor says otherwise.
        assertEquals(3L, long("SELECT COUNT(*) FROM hosts WHERE scrollbackLines IS NULL AND ciphersJson = '[]'"))

        // Version 6: every known host's name is lowercase, and one row stands per endpoint and key type. k3, seen less
        // recently than k2 though saved later, went; k5 and k6 turn on the pin version 2 brought: with it, the pinned
        // k5 stands over the more recently seen k6; without it, k6 does. k7 stands beside k1 as a second key type.
        assertEquals(0L, long("SELECT COUNT(*) FROM known_hosts WHERE host != LOWER(host)"))
        assertEquals(0L, long("SELECT COUNT(*) FROM known_hosts WHERE id = 'k3'"))
        assertEquals("prod-api.example.com", text("SELECT host FROM known_hosts WHERE id = 'k4'"))
        assertEquals(if (from >= 2) "k5" else "k6", text("SELECT id FROM known_hosts WHERE host = 'nas.local'"))
        assertEquals(if (from >= 2) 1L else 0L, long("SELECT pinned FROM known_hosts WHERE host = 'nas.local'"))
        assertEquals("k1,k7", text("SELECT GROUP_CONCAT(id) FROM (SELECT id FROM known_hosts WHERE host = 'example.com' AND port = 22 ORDER BY id)"))
        assertEquals(1L, long("SELECT COUNT(*) FROM known_hosts WHERE host = 'example.com' AND port = 22 AND keyType = 'ssh-rsa'"))

        // Version 5: the table takes a row keyed by host and its index is there to be used.
        execSQL("INSERT INTO command_history (hostId, text, at) VALUES ('h1', 'ls', 7)")
        assertEquals(1L, long("SELECT COUNT(*) FROM command_history WHERE hostId = 'h1' AND at = 7"))
        assertEquals(1L, long("SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = 'index_command_history_hostId_at'"))
        execSQL("DELETE FROM command_history")

        // Version 4: every old host opens a terminal on Connect; the tunnels-only toggle is off until set, and stays set where a version 4 phone set it.
        assertEquals(if (from >= 4) 2L else 3L, long("SELECT COUNT(*) FROM hosts WHERE tunnelsOnly = 0"))
        assertEquals(0L, long("SELECT tunnelsOnly FROM hosts WHERE id = 'h1'"))
        assertEquals(if (from >= 4) 1L else 0L, long("SELECT tunnelsOnly FROM hosts WHERE id = 'bastion'"))

        // Version 3: every old tab is an SSH tab; the rename and the collapsed group a version 3 phone set survive.
        assertEquals(3L, long("SELECT COUNT(*) FROM sessions WHERE kind = 'ssh'"))
        assertEquals(if (from >= 3) "web box" else null, text("SELECT customTitle FROM sessions WHERE id = 's1'"))
        assertNull(text("SELECT customTitle FROM sessions WHERE id = 's2'"))
        assertEquals(0L, long("SELECT collapsed FROM workspaces WHERE id = '${Workspace.DEFAULT_ID}'"))
        assertEquals(if (from >= 3) 1L else 0L, long("SELECT collapsed FROM workspaces WHERE id = 'w2'"))

        // Version 2: defaults when coming from version 1, the values a version 2 phone set otherwise.
        assertEquals(0L, long("SELECT pinned FROM known_hosts WHERE id = 'k1'"))
        assertEquals(if (from >= 2) 1L else 0L, long("SELECT pinned FROM known_hosts WHERE id = 'k2'"))
        assertNull(text("SELECT terminalThemeId FROM workspaces WHERE id = '${Workspace.DEFAULT_ID}'"))
        assertEquals(if (from >= 2) "berth-light" else null, text("SELECT terminalThemeId FROM workspaces WHERE id = 'w2'"))
        assertNull(text("SELECT workspaceId FROM snippets WHERE id = 'sn1'"))
        assertEquals(if (from >= 2) "w2" else null, text("SELECT workspaceId FROM snippets WHERE id = 'sn2'"))
    }

    /** The rows as the app sees them: the repositories map every one back to the object the older build stored. */
    private suspend fun checkRows(db: BerthDatabase, from: Int) {
        val hosts = RoomHostRepository(db)
        assertEquals(prodWeb, hosts.get("h1"))
        assertEquals(
            bastion.copy(tunnelsOnly = from >= 4, persistence = bastion.persistence.copy(keepaliveSeconds = if (from >= 7) 15 else null)),
            hosts.get("bastion"),
        )
        assertEquals(nas, hosts.get("nas"))
        assertEquals(listOf("bastion", "nas", "h1"), hosts.observeAll().first().map { it.id })

        val identities = RoomIdentityRepository(db, EncryptedSecretStore(db, crypto), HardwareKeys(RuntimeEnvironment.getApplication()))
        assertEquals(laptop, identities.get("id-1"))
        assertContentEquals(laptopKey, identities.privateKey("id-1"))
        assertEquals(phone, identities.get("id-2"))
        assertNull(identities.privateKey("id-2"))
        assertEquals(listOf("h1"), identities.hostsUsing("id-1").map { it.id })

        val knownHosts = RoomKnownHostRepository(db)
        assertEquals(
            listOf(KnownHostKey("k1", "example.com", 22, "ssh-ed25519", "AAAA1", "SHA256:1", 1, 2), KnownHostKey("k7", "example.com", 22, "ssh-rsa", "AAAA7", "SHA256:7", 11, 12)),
            knownHosts.find("example.com", 22).sortedBy { it.id },
        )
        assertEquals(listOf(KnownHostKey("k2", "web.example.net", 2200, "ssh-rsa", "AAAA2", "SHA256:2", 3, 4, pinned = from >= 2)), knownHosts.find("web.example.net", 2200))
        // Version 6: the address as typed into the editor finds the row whatever its case, and the row reads back lowercase.
        assertEquals(listOf(KnownHostKey("k2", "web.example.net", 2200, "ssh-rsa", "AAAA2", "SHA256:2", 3, 4, pinned = from >= 2)), knownHosts.find("Web.Example.NET", 2200))
        assertEquals(listOf(KnownHostKey("k4", "prod-api.example.com", 22, "ssh-ed25519", "AAAA4", "SHA256:4", 6, 7)), knownHosts.find("Prod-API.example.com", 22))
        assertEquals(
            if (from >= 2) listOf(KnownHostKey("k5", "nas.local", 22, "ssh-ed25519", "AAAA5", "SHA256:5", 8, 10, pinned = true)) else listOf(KnownHostKey("k6", "nas.local", 22, "ssh-ed25519", "AAAA6", "SHA256:6", 9, 20)),
            knownHosts.find("NAS.local", 22),
        )

        val workspaces = RoomWorkspaceRepository(db)
        assertEquals(home, workspaces.ensureDefault(), "launch finds the existing default group instead of making another")
        assertEquals(
            listOf(home, work.copy(terminalThemeId = if (from >= 2) "berth-light" else null, collapsed = from >= 3)),
            workspaces.observeAll().first(),
        )

        val sessions = RoomSessionRepository(db)
        val records = sessions.getAll()
        val renamed = deploySession.copy(customTitle = if (from >= 3) "web box" else null)
        assertEquals(listOf(renamed, quickSession, nasSession), records)
        assertTrue(records.all { it.kind == TabKind.Ssh })
        assertEquals(if (from >= 3) "web box" else "deploy@web: ~", records.first().displayTitle)
        assertEquals(if (from >= 4) listOf("bastion") else emptyList(), hosts.observeAll().first().filter { it.tunnelsOnly }.map { it.id }, "only a host a version 4 phone marked opens tunnels only")
        assertContentEquals(frame, sessions.loadFrame("s1"))
        assertNull(sessions.loadFrame("s2"))

        // Version 5: the migrated database takes and hands back a host's commands through the repository, empty until then.
        val history = RoomCommandHistoryRepository(db)
        assertEquals(emptyList(), history.observeForHost("h1").first())
        assertEquals(emptyList(), history.observeAll().first())
        assertTrue(history.record("h1", "ls -la", 100))
        assertTrue(history.record("bastion", "uptime", 101))
        assertFalse(history.record("h1", "ls -la", 102), "a repeat of the host's latest is one entry")
        assertEquals(listOf("ls -la"), history.observeForHost("h1").first().map { it.text })
        assertEquals(listOf("ls -la", "uptime"), history.observeAll().first().map { it.text })
        history.clearAll()
        assertEquals(emptyList(), history.observeAll().first())

        val tunnels = RoomTunnelRepository(db)
        assertEquals(listOf(webTunnel), tunnels.observeForHost("h1").first())
        assertEquals(socks, tunnels.get("t2"))

        val snippets = RoomSnippetRepository(db)
        assertEquals(listOf(disk, tail.copy(workspaceId = if (from >= 2) "w2" else null)), snippets.observeAll().first())

        val settings = RoomSettingsRepository(db)
        assertEquals(deck, settings.deckLayout.first())
        assertEquals(HapticLevel.SUBTLE, settings.hapticLevel.first())
        assertEquals(slate, settings.interfaceTheme.first())
        assertEquals(fira, settings.terminalFont.first())
        assertEquals(TerminalTheme.BERTH_LIGHT_ID, settings.defaultTerminalThemeId.first())
        assertEquals(TerminalTheme.builtIns, settings.terminalThemes.first())
        assertEquals("s2", settings.lastActiveSessionId.first())
        assertEquals("w2", settings.currentWorkspaceId.first())
        assertEquals(if (from >= 2) filesPrefs else FilesPrefs(), settings.filesPrefs.first())
        // The tab settings this build added are not in the older document and read as their defaults.
        assertEquals(TabSwipeGesture.TWO_FINGER, settings.tabSwipeGesture.first())
        assertFalse(settings.ctrlTabKeysReachTerminal.first())
        // Version 5's Stage documents: no older build wrote a split or moved the divider, so one tab has the Stage at half.
        assertNull(settings.stageSplit.first())
        assertEquals(0.5f, settings.paneDividerFraction.first())
        // Settings › Connection is new with this build too: idle sessions are kept, and neither one-time notice has been shown.
        assertEquals(ConnectionSettings(), settings.connectionSettings.first())

        // Version 8: the migrated table keeps a host's own scrollback cap and cipher list, and hands them back as set.
        val tuned = prodWeb.copy(scrollbackLines = 50_000, ciphers = listOf("aes256-gcm@openssh.com", "aes256-ctr"))
        hosts.upsert(tuned)
        assertEquals(tuned, hosts.get("h1"))
        hosts.upsert(prodWeb)
        assertEquals(prodWeb, hosts.get("h1"))
    }

    // ---- SQL helpers -------------------------------------------------------------------------------

    private fun SQLiteConnection.insert(table: String, vararg columns: Pair<String, Any?>) {
        val sql = "INSERT INTO $table (${columns.joinToString { "`${it.first}`" }}) VALUES (${columns.joinToString { "?" }})"
        prepare(sql).use { statement ->
            columns.forEachIndexed { i, (name, value) ->
                when (value) {
                    null -> statement.bindNull(i + 1)
                    is String -> statement.bindText(i + 1, value)
                    is Int -> statement.bindLong(i + 1, value.toLong())
                    is Long -> statement.bindLong(i + 1, value)
                    is Boolean -> statement.bindLong(i + 1, if (value) 1 else 0)
                    is ByteArray -> statement.bindBlob(i + 1, value)
                    else -> error("no binding for $table.$name = $value")
                }
            }
            statement.step()
        }
    }

    private fun SQLiteConnection.long(sql: String): Long = prepare(sql).use { assertTrue(it.step(), sql); it.getLong(0) }

    private fun SQLiteConnection.text(sql: String): String? = prepare(sql).use { assertTrue(it.step(), sql); if (it.isNull(0)) null else it.getText(0) }

    private companion object {
        /** Keep in step with `@Database(version)` on [BerthDatabase]; the exported `schemas/` JSON for it must exist. */
        const val CURRENT_VERSION = 8
    }
}
