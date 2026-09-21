package app.berth.data.bundle

import app.berth.domain.model.AuthMethod
import app.berth.domain.model.BerthBundle
import app.berth.domain.model.BundleFormatException
import app.berth.domain.model.BundleImportOptions
import app.berth.domain.model.BundleImportPlan
import app.berth.domain.model.BundleImportReport
import app.berth.domain.model.BundledIdentity
import app.berth.domain.model.BundledSecret
import app.berth.domain.model.Identity
import app.berth.domain.model.KnownHostKey
import app.berth.domain.model.KnownHostStanding
import app.berth.domain.model.RecreateNotice
import app.berth.domain.repository.HostRepository
import app.berth.domain.repository.IdentityRepository
import app.berth.domain.repository.KnownHostRepository
import app.berth.domain.repository.SecretStore
import app.berth.domain.repository.SettingsRepository
import app.berth.domain.repository.SnippetRepository
import app.berth.domain.repository.TunnelRepository
import app.berth.domain.repository.WorkspaceRepository
import app.berth.ssh.SshKeys
import kotlinx.coroutines.flow.first

/**
 * Export and import of the `.berth` bundle (spec C20, Data) over the repositories, so the same
 * code runs over Room and over the in-memory fakes the UI tests use.
 *
 * Export collects everything the bundle covers as it stands and seals it with [BundleCodec].
 * Import applies a document in dependency order (workspaces, identities, passwords, hosts, then
 * what refers to hosts) and upserts by id, so importing a bundle onto the phone that made it
 * restores what was deleted and leaves the rest as it was, and importing onto a new phone adds
 * everything. Three kinds are not written as carried:
 *
 * - Identities: a key already here, by id or by the fingerprint of its own bytes under another
 *   id, is kept and the bundle's hosts are pointed at it; a key new here is stored with the
 *   public half and fingerprint read from its private bytes, never the ones the document claims;
 *   and a hardware-backed identity, which comes without a key by construction, is not created,
 *   the hosts that used it ask each time and the report names both so the user knows what to make again.
 * - Known hosts: a bundled key that differs from a key this phone already trusts for its endpoint
 *   ([KnownHostStanding.differs]) is not taken, since the pin this phone holds is the check between
 *   the user and a wrong key and a bundle is not the place that check is overridden; the same key
 *   is nothing to do, and a plainly new one is written.
 * - Tunnels bound to every interface come in switched off, whatever the bundle said, so nothing
 *   an import brings listens on the network until the user turns it on where the binding shows.
 *
 * The Deck layout and the interface theme are this phone's one copy each, replaced rather than
 * added to, so [BundleImportOptions] lets the import leave either as it is.
 */
class BerthBundles(
    private val hosts: HostRepository,
    private val identities: IdentityRepository,
    private val workspaces: WorkspaceRepository,
    private val snippets: SnippetRepository,
    private val tunnels: TunnelRepository,
    private val knownHosts: KnownHostRepository,
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
    private val codec: BundleCodec = BundleCodec(),
) {
    /** Everything the bundle covers, as it stands now. Hardware identities come without a key. */
    suspend fun collect(exportedAt: Long, appVersion: String = ""): BerthBundle {
        val hostList = hosts.observeAll().first()
        val bundledIdentities = identities.observeAll().first().map { identity ->
            BundledIdentity.of(identity, if (identity.isHardwareBacked) null else identities.privateKey(identity.id))
        }
        val passwords = hostList.mapNotNull { (it.auth as? AuthMethod.Password)?.secretId }.distinct()
            .mapNotNull { id -> secrets.get(id)?.let { BundledSecret.of(id, it) } }
        return BerthBundle(
            exportedAt = exportedAt,
            appVersion = appVersion,
            hosts = hostList,
            identities = bundledIdentities,
            workspaces = workspaces.observeAll().first(),
            snippets = snippets.observeAll().first(),
            tunnels = tunnels.observeAll().first(),
            terminalThemes = settings.terminalThemes.first().filter { !it.builtIn },
            defaultTerminalThemeId = settings.defaultTerminalThemeId.first(),
            interfaceTheme = settings.interfaceTheme.first(),
            deck = settings.deckLayout.first(),
            knownHosts = knownHosts.observeAll().first(),
            passwords = passwords,
        )
    }

    /** The bundle file: [collect] sealed under [passphrase]. The passphrase is the caller's to clear. */
    suspend fun export(passphrase: CharArray, exportedAt: Long, appVersion: String = "", cost: BundleKdf = BundleKdf.DEFAULT): ByteArray {
        val document = collect(exportedAt, appVersion).toJson().toByteArray(Charsets.UTF_8)
        try {
            return codec.seal(document, passphrase, cost)
        } finally {
            document.fill(0)
        }
    }

    /**
     * The document [blob] seals, or [BundleException] when it does not open and
     * [BundleFormatException] when what it seals is not a document this build reads.
     */
    fun open(blob: ByteArray, passphrase: CharArray): BerthBundle {
        val document = codec.open(blob, passphrase)
        try {
            return BerthBundle.fromJson(document.toString(Charsets.UTF_8))
        } finally {
            document.fill(0)
        }
    }

    /**
     * What [apply] would do here with [bundle] beyond writing its records, for the sheet to say
     * before the import: read against this phone as it stands now, so the same rule runs again at
     * the write.
     */
    suspend fun plan(bundle: BerthBundle): BundleImportPlan {
        val standings = standings(bundle.knownHosts)
        return BundleImportPlan(
            knownHostsNew = standings.count { it.second == KnownHostStanding.NEW },
            knownHostsExisting = standings.count { it.second == KnownHostStanding.EXISTING },
            knownHostsKept = standings.filter { it.second.differs }.map { it.first },
            tunnelsOnEveryInterface = bundle.tunnels.filter { it.exposed },
        )
    }

    /** Each bundled key with where it stands against the keys this phone holds for its endpoint. */
    private suspend fun standings(bundled: List<KnownHostKey>): List<Pair<KnownHostKey, KnownHostStanding>> {
        val here = knownHosts.observeAll().first().groupBy { it.host.lowercase() to it.port }
        return bundled.map { key -> key to KnownHostStanding.of(key, here[key.host.lowercase() to key.port].orEmpty()) }
    }

    /** Writes [bundle] into the repositories and says what it did. */
    suspend fun apply(bundle: BerthBundle, options: BundleImportOptions = BundleImportOptions()): BundleImportReport {
        workspaces.upsertAll(bundle.workspaces)

        // Bundled identity id → the id that names the same key here.
        val local = identities.observeAll().first()
        val byId = local.associateBy { it.id }
        val byFingerprint = local.associateBy { it.fingerprintSha256 }
        val mapped = HashMap<String, String>()
        val absent = LinkedHashMap<String, Identity>()
        var identitiesWritten = 0
        for (entry in bundle.identities) {
            val carried = entry.identity
            val key = entry.privateKeyBytes()
            // A software key is what its bytes say it is, not what the document says about them.
            val identity = if (carried.isHardwareBacked || key == null) carried else withOwnPublicHalf(carried, key)
            val here = byId[identity.id] ?: byFingerprint[identity.fingerprintSha256]
            when {
                here != null -> mapped[identity.id] = here.id
                // Hardware-backed where the bundle was made, or a software key the bundle somehow lacks: nothing to store.
                identity.isHardwareBacked || key == null -> absent[identity.id] = identity
                else -> {
                    identities.insert(identity, key)
                    mapped[identity.id] = identity.id
                    identitiesWritten++
                }
            }
        }

        for (secret in bundle.passwords) secrets.put(secret.id, secret.bytes())

        val usedAbsent = LinkedHashMap<String, MutableList<String>>()
        for (host in bundle.hosts) {
            val auth = host.auth
            val patched = when {
                auth !is AuthMethod.Key -> host
                mapped[auth.identityId] != null -> host.copy(auth = AuthMethod.Key(mapped.getValue(auth.identityId)))
                absent.containsKey(auth.identityId) -> {
                    usedAbsent.getOrPut(auth.identityId) { mutableListOf() } += host.name
                    host.copy(auth = AuthMethod.AskEachTime)
                }
                // A key the bundle does not carry: this phone's, on a re-import, or gone, which connecting says.
                else -> host
            }
            hosts.upsert(patched)
        }

        var tunnelsHeldOff = 0
        for (tunnel in bundle.tunnels) {
            // Exposed is #13's rule: only an explicit `*`, `0.0.0.0` or `::` listens for other devices.
            if (tunnel.exposed && tunnel.enabled) tunnelsHeldOff++
            tunnels.upsert(if (tunnel.exposed) tunnel.copy(enabled = false) else tunnel)
        }
        for (snippet in bundle.snippets) snippets.upsert(snippet)

        var knownHostsWritten = 0
        var knownHostsKept = 0
        for ((key, standing) in standings(bundle.knownHosts)) {
            when (standing) {
                KnownHostStanding.NEW -> {
                    knownHosts.upsert(key)
                    knownHostsWritten++
                }
                KnownHostStanding.EXISTING -> Unit
                is KnownHostStanding.Conflicting, is KnownHostStanding.Pinned -> knownHostsKept++
            }
        }

        for (theme in bundle.terminalThemes) settings.upsertTerminalTheme(theme)
        bundle.defaultTerminalThemeId?.let { id ->
            if (settings.terminalThemes.first().any { it.id == id }) settings.setDefaultTerminalTheme(id)
        }
        if (options.interfaceTheme) bundle.interfaceTheme?.let { settings.setInterfaceTheme(it) }
        if (options.deck) bundle.deck?.let { settings.setDeckLayout(it) }

        return BundleImportReport(
            hosts = bundle.hosts.size,
            identities = identitiesWritten,
            workspaces = bundle.workspaces.size,
            snippets = bundle.snippets.size,
            tunnels = bundle.tunnels.size,
            terminalThemes = bundle.terminalThemes.size,
            knownHosts = knownHostsWritten,
            deck = options.deck && bundle.deck != null,
            needsRecreation = absent.values.map { identity ->
                RecreateNotice(identity.name, identity.algorithm, usedAbsent[identity.id].orEmpty())
            },
            knownHostsKept = knownHostsKept,
            tunnelsHeldOff = tunnelsHeldOff,
            interfaceTheme = options.interfaceTheme && bundle.interfaceTheme != null,
        )
    }

    /**
     * [identity] with the public half and fingerprint of [key]'s own bytes. A bundle Berth wrote
     * carries every software key as `openssh-key-v1`, whose public key sits in the clear ahead of
     * the private section; bytes that are not that are not a document Berth wrote.
     */
    private fun withOwnPublicHalf(identity: Identity, key: ByteArray): Identity {
        val public = SshKeys.publicKeyOfOpenSshPrivate(key.toString(Charsets.UTF_8)) ?: throw BundleFormatException.UnreadableKey(identity.name)
        return identity.copy(
            publicKeyOpenSsh = SshKeys.openSshPublic(public, identity.comment),
            fingerprintSha256 = SshKeys.fingerprintSha256(public),
        )
    }
}
