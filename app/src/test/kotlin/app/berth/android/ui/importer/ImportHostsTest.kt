package app.berth.android.ui.importer

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.berth.android.screenshots.TestGraph
import app.berth.domain.model.AuthMethod
import app.berth.domain.model.Host
import app.berth.domain.model.SwatchColor
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** `ProxyJump` through the import sheet's saving step: aliases link, saved hosts link, and what neither names becomes a host. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ImportHostsTest {
    private lateinit var graph: TestGraph

    @Before
    fun setUp() {
        graph = TestGraph(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        graph.close()
    }

    @Test
    fun `hop specs parse as user, host and port with 22 and no user when left out`() {
        assertEquals(HopSpec("ops", "bastion.example.net", 2222), parseHopSpec("ops@bastion.example.net:2222"))
        assertEquals(HopSpec(null, "bastion", 22), parseHopSpec("bastion"))
        assertEquals(HopSpec("ops", "bastion", 22), parseHopSpec("ops@bastion"))
        assertEquals(HopSpec(null, "10.0.0.1", 2200), parseHopSpec("10.0.0.1:2200"))
        assertEquals(HopSpec("ops", "fe80::1", 2222), parseHopSpec("ops@[fe80::1]:2222"))
        assertEquals(HopSpec(null, "fe80::1", 22), parseHopSpec("[fe80::1]"))
        assertEquals("a port out of range falls back to 22", HopSpec(null, "bastion", 22), parseHopSpec("bastion:99999"))
    }

    @Test
    fun `a hop names a saved host by name, or by address and port and the user it gives`() {
        val bastion = saved("bastion", "bastion.example.net", "ops", port = 2222)
        val other = saved("other", "bastion.example.net", "root", port = 2222)
        val all = listOf(bastion, other)
        assertEquals(bastion, hostForHop("bastion", all))
        assertEquals(bastion, hostForHop("ops@bastion.example.net:2222", all))
        assertEquals(other, hostForHop("root@bastion.example.net:2222", all))
        assertEquals("no user in the spec takes the first host at that address and port", bastion, hostForHop("bastion.example.net:2222", all))
        assertNull("the port is part of the match", hostForHop("ops@bastion.example.net", all))
        assertNull(hostForHop("nobody@nowhere", all))
    }

    @Test
    fun `an alias in the same import links, a saved host links, and an unknown spec becomes a host once`() = runBlocking {
        val savedBastion = saved("edge", "edge.example.net", "ops", port = 2200)
        graph.hosts.upsert(savedBastion)
        val parsed = graph.viewModel.parseSshConfig(
            """
            Host bastion
              HostName bastion.example.net
              User ops

            Host prod
              HostName 10.0.4.12
              User deploy
              ProxyJump bastion,ops@edge.example.net:2200,relay@10.9.9.9:2022

            Host staging
              HostName 10.0.4.13
              User deploy
              ProxyJump relay@10.9.9.9:2022

            Host lab
              HostName 10.0.5.1
              User ben
              ProxyJump 10.9.9.9:2022
            """.trimIndent(),
        )
        val candidates = candidatesFor(parsed.hosts, emptyList(), graph.hosts.items.value)
        assertEquals(4, candidates.size)

        val count = importCandidates(graph.viewModel, candidates, graph.hosts.items.value)
        val all = graph.hosts.items.value
        // The aliases have names of their own; the two relays are both named for their address.
        val hosts = all.filter { it.name != "10.9.9.9" }.associateBy { it.name }

        // Four aliases plus the two relays no host answered to: relay@10.9.9.9 and (lab's user) ben@10.9.9.9.
        assertEquals(6, count)
        assertEquals(7, all.size)
        val relay = all.single { it.address == "10.9.9.9" && it.user == "relay" }
        val benRelay = all.single { it.address == "10.9.9.9" && it.user == "ben" }
        assertEquals(2022, relay.port)
        assertTrue("a hop made from a spec is tagged so the library says where it came from", "jump" in relay.tags && "imported" in relay.tags)
        assertEquals("a hop with no key asks on connect", AuthMethod.AskEachTime, relay.auth)

        val prod = hosts.getValue("prod")
        assertEquals(listOf(hosts.getValue("bastion").id, savedBastion.id, relay.id), prod.jumpHostIds)
        assertEquals("the same spec on a second alias is the same host", listOf(relay.id), hosts.getValue("staging").jumpHostIds)
        assertEquals("a spec with no user takes the target's user, so it is a different login", listOf(benRelay.id), hosts.getValue("lab").jumpHostIds)
        assertEquals("the hop hosts themselves have no chain", emptyList<String>(), relay.jumpHostIds)
    }

    private fun saved(name: String, address: String, user: String, port: Int = 22) = Host(
        id = "saved-$name",
        name = name,
        color = SwatchColor.SLATE,
        monogram = Host.monogramFor(name),
        address = address,
        port = port,
        user = user,
        createdAt = 0L,
    )
}
