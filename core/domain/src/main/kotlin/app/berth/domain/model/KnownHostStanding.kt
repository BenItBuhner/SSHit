package app.berth.domain.model

/**
 * Where a key offered for an endpoint stands against the keys Berth already trusts for it (spec
 * A16). It is the answer the live policy gives a server's key, and the answer the two imports give
 * a key read from a file: a `known_hosts` file's rows and a `.berth` bundle's known hosts, where a
 * [Conflicting] key is the Replace decision the changed-key sheet makes, a row that starts
 * unticked, and a [Pinned] endpoint takes nothing. The bundle import lays one rule of its own over
 * [of]: a key of a type this phone holds none of, for an endpoint it does hold a key for, stands
 * as [Conflicting] with what is held, since live that case asks the user and a bundle's row can.
 *
 * [of] is the one rule; the callers decide what each answer means for them.
 */
sealed interface KnownHostStanding {
    /** No key of this type is trusted for the endpoint: plainly new. */
    data object NEW : KnownHostStanding

    /** The very key is already trusted for the endpoint: nothing to do. */
    data object EXISTING : KnownHostStanding

    /** A different key of a type already trusted for the endpoint: the changed-key case. */
    data class Conflicting(val saved: KnownHostKey) : KnownHostStanding

    /** The endpoint has a pinned key and this is not it: a pin takes no other key, of any type. */
    data class Pinned(val saved: KnownHostKey) : KnownHostStanding

    companion object {
        /** Where a key of [keyType] with [publicKeyBase64] stands against [here], the keys held for its endpoint. */
        fun of(keyType: String, publicKeyBase64: String, here: List<KnownHostKey>): KnownHostStanding {
            if (here.any { it.publicKeyBase64 == publicKeyBase64 }) return EXISTING
            val pinned = here.firstOrNull { it.pinned && it.keyType == keyType } ?: here.firstOrNull { it.pinned }
            if (pinned != null) return Pinned(pinned)
            val sameType = here.firstOrNull { it.keyType == keyType }
            if (sameType != null) return Conflicting(sameType)
            return NEW
        }

        /** [of] for a stored key, against the keys held for its own endpoint. */
        fun of(key: KnownHostKey, here: List<KnownHostKey>): KnownHostStanding = of(key.keyType, key.publicKeyBase64, here)
    }
}
