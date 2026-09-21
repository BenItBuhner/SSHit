package app.berth.data

import app.berth.data.bundle.BerthBundles
import app.berth.data.bundle.BundleCodec
import app.berth.data.bundle.BundleKdf
import app.berth.data.crypto.HardwareKeys
import app.berth.data.crypto.SecretCrypto
import app.berth.data.db.BerthDatabase
import app.berth.data.repo.EncryptedSecretStore
import app.berth.data.repo.RoomHostRepository
import app.berth.data.repo.RoomIdentityRepository
import app.berth.data.repo.RoomKnownHostRepository
import app.berth.data.repo.RoomSettingsRepository
import app.berth.data.repo.RoomSnippetRepository
import app.berth.data.repo.RoomTunnelRepository
import app.berth.data.repo.RoomWorkspaceRepository
import app.berth.domain.model.AuthMethod
import app.berth.domain.model.Base64Codec
import app.berth.domain.model.BerthBundle
import app.berth.domain.model.BundleFormatException
import app.berth.domain.model.BundledIdentity
import app.berth.domain.model.DeckAction
import app.berth.domain.model.DeckKey
import app.berth.domain.model.DeckLayer
import app.berth.domain.model.DeckLayout
import app.berth.domain.model.Host
import app.berth.domain.model.Identity
import app.berth.domain.model.InterfaceTheme
import app.berth.domain.model.InterfaceVariant
import app.berth.domain.model.KeyAlgorithm
import app.berth.domain.model.KeyProtection
import app.berth.domain.model.KeyStorage
import app.berth.domain.model.KnownHostKey
import app.berth.domain.model.Snippet
import app.berth.domain.model.SwatchColor
import app.berth.domain.model.TerminalTheme
import app.berth.domain.model.Tunnel
import app.berth.domain.model.TunnelType
import app.berth.domain.model.Workspace
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The bundle over Room, end to end: one phone exports, another imports, and every table reads
 * the same, short of the hardware key that cannot make the trip.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BerthBundlesTest {
    private val quick = BundleKdf(memoryKiB = 1024, iterations = 1, parallelism = 1)

    /** XORs with a fixed byte, like RoomRepositoriesTest: enough to tell a stored secret from its plaintext. */
    private val crypto = object : SecretCrypto {
        override fun encrypt(plain: ByteArray) = byteArrayOf(9) + ByteArray(plain.size) { (plain[it].toInt() xor 0x5A).toByte() }
        override fun decrypt(blob: ByteArray) = ByteArray(blob.size - 1) { (blob[it + 1].toInt() xor 0x5A).toByte() }
    }

    /** One phone: a database and the repositories over it. */
    private inner class Phone {
        val db: BerthDatabase = BerthDatabase.inMemory(RuntimeEnvironment.getApplication())
        val secrets = EncryptedSecretStore(db, crypto)
        val hosts = RoomHostRepository(db)
        val identities = RoomIdentityRepository(db, secrets, HardwareKeys(RuntimeEnvironment.getApplication()))
        val workspaces = RoomWorkspaceRepository(db)
        val snippets = RoomSnippetRepository(db)
        val tunnels = RoomTunnelRepository(db)
        val knownHosts = RoomKnownHostRepository(db)
        val settings = RoomSettingsRepository(db)
        val bundles = BerthBundles(hosts, identities, workspaces, snippets, tunnels, knownHosts, settings, secrets, BundleCodec())
    }

    private lateinit var old: Phone
    private lateinit var new: Phone

    private val laptopKey = "-----BEGIN OPENSSH PRIVATE KEY-----\nlaptop-secret-bytes\n-----END OPENSSH PRIVATE KEY-----\n".toByteArray()
    private val laptop = Identity(
        id = "id-laptop", name = "laptop", algorithm = KeyAlgorithm.ED25519, storage = KeyStorage.SOFTWARE_ENCRYPTED,
        protection = KeyProtection.PASSPHRASE, publicKeyOpenSsh = "ssh-ed25519 AAAAlaptop laptop", fingerprintSha256 = "SHA256:laptop", createdAt = 1,
    )
    private val phoneKey = Identity(
        id = "id-phone", name = "Phone key", algorithm = KeyAlgorithm.ECDSA_P256, storage = KeyStorage.ANDROID_KEYSTORE,
        protection = KeyProtection.BIOMETRIC, publicKeyOpenSsh = "ecdsa-sha2-nistp256 AAAAphone", fingerprintSha256 = "SHA256:phone",
        keystoreAlias = "berth.identity.id-phone", createdAt = 2,
    )
    private val web = Host(id = "h-web", name = "prod-web", color = SwatchColor.VERDIGRIS, monogram = "PW", address = "203.0.113.10", user = "deploy", auth = AuthMethod.Key("id-laptop"), tags = listOf("prod"), createdAt = 3)
    private val db1 = Host(id = "h-db", name = "db-primary", color = SwatchColor.SLATE, monogram = "DB", address = "db.internal", port = 2200, user = "postgres", auth = AuthMethod.Key("id-phone"), jumpHostIds = listOf("h-web"), createdAt = 4)
    private val nas = Host(id = "h-nas", name = "nas", color = SwatchColor.MOSS, monogram = "NA", address = "10.0.0.5", user = "admin", auth = AuthMethod.Password("host-password:h-nas"), createdAt = 5)
    private val work = Workspace(id = "w-work", name = "Work", color = SwatchColor.PLUM, monogram = "WK", sortOrder = 1, createdAt = 6, reconnectAtLaunch = true)
    private val disk = Snippet(id = "s-disk", name = "disk", body = "df -h", tags = listOf("ops"))
    private val tail = Snippet(id = "s-tail", name = "tail", body = "tail -f {{file}}", hostId = "h-web", workspaceId = "w-work", pinnedToDeck = true)
    private val forward = Tunnel(id = "t-web", hostId = "h-web", type = TunnelType.LOCAL, bindPort = 8080, destinationHost = "localhost", destinationPort = 80)
    private val socks = Tunnel(id = "t-socks", hostId = "h-db", type = TunnelType.DYNAMIC, bindPort = 1080, enabled = false)
    private val known = KnownHostKey("k-web", "203.0.113.10", 22, "ssh-ed25519", "AAAAwebkey", "SHA256:webkey", 7, 8, pinned = true)
    private val mine = TerminalTheme.BERTH_LIGHT.copy(id = "mine", name = "Mine", builtIn = false)
    private val deck = DeckLayout(layers = listOf(DeckLayer("Only", listOf(DeckKey(tap = DeckAction.Text("x"))))))
    private val look = InterfaceTheme(variant = InterfaceVariant.LIGHT, tone = 0.8f, radiusScale = 1.2f)

    @Before
    fun setUp() {
        old = Phone()
        new = Phone()
    }

    @After
    fun tearDown() {
        old.db.close()
        new.db.close()
    }

    private suspend fun fillOldPhone() {
        old.workspaces.ensureDefault()
        old.workspaces.upsert(work)
        old.identities.insert(laptop, laptopKey)
        old.identities.insert(phoneKey, null)
        old.hosts.upsert(web)
        old.hosts.upsert(db1)
        old.hosts.upsert(nas)
        old.secrets.put("host-password:h-nas", "hunter2".toByteArray())
        old.snippets.upsert(disk)
        old.snippets.upsert(tail)
        old.tunnels.upsert(forward)
        old.tunnels.upsert(socks)
        old.knownHosts.upsert(known)
        old.settings.upsertTerminalTheme(mine)
        old.settings.setDefaultTerminalTheme("mine")
        old.settings.setDeckLayout(deck)
        old.settings.setInterfaceTheme(look)
    }

    @Test
    fun `a new phone reads every table the old one exported, and is told which key to make again`() = runTest {
        fillOldPhone()
        val passphrase = "correct horse battery staple"
        val blob = old.bundles.export(passphrase.toCharArray(), exportedAt = 99, appVersion = "1.2", cost = quick)

        val bundle = new.bundles.open(blob, passphrase.toCharArray())
        assertEquals(99, bundle.exportedAt)
        assertEquals("1.2", bundle.appVersion)
        assertEquals(listOf("Phone key"), bundle.hardwareIdentities.map { it.name })
        val report = new.bundles.apply(bundle)

        assertEquals(old.workspaces.observeAll().first(), new.workspaces.observeAll().first())
        assertEquals(listOf(disk, tail), new.snippets.observeAll().first())
        assertEquals(setOf(forward, socks), new.tunnels.observeAll().first().toSet())
        assertEquals(listOf(known), new.knownHosts.observeAll().first())
        assertEquals(old.settings.terminalThemes.first(), new.settings.terminalThemes.first())
        assertEquals("mine", new.settings.defaultTerminalThemeId.first())
        assertEquals(deck, new.settings.deckLayout.first())
        assertEquals(look, new.settings.interfaceTheme.first())

        // The software key made the trip, bytes intact and encrypted at rest again; the hardware one did not.
        assertEquals(listOf(laptop), new.identities.observeAll().first())
        assertContentEquals(laptopKey, new.identities.privateKey("id-laptop"))
        assertFalse(assertNotNull(new.db.secrets().get(RoomIdentityRepository.secretId("id-laptop"))).blob.contentEquals(laptopKey))
        assertNull(new.identities.get("id-phone"))

        // The hosts: the one on the laptop key as it was, the one on the phone key asking each time, the password carried.
        assertEquals(web, new.hosts.get("h-web"))
        assertEquals(db1.copy(auth = AuthMethod.AskEachTime), new.hosts.get("h-db"))
        assertEquals(nas, new.hosts.get("h-nas"))
        assertContentEquals("hunter2".toByteArray(), new.secrets.get("host-password:h-nas"))

        assertEquals(3, report.hosts)
        assertEquals(1, report.identities)
        assertEquals(2, report.workspaces)
        assertEquals(2, report.snippets)
        assertEquals(2, report.tunnels)
        assertEquals(1, report.terminalThemes)
        assertEquals(1, report.knownHosts)
        assertTrue(report.deck)
        assertEquals(1, report.needsRecreation.size)
        val notice = report.needsRecreation.single()
        assertEquals("Phone key", notice.identityName)
        assertEquals(KeyAlgorithm.ECDSA_P256, notice.algorithm)
        assertEquals(listOf("db-primary"), notice.hostNames)
        assertEquals("Imported 3 hosts, 1 key, 2 workspaces, 2 snippets, 2 tunnels, 1 theme, 1 known host and the Deck.", report.summary)
    }

    @Test
    fun `a hardware key is not in the document by construction, and neither is its Keystore alias`() = runTest {
        fillOldPhone()
        val bundle = old.bundles.collect(exportedAt = 1)
        val phone = bundle.identities.single { it.identity.id == "id-phone" }
        assertNull(phone.privateKey)
        assertNull(phone.identity.keystoreAlias, "an alias names a key on this phone and nothing elsewhere")
        assertTrue(phone.identity.isHardwareBacked, "the record still says what it was, so the import can say what to make again")
        val text = bundle.toJson()
        assertFalse("berth.identity.id-phone" in text)
        assertTrue(Base64Codec.encode(laptopKey) in text, "the software key travels")

        // No factory or copy can pair key bytes with a hardware identity.
        assertFailsWith<IllegalArgumentException> { BundledIdentity(phoneKey, "AAAA") }
        assertNull(BundledIdentity.of(phoneKey, "leaked".toByteArray()).privateKey)
    }

    @Test
    fun `importing onto the phone that made the bundle changes nothing and brings back what was deleted`() = runTest {
        fillOldPhone()
        val blob = old.bundles.export("pw".toCharArray(), exportedAt = 1, cost = quick)
        old.hosts.delete("h-nas")
        old.snippets.delete("s-disk")
        val before = old.identities.observeAll().first()

        val report = old.bundles.apply(old.bundles.open(blob, "pw".toCharArray()))

        assertEquals(before, old.identities.observeAll().first(), "keys already here are kept, not duplicated")
        assertEquals(0, report.identities)
        assertTrue(report.needsRecreation.isEmpty(), "the phone key is on this phone")
        assertEquals(db1, old.hosts.get("h-db"), "a host on a key this phone has keeps it")
        assertEquals(nas, old.hosts.get("h-nas"))
        assertEquals(disk, old.snippets.get("s-disk"))
        assertEquals(3, old.hosts.observeAll().first().size)
        assertEquals(2, old.workspaces.observeAll().first().size)
    }

    @Test
    fun `a key already here under another id is matched by fingerprint and the bundle's hosts pointed at it`() = runTest {
        fillOldPhone()
        val blob = old.bundles.export("pw".toCharArray(), exportedAt = 1, cost = quick)
        // The same laptop key was imported on the new phone by hand first, with its own id and name.
        new.identities.insert(laptop.copy(id = "id-other", name = "same laptop key"), laptopKey)

        val report = new.bundles.apply(new.bundles.open(blob, "pw".toCharArray()))

        assertEquals(listOf("id-other"), new.identities.observeAll().first().map { it.id })
        assertEquals(0, report.identities)
        assertEquals(AuthMethod.Key("id-other"), assertNotNull(new.hosts.get("h-web")).auth)
    }

    @Test
    fun `a document from a newer Berth is refused whole, and a sealed non-document is not a bundle`() = runTest {
        val codec = BundleCodec()
        val newer = codec.seal("""{"format":2,"exported_at":1,"hosts":[]}""".toByteArray(), "pw".toCharArray(), quick)
        assertEquals(2, assertFailsWith<BundleFormatException.NewerThanThisBuild> { new.bundles.open(newer, "pw".toCharArray()) }.format)
        val garbage = codec.seal("hello".toByteArray(), "pw".toCharArray(), quick)
        assertFailsWith<BundleFormatException.NotABundle> { new.bundles.open(garbage, "pw".toCharArray()) }
        // The document reads its own output back, defaults and all.
        val empty = BerthBundle(exportedAt = 5)
        assertEquals(empty, BerthBundle.fromJson(empty.toJson()))
        assertTrue(empty.isEmpty)
    }

    @Test
    fun `an empty phone exports a bundle that imports as nothing`() = runTest {
        old.workspaces.ensureDefault()
        val blob = old.bundles.export("pw".toCharArray(), exportedAt = 1, cost = quick)
        val bundle = new.bundles.open(blob, "pw".toCharArray())
        val report = new.bundles.apply(bundle)
        assertEquals(0, report.hosts)
        assertEquals(1, report.workspaces, "the default workspace is one")
        assertTrue(report.deck, "the Deck is always carried")
        assertEquals("Imported 1 workspace and the Deck.", report.summary)
    }
}
