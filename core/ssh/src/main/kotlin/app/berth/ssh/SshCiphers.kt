package app.berth.ssh

import net.schmizz.sshj.common.Factory
import net.schmizz.sshj.transport.cipher.Cipher

/**
 * A host's cipher list (spec C10, Advanced › Ciphers): the SSH names offered to its server, most
 * preferred first; the server takes the first one it also speaks. An empty list offers sshj's own,
 * which leads with ChaCha20-Poly1305 and keeps AES-CBC, 3DES and the other older ciphers after it
 * for a server that knows nothing newer.
 */
object SshCiphers {
    /**
     * OpenSSH's own default list, in its order: ChaCha20-Poly1305, AES-CTR and AES-GCM, and none
     * of CBC, 3DES, Blowfish or RC4. A server that speaks only those is refused rather than met there.
     */
    val MODERN: List<String> = listOf(
        "chacha20-poly1305@openssh.com",
        "aes128-ctr",
        "aes192-ctr",
        "aes256-ctr",
        "aes128-gcm@openssh.com",
        "aes256-gcm@openssh.com",
    )

    /**
     * [available] narrowed to [names], in the order [names] gives; a name this client has no
     * cipher for is passed over. With no names, or none it knows, [available] as it is: the host
     * editor only ever saves names it knows, so an unknown one came from a bundle made elsewhere,
     * and the full list is a better answer to that than a login that offers nothing.
     */
    fun select(available: List<Factory.Named<Cipher>>, names: List<String>): List<Factory.Named<Cipher>> {
        if (names.isEmpty()) return available
        val byName = available.associateBy { it.name }
        return names.distinct().mapNotNull { byName[it] }.ifEmpty { available }
    }
}
