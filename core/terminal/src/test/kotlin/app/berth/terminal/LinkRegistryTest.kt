package app.berth.terminal

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The link ids the cells carry (spec A60): one id per URL, or per `id=` parameter with its URL;
 * what is not a URL a terminal should hold gets no id; and a stream that prints links without end
 * runs the registry over its bound, where the oldest are forgotten and their ids never handed out
 * again, so a cell of a forgotten link resolves to nothing rather than to another site.
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
        assertTrue(r.register("", "https://x.example/" + "a".repeat(LinkRegistry.MAX_URL_LENGTH - 19)) > 0, "a URL at the bound is kept")
        assertNull(r.url(0))
        assertNull(r.url(99))
        assertEquals(1, r.size)
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
     * The ring and its probing index against the plain shape of the same rules, a map in insertion
     * order: a long stream of links from a pool wider than the bound, so most are forgotten and many
     * are seen again, some under an id parameter, with a clear in the middle, agreeing on every id
     * handed out, every URL looked up (live, forgotten and never given) and the count held.
     */
    @Test
    fun `over a long stream of repeats, forgets and a clear the registry agrees with a plain model`() {
        val r = LinkRegistry()
        val model = Model()
        val random = Random(7)
        val pool = Array(12_000) { "https://h${it % 97}.example/p/$it" }
        repeat(60_000) { step ->
            val url = pool[random.nextInt(pool.size)]
            val param = if (random.nextInt(4) == 0) "id=${random.nextInt(5)}" else ""
            assertEquals(model.register(param, url), r.register(param, url), "id at step $step")
            val probe = random.nextInt(model.nextId + 1)
            assertEquals(model.url(probe), r.url(probe), "url($probe) at step $step")
            assertEquals(model.size, r.size, "size at step $step")
            if (step == 30_000) {
                r.clear()
                model.clear()
            }
        }
    }

    private class Model {
        private val idByKey = LinkedHashMap<String, Int>()
        private val urlById = HashMap<Int, String>()
        var nextId = 1
        val size: Int get() = idByKey.size

        fun register(param: String, url: String): Int {
            val key = if (param.isEmpty()) url else "$param\u0000$url"
            idByKey[key]?.let { return it }
            val id = nextId++
            idByKey[key] = id
            urlById[id] = url
            if (idByKey.size > LinkRegistry.MAX_LINKS) {
                val eldest = idByKey.entries.first()
                urlById.remove(eldest.value)
                idByKey.remove(eldest.key)
            }
            return id
        }

        fun url(id: Int): String? = urlById[id]

        fun clear() {
            idByKey.clear()
            urlById.clear()
        }
    }
}
