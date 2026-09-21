package app.berth.android.ui.stage

/**
 * The hosts under which the next label belongs to someone else: a Pages site, a bucket, a tenant,
 * a tunnel, a dynamic-DNS name. The link sheet's caption ([LinkLook]) takes a text that names a
 * parent of the link's host for the same site (`github.com` over gist.github.com), which no
 * stranger can arrange; but `github.io` over evil.github.io or `s3.amazonaws.com` over
 * evil-bucket.s3.amazonaws.com is a claim of the platform over a page any customer of it can put
 * there, and [boundaryOf] is how the caption tells the two apart: the platform's own name, under
 * which the claim has to reach to be the page's.
 *
 * The list is the Public Suffix List's private section, whole, from a bundled copy
 * (`public-suffix-private.txt` beside this class, its version and commit in its header): the
 * section is the maintained public answer to exactly this question, kept by the platforms
 * themselves for the browsers' cookie and certificate boundaries, and a hand-picked subset of it
 * would be one engineer's guess at the same list, wrong in the long tail the way a first draft of
 * this file was (a third of its picks were not in the section). Some 3,400 entries, 70 KB of
 * text read once on the first link tap and held as two sets; a fixed snapshot, never fetched,
 * since a list that changed under the user would change what a caption says from one day to the
 * next, and a platform the snapshot does not know only keeps the parent rule it had before.
 * Beside it stand the few hosts in [PATH_HOSTED] the section does not carry because their
 * customers' pages live under a path rather than a label, sites.google.com first among them,
 * since a claim of `google.com` over one is the case the rule exists for.
 *
 * Entries are read as the section writes them: a name matches itself and every host under it,
 * and `*.` before a name means the label directly under it is the platform's too (a region, a
 * service), so the customer's label is the one below that. The section's thirteen Unicode names
 * are held as their punycode, as the hosts they are matched against are ([LinkLook.asciiHost]).
 */
object UserContentHosts {
    private const val RESOURCE = "/app/berth/android/ui/stage/public-suffix-private.txt"

    /** Google's hosts whose customers' pages hang off a path, not a label; the section, by its nature, has no line for them. */
    internal val PATH_HOSTED: Set<String> = setOf("sites.google.com", "docs.google.com", "drive.google.com")

    private class Table(val plain: Set<String>, val wildcard: Set<String>)

    private val table: Table by lazy { load() }

    /** How many names the bundled list holds, wildcards included; for the test that it loaded whole. */
    internal val size: Int get() = table.plain.size + table.wildcard.size

    private fun load(): Table {
        val plain = HashSet<String>(4096)
        val wildcard = HashSet<String>(512)
        val stream = UserContentHosts::class.java.getResourceAsStream(RESOURCE)
            ?: throw IllegalStateException("$RESOURCE is not in the build")
        stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("//")) continue
                if (line.startsWith("*.")) wildcard.add(LinkLook.asciiHost(line.substring(2))) else plain.add(LinkLook.asciiHost(line))
            }
        }
        plain.addAll(PATH_HOSTED)
        return Table(plain, wildcard)
    }

    /**
     * The platform [host] stands under, as the longest listed name that is the host or ends it at
     * a dot, or null for a host the list does not know. The host itself counts (github.io is its
     * own boundary), and a wildcard makes the label under it part of the platform: for
     * evil.s3.amazonaws.com that is s3.amazonaws.com by its own line; for
     * i-0abc.eu-west-1.compute.amazonaws.com, under `*.compute.amazonaws.com`, it is
     * eu-west-1.compute.amazonaws.com. [host] is read lowercased and in ASCII, as [LinkLook.hostOf] gives it.
     */
    fun boundaryOf(host: String): String? {
        val h = LinkLook.asciiHost(host.trimEnd('.'))
        if (h.isEmpty() || h.startsWith("[")) return null
        val t = table
        var start = 0
        while (start < h.length) {
            val suffix = if (start == 0) h else h.substring(start)
            if (suffix in t.plain) return suffix
            val dot = h.indexOf('.', start)
            if (dot < 0) return null
            // `*.c.d` in the list makes `b.c.d` the platform's: this suffix, when the one under its first label is a wildcard's.
            if (h.substring(dot + 1) in t.wildcard) return suffix
            start = dot + 1
        }
        return null
    }
}
