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
     * the first, and a name written twice keeps its last value.
     */
    fun parseEnvironment(text: String): Map<String, String>? {
        val out = LinkedHashMap<String, String>()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val eq = line.indexOf('=')
            if (eq <= 0) return null
            val name = line.substring(0, eq).trim()
            if (!ENV_NAME.matches(name)) return null
            out[name] = line.substring(eq + 1).trim()
        }
        return out
    }

    /** The helper line under the Environment field when [parseEnvironment] refuses the text. */
    const val ENVIRONMENT_HELP = "One NAME=value per line; a name is letters, digits and underscores, not starting with a digit."
}
