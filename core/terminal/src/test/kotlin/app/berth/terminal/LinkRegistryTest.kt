package app.berth.terminal

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
}
