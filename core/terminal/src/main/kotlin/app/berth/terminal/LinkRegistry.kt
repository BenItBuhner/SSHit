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
 *
 * Ids count up from 1, so the newest [MAX_LINKS] live in a ring by id, the one a full ring turn
 * older giving its slot up, and an open-addressing index over the ring finds a known link by its
 * key: a new link allocates nothing beyond its URL's copy and forgetting the oldest costs nothing,
 * which is what `ls --hyperlink` over a large directory asks of it, a link a line. The ring is made
 * on the first link, since most sessions print none.
 */
class LinkRegistry {
    private var ring: Ring? = null
    private var nextId = 1

    val size: Int get() = synchronized(this) { ring?.count ?: 0 }

    /**
     * The id for [url] under the application's [idParam] (empty for none): the existing one when
     * this link is already known, else a new one; 0 when the URL is not one this terminal will hold.
     */
    fun register(idParam: String, url: String): Int = synchronized(this) {
        if (url.isEmpty() || url.length > MAX_URL_LENGTH || nextId == MAX_ID) return 0
        // One pass over the URL says whether a terminal holds it and hashes it for the index.
        var h = 0
        for (c in url) {
            if (c <= ' ' || c == '\u007f') return 0
            h = 31 * h + c.code
        }
        val key = if (idParam.isEmpty()) url else "$idParam\u0000$url"
        if (idParam.isNotEmpty()) h = 31 * h + idParam.hashCode()
        val ring = ring ?: Ring().also { ring = it }
        val known = ring.find(key, h)
        if (known != 0) return known
        val id = nextId++
        ring.put((id - 1) % MAX_LINKS, id, key, url, h)
        id
    }

    /** The URL behind [id], or null for 0, an unknown id or one already forgotten. */
    fun url(id: Int): String? = synchronized(this) {
        val ring = ring
        if (ring == null || id <= 0) return null
        val slot = (id - 1) % MAX_LINKS
        if (ring.ids[slot] == id) ring.urls[slot] else null
    }

    fun clear() = synchronized(this) { ring = null }

    /**
     * Slot `(id - 1) % MAX_LINKS` holds link `id` from its registration until link
     * `id + MAX_LINKS` takes it; [index] maps a key's hash to its slot by linear probing over a
     * table twice the ring, so a probe is a step or two, and a forgotten link is taken out of the
     * run it sat in by shifting the entries behind it back, so no tombstones gather.
     */
    private class Ring {
        val keys = arrayOfNulls<String>(MAX_LINKS)
        val urls = arrayOfNulls<String>(MAX_LINKS)
        val hashes = IntArray(MAX_LINKS)
        val ids = IntArray(MAX_LINKS)

        /** Slot + 1 of the link at each probe position, 0 for none. */
        private val index = IntArray(MAX_LINKS * 2)
        var count = 0

        /** The id of the link under [key], whose hash is [h], or 0. */
        fun find(key: String, h: Int): Int {
            var i = home(h)
            while (true) {
                val slot = index[i] - 1
                if (slot < 0) return 0
                if (hashes[slot] == h && keys[slot] == key) return ids[slot]
                i = (i + 1) and MASK
            }
        }

        /** Files [key], hashed [h], and [url] as link [id] in [slot], forgetting the link the slot held. */
        fun put(slot: Int, id: Int, key: String, url: String, h: Int) {
            if (keys[slot] != null) forget(slot)
            count++
            keys[slot] = key
            urls[slot] = url
            hashes[slot] = h
            ids[slot] = id
            var i = home(h)
            while (index[i] != 0) i = (i + 1) and MASK
            index[i] = slot + 1
        }

        private fun forget(slot: Int) {
            var hole = home(hashes[slot])
            while (index[hole] != slot + 1) hole = (hole + 1) and MASK
            // Every entry behind the hole in its run moves back over it unless its home lies between the two,
            // which would put it before its home and out of reach of a probe.
            var j = hole
            while (true) {
                j = (j + 1) and MASK
                val s = index[j] - 1
                if (s < 0) break
                val home = home(hashes[s])
                val stays = if (hole <= j) home > hole && home <= j else home > hole || home <= j
                if (!stays) {
                    index[hole] = index[j]
                    hole = j
                }
            }
            index[hole] = 0
            keys[slot] = null
            urls[slot] = null
            count--
        }

        private fun home(h: Int): Int = (h xor (h ushr 16)) and MASK

        private companion object {
            const val MASK = MAX_LINKS * 2 - 1
        }
    }

    companion object {
        const val MAX_LINKS = 8192
        const val MAX_URL_LENGTH = 2048

        /** Past two billion links the session prints the rest unlinked rather than wrap the ids onto old cells. */
        private const val MAX_ID = Int.MAX_VALUE - MAX_LINKS
    }
}
