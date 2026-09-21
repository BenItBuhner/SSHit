package app.berth.terminal

/**
 * The OSC 8 hyperlinks a session has printed (spec A60), each as a small id the cells carry
 * ([TerminalLine.links]) and the URL it stands for. One link is one URL, or one `id=` parameter
 * with its URL when the application gives one, so a name split over two rows lights up whole and
 * a directory listing's hundred entries are a hundred links.
 *
 * Bounded three ways against a stream that prints links without end: a URL longer than
 * [MAX_URL_LENGTH] is not a link, past [MAX_LINKS] the oldest is forgotten, and past [MAX_CHARS]
 * of URL text held the oldest are forgotten sooner. Ids are never reused, so a cell whose link has
 * been forgotten resolves to nothing, never to another URL. Reads come from the UI thread and
 * writes from the emulator's, hence the synchronisation.
 *
 * Ids count up from 1, so the newest [MAX_LINKS] live in a ring by id, the one a full ring turn
 * older giving its slot up, and an open-addressing index over the ring finds a known link by its
 * key: a new link allocates nothing beyond its URL's copy and forgetting the oldest costs nothing,
 * which is what `ls --hyperlink` over a large directory asks of it, a link a line. The ring is made
 * on the first link, since most sessions print none.
 *
 * A URL holds no character that draws nothing: the controls, the spaces of every script, an
 * unpaired surrogate, and the format characters (the bidi overrides and isolates, the zero-width
 * joiners and spaces, the byte-order mark), since the one panel that shows a link's real address
 * must show it in the order and the letters it has. Other non-ASCII stays: an IRI a tool prints
 * raw is a link the user can read.
 */
class LinkRegistry {
    private var ring: Ring? = null
    private var nextId = 1

    val size: Int get() = synchronized(this) { ring?.count ?: 0 }

    /** The characters of URL text held, URLs and the keys that are not the URL itself; what [MAX_CHARS] bounds. */
    val chars: Int get() = synchronized(this) { ring?.chars ?: 0 }

    /**
     * The id for [url] under the application's [idParam] (empty for none): the existing one when
     * this link is already known and not about to be forgotten, else a new one; 0 when the URL is
     * not one this terminal will hold.
     */
    fun register(idParam: String, url: String): Int = synchronized(this) {
        if (url.isEmpty() || url.length > MAX_URL_LENGTH || nextId == MAX_ID) return 0
        // One pass over the URL says whether a terminal holds it and hashes it for the index.
        var h = 0
        var i = 0
        val n = url.length
        while (i < n) {
            val c = url[i]
            if (c < '\u0080') {
                if (c <= ' ' || c == '\u007f') return 0
                h = 31 * h + c.code
                i++
            } else {
                val cp = url.codePointAt(i)
                if (!drawsAsItself(cp)) return 0
                val count = Character.charCount(cp)
                for (k in 0 until count) h = 31 * h + url[i + k].code
                i += count
            }
        }
        val key = if (idParam.isEmpty()) url else "$idParam\u0000$url"
        if (idParam.isNotEmpty()) h = 31 * h + idParam.hashCode()
        val ring = ring ?: Ring(nextId).also { ring = it }
        val known = ring.find(key, h)
        if (known != 0) {
            // A known link among the newest half of the ids is the link. One older is about to be
            // forgotten, so what is printed now gets a fresh id and the cells that carry the old one
            // keep resolving until its turn, rather than the newest print dying with the oldest.
            if (known >= nextId - MAX_LINKS / 2) return known
            ring.unindex((known - 1) % MAX_LINKS)
        }
        val id = nextId++
        ring.put((id - 1) % MAX_LINKS, id, key, url, h)
        ring.trim(nextId)
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
     * table twice the ring, so a probe is a step or two, and a link taken out of the index leaves
     * the run it sat in by shifting the entries behind it back, so no tombstones gather. A slot
     * may hold a URL without a key: a link the index has let go of ([unindex]) whose cells still
     * resolve until the slot is taken.
     */
    private class Ring(firstId: Int) {
        val keys = arrayOfNulls<String>(MAX_LINKS)
        val urls = arrayOfNulls<String>(MAX_LINKS)
        val hashes = IntArray(MAX_LINKS)
        val ids = IntArray(MAX_LINKS)

        /** Slot + 1 of the link at each probe position, 0 for none. */
        private val index = IntArray(MAX_LINKS * 2)
        var count = 0
        var chars = 0

        /** The lowest id that may still hold a URL; [trim] walks it up, since the ring is in id order. */
        private var oldest = firstId

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
            if (keys[slot] != null) unindex(slot)
            if (urls[slot] != null) drop(slot)
            count++
            chars += url.length
            if (key !== url) chars += key.length
            keys[slot] = key
            urls[slot] = url
            hashes[slot] = h
            ids[slot] = id
            var i = home(h)
            while (index[i] != 0) i = (i + 1) and MASK
            index[i] = slot + 1
        }

        /** Takes the link in [slot] out of the index, so its key finds nothing; its URL stays for the cells that carry its id. */
        fun unindex(slot: Int) {
            val key = keys[slot] ?: return
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
            if (key !== urls[slot]) chars -= key.length
            keys[slot] = null
        }

        /** Forgets the oldest links while the text held is over [MAX_CHARS]; [nextId] bounds the walk, since no link is newer. */
        fun trim(nextId: Int) {
            while (chars > MAX_CHARS && oldest < nextId) {
                val slot = (oldest - 1) % MAX_LINKS
                if (ids[slot] == oldest && urls[slot] != null) {
                    if (keys[slot] != null) unindex(slot)
                    drop(slot)
                }
                oldest++
            }
        }

        /** Forgets the URL in [slot], already out of the index; the cells that carry its id resolve to nothing from here. */
        private fun drop(slot: Int) {
            chars -= urls[slot]!!.length
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

        /** The URL text the ring holds at most, keys included: two million characters, past which the oldest links go early. */
        const val MAX_CHARS = 2 * 1024 * 1024

        /** Past two billion links the session prints the rest unlinked rather than wrap the ids onto old cells. */
        private const val MAX_ID = Int.MAX_VALUE - MAX_LINKS

        /**
         * Whether a code point beyond ASCII is one a URL may hold: not a control, not a space of
         * any script, not a line or paragraph separator, not an unpaired surrogate, and not a format
         * character (`Cf`: the bidi overrides and isolates, zero-width joiners and spaces, the
         * byte-order mark, tag characters), which draw nothing and re-order or hide what does.
         */
        private fun drawsAsItself(cp: Int): Boolean = when (Character.getType(cp)) {
            Character.CONTROL.toInt(), Character.FORMAT.toInt(), Character.SURROGATE.toInt(),
            Character.SPACE_SEPARATOR.toInt(), Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt() -> false
            else -> true
        }
    }
}
