package app.berth.ssh

import app.berth.domain.model.TunnelType

/** A `LocalForward`, `RemoteForward` or `DynamicForward` line, in the shape of a tunnel. */
data class SshConfigForward(
    val type: TunnelType,
    val bindAddress: String,
    val bindPort: Int,
    val destinationHost: String,
    val destinationPort: Int,
)

/** One concrete `Host` alias from an OpenSSH client config with its effective settings. */
data class SshConfigHost(
    val alias: String,
    /** `HostName`, or the alias itself when none was given. */
    val hostName: String,
    val user: String?,
    val port: Int,
    val identityFiles: List<String>,
    /** `ProxyJump` chain, first hop first; entries are aliases or `user@host:port` specs. */
    val proxyJump: List<String>,
    val forwards: List<SshConfigForward>,
    val compression: Boolean?,
    val serverAliveInterval: Int?,
    /** `inet`, `inet6` or `any` as written. */
    val addressFamily: String?,
    val forwardAgent: Boolean?,
    /** Startup command from `RemoteCommand`, when present. */
    val remoteCommand: String?,
)

data class SshConfigParseResult(
    val hosts: List<SshConfigHost>,
    /** Things in the file that could not be carried over, worded for the import sheet. */
    val notes: List<String>,
)

/**
 * Parses `~/.ssh/config` text. Follows OpenSSH semantics for the parts that matter to an import:
 * the first obtained value of a keyword wins, `Host` blocks apply to every alias their patterns
 * match (so a `Host *` block supplies defaults), and only concrete aliases (no `*`, `?` or `!`)
 * become hosts. `Match` blocks and `Include` files are reported in [SshConfigParseResult.notes]
 * rather than guessed at.
 */
object SshConfigParser {
    private class Block(val patterns: List<String>, val entries: MutableList<Pair<String, String>> = ArrayList())

    fun parse(text: String): SshConfigParseResult {
        val blocks = ArrayList<Block>()
        val notes = LinkedHashSet<String>()
        var current = Block(listOf("*")).also { blocks += it }
        var skipping = false
        var matchBlocks = 0

        for (rawLine in text.lineSequence()) {
            val line = rawLine.substringBefore('#').trim()
            if (line.isEmpty()) continue
            val (keyword, value) = splitKeyword(line) ?: continue
            when (keyword) {
                "host" -> {
                    skipping = false
                    current = Block(tokenize(value)).also { blocks += it }
                }
                "match" -> {
                    skipping = true
                    matchBlocks++
                }
                "include" -> notes += "Include ${tokenize(value).joinToString(", ")} was not followed; paste that file too if it has hosts."
                else -> if (!skipping) current.entries += keyword to value
            }
        }
        if (matchBlocks > 0) notes += "$matchBlocks Match ${if (matchBlocks == 1) "block was" else "blocks were"} skipped; only Host blocks import."

        val aliases = LinkedHashSet<String>()
        for (block in blocks) block.patterns.filter(::isConcrete).forEach { aliases += it }

        val hosts = aliases.map { alias ->
            val effective = LinkedHashMap<String, String>()
            val identityFiles = ArrayList<String>()
            val forwards = ArrayList<SshConfigForward>()
            for (block in blocks) {
                if (!matches(alias, block.patterns)) continue
                for ((key, value) in block.entries) {
                    when (key) {
                        "identityfile" -> identityFiles += tokenize(value)
                        "localforward" -> parseForward(TunnelType.LOCAL, value)?.let(forwards::add)
                            ?: notes.add("Couldn't read LocalForward \"$value\" on $alias.")
                        "remoteforward" -> parseForward(TunnelType.REMOTE, value)?.let(forwards::add)
                            ?: notes.add("Couldn't read RemoteForward \"$value\" on $alias.")
                        "dynamicforward" -> parseForward(TunnelType.DYNAMIC, value)?.let(forwards::add)
                            ?: notes.add("Couldn't read DynamicForward \"$value\" on $alias.")
                        else -> effective.putIfAbsent(key, value)
                    }
                }
            }
            val hostName = effective["hostname"]?.let { expandTokens(it, alias) } ?: alias
            SshConfigHost(
                alias = alias,
                hostName = hostName,
                user = effective["user"]?.let { firstToken(it) },
                port = effective["port"]?.let { firstToken(it).toIntOrNull() }?.takeIf { it in 1..65535 } ?: 22,
                identityFiles = identityFiles.distinct(),
                proxyJump = effective["proxyjump"]?.takeUnless { it.equals("none", ignoreCase = true) }
                    ?.split(',')?.map(String::trim)?.filter(String::isNotEmpty) ?: emptyList(),
                forwards = forwards,
                compression = effective["compression"]?.let(::yesNo),
                serverAliveInterval = effective["serveraliveinterval"]?.let { firstToken(it).toIntOrNull() },
                addressFamily = effective["addressfamily"]?.let { firstToken(it).lowercase() },
                forwardAgent = effective["forwardagent"]?.let(::yesNo),
                remoteCommand = effective["remotecommand"]?.let(::unquote)?.takeIf(String::isNotBlank),
            )
        }
        return SshConfigParseResult(hosts, notes.toList())
    }

    /** `Keyword value`, `Keyword=value` or `Keyword = value`; keyword lower-cased. */
    private fun splitKeyword(line: String): Pair<String, String>? {
        val separator = line.indexOfFirst { it == ' ' || it == '\t' || it == '=' }
        if (separator <= 0) return null
        val keyword = line.substring(0, separator).lowercase()
        val value = line.substring(separator).trimStart(' ', '\t').removePrefix("=").trim()
        if (value.isEmpty()) return null
        return keyword to value
    }

    /** Whitespace-separated tokens honouring double quotes. */
    private fun tokenize(value: String): List<String> {
        val tokens = ArrayList<String>()
        val current = StringBuilder()
        var quoted = false
        for (ch in value) {
            when {
                ch == '"' -> quoted = !quoted
                !quoted && (ch == ' ' || ch == '\t') -> if (current.isNotEmpty()) { tokens += current.toString(); current.clear() }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) tokens += current.toString()
        return tokens
    }

    private fun firstToken(value: String): String = tokenize(value).firstOrNull() ?: value.trim()

    private fun unquote(value: String): String = value.trim().removeSurrounding("\"")

    private fun yesNo(value: String): Boolean? = when (firstToken(value).lowercase()) {
        "yes", "true" -> true
        "no", "false" -> false
        else -> null
    }

    private fun isConcrete(pattern: String): Boolean =
        pattern.isNotEmpty() && !pattern.startsWith("!") && pattern.none { it == '*' || it == '?' }

    /** OpenSSH pattern-list semantics: a negated pattern that matches excludes; otherwise any match wins. */
    internal fun matches(alias: String, patterns: List<String>): Boolean {
        var matched = false
        for (pattern in patterns) {
            if (pattern.startsWith("!")) {
                if (globMatches(pattern.substring(1), alias)) return false
            } else if (globMatches(pattern, alias)) {
                matched = true
            }
        }
        return matched
    }

    private fun globMatches(pattern: String, text: String): Boolean {
        val regex = buildString {
            append('^')
            for (ch in pattern) {
                when (ch) {
                    '*' -> append(".*")
                    '?' -> append('.')
                    else -> append(Regex.escape(ch.toString()))
                }
            }
            append('$')
        }
        return Regex(regex, RegexOption.IGNORE_CASE).matches(text)
    }

    /** Only `%h` (the alias) and `%%` are meaningful before connecting. */
    private fun expandTokens(value: String, alias: String): String =
        firstToken(value).replace("%%", "\u0000").replace("%h", alias).replace("\u0000", "%")

    /**
     * `[bind_address:]port host:hostport` for local and remote forwards, `[bind_address:]port` for
     * dynamic ones. IPv6 hosts may be written in brackets; `*` means all interfaces.
     */
    internal fun parseForward(type: TunnelType, value: String): SshConfigForward? {
        val tokens = tokenize(value)
        val listen = tokens.getOrNull(0)?.let(::splitHostPort) ?: return null
        val bindAddress = when (val h = listen.first) {
            null, "localhost" -> "127.0.0.1"
            "*" -> "0.0.0.0"
            else -> h
        }
        val bindPort = listen.second ?: return null
        return when (type) {
            TunnelType.DYNAMIC -> SshConfigForward(type, bindAddress, bindPort, "", 0)
            TunnelType.LOCAL, TunnelType.REMOTE -> {
                val target = tokens.getOrNull(1)?.let(::splitHostPort) ?: return null
                val destination = target.first ?: return null
                val destinationPort = target.second ?: return null
                SshConfigForward(type, bindAddress, bindPort, destination, destinationPort)
            }
        }
    }

    /** `host:port`, `[v6]:port`, `port` or `host/port`; the host is null when only a port was given. */
    private fun splitHostPort(spec: String): Pair<String?, Int?>? {
        val s = spec.trim()
        if (s.isEmpty()) return null
        if (s.startsWith("[")) {
            val end = s.indexOf(']')
            if (end < 0) return null
            val port = s.substring(end + 1).removePrefix(":").removePrefix("/").toIntOrNull()
            return s.substring(1, end) to port
        }
        val separator = if (s.contains('/')) s.lastIndexOf('/') else s.lastIndexOf(':')
        if (separator < 0) return null to s.toIntOrNull()
        val host = s.substring(0, separator)
        val port = s.substring(separator + 1).toIntOrNull()
        return host.ifEmpty { null } to port
    }
}
