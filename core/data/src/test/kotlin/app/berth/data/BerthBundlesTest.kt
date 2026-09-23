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
import app.berth.domain.model.AppearanceOverride
import app.berth.domain.model.AuthMethod
import app.berth.domain.model.Base64Codec
import app.berth.domain.model.BerthBundle
import app.berth.domain.model.BundleFormatException
import app.berth.domain.model.BundledIdentity
import app.berth.domain.model.BundledKnownHost
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
import app.berth.domain.model.KnownHostStanding
import app.berth.domain.model.RecreateNotice
import app.berth.domain.model.Snippet
import app.berth.domain.model.SwatchColor
import app.berth.domain.model.TerminalTheme
import app.berth.domain.model.Tunnel
import app.berth.domain.model.TunnelType
import app.berth.domain.model.Workspace
import app.berth.domain.model.BundleImportOptions
import app.berth.ssh.SshKeys
import app.berth.ssh.SshSecurity
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
    init {
        SshSecurity.ensureProviders()
    }

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

    // A real key, passphrase-protected as a stored one may be: the import reads its public half from
    // the bytes, which for an OpenSSH file needs no passphrase.
    private val laptopPair = SshKeys.generate(KeyAlgorithm.ED25519)
    private val laptopKey = SshKeys.openSshPrivate(laptopPair, passphrase = "hunter2".toCharArray()).toByteArray()
    private val laptop = Identity(
        id = "id-laptop", name = "laptop", algorithm = KeyAlgorithm.ED25519, storage = KeyStorage.SOFTWARE_ENCRYPTED,
        protection = KeyProtection.PASSPHRASE, publicKeyOpenSsh = SshKeys.openSshPublic(laptopPair.public),
        fingerprintSha256 = SshKeys.fingerprintSha256(laptopPair.public), createdAt = 1,
    )
    private val phoneKey = Identity(
        id = "id-phone", name = "Phone key", algorithm = KeyAlgorithm.ECDSA_P256, storage = KeyStorage.ANDROID_KEYSTORE,
        protection = KeyProtection.BIOMETRIC, publicKeyOpenSsh = "ecdsa-sha2-nistp256 AAAAphone", fingerprintSha256 = "SHA256:phone",
        keystoreAlias = "berth.identity.id-phone", createdAt = 2,
    )
    private val web = Host(id = "h-web", name = "prod-web", color = SwatchColor.VERDIGRIS, monogram = "PW", address = "203.0.113.10", user = "deploy", auth = AuthMethod.Key("id-laptop"), tags = listOf("prod"),
        scrollbackLines = 50_000, ciphers = listOf("aes256-gcm@openssh.com", "aes256-ctr"), createdAt = 3)
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
        assertTrue(report.interfaceTheme)
        assertTrue(report.defaultTerminalTheme, "the new phone opened new terminals in Berth Dark; now in Mine")
        assertEquals(1, report.needsRecreation.size)
        val notice = report.needsRecreation.single()
        assertEquals("Phone key", notice.identityName)
        assertEquals(KeyAlgorithm.ECDSA_P256, notice.algorithm)
        assertEquals(listOf("db-primary"), notice.hostNames)
        assertEquals("Imported 3 hosts, 1 key, 2 workspaces, 2 snippets, 2 tunnels, 1 theme, 1 known host, the Deck, the interface theme and the default terminal theme.", report.summary)
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
        val newer = codec.seal("""{"format":3,"exported_at":1,"hosts":[]}""".toByteArray(), "pw".toCharArray(), quick)
        assertEquals(3, assertFailsWith<BundleFormatException.NewerThanThisBuild> { new.bundles.open(newer, "pw".toCharArray()) }.format)
        val garbage = codec.seal("hello".toByteArray(), "pw".toCharArray(), quick)
        assertFailsWith<BundleFormatException.NotABundle> { new.bundles.open(garbage, "pw".toCharArray()) }
        // The document reads its own output back, defaults and all.
        val empty = BerthBundle(exportedAt = 5)
        assertEquals(empty, BerthBundle.fromJson(empty.toJson()))
        assertTrue(empty.isEmpty)
    }

    // ---- what is not written as carried ------------------------------------------------------------

    /**
     * The check between the user and a wrong key is the key this phone holds; a bundle does not
     * override it on its own. The standings, before the import in the plan and after it in the
     * report: a different key for a pinned endpoint is not offered and stays; a different key of
     * the same type for an unpinned one, and a key of another type for an endpoint this phone holds
     * a key for (live, the case the policy asks about), are the changed-key case, offered as the
     * Replace decision and kept while it is not made; the very key is nothing to do; a new endpoint
     * is written.
     */
    @Test
    fun `a bundled known host for an endpoint this phone trusts another key for stays as this phone has it, and the sheet is told so first`() = runTest {
        val rotatedWeb = known.copy(id = "k-web-old", publicKeyBase64 = "AAAAoldwebkey", fingerprintSha256 = "SHA256:oldwebkey", pinned = false)
        val dbHere = KnownHostKey("k-db", "db.internal", 2200, "ssh-ed25519", "AAAAdbhere", "SHA256:dbhere", 1, 2)
        val dbBundled = dbHere.copy(id = "k-db-theirs", publicKeyBase64 = "AAAAdbtheirs", fingerprintSha256 = "SHA256:dbtheirs")
        // The other phone met db.internal on RSA: a type this phone holds none of, for an endpoint it does hold a key for.
        val dbRsa = KnownHostKey("k-db-rsa", "db.internal", 2200, "ssh-rsa", "AAAAdbrsa", "SHA256:dbrsa", 5, 6)
        val nasKey = KnownHostKey("k-nas", "10.0.0.5", 22, "ssh-ed25519", "AAAAnas", "SHA256:nas", 3, 4)
        val sameWeb = known.copy(id = "k-web-same", pinned = false, firstSeenAt = 50, lastSeenAt = 60)
        new.knownHosts.upsert(known) // pinned, the bundle's is a rotated (or planted) one
        new.knownHosts.upsert(dbHere) // unpinned, the bundle's differs: the changed-key case
        val bundle = BerthBundle(exportedAt = 1, knownHosts = listOf(rotatedWeb, dbBundled, dbRsa, nasKey, sameWeb))

        val plan = new.bundles.plan(bundle)
        assertEquals(1, plan.knownHostsNew)
        assertEquals(1, plan.knownHostsExisting)
        assertEquals(listOf(nasKey, sameWeb), plan.knownHostsRoutine.map { it.key }, "the count row's keys, in the bundle's order")
        assertEquals(listOf(BundledKnownHost(rotatedWeb, KnownHostStanding.Pinned(known))), plan.knownHostsPinned)
        assertEquals(
            listOf(BundledKnownHost(dbBundled, KnownHostStanding.Conflicting(dbHere)), BundledKnownHost(dbRsa, KnownHostStanding.Conflicting(dbHere))),
            plan.knownHostsConflicting,
            "both differ from the ed25519 key this phone holds, the RSA one by the import's own rule",
        )
        assertEquals(listOf(false, true), plan.knownHostsConflicting.map { it.addsBeside }, "the ed25519 key's tick is the Replace; the RSA key's, a type this phone holds none of, adds it beside")
        assertEquals(listOf(rotatedWeb, dbBundled, dbRsa, nasKey, sameWeb), plan.knownHosts.map { it.key }, "every bundled key, as the bundle has them")

        val report = new.bundles.apply(bundle)
        val here = new.knownHosts.observeAll().first()
        assertEquals(setOf(known, dbHere, nasKey), here.toSet(), "the pin and the unpinned key stay; the new endpoint is written; the same key is not written twice")
        assertEquals(listOf(dbHere), here.filter { it.host == "db.internal" }, "one key for the endpoint, the one this phone had; the RSA one was not added beside it")
        assertEquals(1, report.knownHosts)
        assertEquals(3, report.knownHostsKept)
        assertEquals(0, report.knownHostsReplaced)
        assertEquals("Imported 1 known host.", report.summary)
    }

    /**
     * The tick on a conflicting row is the changed-key sheet's Replace (spec C13), as #19's
     * `known_hosts` import takes it: the saved key goes and the bundle's is written in its place,
     * under the name as the store keys it (lowercase, read case-blind, so the live lookup under
     * the address as typed finds it whichever spelling the saved key had). A tick names one key;
     * the other conflict for the same endpoint stays kept, and a pinned endpoint's key is never
     * taken, ticked or not. Two ticks for one endpoint both stand.
     */
    @Test
    fun `a conflicting known host ticked to replace takes the saved key's place under its spelling, and only that one`() = runTest {
        val dbHere = KnownHostKey("k-db", "DB.Internal", 2200, "ssh-ed25519", "AAAAdbhere", "SHA256:dbhere", 1, 2)
        val dbBundled = KnownHostKey("k-db-theirs", "db.internal", 2200, "ssh-ed25519", "AAAAdbtheirs", "SHA256:dbtheirs", 3, 4)
        val dbRsa = KnownHostKey("k-db-rsa", "db.internal", 2200, "ssh-rsa", "AAAAdbrsa", "SHA256:dbrsa", 5, 6)
        val rotatedWeb = known.copy(id = "k-web-old", publicKeyBase64 = "AAAAoldwebkey", fingerprintSha256 = "SHA256:oldwebkey", pinned = false)
        val nasKey = KnownHostKey("k-nas", "10.0.0.5", 22, "ssh-ed25519", "AAAAnas", "SHA256:nas", 3, 4)
        new.knownHosts.upsert(known)
        new.knownHosts.upsert(dbHere)
        val bundle = BerthBundle(exportedAt = 1, knownHosts = listOf(rotatedWeb, dbBundled, dbRsa, nasKey))
        val plan = new.bundles.plan(bundle)
        val (theirs, rsa) = plan.knownHostsConflicting
        assertEquals(dbBundled, theirs.key)
        assertEquals("db.internal:2200:AAAAdbtheirs", theirs.id, "the decision is named by endpoint and public key, not by the other phone's record id")

        // The pinned endpoint's key ticked as well: a pin is not offered, and a tick that never was on the sheet changes nothing.
        val report = new.bundles.apply(bundle, BundleImportOptions(replaceKnownHosts = setOf(theirs.id, plan.knownHostsPinned.single().id)))

        val here = new.knownHosts.observeAll().first()
        assertEquals(listOf(dbBundled), here.filter { it.host == "db.internal" }, "the bundle's key in the saved key's place, under the store's lowercase name; the RSA one, unticked, was not added beside it")
        assertEquals(setOf(known, nasKey, dbBundled), here.toSet())
        assertEquals(2, report.knownHosts, "the new endpoint and the replaced key were written")
        assertEquals(1, report.knownHostsReplaced)
        assertEquals(2, report.knownHostsKept, "the pinned endpoint's and the unticked RSA key")
        assertEquals("Imported 2 known hosts (1 replaced).", report.summary)

        // Both conflicts ticked: the saved key goes once, on the ed25519 tick that replaces it, and each of the two is written.
        val other = Phone()
        try {
            other.knownHosts.upsert(dbHere)
            val both = other.bundles.plan(bundle).knownHostsConflicting.map { it.id }.toSet()
            val twice = other.bundles.apply(bundle, BundleImportOptions(replaceKnownHosts = both))
            assertEquals(setOf(dbBundled, dbRsa), other.knownHosts.observeAll().first().filter { it.port == 2200 }.toSet())
            assertEquals(1, twice.knownHostsReplaced, "the RSA key, of a type this phone held none of, was added and replaced nothing")
            assertEquals(4, twice.knownHosts, "rotatedWeb is new on this phone, which has no key for it, beside the nas key and the two")
            assertEquals("Imported 4 known hosts (1 replaced).", twice.summary)
        } finally {
            other.db.close()
        }
    }

    /**
     * A bundled key of a type this phone holds none of for the endpoint (review #18 nit 19): live,
     * that key reaches the first-connection sheet, and an accept saves it beside what is held (spec
     * C13's additional key); #19's `known_hosts` import writes it beside too. So its tick adds it
     * and the held key stays, both under the store's lowercase name, counted as written and not as
     * replaced. The same-type case is unchanged: its tick deletes the saved key.
     */
    @Test
    fun `a ticked key of a type this phone holds none of is added beside the saved key, which stays, and a ticked key of its type still takes its place`() = runTest {
        val dbHere = KnownHostKey("k-db", "DB.Internal", 2200, "ssh-ed25519", "AAAAdbhere", "SHA256:dbhere", 1, 2)
        val dbBundled = KnownHostKey("k-db-theirs", "db.internal", 2200, "ssh-ed25519", "AAAAdbtheirs", "SHA256:dbtheirs", 3, 4)
        val dbRsa = KnownHostKey("k-db-rsa", "db.internal", 2200, "ssh-rsa", "AAAAdbrsa", "SHA256:dbrsa", 5, 6)
        new.knownHosts.upsert(dbHere)
        val bundle = BerthBundle(exportedAt = 1, knownHosts = listOf(dbBundled, dbRsa))
        val (theirs, rsa) = new.bundles.plan(bundle).knownHostsConflicting
        assertEquals(dbRsa, rsa.key)
        assertTrue(rsa.addsBeside)
        assertFalse(theirs.addsBeside)

        // The RSA key ticked alone: both keys held for the endpoint, under the one name the store keys them by.
        val report = new.bundles.apply(bundle, BundleImportOptions(replaceKnownHosts = setOf(rsa.id)))
        assertEquals(setOf(dbHere.copy(host = "db.internal"), dbRsa), new.knownHosts.observeAll().first().toSet(), "the ed25519 key stays and the RSA key stands beside it")
        assertEquals(1, report.knownHosts, "the added key is written")
        assertEquals(0, report.knownHostsReplaced, "and replaced nothing")
        assertEquals(1, report.knownHostsKept, "the unticked ed25519 conflict")
        assertEquals("Imported 1 known host.", report.summary)

        // The same-type key ticked alone, on a phone as it stood: the saved key goes and the bundle's takes its place; the RSA key is kept out.
        val other = Phone()
        try {
            other.knownHosts.upsert(dbHere)
            val plan = other.bundles.plan(bundle)
            val replaced = other.bundles.apply(bundle, BundleImportOptions(replaceKnownHosts = setOf(plan.knownHostsConflicting.first { !it.addsBeside }.id)))
            assertEquals(setOf(dbBundled), other.knownHosts.observeAll().first().toSet(), "the saved key is gone, the bundle's ed25519 key alone in its place")
            assertEquals(1, replaced.knownHostsReplaced)
            assertEquals(1, replaced.knownHostsKept)
            assertEquals("Imported 1 known host (1 replaced).", replaced.summary)
        } finally {
            other.db.close()
        }
    }

    /** A software key is what its bytes say it is; the document's claim about them is not taken. */
    @Test
    fun `a new software key is stored with the public half and fingerprint of its own bytes, not the ones the document claims`() = runTest {
        val forged = laptop.copy(publicKeyOpenSsh = "ssh-ed25519 AAAAforged", fingerprintSha256 = "SHA256:forged")
        val bundle = BerthBundle(exportedAt = 1, identities = listOf(BundledIdentity.of(forged, laptopKey)), hosts = listOf(web))

        val report = new.bundles.apply(bundle)

        assertEquals(1, report.identities)
        val stored = assertNotNull(new.identities.get("id-laptop"))
        assertEquals(SshKeys.openSshPublic(laptopPair.public), stored.publicKeyOpenSsh)
        assertEquals(SshKeys.fingerprintSha256(laptopPair.public), stored.fingerprintSha256)
        assertContentEquals(laptopKey, new.identities.privateKey("id-laptop"))
        assertEquals(AuthMethod.Key("id-laptop"), assertNotNull(new.hosts.get("h-web")).auth)

        // The match against a key already here goes by the bytes' fingerprint too, whatever the document says.
        val other = Phone()
        try {
            other.identities.insert(laptop.copy(id = "id-other", name = "same laptop key"), laptopKey)
            other.bundles.apply(bundle)
            assertEquals(listOf("id-other"), other.identities.observeAll().first().map { it.id })
            assertEquals(AuthMethod.Key("id-other"), assertNotNull(other.hosts.get("h-web")).auth)
        } finally {
            other.db.close()
        }

        // Bytes that are not an OpenSSH key are not a document Berth wrote: refused whole, by name,
        // by the plan before the sheet offers the import and by the import before its first write,
        // so the workspace and the host that came with the key are nowhere.
        val junkBytes = "-----BEGIN OPENSSH PRIVATE KEY-----\nbGFwdG9wLXNlY3JldC1ieXRlcw==\n-----END OPENSSH PRIVATE KEY-----\n".toByteArray()
        val junk = BerthBundle(exportedAt = 1, identities = listOf(BundledIdentity.of(laptop.copy(id = "id-junk", name = "junk key"), junkBytes)), workspaces = listOf(work), hosts = listOf(nas))
        assertTrue("junk key" in assertFailsWith<BundleFormatException.UnreadableKey> { new.bundles.plan(junk) }.message!!)
        assertTrue("junk key" in assertFailsWith<BundleFormatException.UnreadableKey> { new.bundles.apply(junk) }.message!!)
        assertNull(new.identities.get("id-junk"))
        assertEquals(listOf("id-laptop"), new.identities.observeAll().first().map { it.id })
        assertNull(new.workspaces.get("w-work"), "nothing was written ahead of the refusal")
        assertNull(new.hosts.get("h-nas"))
    }

    /**
     * Nothing an import brings listens for other devices until the user turns it on where the
     * binding shows. The plan's row and the report count the same thing: the tunnels the import
     * switches off; one the bundle had off already comes in as it was and is neither.
     */
    @Test
    fun `a tunnel bound to every interface comes in switched off, and the plan names it`() = runTest {
        val everywhere = Tunnel(id = "t-all", hostId = "h-web", type = TunnelType.LOCAL, bindAddress = "*", bindPort = 9090, destinationHost = "localhost", destinationPort = 9090, enabled = true)
        val v4 = everywhere.copy(id = "t-v4", bindAddress = "0.0.0.0", bindPort = 9091)
        val alreadyOff = everywhere.copy(id = "t-off", bindPort = 9092, enabled = false)
        val remote = Tunnel(id = "t-remote", hostId = "h-web", type = TunnelType.REMOTE, bindAddress = "*", bindPort = 9000, destinationHost = "127.0.0.1", destinationPort = 3000, enabled = true)
        val bundle = BerthBundle(exportedAt = 1, hosts = listOf(web), tunnels = listOf(forward, everywhere, v4, alreadyOff, remote))

        assertEquals(listOf(everywhere, v4), new.bundles.plan(bundle).tunnelsOnEveryInterface, "a remote forward's bind is the server's, not this phone's; one already off is not held off")
        val report = new.bundles.apply(bundle)

        assertEquals(5, report.tunnels)
        assertEquals(2, report.tunnelsHeldOff)
        val stored = new.tunnels.observeAll().first().associateBy { it.id }
        assertEquals(false, stored.getValue("t-all").enabled)
        assertEquals(false, stored.getValue("t-v4").enabled)
        assertEquals(false, stored.getValue("t-off").enabled)
        assertEquals(true, stored.getValue("t-web").enabled, "a loopback forward comes in as it was")
        assertEquals(true, stored.getValue("t-remote").enabled)
    }

    /** The Deck, the interface theme and the default terminal theme are this phone's one copy each; the import leaves any alone when told to. */
    @Test
    fun `the Deck, the interface theme and the default terminal theme are kept when the import's switches are off`() = runTest {
        fillOldPhone()
        val mineDeck = DeckLayout(layers = listOf(DeckLayer("Mine", listOf(DeckKey(tap = DeckAction.Text("y"))))))
        val mineLook = InterfaceTheme(variant = InterfaceVariant.DARK, tone = 0.2f, radiusScale = 0.8f)
        new.settings.setDeckLayout(mineDeck)
        new.settings.setInterfaceTheme(mineLook)
        val bundle = new.bundles.open(old.bundles.export("pw".toCharArray(), exportedAt = 1, cost = quick), "pw".toCharArray())
        assertEquals("Mine", new.bundles.plan(bundle).defaultTerminalTheme, "the bundle's default is a theme it carries, and not this phone's default")

        val report = new.bundles.apply(bundle, BundleImportOptions(deck = false, interfaceTheme = false, defaultTerminalTheme = false))

        assertEquals(mineDeck, new.settings.deckLayout.first())
        assertEquals(mineLook, new.settings.interfaceTheme.first())
        assertEquals(TerminalTheme.BERTH_DARK_ID, new.settings.defaultTerminalThemeId.first())
        assertFalse(report.deck)
        assertFalse(report.interfaceTheme)
        assertFalse(report.defaultTerminalTheme)
        assertEquals(3, report.hosts, "everything else came in")
        assertFalse("the Deck" in report.summary)
        assertFalse("the interface theme" in report.summary)
        assertFalse("the default terminal theme" in report.summary)

        val deckOnly = new.bundles.apply(bundle, BundleImportOptions(deck = true, interfaceTheme = false, defaultTerminalTheme = false))
        assertEquals(deck, new.settings.deckLayout.first())
        assertEquals(mineLook, new.settings.interfaceTheme.first())
        assertEquals(TerminalTheme.BERTH_DARK_ID, new.settings.defaultTerminalThemeId.first())
        assertTrue(deckOnly.deck)
        assertFalse(deckOnly.interfaceTheme)
        assertTrue(deckOnly.summary.endsWith(" and the Deck."), deckOnly.summary)

        // The third switch on its own; and once Mine is the default, the plan has nothing to offer and the report nothing to say.
        val themeOnly = new.bundles.apply(bundle, BundleImportOptions(deck = false, interfaceTheme = false, defaultTerminalTheme = true))
        assertEquals("mine", new.settings.defaultTerminalThemeId.first())
        assertTrue(themeOnly.defaultTerminalTheme)
        assertTrue(themeOnly.summary.endsWith(" and the default terminal theme."), themeOnly.summary)
        assertNull(new.bundles.plan(bundle).defaultTerminalTheme)
        assertFalse(new.bundles.apply(bundle).defaultTerminalTheme)
    }

    /**
     * A bundle from a build before the stock set grew can carry a custom theme under what is now a
     * stock id: that build saved a Gogh "Dracula" with no id at `dracula`. The import moves it where
     * the database step moves one, with the web host, the Work group and the default on it following,
     * so it stands beside the stock Dracula instead of behind it; the nas on stock Nord keeps Nord.
     * Imported again, it lands on the same theme.
     */
    @Test
    fun `a bundled custom theme under a stock id moves to its own id, and what named it follows`() = runTest {
        val gogh = TerminalTheme.DRACULA.copy(background = 0x1E1F29, suggestedAccent = null, builtIn = false)
        val bundle = BerthBundle(
            exportedAt = 1,
            hosts = listOf(
                web.copy(appearance = AppearanceOverride(terminalThemeId = "dracula")),
                nas.copy(appearance = AppearanceOverride(terminalThemeId = TerminalTheme.NORD_ID)),
            ),
            workspaces = listOf(work.copy(terminalThemeId = "dracula")),
            terminalThemes = listOf(gogh, mine),
            defaultTerminalThemeId = "dracula",
        )
        new.settings.setDefaultTerminalTheme(TerminalTheme.DRACULA_ID)
        val freed = TerminalTheme.freedId("dracula")

        // The bundle's default is its own Dracula, not the stock one this phone is on, so the plan offers it.
        assertEquals("Dracula", new.bundles.plan(bundle).defaultTerminalTheme)
        assertTrue(new.bundles.apply(bundle).defaultTerminalTheme)
        assertEquals(TerminalTheme.builtIns + gogh.copy(id = freed) + mine, new.settings.terminalThemes.first())
        assertEquals(freed, new.settings.defaultTerminalThemeId.first())
        assertEquals(freed, new.hosts.get("h-web")?.appearance?.terminalThemeId)
        assertEquals(TerminalTheme.NORD_ID, new.hosts.get("h-nas")?.appearance?.terminalThemeId)
        assertEquals(freed, new.workspaces.observeAll().first().single { it.id == "w-work" }.terminalThemeId)

        new.bundles.apply(bundle)
        assertEquals(TerminalTheme.builtIns + gogh.copy(id = freed) + mine, new.settings.terminalThemes.first())
    }

    @Test
    fun `an empty phone exports a bundle that imports as nothing`() = runTest {
        old.workspaces.ensureDefault()
        val blob = old.bundles.export("pw".toCharArray(), exportedAt = 1, cost = quick)
        val bundle = new.bundles.open(blob, "pw".toCharArray())
        assertNull(new.bundles.plan(bundle).defaultTerminalTheme, "both phones open new terminals in Berth Dark")
        val report = new.bundles.apply(bundle)
        assertEquals(0, report.hosts)
        assertEquals(1, report.workspaces, "the default workspace is one")
        assertTrue(report.deck, "the Deck is always carried")
        assertTrue(report.interfaceTheme, "and so is the interface theme")
        assertFalse(report.defaultTerminalTheme, "the default terminal theme is carried too, and is this phone's already")
        assertEquals("Imported 1 workspace, the Deck and the interface theme.", report.summary)
    }

    // ---- the hosts alone (Hosts › Export) ----------------------------------------------------------

    @Test
    fun `a hosts-only export carries the hosts, their passwords and tunnels, and each key a host logs in with by its public record alone`() = runTest {
        fillOldPhone()
        // A key no host logs in with is not the hosts' business, nor a tunnel left from a host deleted since.
        val sparePair = SshKeys.generate(KeyAlgorithm.ED25519)
        old.identities.insert(
            laptop.copy(id = "id-spare", name = "spare", publicKeyOpenSsh = SshKeys.openSshPublic(sparePair.public), fingerprintSha256 = SshKeys.fingerprintSha256(sparePair.public)),
            SshKeys.openSshPrivate(sparePair).toByteArray(),
        )
        old.tunnels.upsert(forward.copy(id = "t-gone", hostId = "h-gone", bindPort = 8081))
        val bundle = old.bundles.collectHosts(exportedAt = 5, appVersion = "1.2")

        assertEquals(5, bundle.exportedAt)
        assertEquals("1.2", bundle.appVersion)
        assertEquals(setOf(web, db1, nas), bundle.hosts.toSet())
        assertEquals(listOf("host-password:h-nas"), bundle.passwords.map { it.id })
        assertContentEquals("hunter2".toByteArray(), bundle.passwords.single().bytes())
        // Both keys the hosts use are named and neither travels: the software key's private half stays here, as a hardware key's always does.
        assertEquals(setOf("id-laptop", "id-phone"), bundle.identities.map { it.identity.id }.toSet())
        assertTrue(bundle.identities.all { it.privateKey == null })
        assertEquals(listOf("laptop"), bundle.leftBehindIdentities.map { it.name })
        assertEquals(listOf("Phone key"), bundle.hardwareIdentities.map { it.name })
        assertEquals(laptop.fingerprintSha256, bundle.identities.single { it.identity.id == "id-laptop" }.identity.fingerprintSha256)
        assertNull(bundle.identities.single { it.identity.id == "id-phone" }.identity.keystoreAlias)
        val text = bundle.toJson()
        assertFalse(Base64Codec.encode(laptopKey) in text, "no private half is in the document")
        assertFalse("berth.identity.id-phone" in text)
        assertEquals(setOf(forward, socks), bundle.tunnels.toSet(), "the tunnels defined on the hosts go with them")
        // Nothing else goes in, so an import leaves the rest of the other phone as it is.
        assertTrue(bundle.workspaces.isEmpty())
        assertTrue(bundle.snippets.isEmpty())
        assertTrue(bundle.knownHosts.isEmpty())
        assertTrue(bundle.terminalThemes.isEmpty())
        assertNull(bundle.deck)
        assertNull(bundle.interfaceTheme)
        assertNull(bundle.defaultTerminalThemeId)
    }

    @Test
    fun `a hosts-only bundle points a host at the same key found here by fingerprint, and the rest ask each time and are named`() = runTest {
        fillOldPhone()
        val blob = old.bundles.exportHosts("pw".toCharArray(), exportedAt = 1, cost = quick)
        // The new phone already holds the laptop key, imported by hand under its own id and name, and a snippet and a Deck of its own.
        new.identities.insert(laptop.copy(id = "id-other", name = "same laptop key"), laptopKey)
        val mySnippet = Snippet(id = "s-mine", name = "uptime", body = "uptime")
        new.snippets.upsert(mySnippet)
        val myDeck = DeckLayout(layers = listOf(DeckLayer("Mine", listOf(DeckKey(tap = DeckAction.Text("y"))))))
        new.settings.setDeckLayout(myDeck)

        val bundle = new.bundles.open(blob, "pw".toCharArray())
        val plan = new.bundles.plan(bundle)
        assertEquals(mapOf("id-laptop" to "same laptop key"), plan.identitiesHere, "the sheet can say which key this phone already has")
        assertNull(plan.defaultTerminalTheme)
        val report = new.bundles.apply(bundle)

        assertEquals(web.copy(auth = AuthMethod.Key("id-other")), new.hosts.get("h-web"))
        assertEquals(db1.copy(auth = AuthMethod.AskEachTime), new.hosts.get("h-db"))
        assertEquals(nas, new.hosts.get("h-nas"))
        assertContentEquals("hunter2".toByteArray(), new.secrets.get("host-password:h-nas"))
        assertEquals(listOf("id-other"), new.identities.observeAll().first().map { it.id }, "no key is written")
        assertEquals(0, report.identities)
        assertEquals(listOf(RecreateNotice("Phone key", KeyAlgorithm.ECDSA_P256, listOf("db-primary"), hardware = true)), report.needsRecreation)
        assertEquals("Imported 3 hosts and 2 tunnels.", report.summary)
        assertEquals(setOf(forward, socks), new.tunnels.observeAll().first().toSet())
        assertEquals(listOf(mySnippet), new.snippets.observeAll().first())
        assertEquals(myDeck, new.settings.deckLayout.first())
        assertFalse(report.deck)

        // A phone that holds neither key: the host on the software key asks each time too, named as a key left on the other phone.
        val third = Phone()
        try {
            val onThird = third.bundles.open(blob, "pw".toCharArray())
            assertTrue(third.bundles.plan(onThird).identitiesHere.isEmpty())
            val thirdReport = third.bundles.apply(onThird)
            assertEquals(web.copy(auth = AuthMethod.AskEachTime), third.hosts.get("h-web"))
            assertEquals(
                setOf(
                    RecreateNotice("laptop", KeyAlgorithm.ED25519, listOf("prod-web"), hardware = false),
                    RecreateNotice("Phone key", KeyAlgorithm.ECDSA_P256, listOf("db-primary"), hardware = true),
                ),
                thirdReport.needsRecreation.toSet(),
            )
            assertTrue(third.identities.observeAll().first().isEmpty())
        } finally {
            third.db.close()
        }
    }

    @Test
    fun `a hosts-only bundle opened on the phone that made it keeps every host on its key and names nothing`() = runTest {
        fillOldPhone()
        val blob = old.bundles.exportHosts("pw".toCharArray(), exportedAt = 1, cost = quick)
        old.hosts.delete("h-nas")
        val before = old.identities.observeAll().first()

        val bundle = old.bundles.open(blob, "pw".toCharArray())
        assertEquals(mapOf("id-laptop" to "laptop", "id-phone" to "Phone key"), old.bundles.plan(bundle).identitiesHere)
        val report = old.bundles.apply(bundle)

        assertEquals(before, old.identities.observeAll().first())
        assertTrue(report.needsRecreation.isEmpty())
        assertEquals(web, old.hosts.get("h-web"))
        assertEquals(db1, old.hosts.get("h-db"), "the host on the hardware key keeps it")
        assertEquals(nas, old.hosts.get("h-nas"), "the deleted host is back")
        assertEquals(deck, old.settings.deckLayout.first())
        assertEquals(look, old.settings.interfaceTheme.first())
        assertEquals("mine", old.settings.defaultTerminalThemeId.first())
    }
}
