package app.berth.data.bundle

import app.berth.domain.model.AuthMethod
import app.berth.domain.model.BerthBundle
import app.berth.domain.model.BundleFormatException
import app.berth.domain.model.BundleImportOptions
import app.berth.domain.model.BundleImportPlan
import app.berth.domain.model.BundleImportReport
import app.berth.domain.model.BundledIdentity
import app.berth.domain.model.BundledKnownHost
import app.berth.domain.model.BundledSecret
import app.berth.domain.model.Host
import app.berth.domain.model.Identity
import app.berth.domain.model.KnownHostKey
import app.berth.domain.model.KnownHostStanding
import app.berth.domain.model.RecreateNotice
import app.berth.domain.model.TerminalTheme
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
 *   the hosts that used it ask each time and the report names both so the user knows what to make
 *   again. A software key a hosts-only export ([collectHosts]) named without its private half
 *   takes the same path: matched here if this phone holds it, else named with its hosts.
 * - Known hosts: a bundled key for an endpoint this phone already holds a key for is not taken as
 *   carried, whether it differs from the key of its type, from a pin, or is of a type this phone
 *   holds none of for that endpoint ([standings]); the key this phone holds is the check between
 *   the user and a wrong key, and a bundle does not override it on its own. A key that differs
 *   from an unpinned one of its type is the changed-key case (spec C13), which the sheet offers as
 *   the Replace decision the changed-key sheet makes, unticked; ticked ([BundleImportOptions.replaceKnownHosts]),
 *   the saved key is deleted and the bundle's written in its place, as #19's `known_hosts` import
 *   and the live sheet's Replace do. A key of a type this phone holds none of for the endpoint is
 *   offered the same way, unticked, and its tick adds it beside the saved key, which stays, as an
 *   accept on the live first-connection sheet does (C13's additional key). A pinned endpoint takes
 *   nothing and is not offered. The same key is nothing to do, and a key for an endpoint held
 *   nothing of is written.
 * - Tunnels bound to every interface that the bundle had switched on come in switched off, so
 *   nothing an import brings listens on the network until the user turns it on where the binding shows.
 *
 * The Deck layout, the interface theme and the default terminal theme are this phone's one copy
 * each, replaced rather than added to, so [BundleImportOptions] lets the import leave any as it is.
 *
 * A bundle with a software key Berth cannot read is refused whole, by [plan] before the sheet
 * offers the import and by [apply] before its first write, so it leaves nothing behind.
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
            passwords = passwordsOf(hostList),
        )
    }

    /**
     * The hosts alone (spec C9, Hosts › Export): every host, the passwords it logs in with and the
     * tunnels defined on it, and each key a host logs in with as its public record only, no
     * private half whatever its kind. The phone that opens it points a host at the same key where
     * it holds one ([plan]'s [BundleImportPlan.identitiesHere]); elsewhere the host asks each time
     * and the report names the key. A tunnel bound to every interface comes in switched off, as
     * from any bundle. Nothing else goes in, so the import leaves the rest of that phone as it is.
     */
    suspend fun collectHosts(exportedAt: Long, appVersion: String = ""): BerthBundle {
        val hostList = hosts.observeAll().first()
        val hostIds = hostList.map { it.id }.toSet()
        val used = hostList.mapNotNull { (it.auth as? AuthMethod.Key)?.identityId }.toSet()
        return BerthBundle(
            exportedAt = exportedAt,
            appVersion = appVersion,
            hosts = hostList,
            identities = identities.observeAll().first().filter { it.id in used }.map { BundledIdentity.of(it, null) },
            tunnels = tunnels.observeAll().first().filter { it.hostId in hostIds },
            passwords = passwordsOf(hostList),
        )
    }

    private suspend fun passwordsOf(hostList: List<Host>): List<BundledSecret> =
        hostList.mapNotNull { (it.auth as? AuthMethod.Password)?.secretId }.distinct()
            .mapNotNull { id -> secrets.get(id)?.let { BundledSecret.of(id, it) } }

    /** The bundle file: [collect] sealed under [passphrase]. The passphrase is the caller's to clear. */
    suspend fun export(passphrase: CharArray, exportedAt: Long, appVersion: String = "", cost: BundleKdf = BundleKdf.DEFAULT): ByteArray =
        seal(collect(exportedAt, appVersion), passphrase, cost)

    /** The hosts-only bundle file: [collectHosts] sealed under [passphrase], as [export] seals everything. */
    suspend fun exportHosts(passphrase: CharArray, exportedAt: Long, appVersion: String = "", cost: BundleKdf = BundleKdf.DEFAULT): ByteArray =
        seal(collectHosts(exportedAt, appVersion), passphrase, cost)

    private fun seal(bundle: BerthBundle, passphrase: CharArray, cost: BundleKdf): ByteArray {
        val document = bundle.toJson().toByteArray(Charsets.UTF_8)
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
     * What [apply] would do here with [carried] beyond writing its records, for the sheet to say
     * before the import: read against this phone as it stands now, so the same rule runs again at
     * the write.
     */
    suspend fun plan(carried: BerthBundle): BundleImportPlan {
        val bundle = withStockThemeIdsFreed(carried)
        // A key Berth cannot read fails the plan here, so the sheet never offers the import.
        val read = readIdentities(bundle)
        val here = localIdentities()
        return BundleImportPlan(
            knownHosts = standings(bundle.knownHosts),
            tunnelsOnEveryInterface = bundle.tunnels.filter { it.exposed && it.enabled },
            defaultTerminalTheme = newDefaultTerminalTheme(bundle)?.name,
            identitiesHere = read.mapNotNull { (identity) -> here.match(identity)?.let { identity.id to it.name } }.toMap(),
        )
    }

    /** This phone's keys, looked up the way the import names a bundled key here: by id, else by the fingerprint of its public half. */
    private class LocalIdentities(all: List<Identity>) {
        private val byId = all.associateBy { it.id }
        private val byFingerprint = all.associateBy { it.fingerprintSha256 }

        fun match(identity: Identity): Identity? = byId[identity.id] ?: byFingerprint[identity.fingerprintSha256]
    }

    private suspend fun localIdentities() = LocalIdentities(identities.observeAll().first())

    /**
     * Each bundled key with where it stands for the import against the keys this phone holds for
     * its endpoint: [KnownHostStanding.of], with one rule of the import's own over it. A key of a
     * type this phone holds none of, for an endpoint it does hold a key for, is NEW to `of`: live,
     * that is the case the policy asks the user about, the trust sheet handed what is held for the
     * endpoint; here it stands as [KnownHostStanding.Conflicting] with what is held, so the sheet
     * asks the same way it asks about any key that differs, and its tick adds the key beside what
     * is held, as the live accept does ([BundledKnownHost.addsBeside]). The shared rule stays as it
     * is. Read against this phone once, so the write judges every key as the sheet showed it.
     */
    private suspend fun standings(bundled: List<KnownHostKey>): List<BundledKnownHost> {
        val here = knownHosts.observeAll().first().groupBy { KnownHostKey.canonicalHost(it.host) to it.port }
        return bundled.map { key ->
            val held = here[KnownHostKey.canonicalHost(key.host) to key.port].orEmpty()
            val standing = KnownHostStanding.of(key, held)
            BundledKnownHost(key, if (standing == KnownHostStanding.NEW && held.isNotEmpty()) KnownHostStanding.Conflicting(held.first()) else standing)
        }
    }

    /**
     * The theme the bundle would make this phone's default for new terminals, when taking it
     * changes anything: the bundle's default, if it is a theme the bundle carries or this phone has
     * (a built-in), and not the default here already. Null when the import leaves the default as it is.
     */
    private suspend fun newDefaultTerminalTheme(bundle: BerthBundle): TerminalTheme? {
        val id = bundle.defaultTerminalThemeId ?: return null
        if (id == settings.defaultTerminalThemeId.first()) return null
        return bundle.terminalThemes.firstOrNull { it.id == id } ?: settings.terminalThemes.first().firstOrNull { it.id == id }
    }

    /** A bundled identity as the import stores it, with the private bytes it came with, if any. */
    private data class ReadIdentity(val identity: Identity, val key: ByteArray?)

    /**
     * Every bundled identity read as its own bytes say ([withOwnPublicHalf]), before anything is
     * written: bytes Berth cannot read are [BundleFormatException.UnreadableKey] here, so the
     * refusal comes with nothing stored behind it.
     */
    private fun readIdentities(bundle: BerthBundle): List<ReadIdentity> = bundle.identities.map { entry ->
        val carried = entry.identity
        val key = entry.privateKeyBytes()
        // A software key is what its bytes say it is, not what the document says about them.
        ReadIdentity(if (carried.isHardwareBacked || key == null) carried else withOwnPublicHalf(carried, key), key)
    }

    /**
     * [bundle] with each custom theme under a stock id moved where the database step moves one
     * ([TerminalTheme.freedId]), and each host, group and the default naming it following. A
     * bundle made before the stock set grew can carry a custom `dracula`, which written as it came
     * would sit behind the stock Dracula; moved the same way as the phone's own, a bundle made on
     * this phone before the upgrade lands on the theme the upgrade moved instead of beside it.
     */
    private fun withStockThemeIdsFreed(bundle: BerthBundle): BerthBundle {
        val moved = bundle.terminalThemes.map { it.id }.filter(TerminalTheme::isStockId).associateWith(TerminalTheme::freedId)
        if (moved.isEmpty()) return bundle
        fun repointed(id: String?): String? = id?.let { moved[it] ?: it }
        return bundle.copy(
            terminalThemes = bundle.terminalThemes.map { theme -> moved[theme.id]?.let { theme.copy(id = it) } ?: theme },
            hosts = bundle.hosts.map { host -> host.copy(appearance = host.appearance.copy(terminalThemeId = repointed(host.appearance.terminalThemeId))) },
            workspaces = bundle.workspaces.map { workspace -> workspace.copy(terminalThemeId = repointed(workspace.terminalThemeId)) },
            defaultTerminalThemeId = repointed(bundle.defaultTerminalThemeId),
        )
    }

    /** Writes [carried] into the repositories and says what it did. */
    suspend fun apply(carried: BerthBundle, options: BundleImportOptions = BundleImportOptions()): BundleImportReport {
        val bundle = withStockThemeIdsFreed(carried)
        // Before the first write: a bundle this build refuses leaves nothing behind.
        val read = readIdentities(bundle)
        workspaces.upsertAll(bundle.workspaces)

        // Bundled identity id → the id that names the same key here.
        val local = localIdentities()
        val mapped = HashMap<String, String>()
        val absent = LinkedHashMap<String, Identity>()
        var identitiesWritten = 0
        for ((identity, key) in read) {
            val here = local.match(identity)
            when {
                here != null -> mapped[identity.id] = here.id
                // Hardware-backed where the bundle was made, or a software key a hosts-only export left there: nothing to store.
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
            // Held off is the one the import switches off, the count the plan's row gave; one already off comes in as it was.
            if (tunnel.exposed && tunnel.enabled) tunnelsHeldOff++
            tunnels.upsert(if (tunnel.exposed) tunnel.copy(enabled = false) else tunnel)
        }
        for (snippet in bundle.snippets) snippets.upsert(snippet)

        var knownHostsWritten = 0
        var knownHostsReplaced = 0
        var knownHostsKept = 0
        for (bundled in standings(bundle.knownHosts)) {
            when (val standing = bundled.standing) {
                KnownHostStanding.NEW -> {
                    knownHosts.upsert(bundled.key)
                    knownHostsWritten++
                }
                KnownHostStanding.EXISTING -> Unit
                // Ticked. A key of a type this phone holds: the changed-key sheet's Replace, the saved key goes and the
                // bundle's is written in its place. One of a type it holds none of: added beside the saved key, which
                // stays, as an accept on the live first-connection sheet saves it. The store keys the name lowercase
                // and reads it case-blind, so the bundle's spelling and the saved key's land on one row either way.
                // Two ticked keys for one endpoint both stand: the saved key goes once, on the tick that replaces it,
                // and each is written.
                is KnownHostStanding.Conflicting -> if (bundled.id in options.replaceKnownHosts) {
                    if (!bundled.addsBeside) {
                        knownHosts.delete(standing.saved.id)
                        knownHostsReplaced++
                    }
                    knownHosts.upsert(bundled.key)
                    knownHostsWritten++
                } else {
                    knownHostsKept++
                }
                is KnownHostStanding.Pinned -> knownHostsKept++
            }
        }

        for (theme in bundle.terminalThemes) settings.upsertTerminalTheme(theme)
        val newDefault = if (options.defaultTerminalTheme) newDefaultTerminalTheme(bundle) else null
        newDefault?.let { settings.setDefaultTerminalTheme(it.id) }
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
                RecreateNotice(identity.name, identity.algorithm, usedAbsent[identity.id].orEmpty(), hardware = identity.isHardwareBacked)
            },
            knownHostsKept = knownHostsKept,
            knownHostsReplaced = knownHostsReplaced,
            tunnelsHeldOff = tunnelsHeldOff,
            interfaceTheme = options.interfaceTheme && bundle.interfaceTheme != null,
            defaultTerminalTheme = newDefault != null,
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
