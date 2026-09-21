package app.berth.android.ui.hosts

import app.berth.domain.model.Host
import app.berth.domain.model.SwatchColor
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Hosts screen's search, chips and Sort by tag (spec C9) as the pure functions behind them. */
class HostsLibraryTest {
    private fun host(name: String, tags: List<String> = emptyList(), address: String = "$name.local", user: String = "ben", port: Int = 22, jump: List<String> = emptyList(), connected: Long? = null) =
        Host(id = name, name = name, color = SwatchColor.COPPER, monogram = Host.monogramFor(name), address = address, port = port, user = user, tags = tags, jumpHostIds = jump, lastConnectedAt = connected, createdAt = 0)

    private val hosts = listOf(
        host("web", tags = listOf("prod", "Web")),
        host("db", tags = listOf("prod")),
        host("nas", tags = listOf("homelab"), user = "admin", address = "10.0.0.5"),
        host("scratch"),
    )

    @Test
    fun `tags are every tag once, alphabetically`() {
        assertEquals(listOf("homelab", "prod", "Web"), hosts.allTags())
        assertEquals(emptyList<String>(), listOf(host("a"), host("b", tags = listOf(" "))).allTags())
    }

    @Test
    fun `search reads name, address, user and tags, case aside, every word somewhere`() {
        assertEquals(listOf("web", "db"), hosts.matching("PROD", null).map { it.name })
        assertEquals(listOf("nas"), hosts.matching("admin", null).map { it.name })
        assertEquals(listOf("nas"), hosts.matching("10.0.0", null).map { it.name })
        assertEquals(listOf("web"), hosts.matching("prod web", null).map { it.name })
        assertEquals(emptyList<String>(), hosts.matching("prod nas", null).map { it.name })
        assertEquals(hosts, hosts.matching("   ", null))
    }

    @Test
    fun `a chosen chip narrows before the search does`() {
        assertEquals(listOf("web", "db"), hosts.matching("", "prod").map { it.name })
        assertEquals(listOf("db"), hosts.matching("db", "prod").map { it.name })
        assertEquals("a chip is matched case aside too", listOf("web"), hosts.matching("", "web").map { it.name })
        assertEquals(emptyList<String>(), hosts.matching("", "nowhere").map { it.name })
    }

    @Test
    fun `sort by recent is the last connected first, the never-connected last by name`() {
        val library = listOf(
            host("staging", connected = 2_000),
            host("Bastion"),
            host("api-gateway", connected = 9_000),
            host("lab-gpu", connected = 5_000),
            host("archive"),
            host("mirror", connected = 5_000),
        )
        assertEquals(listOf("api-gateway", "lab-gpu", "mirror", "staging", "archive", "Bastion"), library.byRecency().map { it.name })
        assertEquals("nothing connected yet is the alphabet", listOf("archive", "Bastion"), listOf(host("Bastion"), host("archive")).byRecency().map { it.name })
        assertEquals(emptyList<Host>(), emptyList<Host>().byRecency())
    }

    @Test
    fun `sort by tag is a section per tag, hosts by name, the untagged last`() {
        val sections = hosts.byTag()
        assertEquals(listOf("homelab", "prod", "Web", "Untagged"), sections.map { it.first })
        assertEquals(listOf("db", "web"), sections[1].second.map { it.name })
        assertEquals(listOf("scratch"), sections.last().second.map { it.name })
        assertEquals("no Untagged section when every host is tagged", listOf("prod"), listOf(host("a", tags = listOf("prod"))).byTag().map { it.first })
    }

    @Test
    fun `the row's second line is user at address, the port when odd, the chain when there is one`() {
        val byId = hosts.associateBy { it.id }
        assertEquals("ben@web.local", host("web").rowSubtitle(byId))
        assertEquals("ben@web.local:2222", host("web", port = 2222).rowSubtitle(byId))
        assertEquals("ben@edge.local \u00B7 via db \u203A nas", host("edge", jump = listOf("db", "nas")).rowSubtitle(byId))
        assertEquals("ben@edge.local \u00B7 via a deleted host", host("edge", jump = listOf("gone")).rowSubtitle(byId))
    }
}
