package app.berth.android.ui.hosts

/** The editor's text forms of a host's tags and environment (spec C10), and the way back. */
object HostEditorFields {
    private val ENV_NAME = Regex("""^[A-Za-z_][A-Za-z0-9_]*$""")

    /** `prod, homelab` from a host's tags. */
    fun tagsText(tags: List<String>): String = tags.joinToString(", ")

    /** Tags from the field: split on commas, trimmed, empties dropped, each kept once (case aside), in the order written. */
    fun parseTags(text: String): List<String> {
        val seen = HashSet<String>()
        return text.split(',').map { it.trim() }.filter { it.isNotEmpty() && seen.add(it.lowercase()) }
    }

    /** One `NAME=value` per line from a host's environment. */
    fun environmentText(environment: Map<String, String>): String = environment.entries.joinToString("\n") { "${it.key}=${it.value}" }

    /**
     * The environment from the field, or null when a line is not `NAME=value` with a name a shell
     * takes (a letter or underscore, then letters, digits and underscores). Blank lines and lines
     * starting with `#` are skipped, as in an env file; a value keeps its spaces and any `=` after
     * the first, and a name written twice keeps its last value. [environmentProblem] says which
     * line stopped it.
     */
    fun parseEnvironment(text: String): Map<String, String>? = readEnvironment(text).first

    /** The first line [parseEnvironment] refuses, or null when it refuses none. */
    fun environmentProblem(text: String): BadLine? = readEnvironment(text).second

    private fun readEnvironment(text: String): Pair<Map<String, String>?, BadLine?> {
        val out = LinkedHashMap<String, String>()
        for ((index, raw) in text.lines().withIndex()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val eq = line.indexOf('=')
            if (eq <= 0) return null to BadLine(index + 1, "${line.shown()} is not NAME=value.")
            val name = line.substring(0, eq).trim()
            if (!ENV_NAME.matches(name)) return null to BadLine(index + 1, "${name.shown()} is not a name a shell takes.")
            out[name] = line.substring(eq + 1).trim()
        }
        return out to null
    }

    /** The text as an error names it: a long line cut to its head, so the line's number and the rule still fit under the field. */
    private fun String.shown(): String = if (length > 24) take(23) + "\u2026" else this

    /**
     * A line the Environment field cannot take: its [number], counted from 1 as the field's lines
     * are, and the [reason], a sentence naming the text ("1BAD is not a name a shell takes.").
     * [helper] is what goes under the field: the line first, then the rule.
     */
    data class BadLine(val number: Int, val reason: String) {
        val message: String get() = "Line $number: $reason"
        val helper: String get() = "$message $ENVIRONMENT_HELP"
    }

    /** The rule, under the line that broke it, when [parseEnvironment] refuses the text. */
    const val ENVIRONMENT_HELP = "One NAME=value per line; a name is letters, digits and underscores, not starting with a digit."
}
