package app.berth.terminal

/**
 * The OSC 8 hyperlinks a session has printed (spec A60), each as a small id the cells carry
 * ([TerminalLine.links]) and the URL it stands for. One link is one URL, or one `id=` parameter
 * with its URL when the application gives one, so a name split over two rows lights up whole and
 * a directory listing's hundred entries are a hundred links.
 *
 * Bounded two ways against a stream that prints links without end: a URL longer than
 * [MAX_URL_LENGTH] is not a link, and past [MAX_LINKS] the oldest is forgotten. Ids are never
 * reused, so a cell whose link has been forgotten resolves to nothing, never to another URL.
 * Reads come from the UI thread and writes from the emulator's, hence the synchronisation.
 */
class LinkRegistry {
    private class Entry(val key: String, val url: String)

    /** Insertion order, oldest first, for eviction. */
    private val byId = LinkedHashMap<Int, Entry>()
    private val idByKey = HashMap<String, Int>()
    private var nextId = 1

    val size: Int get() = synchronized(this) { byId.size }

    /**
     * The id for [url] under the application's [idParam] (empty for none): the existing one when
     * this link is already known, else a new one; 0 when the URL is not one this terminal will hold.
     */
    fun register(idParam: String, url: String): Int = synchronized(this) {
        if (url.isEmpty() || url.length > MAX_URL_LENGTH || url.any { it <= ' ' || it == '\u007f' }) return 0
        val key = if (idParam.isEmpty()) url else "$idParam\u0000$url"
        idByKey[key]?.let { return it }
        val id = nextId++
        byId[id] = Entry(key, url)
        idByKey[key] = id
        while (byId.size > MAX_LINKS) {
            val eldest = byId.entries.iterator().next()
            idByKey.remove(eldest.value.key)
            byId.remove(eldest.key)
        }
        id
    }

    /** The URL behind [id], or null for 0, an unknown id or one already forgotten. */
    fun url(id: Int): String? = synchronized(this) { if (id == 0) null else byId[id]?.url }

    fun clear() = synchronized(this) {
        byId.clear()
        idByKey.clear()
    }

    companion object {
        const val MAX_LINKS = 8192
        const val MAX_URL_LENGTH = 2048
    }
}
