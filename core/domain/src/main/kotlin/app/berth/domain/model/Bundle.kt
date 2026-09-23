package app.berth.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What a `.berth` bundle carries (spec C20, Data; the vision's migration path between phones):
 * hosts, identities, workspaces, snippets, tunnels, themes, the Deck and known hosts, as one
 * JSON document. The document only ever exists inside the bundle's authenticated ciphertext, so
 * saved passwords and software private keys travel with the hosts and identities they belong to.
 *
 * Hardware-backed private keys are not here by construction: an [Identity] never holds key
 * material, the data layer has no bytes to hand out for a Keystore key, and [BundledIdentity]
 * refuses key bytes beside a hardware identity. Such an identity travels as its public half and
 * name only, so the import can say which keys need creating again.
 *
 * [format] is the document's own version, separate from the container's: a newer document is
 * refused whole rather than read with its new fields ignored. Format 2 lets a host's Keepalive and
 * Reconnect inherit (null, [PersistencePolicy]); a format 1 document stored the editor's starting
 * values on every host that never changed them, and reads here as [PersistencePolicy.foldLegacyDefaults] says.
 */
@Serializable
data class BerthBundle(
    val format: Int = FORMAT,
    @SerialName("exported_at") val exportedAt: Long,
    @SerialName("app_version") val appVersion: String = "",
    val hosts: List<Host> = emptyList(),
    val identities: List<BundledIdentity> = emptyList(),
    val workspaces: List<Workspace> = emptyList(),
    val snippets: List<Snippet> = emptyList(),
    val tunnels: List<Tunnel> = emptyList(),
    /** Custom terminal themes only; the stock ones are in every build. */
    @SerialName("terminal_themes") val terminalThemes: List<TerminalTheme> = emptyList(),
    @SerialName("default_terminal_theme") val defaultTerminalThemeId: String? = null,
    @SerialName("interface_theme") val interfaceTheme: InterfaceTheme? = null,
    val deck: DeckLayout? = null,
    @SerialName("known_hosts") val knownHosts: List<KnownHostKey> = emptyList(),
    /** The saved passwords the hosts above refer to by secret id. */
    val passwords: List<BundledSecret> = emptyList(),
) {
    /** Identities that were hardware-backed where the bundle was made: they come without a key. */
    val hardwareIdentities: List<Identity> get() = identities.filter { it.identity.isHardwareBacked }.map { it.identity }

    val isEmpty: Boolean
        get() = hosts.isEmpty() && identities.isEmpty() && workspaces.isEmpty() && snippets.isEmpty() && tunnels.isEmpty() &&
            terminalThemes.isEmpty() && deck == null && knownHosts.isEmpty() && interfaceTheme == null

    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        /** The document version this build writes and the newest it reads. */
        const val FORMAT = 2

        /** The file extension, and the name a fresh export suggests. */
        const val EXTENSION = "berth"

        /** The Deck's actions are a sealed hierarchy; its own JSON names the branch `kind`, so this does too. */
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            classDiscriminator = "kind"
        }

        /**
         * The document in [text], or [BundleFormatException] when it is not one this build reads: a
         * newer [format], or not a bundle document at all.
         */
        fun fromJson(text: String): BerthBundle {
            val bundle = try {
                json.decodeFromString(serializer(), text)
            } catch (e: IllegalArgumentException) {
                throw BundleFormatException.NotABundle(e.message)
            }
            if (bundle.format > FORMAT) throw BundleFormatException.NewerThanThisBuild(bundle.format)
            if (bundle.format >= 2) return bundle
            return bundle.copy(hosts = bundle.hosts.map { it.copy(persistence = it.persistence.foldLegacyDefaults()) })
        }
    }
}

/**
 * An identity as the bundle carries it: the public record and, for a software key, the OpenSSH
 * private key as it is stored (base64 of the file's bytes, passphrase protection and all). A
 * hardware-backed identity cannot carry key bytes: the constructor refuses them, so no export
 * path can put a Keystore key in a file even by mistake.
 */
@Serializable
data class BundledIdentity(
    val identity: Identity,
    @SerialName("private_key") val privateKey: String? = null,
) {
    init {
        require(!(identity.isHardwareBacked && privateKey != null)) { "a hardware-backed identity carries no private key" }
    }

    companion object {
        /**
         * [identity] with [privateKeyBytes], the bytes the data layer holds for a software key and
         * null for a hardware one. The Keystore alias is dropped: it names a key on the exporting
         * phone and nothing on another.
         */
        fun of(identity: Identity, privateKeyBytes: ByteArray?): BundledIdentity {
            val hardware = identity.isHardwareBacked
            return BundledIdentity(
                identity = identity.copy(keystoreAlias = null),
                privateKey = if (hardware) null else privateKeyBytes?.let { Base64Codec.encode(it) },
            )
        }
    }

    fun privateKeyBytes(): ByteArray? = privateKey?.let { Base64Codec.decode(it) }
}

/** A saved secret by the id the host's auth refers to; [value] is base64 of the bytes. */
@Serializable
data class BundledSecret(val id: String, val value: String) {
    fun bytes(): ByteArray = Base64Codec.decode(value)

    companion object {
        fun of(id: String, bytes: ByteArray) = BundledSecret(id, Base64Codec.encode(bytes))
    }
}

/**
 * What a bundle's document is not, before any of it is read: the two cases the import tells apart
 * from a wrong passphrase, which the container layer raises on its own.
 */
sealed class BundleFormatException(message: String) : RuntimeException(message) {
    class NotABundle(detail: String?) : BundleFormatException("This is not a Berth bundle" + (detail?.let { ": $it" } ?: "."))
    class NewerThanThisBuild(val format: Int) : BundleFormatException("This bundle was made by a newer Berth (document format $format).")

    /** A software identity whose private bytes are not an OpenSSH key: not a document Berth wrote. */
    class UnreadableKey(identityName: String) : BundleFormatException("The key \u201C$identityName\u201D in this bundle is not one Berth can read.")
}

/**
 * What the import sheet decides for the user, as the sheet leaves it: the three things in a bundle
 * that are not records with an id but this phone's one copy, and so are replaced rather than added
 * to, each a switch on by default; and the bundled known hosts that differ from a key this phone
 * trusts for their endpoint ([BundleImportPlan.knownHostsConflicting]), each a decision (spec C13)
 * offered unticked, [replaceKnownHosts] naming the ones ticked ([BundledKnownHost.id]): a key of a
 * type this phone holds takes the saved key's place, and one of a type it holds none of is added
 * beside the saved key ([BundledKnownHost.addsBeside]).
 */
data class BundleImportOptions(
    val deck: Boolean = true,
    val interfaceTheme: Boolean = true,
    val defaultTerminalTheme: Boolean = true,
    val replaceKnownHosts: Set<String> = emptySet(),
)

/**
 * What an import of a bundle would do here beyond writing its records, read before it is done so
 * the sheet can say so: every bundled known host by where it stands against this phone's keys for
 * its endpoint ([KnownHostStanding]), the tunnels that would listen on every interface, which come
 * in switched off, and the theme that would become the default for new terminals.
 */
data class BundleImportPlan(
    /** The bundled known hosts, each with its standing, in the bundle's order. */
    val knownHosts: List<BundledKnownHost>,
    /** Bundled tunnels bound to every interface that the bundle had switched on: imported switched off. */
    val tunnelsOnEveryInterface: List<Tunnel>,
    /**
     * The name of the theme the bundle would make this phone's default for new terminals, one the
     * bundle carries or this phone has; null when the import leaves the default as it is, the
     * bundle's being this phone's already or none this phone will have.
     */
    val defaultTerminalTheme: String? = null,
) {
    /** Bundled keys for endpoints this phone holds nothing for: written, no decision to make. */
    val knownHostsNew: Int get() = knownHosts.count { it.standing == KnownHostStanding.NEW }

    /** Bundled keys this phone already trusts as they are: nothing to do. */
    val knownHostsExisting: Int get() = knownHosts.count { it.standing == KnownHostStanding.EXISTING }

    /** The endpoints of the keys that need no decision, [knownHostsNew] and [knownHostsExisting], for the sheet's one count row. */
    val knownHostsRoutine: List<BundledKnownHost> get() = knownHosts.filter { it.standing == KnownHostStanding.NEW || it.standing == KnownHostStanding.EXISTING }

    /**
     * Bundled keys that differ from a key this phone trusts for their endpoint, the pin aside: the
     * changed-key case (spec C13), each offered as a decision the sheet starts unticked, the Replace
     * for a key of a type this phone holds, the add beside for one it holds none of ([BundledKnownHost.addsBeside]).
     */
    val knownHostsConflicting: List<BundledKnownHost> get() = knownHosts.filter { it.standing is KnownHostStanding.Conflicting }

    /** Bundled keys for an endpoint this phone pins another key for: not offered, since a pin takes no other key. */
    val knownHostsPinned: List<BundledKnownHost> get() = knownHosts.filter { it.standing is KnownHostStanding.Pinned }
}

/**
 * One known host as a bundle carries it, with where it stands against the keys this phone holds
 * for its endpoint. [id] names it in [BundleImportOptions.replaceKnownHosts]: the endpoint and the
 * public key, so two copies of one key in a bundle are one decision, and the bundle's own record
 * ids, another phone's, decide nothing here.
 */
data class BundledKnownHost(val key: KnownHostKey, val standing: KnownHostStanding) {
    val id: String get() = "${key.host.lowercase()}:${key.port}:${key.publicKeyBase64}"

    /**
     * Whether a tick on this key adds it beside the saved key rather than in its place: a
     * [KnownHostStanding.Conflicting] key of a type this phone holds none of for the endpoint,
     * which live is the first-connection question and an accept there saves beside what is held
     * (spec C13's additional key). A tick on a key of a type this phone holds is the Replace.
     */
    val addsBeside: Boolean get() = standing is KnownHostStanding.Conflicting && standing.saved.keyType != key.keyType
}

/**
 * What an import did (spec C20, Data): the counts, and the identities that were hardware-backed
 * on the phone that made the bundle, with the hosts that used them, since those keys have to be
 * made again here and the hosts ask each time until they are.
 */
data class BundleImportReport(
    val hosts: Int,
    val identities: Int,
    val workspaces: Int,
    val snippets: Int,
    val tunnels: Int,
    val terminalThemes: Int,
    /** Known hosts written, the [knownHostsReplaced] among them; the ones kept as this phone had them are [knownHostsKept]. */
    val knownHosts: Int,
    val deck: Boolean,
    val needsRecreation: List<RecreateNotice>,
    /** Bundled known-host keys that differed from what this phone trusts and were not taken: the conflicts left unticked, and every pinned endpoint's. */
    val knownHostsKept: Int = 0,
    /** Bundled known-host keys that took the place of a key this phone trusted, on the sheet's tick ([BundleImportOptions.replaceKnownHosts]). */
    val knownHostsReplaced: Int = 0,
    /** Tunnels bound to every interface that the bundle had switched on, imported switched off. */
    val tunnelsHeldOff: Int = 0,
    /** Whether the bundle's interface theme was taken over this phone's ([BundleImportOptions.interfaceTheme]). */
    val interfaceTheme: Boolean = false,
    /** Whether the bundle's default terminal theme became this phone's ([BundleImportOptions.defaultTerminalTheme]); false when it was already. */
    val defaultTerminalTheme: Boolean = false,
) {
    /** One line for a notice: `Imported 12 hosts, 3 keys and 8 snippets.`; a known host that took a saved key's place is said so, `2 known hosts (1 replaced)`. */
    val summary: String
        get() {
            val parts = buildList {
                if (hosts > 0) add(count(hosts, "host"))
                if (identities > 0) add(count(identities, "key"))
                if (workspaces > 0) add(count(workspaces, "workspace"))
                if (snippets > 0) add(count(snippets, "snippet"))
                if (tunnels > 0) add(count(tunnels, "tunnel"))
                if (terminalThemes > 0) add(count(terminalThemes, "theme"))
                if (knownHosts > 0) add(count(knownHosts, "known host") + if (knownHostsReplaced > 0) " ($knownHostsReplaced replaced)" else "")
                if (deck) add("the Deck")
                if (interfaceTheme) add("the interface theme")
                if (defaultTerminalTheme) add("the default terminal theme")
            }
            return when (parts.size) {
                0 -> "Nothing to import."
                1 -> "Imported ${parts[0]}."
                else -> "Imported " + parts.dropLast(1).joinToString(", ") + " and " + parts.last() + "."
            }
        }

    companion object {
        fun count(n: Int, noun: String) = "$n $noun" + if (n == 1) "" else "s"
    }
}

/** A hardware-backed identity the bundle could not carry, and the hosts that used it. */
data class RecreateNotice(val identityName: String, val algorithm: KeyAlgorithm, val hostNames: List<String>)

/** Standard base64 with padding, the JDK's (API 26 and later on Android). */
object Base64Codec {
    fun encode(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)
    fun decode(text: String): ByteArray = java.util.Base64.getDecoder().decode(text)
}
