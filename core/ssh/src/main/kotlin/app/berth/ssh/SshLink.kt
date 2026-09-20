package app.berth.ssh

import app.berth.domain.model.TunnelType
import java.net.URLDecoder

/**
 * What an `ssh://` or `sftp://` link asks for, or a bare `user@host:port` handed to Berth as text.
 *
 * The forms read, after the URI scheme draft for ssh and sftp (draft-ietf-secsh-scp-sftp-ssh-uri):
 *
 *     ssh://[user[;fingerprint=<fp>]@]host[:port][/][?query][#name]
 *     sftp://[user[;fingerprint=<fp>]@]host[:port][/path][?query][#name]
 *     [user@]host[:port]
 *
 * The host is a name, an IPv4 address, or an IPv6 address in brackets. The query carries OpenSSH's
 * forward flags, each repeatable: `L=[bind:]port:host:hostport`, `R=[bind:]port:host:hostport` and
 * `D=[bind:]port` (long forms `local`, `remote`, `dynamic`), and `N` for no shell, as `ssh -N`. A
 * link that only asks for forwards, or says `N`, is a login that carries the forwards and opens no
 * shell. A forward's bind address left out, or left empty (`:8080:host:80`), is loopback; every
 * interface has to be written (`*`). The fragment names the host when one is made from the link
 * (ConnectBot writes `#name`). Anything else in the query is left alone rather than refused.
 */
data class SshLink(
    val scheme: Scheme,
    /** Null when the link leaves the user out; the host's own user then, or the editor's field. */
    val user: String?,
    val host: String,
    /** 22 when the link gives none. */
    val port: Int,
    /** The `sftp://` folder to open, absolute, without a trailing slash; null for the home folder or on `ssh://`. */
    val path: String?,
    /** The `;fingerprint=` connection parameter as written, kept for a check against the host key. */
    val fingerprint: String?,
    val forwards: List<SshConfigForward>,
    /** False when the link says `N` or asks only for forwards: a login with no shell. */
    val shell: Boolean,
    /** The fragment, as the name of a host made from this link. */
    val name: String?,
) {
    enum class Scheme { SSH, SFTP }

    /** The ask is the host's forwards with no shell: `ssh://` with `N`, or with forwards and nothing else. */
    val tunnelsOnly: Boolean get() = scheme == Scheme.SSH && !shell

    /** `user@host:port` as a row would show it: the user when given, the port when it is not 22. */
    val target: String
        get() = buildString {
            if (user != null) append(user).append('@')
            append(if (':' in host) "[$host]" else host)
            if (port != 22) append(':').append(port)
        }

    sealed interface Result {
        data class Parsed(val link: SshLink) : Result

        /** [reason] is one sentence for a notice, naming the part that was wrong. */
        data class Malformed(val reason: String) : Result
    }

    companion object {
        private val SCHEME = Regex("""^([A-Za-z][A-Za-z0-9+.-]*)://""")
        private val HOST_NAME = Regex("""^[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9_])?$""")
        private val IPV6 = Regex("""^[0-9A-Fa-f:.]+(?:%[A-Za-z0-9._-]+)?$""")

        /** Reads [text] as a link; a [Result.Malformed] names the first thing wrong with it and never throws. */
        fun parse(text: String): Result {
            val raw = text.trim()
            if (raw.isEmpty()) return Result.Malformed("The link is empty.")

            val schemeMatch = SCHEME.find(raw)
            val scheme = when (schemeMatch?.groupValues?.get(1)?.lowercase()) {
                null -> Scheme.SSH
                "ssh" -> Scheme.SSH
                "sftp" -> Scheme.SFTP
                else -> return Result.Malformed("Only ssh:// and sftp:// links open here, not ${schemeMatch.groupValues[1]}://.")
            }
            var rest = if (schemeMatch != null) raw.substring(schemeMatch.range.last + 1) else raw

            val fragment = rest.substringAfter('#', "").takeIf { '#' in rest }
            rest = rest.substringBefore('#')
            val query = rest.substringAfter('?', "").takeIf { '?' in rest }
            rest = rest.substringBefore('?')
            val path = rest.substringAfter('/', "").takeIf { '/' in rest }?.let { "/$it" }
            val authority = rest.substringBefore('/')
            if (authority.isEmpty()) return Result.Malformed("The link names no host.")
            if (authority.any { it.isWhitespace() }) return Result.Malformed("The link has a space in the host part.")

            val at = authority.lastIndexOf('@')
            val userInfo = if (at >= 0) authority.substring(0, at) else null
            val hostPort = if (at >= 0) authority.substring(at + 1) else authority
            var user: String? = null
            var fingerprint: String? = null
            if (userInfo != null) {
                val pieces = userInfo.split(';')
                user = decode(pieces[0])
                if (user.isEmpty()) return Result.Malformed("The user before @ is empty.")
                for (param in pieces.drop(1)) {
                    val key = param.substringBefore('=')
                    if (key.equals("fingerprint", ignoreCase = true) && '=' in param) fingerprint = decode(param.substringAfter('='))
                }
            }

            val host: String
            val portText: String?
            if (hostPort.startsWith("[")) {
                val end = hostPort.indexOf(']')
                if (end < 0) return Result.Malformed("The IPv6 address is missing its closing bracket.")
                host = hostPort.substring(1, end)
                if (host.isEmpty() || !IPV6.matches(host)) return Result.Malformed("What is in the brackets isn't an IPv6 address.")
                val tail = hostPort.substring(end + 1)
                portText = when {
                    tail.isEmpty() -> null
                    tail.startsWith(":") -> tail.substring(1)
                    else -> return Result.Malformed("Only a :port can follow the IPv6 address.")
                }
            } else {
                val colons = hostPort.count { it == ':' }
                if (colons > 1) return Result.Malformed("An IPv6 address needs brackets: [address]:port.")
                host = hostPort.substringBefore(':')
                portText = if (colons == 1) hostPort.substringAfter(':') else null
                if (host.isEmpty()) return Result.Malformed("The link names no host.")
                if (!HOST_NAME.matches(host)) return Result.Malformed("\u201C$host\u201D isn't a host name or address.")
            }
            val port = when {
                portText == null -> 22
                portText.isEmpty() -> return Result.Malformed("The port after : is empty.")
                else -> portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: return Result.Malformed("\u201C$portText\u201D isn't a port from 1 to 65535.")
            }

            val forwards = ArrayList<SshConfigForward>()
            var noShell = false
            if (query != null) {
                for (pair in query.split('&')) {
                    if (pair.isEmpty()) continue
                    val key = decode(pair.substringBefore('='))
                    val value = if ('=' in pair) decode(pair.substringAfter('=')) else ""
                    when {
                        key == "L" || key.equals("local", ignoreCase = true) -> forwards += forward(TunnelType.LOCAL, value) ?: return Result.Malformed("The forward \u201C$value\u201D isn't [bind:]port:host:hostport.")
                        key == "R" || key.equals("remote", ignoreCase = true) -> forwards += forward(TunnelType.REMOTE, value) ?: return Result.Malformed("The remote forward \u201C$value\u201D isn't [bind:]port:host:hostport.")
                        key == "D" || key.equals("dynamic", ignoreCase = true) -> forwards += forward(TunnelType.DYNAMIC, value) ?: return Result.Malformed("The dynamic forward \u201C$value\u201D isn't [bind:]port.")
                        key == "N" -> noShell = true
                    }
                }
            }

            val folder = path?.let(::decode)?.trimEnd('/')?.takeIf { it.isNotEmpty() && scheme == Scheme.SFTP }
            val name = fragment?.let(::decode)?.trim()?.takeIf { it.isNotEmpty() }
            return Result.Parsed(
                SshLink(
                    scheme = scheme,
                    user = user,
                    host = host,
                    port = port,
                    path = folder,
                    fingerprint = fingerprint,
                    forwards = forwards,
                    shell = !noShell && forwards.isEmpty(),
                    name = name,
                ),
            )
        }

        /**
         * `ssh -L`'s `[bind:]port:host:hostport` (or `-D`'s `[bind:]port`) put in the config file's
         * `[bind:]port host:hostport` shape and read by the config parser, so a link's forward and
         * an imported one come out the same. Brackets keep an IPv6 address in one piece.
         */
        private fun forward(type: TunnelType, value: String): SshConfigForward? {
            val parts = splitColons(value).toMutableList()
            var listenParts = if (type == TunnelType.DYNAMIC) parts.size else parts.size - 2
            if (listenParts !in 1..2) return null
            // `:8080:host:80`: `ssh -L` reads an empty bind address as every interface. A link is
            // not trusted that far: here it is no bind address, so loopback, and a listener on
            // every interface has to be written out (`*:8080:host:80`) to be asked for.
            if (listenParts == 2 && parts[0].isEmpty()) {
                parts.removeAt(0)
                listenParts = 1
            }
            if (parts.any { it.isEmpty() }) return null
            val listen = parts.take(listenParts).joinToString(":")
            val spec = if (type == TunnelType.DYNAMIC) listen else "$listen ${parts[listenParts]}:${parts[listenParts + 1]}"
            return SshConfigParser.parseForward(type, spec)
        }

        private fun splitColons(value: String): List<String> {
            val parts = ArrayList<String>()
            val current = StringBuilder()
            var depth = 0
            for (ch in value) {
                when {
                    ch == '[' -> { depth++; current.append(ch) }
                    ch == ']' -> { depth--; current.append(ch) }
                    ch == ':' && depth == 0 -> { parts += current.toString(); current.setLength(0) }
                    else -> current.append(ch)
                }
            }
            parts += current.toString()
            return parts
        }

        private fun decode(text: String): String = try {
            URLDecoder.decode(text.replace("+", "%2B"), "UTF-8")
        } catch (e: IllegalArgumentException) {
            text
        }
    }
}
