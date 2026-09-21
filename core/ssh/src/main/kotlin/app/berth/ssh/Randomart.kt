package app.berth.ssh

import java.security.MessageDigest
import java.security.PublicKey

/**
 * OpenSSH's visual host key ("randomart"), as `ssh-keygen -lv` and `VisualHostKey yes` draw it,
 * character for character, so a picture on the trust sheet can be held next to the one a server
 * prints. The algorithm is the drunken bishop of `sshkey.c`: the fingerprint's bytes are read two
 * bits at a time as diagonal moves of a piece on a 17×9 board, each square counts its visits, and
 * the count picks the glyph; `S` marks the start and `E` where the piece ended.
 */
object Randomart {
    const val WIDTH = 17
    const val HEIGHT = 9
    private const val GLYPHS = " .o+=*BOX@%&#/^SE"

    /** The full art with its `[TYPE bits]` and `[SHA256]` borders, eleven lines joined by `\n`. */
    fun of(key: PublicKey): String = lines(key).joinToString("\n")

    /** [of] for a key given as its base64 wire blob, as a known host or a trust prompt carries it. */
    fun of(publicKeyBase64: String): String = of(SshKeys.parsePublicKeyBlob(publicKeyBase64))

    fun lines(key: PublicKey): List<String> {
        val digest = MessageDigest.getInstance("SHA-256").digest(SshKeys.publicKeyBlob(key))
        val type = SshKeys.keygenType(key)
        // `[type bits]` when it fits the border, else `[type]`, as OpenSSH falls back for `[ED25519-CERT 256]`.
        val title = "[$type ${SshKeys.bits(key)}]".takeIf { it.length <= WIDTH } ?: "[$type]"
        return lines(digest, title, "[SHA256]")
    }

    /** The board rows framed by borders whose captions sit as OpenSSH sits them (left when the padding is odd). */
    fun lines(digest: ByteArray, title: String, footer: String): List<String> {
        val field = Array(WIDTH) { IntArray(HEIGHT) }
        var x = WIDTH / 2
        var y = HEIGHT / 2
        val cap = GLYPHS.length - 1
        for (byte in digest) {
            var input = byte.toInt() and 0xFF
            repeat(4) {
                x += if (input and 0x1 != 0) 1 else -1
                y += if (input and 0x2 != 0) 1 else -1
                x = x.coerceIn(0, WIDTH - 1)
                y = y.coerceIn(0, HEIGHT - 1)
                if (field[x][y] < cap - 2) field[x][y]++
                input = input shr 2
            }
        }
        field[WIDTH / 2][HEIGHT / 2] = cap - 1
        field[x][y] = cap
        val rows = (0 until HEIGHT).map { row ->
            buildString {
                append('|')
                for (col in 0 until WIDTH) append(GLYPHS[minOf(field[col][row], cap)])
                append('|')
            }
        }
        return listOf(border(title)) + rows + border(footer)
    }

    private fun border(caption: String): String {
        val text = caption.take(WIDTH)
        val left = (WIDTH - text.length) / 2
        return "+" + "-".repeat(left) + text + "-".repeat(WIDTH - left - text.length) + "+"
    }
}
