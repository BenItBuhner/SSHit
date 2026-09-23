package app.berth.domain

import app.berth.domain.model.BerthBundle
import app.berth.domain.model.ConnectionSettings
import app.berth.domain.model.DeckAction
import app.berth.domain.model.DeckKey
import app.berth.domain.model.DeckKeyCode
import app.berth.domain.model.DeckLayout
import app.berth.domain.model.DeckModifier
import app.berth.domain.model.HexColorSerializer
import app.berth.domain.model.Host
import app.berth.domain.model.KnownHostKey
import app.berth.domain.model.PersistencePolicy
import app.berth.domain.model.ReconnectBackoff
import app.berth.domain.model.Snippet
import app.berth.domain.model.SwatchColor
import app.berth.domain.model.TerminalTheme
import app.berth.domain.model.TmuxMode
import app.berth.domain.model.Tunnel
import app.berth.domain.model.TunnelType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DomainModelTest {
    @Test
    fun `default deck round-trips through json and matches the spec`() {
        val layout = DeckLayout.default()
        assertEquals(listOf("Base", "Symbols", "Nav/Fn", "tmux", "Snippets"), layout.layers.map { it.name })
        val base = layout.layers[0]
        assertEquals(listOf("Esc", "Tab", "Ctrl", "Alt", "-", "/", "Nub"), base.keys.map { it.label })
        assertEquals(listOf("`", "S-Tab", "^C", "^R", "|", "\\", null), base.keys.map { it.secondaryLabel })
        // The shell's other half of three pairs under the swipe down (D2), and nothing under the rest.
        assertEquals(listOf("~", null, null, null, "_", "?", null), base.keys.map { it.tertiaryLabel })
        assertEquals("CTRL b", layout.layers[3].prefix)

        val json = layout.toJson()
        assertTrue(json.contains("\"height_dp\": 44"))
        val back = DeckLayout.fromJson(json)
        assertEquals(layout, back)
    }

    @Test
    fun `combo parsing splits modifiers from the target`() {
        val combo = DeckAction.Combo("CTRL SHIFT c")
        assertEquals(setOf(DeckModifier.CTRL, DeckModifier.SHIFT), combo.modifiers)
        assertEquals("c", combo.target)
        assertEquals("TAB", DeckAction.Combo("SHIFT TAB").target)
    }

    @Test
    fun `secondary labels upper-case control chords and keep meta chords as typed`() {
        fun label(combo: String) = DeckKey(tap = DeckAction.Key(DeckKeyCode.ESC), up = DeckAction.Combo(combo)).secondaryLabel
        assertEquals("^C", label("CTRL c"))
        assertEquals("M-x", label("ALT x"))
        assertEquals("M-X", label("ALT X"))
        assertEquals("S-Tab", label("SHIFT TAB"))
    }

    @Test
    fun `deck json accepts hand-written keys`() {
        val text = """
            {"layers":[{"name":"Mine","keys":[{"tap":{"kind":"key","key":"HOME"}},{"tap":{"kind":"text","text":"ls"},"up":{"kind":"combo","combo":"CTRL l"}}]}]}
        """.trimIndent()
        val layout = DeckLayout.fromJson(text)
        assertEquals(1, layout.rows)
        assertEquals(DeckAction.Key(DeckKeyCode.HOME), layout.layers[0].keys[0].tap)
        assertEquals("ls", layout.layers[0].keys[1].label)
    }

    @Test
    fun `terminal theme json uses hex colours`() {
        val json = TerminalTheme.BERTH_DARK.toJson()
        assertTrue(json.contains("\"background\": \"#121110\""))
        assertTrue(json.contains("\"cursor_text\""))
        val back = TerminalTheme.fromJson(json)
        assertEquals(TerminalTheme.BERTH_DARK, back)
        assertEquals(0xABCDEF, HexColorSerializer.parse("#abcdef"))
        assertEquals(0xFFFFFF, HexColorSerializer.parse("fff"))
    }

    @Test
    fun `monograms follow the spec rule`() {
        assertEquals("PW", Host.monogramFor("prod-web"))
        assertEquals("DP", Host.monogramFor("db primary"))
        assertEquals("BA", Host.monogramFor("bastion"))
        assertEquals("X?", Host.monogramFor("x"))
        assertEquals("AG", Host.monogramFor("api_gateway"))
    }

    @Test
    fun `swatch colour for a name is stable`() {
        assertEquals(SwatchColor.forName("prod-web"), SwatchColor.forName("prod-web"))
    }

    @Test
    fun `snippet placeholders are detected with defaults`() {
        val s = Snippet(id = "1", name = "tail", body = "tail -n {{lines:100}} -f {{file}} {{lines}}")
        assertEquals(listOf("lines" to "100", "file" to null), s.placeholders())
    }

    @Test
    fun `snippets render placeholders with values then defaults and scope by host and workspace`() {
        val s = Snippet(id = "1", name = "tail", body = "tail -n {{lines:100}} -f {{file}}", hostId = "h1", workspaceId = "w1")
        assertEquals("tail -n 100 -f ", s.render())
        assertEquals("tail -n 20 -f app.log", s.render(mapOf("lines" to "20", "file" to "app.log")))
        assertTrue(s.hasPlaceholders)
        assertFalse(Snippet(id = "2", name = "df", body = "df -h").hasPlaceholders)
        assertEquals("df -h", Snippet(id = "2", name = "df", body = "\n  df -h\nsecond").preview)
        assertTrue(s.visibleFor("h1", "w1"))
        assertFalse(s.visibleFor("h2", "w1"))
        assertFalse(s.visibleFor("h1", "w2"))
        assertTrue(Snippet(id = "3", name = "g", body = "ls").visibleFor("anything", null))
    }

    @Test
    fun `tunnel specs, browser urls and validation`() {
        val local = Tunnel(id = "a", hostId = "h", type = TunnelType.LOCAL, bindPort = 8080, destinationHost = "localhost", destinationPort = 80)
        assertEquals("127.0.0.1:8080 \u2192 localhost:80", local.spec)
        assertEquals("http://127.0.0.1:8080/", local.openUrl)
        assertEquals(null, local.copy(destinationPort = 5432).openUrl)
        val remote = Tunnel(id = "b", hostId = "h", type = TunnelType.REMOTE, bindPort = 9000, destinationHost = "127.0.0.1", destinationPort = 3000)
        assertEquals("remote:9000 \u2192 127.0.0.1:3000", remote.spec)
        assertEquals(null, remote.openUrl)
        val socks = Tunnel(id = "c", hostId = "h", type = TunnelType.DYNAMIC, bindPort = 1080)
        assertEquals("SOCKS5 on 127.0.0.1:1080", socks.spec)
        assertTrue(socks.copy(bindAddress = "0.0.0.0").exposed)
        assertEquals("SOCKS5 on 0.0.0.0:1080", socks.copy(bindAddress = "*").spec)

        assertEquals(null, local.validate(listOf(remote, socks)))
        assertEquals("Port must be between 1 and 65535.", local.copy(bindPort = 70000).validate(emptyList()))
        assertEquals("Destination host is needed.", local.copy(destinationHost = " ").validate(emptyList()))
        assertEquals("Destination port must be between 1 and 65535.", local.copy(destinationPort = 0).validate(emptyList()))
        assertEquals(null, socks.copy(destinationPort = 0, destinationHost = "").validate(emptyList()), "dynamic tunnels have no destination")
        assertEquals("Port 8080 is already used by another tunnel on this host.", socks.copy(bindPort = 8080).validate(listOf(local)))
        assertEquals("Port 8080 is already used by a tunnel on another host.", local.copy(id = "z", hostId = "other").validate(listOf(local)))
        assertEquals(null, local.copy(id = "z", hostId = "other").validate(listOf(local.copy(enabled = false))), "disabled tunnels do not clash")
        assertEquals(null, remote.copy(id = "z", hostId = "other").validate(listOf(remote)), "remote listeners live on different servers")
        assertEquals("Port 9000 is already used by another tunnel on this host.", remote.copy(id = "z").validate(listOf(remote)))
        assertEquals(null, local.copy(id = "z", bindAddress = "127.0.0.2").validate(listOf(local)), "different loopback addresses can share a port")
        assertEquals("Port 8080 is already used by another tunnel on this host.", local.copy(id = "z", bindAddress = "0.0.0.0").validate(listOf(local)))
    }

    @Test
    fun `known host labels`() {
        val key = KnownHostKey("k", "10.0.0.12", 22, "ssh-ed25519", "AAAA", "SHA256:x", 0, 0)
        assertEquals("10.0.0.12", key.endpoint)
        assertEquals("10.0.0.12:2222", key.copy(port = 2222).endpoint)
        assertEquals("ED25519", key.algorithmLabel)
        assertEquals("ECDSA P-256", KnownHostKey.algorithmLabelFor("ecdsa-sha2-nistp256"))
        assertEquals("RSA", KnownHostKey.algorithmLabelFor("rsa-sha2-512"))
        assertEquals("FIDO ED25519", KnownHostKey.algorithmLabelFor("sk-ssh-ed25519@openssh.com"))
        assertFalse(key.pinned)
    }

    @Test
    fun `reconnect backoff schedule`() {
        assertEquals(listOf(1, 2, 4, 8, 15, 30, 60, 60), (0 until 8).map(ReconnectBackoff::delaySeconds))
        assertTrue(ReconnectBackoff.shouldRetry(14 * 60_000L, 15))
        assertFalse(ReconnectBackoff.shouldRetry(15 * 60_000L, 15))
        assertTrue(ReconnectBackoff.shouldRetry(Long.MAX_VALUE / 2, 0))
    }

    @Test
    fun `a host's keepalive and reconnect are its own when set and Settings › Connection's when not`() {
        val inherits = PersistencePolicy()
        // The shipped defaults are what every host did before it could inherit.
        assertEquals(15, inherits.effectiveKeepaliveSeconds(ConnectionSettings()))
        assertEquals(15, inherits.effectiveReconnectMinutes(ConnectionSettings()))

        val moved = ConnectionSettings(keepaliveSeconds = 30, reconnectMinutes = 0)
        assertEquals(30, inherits.effectiveKeepaliveSeconds(moved))
        assertEquals(0, inherits.effectiveReconnectMinutes(moved), "forever reaches the host too")
        val own = PersistencePolicy(keepaliveSeconds = 0, reconnectMinutes = 60)
        assertEquals(0, own.effectiveKeepaliveSeconds(moved), "a host's Off is its own, not an inherit")
        assertEquals(60, own.effectiveReconnectMinutes(moved))
    }

    @Test
    fun `the host editor's old starting values read as inherit, field by field, and nothing else does`() {
        assertEquals(PersistencePolicy(), PersistencePolicy(keepaliveSeconds = 15, reconnectMinutes = 15).foldLegacyDefaults())
        assertEquals(PersistencePolicy(keepaliveSeconds = 30), PersistencePolicy(keepaliveSeconds = 30, reconnectMinutes = 15).foldLegacyDefaults())
        assertEquals(
            PersistencePolicy(reconnectMinutes = 0, tmux = TmuxMode.ATTACH_OR_CREATE, tmuxSessionName = "web"),
            PersistencePolicy(keepaliveSeconds = 15, reconnectMinutes = 0, tmux = TmuxMode.ATTACH_OR_CREATE, tmuxSessionName = "web").foldLegacyDefaults(),
        )
        assertEquals(PersistencePolicy(keepaliveSeconds = 0, reconnectMinutes = 60), PersistencePolicy(keepaliveSeconds = 0, reconnectMinutes = 60).foldLegacyDefaults())
    }

    /**
     * A format 1 bundle stored the editor's starting values on every host that never changed them;
     * this build reads those as inherit. A format 2 document says inherit itself, so a 15 there is
     * the host's own and stays one.
     */
    @Test
    fun `a format 1 bundle's hosts inherit where they carried the old starting values`() {
        val host = """"color":"SLATE","monogram":"AA","user":"ben","createdAt":1"""
        val v1 = """{"format":1,"exported_at":1,"hosts":[""" +
            """{"id":"a","name":"a","address":"a.example",$host,"persistence":{"keepaliveSeconds":15,"reconnectMinutes":15,"tmux":"OFF","tmuxSessionName":null,"tmuxPrefix":"C-b","transport":"SSH"}},""" +
            """{"id":"b","name":"b","address":"b.example",$host,"persistence":{"keepaliveSeconds":60,"reconnectMinutes":0,"tmux":"ATTACH_OR_CREATE","tmuxSessionName":null,"tmuxPrefix":"C-a","transport":"SSH"}},""" +
            """{"id":"c","name":"c","address":"c.example",$host}]}"""
        val read = BerthBundle.fromJson(v1)
        assertEquals(PersistencePolicy(), read.hosts[0].persistence)
        assertEquals(PersistencePolicy(keepaliveSeconds = 60, reconnectMinutes = 0, tmux = TmuxMode.ATTACH_OR_CREATE, tmuxPrefix = "C-a"), read.hosts[1].persistence)
        assertEquals(PersistencePolicy(), read.hosts[2].persistence)
        assertEquals(1, read.format, "the document read says what it was")

        val pinned = Host(id = "d", name = "d", color = SwatchColor.SLATE, monogram = "DD", address = "d.example", user = "ben", createdAt = 1, persistence = PersistencePolicy(keepaliveSeconds = 15))
        val v2 = BerthBundle(exportedAt = 1, hosts = listOf(pinned, pinned.copy(id = "e", persistence = PersistencePolicy()))).toJson()
        assertTrue(v2.contains(""""format":2"""))
        assertEquals(listOf(PersistencePolicy(keepaliveSeconds = 15), PersistencePolicy()), BerthBundle.fromJson(v2).hosts.map { it.persistence })
    }
}
