package app.berth.data.bundle

import app.berth.domain.model.AuthMethod
import app.berth.domain.model.BerthBundle
import app.berth.domain.model.BundleFormatException
import app.berth.domain.model.BundleImportReport
import app.berth.domain.model.BundledIdentity
import app.berth.domain.model.BundledSecret
import app.berth.domain.model.Identity
import app.berth.domain.model.RecreateNotice
import app.berth.domain.repository.HostRepository
import app.berth.domain.repository.IdentityRepository
import app.berth.domain.repository.KnownHostRepository
import app.berth.domain.repository.SecretStore
import app.berth.domain.repository.SettingsRepository
import app.berth.domain.repository.SnippetRepository
import app.berth.domain.repository.TunnelRepository
import app.berth.domain.repository.WorkspaceRepository
import kotlinx.coroutines.flow.first

/**
 * Export and import of the `.berth` bundle (spec C20, Data) over the repositories, so the same
 * code runs over Room and over the in-memory fakes the UI tests use.
 *
 * Export collects everything the bundle covers as it stands and seals it with [BundleCodec].
 * Import applies a document in dependency order (workspaces, identities, passwords, hosts, then
 * what refers to hosts) and upserts by id, so importing a bundle onto the phone that made it
 * restores what was deleted and leaves the rest as it was, and importing onto a new phone adds
 * everything. Identities are the exception: a key already here, by id or by fingerprint under
 * another id, is kept and the bundle's hosts are pointed at it, and a hardware-backed identity,
 * which comes without a key by construction, is not created; the hosts that used it ask each
 * time and the report names both so the user knows what to make again.
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

    /** Writes [bundle] into the repositories and says what it did. */
    suspend fun apply(bundle: BerthBundle): BundleImportReport {
        workspaces.upsertAll(bundle.workspaces)

        // Bundled identity id → the id that names the same key here.
        val local = identities.observeAll().first()
        val byId = local.associateBy { it.id }
        val byFingerprint = local.associateBy { it.fingerprintSha256 }
        val mapped = HashMap<String, String>()
        val absent = LinkedHashMap<String, Identity>()
        var identitiesWritten = 0
        for (entry in bundle.identities) {
            val identity = entry.identity
            val here = byId[identity.id] ?: byFingerprint[identity.fingerprintSha256]
            val key = entry.privateKeyBytes()
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

        for (tunnel in bundle.tunnels) tunnels.upsert(tunnel)
        for (snippet in bundle.snippets) snippets.upsert(snippet)
        for (key in bundle.knownHosts) knownHosts.upsert(key)

        for (theme in bundle.terminalThemes) settings.upsertTerminalTheme(theme)
        bundle.defaultTerminalThemeId?.let { id ->
            if (settings.terminalThemes.first().any { it.id == id }) settings.setDefaultTerminalTheme(id)
        }
        bundle.interfaceTheme?.let { settings.setInterfaceTheme(it) }
        bundle.deck?.let { settings.setDeckLayout(it) }

        return BundleImportReport(
            hosts = bundle.hosts.size,
            identities = identitiesWritten,
            workspaces = bundle.workspaces.size,
            snippets = bundle.snippets.size,
            tunnels = bundle.tunnels.size,
            terminalThemes = bundle.terminalThemes.size,
            knownHosts = bundle.knownHosts.size,
            deck = bundle.deck != null,
            needsRecreation = absent.values.map { identity ->
                RecreateNotice(identity.name, identity.algorithm, usedAbsent[identity.id].orEmpty())
            },
        )
    }
}
