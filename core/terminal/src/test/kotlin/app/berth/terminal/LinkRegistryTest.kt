package app.berth.terminal

import java.util.Random
import java.util.TreeMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The link ids the cells carry (spec A60): one id per URL, or per `id=` parameter with its URL;
 * what is not a URL a terminal should hold gets no id, the characters that draw nothing among it
 * (a bidi override can turn the address panel's tail around, a zero-width space can dress one
 * host as another); a link printed again from the older half of the ring is a fresh id, so the
 * print at the bottom of the screen does not die with the ring's oldest; and a stream that prints
 * links without end runs the registry over its bounds, links and text alike, where the oldest are
 * forgotten and their ids never handed out again, so a cell of a forgotten link resolves to
 * nothing rather than to another site.
 */
class LinkRegistryTest {
    @Test
    fun `one URL is one id, and the same URL under another id parameter is another`() {
        val r = LinkRegistry()
        val a = r.register("", "https://a.example/")
        assertTrue(a > 0)
        assertEquals(a, r.register("", "https://a.example/"))
        val b = r.register("", "https://b.example/")
        assertTrue(b != a)
        val named = r.register("id=one", "https://a.example/")
        assertTrue(named != a, "an id parameter keeps the link apart from the bare URL")
        assertEquals(named, r.register("id=one", "https://a.example/"))
        assertTrue(r.register("id=two", "https://a.example/") != named)
        assertEquals("https://a.example/", r.url(a))
        assertEquals("https://a.example/", r.url(named))
        assertEquals(4, r.size)
    }

    @Test
    fun `what is not a URL a terminal holds gets no id`() {
        val r = LinkRegistry()
        assertEquals(0, r.register("", ""))
        assertEquals(0, r.register("", "https://x.example/" + "a".repeat(LinkRegistry.MAX_URL_LENGTH)))
        assertEquals(0, r.register("", "https://x.example/with space"))
        assertEquals(0, r.register("", "https://x.example/\u0007bell"))
        assertEquals(0, r.register("", "https://x.example/\u007f"))
        // What draws nothing and re-orders or hides what does: the bidi override that turns the panel's
        // tail around, the zero-width space inside a host, the isolates, the byte-order mark, the line
        // and paragraph separators, a space of another script, a C1 control, an unpaired surrogate.
        assertEquals(0, r.register("", "https://evil.example/\u202Emoc.elgoog//:sptth"), "right-to-left override")
        assertEquals(0, r.register("", "https://goo\u200Bgle.com/"), "zero-width space")
        assertEquals(0, r.register("", "https://x.example/\u2066rtl\u2069"), "isolates")
        assertEquals(0, r.register("", "\uFEFFhttps://x.example/"), "byte-order mark")
        assertEquals(0, r.register("", "https://x.example/a\u2028b"), "line separator")
        assertEquals(0, r.register("", "https://x.example/a\u2029b"), "paragraph separator")
        assertEquals(0, r.register("", "https://x.example/a\u00A0b"), "no-break space")
        assertEquals(0, r.register("", "https://x.example/a\u3000b"), "ideographic space")
        assertEquals(0, r.register("", "https://x.example/a\u0085b"), "C1 control")
        assertEquals(0, r.register("", "https://x.example/\uD800"), "unpaired surrogate")
        assertEquals(0, r.register("", "https://x.example/\uE000\uDC00"), "the wrong half of a pair")
        assertTrue(r.register("", "https://x.example/" + "a".repeat(LinkRegistry.MAX_URL_LENGTH - 19)) > 0, "a URL at the bound is kept")
        // An IRI a tool prints raw is a link the user can read: letters of every script stay, and so do emoji.
        assertTrue(r.register("", "https://m\u00FCnchen.example/stra\u00DFe") > 0, "Latin with diacritics")
        assertTrue(r.register("", "https://\u4F8B\u3048.jp/\u30D1\u30B9") > 0, "CJK")
        assertTrue(r.register("", "https://x.example/\u05E9\u05DC\u05D5\u05DD") > 0, "Hebrew letters, right to left but drawn as themselves")
        assertTrue(r.register("", "https://x.example/\uD83D\uDE80") > 0, "an emoji, a pair of surrogates that is one character")
        assertNull(r.url(0))
        assertNull(r.url(99))
        assertEquals(5, r.size)
    }

    @Test
    fun `a link printed again from the older half of the ring is a fresh id, and the old one resolves until its turn`() {
        val r = LinkRegistry()
        val x = "https://x.example/"
        val first = r.register("", x)
        assertEquals(1, first)
        // In the newer half of the ring the same link is the same id.
        for (i in 1 until LinkRegistry.MAX_LINKS / 2) r.register("", "https://n$i.example/")
        assertEquals(first, r.register("", x), "with ${LinkRegistry.MAX_LINKS / 2} ids handed out the first is still in the newer half")
        // One more and the first is the older half's: what is printed now gets a fresh id, and both resolve.
        r.register("", "https://more.example/")
        val fresh = r.register("", x)
        assertNotEquals(first, fresh)
        assertEquals(x, r.url(first), "the cells that carry the old id still resolve")
        assertEquals(x, r.url(fresh))
        assertEquals(fresh, r.register("", x), "the fresh id is the one the link is known by now")
        assertEquals(LinkRegistry.MAX_LINKS / 2 + 2, r.size, "both copies count while both are held")
        // The old id goes when its slot's turn comes, a ring turn after it was handed out, and not before.
        for (i in 0 until LinkRegistry.MAX_LINKS - (LinkRegistry.MAX_LINKS / 2 + 2)) {
            r.register("", "https://later$i.example/")
            assertEquals(x, r.url(first), "still held at $i")
        }
        r.register("", "https://the-one-that-takes-the-slot.example/")
        assertNull(r.url(first))
        assertEquals(x, r.url(fresh), "the print at the bottom of the screen outlives the ring's oldest")
        // The reviewer's case: the very oldest printed again at the moment the ring is full. The
        // old id's slot is the next taken, so the old cells die at once and the new print stands.
        val y = "https://y.example/"
        val r2 = LinkRegistry()
        val old = r2.register("", y)
        for (i in 1 until LinkRegistry.MAX_LINKS) r2.register("", "https://f$i.example/")
        val again = r2.register("", y)
        assertNotEquals(old, again)
        assertNull(r2.url(old))
        assertEquals(y, r2.url(again))
        r2.register("", "https://distinct.example/")
        assertEquals(y, r2.url(again), "the next distinct link takes nothing from it")
    }

    @Test
    fun `past the text budget the oldest links are forgotten sooner than the ring would`() {
        val r = LinkRegistry()
        val body = "a".repeat(1400)
        // Every URL the same length, so how many fit is a division.
        fun url(i: Int) = "https://h.example/$body/${i.toString().padStart(5, '0')}"
        val fit = LinkRegistry.MAX_CHARS / url(0).length
        assertTrue(fit < LinkRegistry.MAX_LINKS, "the budget bites before the ring does for URLs this long")
        for (i in 1..2 * fit) r.register("", url(i))
        assertTrue(r.chars <= LinkRegistry.MAX_CHARS, "${r.chars} chars held")
        assertEquals(fit, r.size, "as many held as fit")
        assertNull(r.url(1), "the oldest is gone")
        assertNull(r.url(fit / 2))
        assertEquals(url(2 * fit), r.url(2 * fit), "the newest stands")
        assertEquals(url(fit + 2), r.url(fit + 2))
        // A key that is not the URL itself is text held too, so a link under an id parameter costs its key as well.
        val plain = LinkRegistry()
        plain.register("", "https://k.example/")
        val keyed = LinkRegistry()
        keyed.register("id=one", "https://k.example/")
        assertEquals("https://k.example/".length, plain.chars)
        assertEquals("https://k.example/".length + "id=one\u0000https://k.example/".length, keyed.chars)
        // Forgotten links give their text back; a clear gives all of it.
        r.clear()
        assertEquals(0, r.chars)
        assertEquals(0, r.size)
    }

    @Test
    fun `past the bound the oldest link is forgotten and its id is never reused`() {
        val r = LinkRegistry()
        val first = r.register("", "https://first.example/")
        for (i in 0 until LinkRegistry.MAX_LINKS) r.register("", "https://n$i.example/")
        assertEquals(LinkRegistry.MAX_LINKS, r.size)
        assertNull(r.url(first), "the oldest went with the one over the bound")
        assertEquals("https://n0.example/", r.url(first + 1))
        // Registering the forgotten URL again is a new link with a new id; the old id stays dead.
        val again = r.register("", "https://first.example/")
        assertTrue(again > first + LinkRegistry.MAX_LINKS)
        assertNull(r.url(first))
        assertEquals("https://first.example/", r.url(again))
        r.clear()
        assertEquals(0, r.size)
        assertNull(r.url(again))
        assertTrue(r.register("", "https://first.example/") > again, "ids keep counting after a clear")
    }

    /**
     * The ring and its probing index against the plain shape of the same rules, maps by id and by
     * key: a long stream of links from a pool wider than the bound, so most are forgotten and many
     * are seen again, some under an id parameter, with a clear in the middle, agreeing on every id
     * handed out, every URL looked up (live, forgotten and never given), the count held and the
     * text held.
     */
    @Test
    fun `over a long stream of repeats, forgets and a clear the registry agrees with a plain model`() {
        val pool = Array(12_000) { "https://h${it % 97}.example/p/$it" }
        agreeWithModel(pool, steps = 60_000, clearAt = 30_000, seed = 7)
    }

    /** The same stream with URLs long enough that the text budget, not the ring, is what forgets. */
    @Test
    fun `over a long stream of long links it is the text budget that forgets, and the registry still agrees with the model`() {
        val body = "b".repeat(1_200)
        val pool = Array(4_000) { "https://h${it % 41}.example/$body/$it" }
        agreeWithModel(pool, steps = 12_000, clearAt = 6_000, seed = 11)
    }

    private fun agreeWithModel(pool: Array<String>, steps: Int, clearAt: Int, seed: Long) {
        val r = LinkRegistry()
        val model = Model()
        val random = Random(seed)
        var budgetForgot = false
        repeat(steps) { step ->
            val url = pool[random.nextInt(pool.size)]
            val param = if (random.nextInt(4) == 0) "id=${random.nextInt(5)}" else ""
            assertEquals(model.register(param, url), r.register(param, url), "id at step $step")
            val probe = random.nextInt(model.nextId + 1)
            assertEquals(model.url(probe), r.url(probe), "url($probe) at step $step")
            assertEquals(model.size, r.size, "size at step $step")
            assertEquals(model.chars, r.chars, "chars at step $step")
            assertTrue(r.chars <= LinkRegistry.MAX_CHARS, "over the text budget at step $step")
            if (model.budgetForgot) budgetForgot = true
            if (step == clearAt) {
                r.clear()
                model.clear()
            }
        }
        assertEquals(pool[0].length > 1_000, budgetForgot, "the budget forgets when the links are long, and only then")
    }

    /**
     * The rules as plain maps: ids count up; a key known among the newest half of the ids is that
     * id, an older one is let go of and the link gets a fresh id; slot `id - MAX_LINKS` is forgotten
     * when `id` arrives; and past the text budget the oldest ids still held are forgotten in order.
     */
    private class Model {
        private val idByKey = HashMap<String, Int>()
        private val keyById = HashMap<Int, String>()
        private val urlById = TreeMap<Int, String>()
        var nextId = 1
        var chars = 0
        var budgetForgot = false
        val size: Int get() = urlById.size

        fun register(param: String, url: String): Int {
            val key = if (param.isEmpty()) url else "$param\u0000$url"
            idByKey[key]?.let { known ->
                if (known >= nextId - LinkRegistry.MAX_LINKS / 2) return known
                unindex(known)
            }
            val id = nextId++
            forget(id - LinkRegistry.MAX_LINKS)
            idByKey[key] = id
            keyById[id] = key
            urlById[id] = url
            chars += url.length + if (param.isEmpty()) 0 else key.length
            while (chars > LinkRegistry.MAX_CHARS) {
                forget(urlById.firstKey())
                budgetForgot = true
            }
            return id
        }

        private fun unindex(id: Int) {
            val key = keyById.remove(id) ?: return
            idByKey.remove(key)
            if (key != urlById[id]) chars -= key.length
        }

        private fun forget(id: Int) {
            unindex(id)
            val url = urlById.remove(id) ?: return
            chars -= url.length
        }

        fun url(id: Int): String? = urlById[id]

        fun clear() {
            idByKey.clear()
            keyById.clear()
            urlById.clear()
            chars = 0
        }
    }
}
