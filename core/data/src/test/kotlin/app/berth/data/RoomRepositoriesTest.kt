package app.berth.data

import app.berth.data.crypto.HardwareKeys
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
import app.berth.domain.model.AuthMethod
import app.berth.domain.model.DeckAction
import app.berth.domain.model.DeckKey
import app.berth.domain.model.DeckLayer
import app.berth.domain.model.DeckLayout
import app.berth.domain.model.Host
import app.berth.domain.model.Identity
import app.berth.domain.model.KeyAlgorithm
import app.berth.domain.model.KeyProtection
import app.berth.domain.model.KeyStorage
import app.berth.domain.model.KnownHostKey
import app.berth.domain.model.PersistenceLayer
import app.berth.domain.model.PersistencePolicy
import app.berth.domain.model.SessionRecord
import app.berth.domain.model.SessionState
import app.berth.domain.model.Snippet
import app.berth.domain.model.StageSide
import app.berth.domain.model.StageSplit
import app.berth.domain.model.SwatchColor
import app.berth.domain.model.TabKind
import app.berth.domain.model.TabSwipeGesture
import app.berth.domain.model.TerminalTheme
import app.berth.domain.model.TmuxMode
import app.berth.domain.model.Tunnel
import app.berth.domain.model.TunnelType
import app.berth.domain.model.Workspace
import app.berth.domain.repository.CommandHistoryRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomRepositoriesTest {
    private lateinit var db: BerthDatabase

    /** XORs with a fixed byte; enough to prove the store never writes plaintext. */
    private val crypto = object : SecretCrypto {
        override fun encrypt(plain: ByteArray) = byteArrayOf(9) + ByteArray(plain.size) { (plain[it].toInt() xor 0x5A).toByte() }
        override fun decrypt(blob: ByteArray) = ByteArray(blob.size - 1) { (blob[it + 1].toInt() xor 0x5A).toByte() }
    }

    @Before
    fun setUp() {
        db = BerthDatabase.inMemory(RuntimeEnvironment.getApplication())
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `hosts round trip including nested documents`() = runTest {
        val repo = RoomHostRepository(db)
        val host = Host(
            id = "h1",
            name = "prod web",
            color = SwatchColor.VERDIGRIS,
            monogram = "PW",
            address = "web.example.net",
            port = 2200,
            user = "deploy",
            auth = AuthMethod.Key("id-1"),
            jumpHostIds = listOf("bastion"),
            persistence = PersistencePolicy(keepaliveSeconds = 30, tmux = TmuxMode.ATTACH_OR_CREATE, tmuxSessionName = "web"),
            environment = mapOf("LANG" to "C.UTF-8"),
            tags = listOf("prod", "web"),
            createdAt = 1_000,
        )
        repo.upsert(host)
        assertEquals(host, repo.get("h1"))
        assertEquals(listOf(host), repo.observeAll().first())

        repo.markConnected("h1", 5_000)
        assertEquals(5_000, repo.get("h1")?.lastConnectedAt)

        repo.upsert(host.copy(auth = AuthMethod.Password("pw-1"), port = 22))
        val updated = assertNotNull(repo.get("h1"))
        assertEquals(AuthMethod.Password("pw-1"), updated.auth)
        assertEquals(22, updated.port)

        repo.delete("h1")
        assertNull(repo.get("h1"))
    }

    @Test
    fun `software identities keep their private key encrypted and hardware ones keep none`() = runTest {
        val secrets = EncryptedSecretStore(db, crypto)
        val repo = RoomIdentityRepository(db, secrets, HardwareKeys(RuntimeEnvironment.getApplication()))
        val software = Identity(
            id = "id-1", name = "laptop", algorithm = KeyAlgorithm.ED25519, storage = KeyStorage.SOFTWARE_ENCRYPTED,
            protection = KeyProtection.NONE, publicKeyOpenSsh = "ssh-ed25519 AAAA laptop", fingerprintSha256 = "SHA256:abc", createdAt = 1,
        )
        val pem = "-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n-----END OPENSSH PRIVATE KEY-----\n".toByteArray()
        repo.insert(software, pem)
        assertContentEquals(pem, repo.privateKey("id-1"))
        val stored = assertNotNull(db.secrets().get(RoomIdentityRepository.secretId("id-1")))
        assertFalse(stored.blob.contentEquals(pem), "secret must not be stored in the clear")

        val hardware = software.copy(id = "id-2", name = "phone", algorithm = KeyAlgorithm.ECDSA_P256, storage = KeyStorage.ANDROID_KEYSTORE, keystoreAlias = "berth.identity.id-2")
        repo.insert(hardware, null)
        assertNull(repo.privateKey("id-2"))
        assertTrue(assertNotNull(repo.get("id-2")).isHardwareBacked)

        val hosts = RoomHostRepository(db)
        hosts.upsert(Host(id = "h1", name = "a", color = SwatchColor.SLATE, monogram = "A", address = "a", user = "u", auth = AuthMethod.Key("id-1"), createdAt = 1))
        hosts.upsert(Host(id = "h2", name = "b", color = SwatchColor.SLATE, monogram = "B", address = "b", user = "u", auth = AuthMethod.Key("id-2"), createdAt = 1))
        hosts.upsert(Host(id = "h3", name = "c", color = SwatchColor.SLATE, monogram = "C", address = "c", user = "u", auth = AuthMethod.Password("id-1"), createdAt = 1))
        assertEquals(listOf("h1"), repo.hostsUsing("id-1").map { it.id })

        repo.delete("id-1")
        assertNull(repo.get("id-1"))
        assertNull(repo.privateKey("id-1"))
    }

    @Test
    fun `known hosts are looked up by endpoint and can be pinned`() = runTest {
        val repo = RoomKnownHostRepository(db)
        repo.upsert(KnownHostKey("k1", "example.com", 22, "ssh-ed25519", "AAAA1", "SHA256:1", 1, 1))
        repo.upsert(KnownHostKey("k2", "example.com", 2222, "ssh-ed25519", "AAAA2", "SHA256:2", 1, 1))
        repo.upsert(KnownHostKey("k3", "example.com", 22, "ssh-rsa", "AAAA3", "SHA256:3", 1, 1, pinned = true))
        assertEquals(setOf("k1", "k3"), repo.find("example.com", 22).map { it.id }.toSet())
        assertEquals(3, repo.observeAll().first().size)
        assertEquals(listOf("k3"), repo.find("example.com", 22).filter { it.pinned }.map { it.id })

        repo.setPinned("k1", true)
        repo.setPinned("k3", false)
        assertEquals(listOf("k1"), repo.find("example.com", 22).filter { it.pinned }.map { it.id })
    }

    @Test
    fun `tunnels and snippets round trip with their scopes`() = runTest {
        val tunnels = RoomTunnelRepository(db)
        val local = Tunnel(id = "t1", hostId = "h1", type = TunnelType.LOCAL, bindPort = 8080, destinationHost = "localhost", destinationPort = 80)
        val socks = Tunnel(id = "t2", hostId = "h2", type = TunnelType.DYNAMIC, bindAddress = "0.0.0.0", bindPort = 1080, enabled = false)
        tunnels.upsert(local)
        tunnels.upsert(socks)
        assertEquals(listOf(local), tunnels.observeForHost("h1").first())
        assertEquals(listOf(local, socks), tunnels.observeAll().first())
        assertEquals(socks, tunnels.get("t2"))
        tunnels.setEnabled("t2", true)
        assertTrue(assertNotNull(tunnels.get("t2")).enabled)
        tunnels.delete("t1")
        assertNull(tunnels.get("t1"))

        val snippets = RoomSnippetRepository(db)
        val global = Snippet(id = "s1", name = "disk", body = "df -h", tags = listOf("ops"))
        val scoped = Snippet(id = "s2", name = "tail", body = "tail -f {{file}}", hostId = "h1", workspaceId = "w1", runOnConnect = true, pinnedToDeck = true)
        snippets.upsert(global)
        snippets.upsert(scoped)
        assertEquals(listOf(global, scoped), snippets.observeAll().first())
        assertEquals("w1", assertNotNull(snippets.get("s2")).workspaceId)
        snippets.delete("s1")
        assertNull(snippets.get("s1"))
    }

    @Test
    fun `tabs round trip their kind, custom title and group state, and reorder in one write`() = runTest {
        val workspaces = RoomWorkspaceRepository(db)
        val home = workspaces.ensureDefault()
        val work = Workspace(id = "w2", name = "Work", color = SwatchColor.SLATE, monogram = "WK", sortOrder = 1, createdAt = 2, collapsed = true)
        workspaces.upsert(work)
        assertEquals(listOf(home, work), workspaces.observeAll().first())
        assertTrue(assertNotNull(workspaces.get("w2")).collapsed)

        val sessions = RoomSessionRepository(db)
        val host = Host(id = "h1", name = "box", color = SwatchColor.OCHRE, monogram = "BO", address = "10.0.2.2", user = "demo", createdAt = 1)
        val a = SessionRecord(id = "a", workspaceId = home.id, hostId = "h1", hostSnapshot = host, state = SessionState.DETACHED, sortOrder = 0, createdAt = 1, customTitle = "deploy")
        // A Tunnels tab on a tunnels-only host behind a jump host: the kind is its own column, the host's schema-4 fields ride the snapshot's JSON.
        val carrier = host.copy(tunnelsOnly = true, jumpHostIds = listOf("bastion"))
        val b = SessionRecord(id = "b", workspaceId = home.id, hostId = "h1", hostSnapshot = carrier, state = SessionState.DETACHED, sortOrder = 1, createdAt = 2, kind = TabKind.Tunnels)
        sessions.upsertAll(listOf(a, b))
        assertEquals(listOf(a, b), sessions.getAll())
        assertEquals("deploy", assertNotNull(sessions.getAll().first()).displayTitle)
        assertEquals(TabKind.Ssh, sessions.getAll().first().kind)
        val restored = sessions.getAll().last()
        assertEquals(TabKind.Tunnels, restored.kind)
        assertTrue(restored.hostSnapshot.tunnelsOnly)
        assertEquals(listOf("bastion"), restored.hostSnapshot.jumpHostIds)

        sessions.upsertAll(listOf(a.copy(sortOrder = 1, workspaceId = "w2"), b.copy(sortOrder = 0)))
        assertEquals(listOf("b", "a"), sessions.getAll().map { it.id })
        assertEquals("w2", sessions.getAll().last().workspaceId)

        workspaces.upsertAll(listOf(home.copy(sortOrder = 1), work.copy(sortOrder = 0, collapsed = false)))
        assertEquals(listOf("w2", home.id), workspaces.observeAll().first().map { it.id })
    }

    @Test
    fun `default workspace is created once and sessions carry their host snapshot and frame`() = runTest {
        val workspaces = RoomWorkspaceRepository(db)
        val first = workspaces.ensureDefault()
        assertEquals(Workspace.DEFAULT_ID, first.id)
        assertEquals(first, workspaces.ensureDefault())
        assertEquals(1, workspaces.observeAll().first().size)

        val sessions = RoomSessionRepository(db)
        // The snapshot is stored as JSON, so the chain rides the record as a list, not a column.
        val host = Host(id = "h1", name = "box", color = SwatchColor.OCHRE, monogram = "BO", address = "10.0.2.2", port = 2222, user = "demo", jumpHostIds = listOf("bastion", "edge"), createdAt = 1)
        val record = SessionRecord(
            id = "s1", workspaceId = first.id, hostId = "h1", hostSnapshot = host, state = SessionState.DETACHED,
            layer = PersistenceLayer.LOCAL_FRAME, title = "demo@box: ~", cwd = "/home/demo", createdAt = 2, lastLiveAt = 3,
        )
        sessions.upsert(record)
        sessions.saveFrame("s1", byteArrayOf(1, 2, 3))
        assertEquals(listOf(record), sessions.getAll())
        assertContentEquals(byteArrayOf(1, 2, 3), sessions.loadFrame("s1"))

        sessions.delete("s1")
        assertTrue(sessions.getAll().isEmpty())
        assertNull(sessions.loadFrame("s1"))
    }

    @Test
    fun `settings fall back to defaults and observe edits`() = runTest {
        val settings = RoomSettingsRepository(db)
        assertEquals(DeckLayout.default(), settings.deckLayout.first())
        assertEquals(TerminalTheme.BERTH_DARK_ID, settings.defaultTerminalThemeId.first())
        assertEquals(TerminalTheme.builtIns, settings.terminalThemes.first())

        val custom = DeckLayout(layers = listOf(DeckLayer("Only", listOf(DeckKey(tap = DeckAction.Text("x"))))))
        settings.setDeckLayout(custom)
        assertEquals(custom, settings.deckLayout.first())

        val theme = TerminalTheme.BERTH_LIGHT.copy(id = "mine", name = "Mine", builtIn = true)
        settings.upsertTerminalTheme(theme)
        val themes = settings.terminalThemes.first()
        assertEquals(TerminalTheme.builtIns.size + 1, themes.size)
        assertFalse(themes.last().builtIn, "custom themes can never claim to be built in")

        settings.setDefaultTerminalTheme("mine")
        assertEquals("mine", settings.defaultTerminalThemeId.first())

        settings.deleteTerminalTheme("mine")
        assertEquals(TerminalTheme.builtIns, settings.terminalThemes.first())
        settings.deleteTerminalTheme(TerminalTheme.BERTH_LIGHT_ID)
        assertEquals(TerminalTheme.builtIns, settings.terminalThemes.first(), "stock themes cannot be deleted")

        assertNull(settings.lastActiveSessionId.first())
        settings.setLastActiveSessionId("s1")
        assertEquals("s1", settings.lastActiveSessionId.first())
        settings.setLastActiveSessionId(null)
        assertNull(settings.lastActiveSessionId.first())

        assertEquals(TabSwipeGesture.TWO_FINGER, settings.tabSwipeGesture.first())
        settings.setTabSwipeGesture(TabSwipeGesture.RIGHT_EDGE)
        assertEquals(TabSwipeGesture.RIGHT_EDGE, settings.tabSwipeGesture.first())
        assertFalse(settings.ctrlTabKeysReachTerminal.first(), "Ctrl+T and Ctrl+W are tab shortcuts by default")
        settings.setCtrlTabKeysReachTerminal(true)
        assertTrue(settings.ctrlTabKeysReachTerminal.first())
    }

    @Test
    fun `the split and the divider are kept beside the active tab, the split cleared as one tab takes the Stage`() = runTest {
        val settings = RoomSettingsRepository(db)
        assertNull(settings.stageSplit.first(), "one tab has the Stage until a split is written")
        assertEquals(0.5f, settings.paneDividerFraction.first(), "the panes start at half each")

        settings.setStageSplit(StageSplit("s-companion", StageSide.LEFT))
        assertEquals(StageSplit("s-companion", StageSide.LEFT), settings.stageSplit.first())
        settings.setStageSplit(StageSplit("s-other", StageSide.RIGHT))
        assertEquals(StageSplit("s-other", StageSide.RIGHT), settings.stageSplit.first())
        settings.setStageSplit(null)
        assertNull(settings.stageSplit.first())
        assertNull(db.preferences().get(RoomSettingsRepository.KEY_STAGE_SPLIT), "cleared is deleted, not written as null")

        settings.setPaneDividerFraction(1f / 3f)
        assertEquals(1f / 3f, settings.paneDividerFraction.first())
        assertEquals(1f / 3f, settings.paneDividerFraction.first(), "the divider's rest outlives the split")
    }

    // ---- command history (spec C16) -------------------------------------------------------------

    @Test
    fun `command history is per host, oldest first, a repeat of the latest folded and the cap kept`() = runTest {
        val history = RoomCommandHistoryRepository(db)
        assertTrue(history.record("h1", "  ls -la ", 1))
        assertTrue(history.record("h2", "uptime", 2))
        assertFalse(history.record("h1", "ls -la", 3), "the host's latest again is one entry")
        assertFalse(history.record("h1", "   ", 4), "a blank is nothing")
        assertTrue(history.record("h1", "pwd", 5))
        assertTrue(history.record("h1", "ls -la", 6), "the same command after another is a new entry")
        assertEquals(listOf("ls -la" to 1L, "pwd" to 5L, "ls -la" to 6L), history.observeForHost("h1").first().map { it.text to it.at })
        assertEquals(listOf("uptime"), history.observeForHost("h2").first().map { it.text })
        assertEquals(listOf("ls -la", "uptime", "pwd", "ls -la"), history.observeAll().first().map { it.text })

        // Past the cap the oldest go, one host's cap never touching another's.
        for (i in 0 until CommandHistoryRepository.CAP + 10) history.record("h3", "cmd $i", 100L + i)
        val h3 = history.observeForHost("h3").first()
        assertEquals(CommandHistoryRepository.CAP, h3.size)
        assertEquals("cmd 10", h3.first().text)
        assertEquals("cmd ${CommandHistoryRepository.CAP + 9}", h3.last().text)
        assertEquals(3, history.observeForHost("h1").first().size)
        assertEquals(CommandHistoryRepository.CAP, history.observeAll().first().size, "All hosts shows the newest up to the cap")

        // Delete one row, not every equal command; clear one host; clear all.
        val first = history.observeForHost("h1").first().first()
        history.delete(first.id)
        assertEquals(listOf("pwd", "ls -la"), history.observeForHost("h1").first().map { it.text })
        history.clear("h3")
        assertTrue(history.observeForHost("h3").first().isEmpty())
        assertEquals(3, history.observeAll().first().size)
        history.clearAll()
        assertTrue(history.observeAll().first().isEmpty())
    }

    @Test
    fun `entries an older build kept in a frame are imported once`() = runTest {
        val history = RoomCommandHistoryRepository(db)
        history.record("h1", "already", 50)
        val fromFrame = listOf("git status" to 10L, "already" to 50L, "make" to 60L, "" to 61L, "make" to 60L)
        history.importEntries("h1", fromFrame)
        assertEquals(listOf("git status" to 10L, "already" to 50L, "make" to 60L), history.observeForHost("h1").first().map { it.text to it.at })

        // The same frame restored again (the process died before the frame was saved without them) changes nothing.
        history.importEntries("h1", fromFrame)
        assertEquals(3, history.observeForHost("h1").first().size)
        history.importEntries("h1", emptyList())
        assertEquals(3, history.observeForHost("h1").first().size)
    }

    @Test
    fun `deleting a host takes its command history with it`() = runTest {
        val hosts = RoomHostRepository(db)
        val history = RoomCommandHistoryRepository(db)
        hosts.upsert(Host(id = "h1", name = "box", color = SwatchColor.MOSS, monogram = "BO", address = "box", user = "me", createdAt = 1))
        history.record("h1", "ls", 1)
        history.record("h2", "pwd", 2)
        hosts.delete("h1")
        assertNull(hosts.get("h1"))
        assertTrue(history.observeForHost("h1").first().isEmpty())
        assertEquals(listOf("pwd"), history.observeForHost("h2").first().map { it.text }, "another host's history stays")
    }
}
