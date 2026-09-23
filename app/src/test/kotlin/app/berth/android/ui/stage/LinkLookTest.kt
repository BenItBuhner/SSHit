package app.berth.android.ui.stage

import app.berth.android.ui.terminal.LinkTap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The rule behind the link sheet (spec A60), pinned as a table: which scheme takes which posture
 * and says what; which shown texts claim an address (a scheme, `www.`, a bare host that is neither
 * a file name nor a segment of the link's own path) and which do not (a directory listing's every
 * entry, a version tag, a decimal, `Node.js`, `PR #12`); the equivalences that make a text the
 * address itself; the dressed links the rule exists for, with their captions, the backslash a
 * browser reads as a slash and the host after the last `@` among them; the parent claims that
 * are the site's and those that are a platform's over a stranger's page (the Public Suffix List's
 * private section, bundled); the punycode every host outside ASCII is named by, so a homograph
 * reads as itself; and the path a `file://` link hands to Copy path and Paste path, decoded and
 * quoted for the shell. Under Robolectric for the framework's `android.icu`, the UTS #46 processor
 * the punycode comes from; the rule itself touches nothing else of Android's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LinkLookTest {
    private fun look(url: String, text: String) = LinkLook.of(LinkTap(url, text))

    private fun assertPlain(url: String, text: String, caption: String, posture: LinkLook.Posture = LinkLook.Posture.OPEN) {
        val look = look(url, text)
        assertEquals("$text over $url", caption, look.caption)
        assertFalse("$text over $url warns", look.warning)
        assertEquals(posture, look.posture)
    }

    private fun assertWarns(url: String, text: String, caption: String, posture: LinkLook.Posture = LinkLook.Posture.OPEN) {
        val look = look(url, text)
        assertEquals("$text over $url", caption, look.caption)
        assertTrue("$text over $url does not warn", look.warning)
        assertEquals(posture, look.posture)
    }

    // ---- what claims an address and what does not ----------------------------------------------------------

    @Test
    fun `a directory listing's entries are names, not hosts, whatever their extension`() {
        for (name in listOf("notes.txt", "main.py", "archive.tar.gz", "README.md", "Makefile", "hosts.allow", "id_rsa.pub", "Node.js", "report.pdf", "config.local")) {
            val url = "file://homelab/home/ben/$name"
            val look = look(url, name)
            assertEquals(name, "A file on homelab: /home/ben/$name", look.caption)
            assertFalse("$name warns", look.warning)
            assertEquals(LinkLook.Posture.FILE, look.posture)
            assertEquals("/home/ben/$name", look.path)
        }
        // A name the address does not hold is still a name when its extension says so, over any scheme.
        assertPlain("https://files.example/dl?id=91", "report.pdf", "Shown as \u201Creport.pdf\u201D, goes to files.example")
        assertPlain("https://nodejs.org/", "Node.js", "Shown as \u201CNode.js\u201D, goes to nodejs.org")
        assertPlain("https://files.example/reports/report.pdf", "report.pdf", "Shown as \u201Creport.pdf\u201D, goes to files.example")
    }

    @Test
    fun `version tags, decimals and labels with spaces claim nothing`() {
        assertPlain("https://github.com/berth/berth/releases/tag/v1.2.3", "v1.2.3", "Shown as \u201Cv1.2.3\u201D, goes to github.com")
        assertPlain("https://pypi.org/project/berth/3.14/", "3.14", "Shown as \u201C3.14\u201D, goes to pypi.org")
        assertPlain("https://git.homelab.lan/berth/berth/pulls/12", "PR #12", "Shown as \u201CPR #12\u201D, goes to git.homelab.lan")
        assertPlain("https://evil.example/login", "release notes", "Shown as \u201Crelease notes\u201D, goes to evil.example")
        assertNull(LinkLook.hostClaim("v1.2.3", "https://x.example/"))
        assertNull(LinkLook.hostClaim("3.14", "https://x.example/"))
        assertNull(LinkLook.hostClaim("notes.txt", "https://x.example/"))
        assertNull(LinkLook.hostClaim("Makefile", "https://x.example/"))
        assertNull(LinkLook.hostClaim("PR #12", "https://x.example/"))
    }

    @Test
    fun `a text that is a segment of the link's own path is the address naming itself`() {
        // A certificate directory holds a file named for a site; the listing's link to it is not a lie.
        assertPlain("file://homelab/etc/ssl/certs/google.com", "google.com", "A file on homelab: /etc/ssl/certs/google.com", LinkLook.Posture.FILE)
        assertNull(LinkLook.hostClaim("google.com", "https://mirror.example/pool/google.com/index.html"))
        assertNull(LinkLook.hostClaim("Google.COM", "https://mirror.example/pool/google.com/"))
        // A percent-encoded segment is read decoded.
        assertNull(LinkLook.hostClaim("my.site.io", "https://mirror.example/my%2Esite%2Eio"))
        // The query is not the path: a redirect parameter is how a dressed link carries the name it wears.
        assertEquals("google.com", LinkLook.hostClaim("google.com", "https://evil.example/?r=google.com"))
        assertEquals("google.com", LinkLook.hostClaim("google.com", "https://evil.example/login#google.com"))
    }

    @Test
    fun `the address itself, give or take the scheme, www and a closing slash, names the host alone`() {
        assertPlain("https://caddyserver.com/docs/", "https://caddyserver.com/docs/", "Goes to caddyserver.com")
        assertPlain("https://caddyserver.com/docs/", "caddyserver.com/docs", "Goes to caddyserver.com")
        assertPlain("https://caddyserver.com/docs/", "www.caddyserver.com/docs", "Goes to caddyserver.com")
        assertPlain("https://www.caddyserver.com/docs", "HTTPS://caddyserver.com/docs/", "Goes to www.caddyserver.com")
        assertPlain("https://caddyserver.com/docs/", "", "Goes to caddyserver.com")
        // `www.` on the text alone is the same site, so the caption names the host alone.
        assertPlain("https://google.com/", "www.google.com", "Goes to google.com")
        assertPlain("https://www.google.com/", "google.com", "Goes to www.google.com")
    }

    @Test
    fun `a link dressed as another site warns, and the caption shows the text as it was`() {
        assertWarns("https://evil.example/login", "https://github.com/berth/releases", "Shown as \u201Chttps://github.com/berth/releases\u201D, but goes to evil.example")
        assertWarns("https://google.com@evil.example/", "google.com", "Shown as \u201Cgoogle.com\u201D, but goes to evil.example")
        assertWarns("https://evil.example/?r=google.com", "google.com", "Shown as \u201Cgoogle.com\u201D, but goes to evil.example")
        assertWarns("https://evil.example/", "www.bank.com", "Shown as \u201Cwww.bank.com\u201D, but goes to evil.example")
        assertWarns("https://evil.example/", "bank.co.uk/login", "Shown as \u201Cbank.co.uk/login\u201D, but goes to evil.example")
        // The caption keeps the text's own case: what the user saw, not a lowercased host.
        assertWarns("https://evil.example/", "GitHub.com", "Shown as \u201CGitHub.com\u201D, but goes to evil.example")
        // The shown text is the attacker's to draw: a backslash for the slash claims the host all the same.
        assertWarns("https://evil.example/", "google.com\\login", "Shown as \u201Cgoogle.com\\login\u201D, but goes to evil.example")
        assertEquals("google.com", LinkLook.hostClaim("google.com\\login", "https://x.example/"))
    }

    @Test
    fun `the host is what follows the last @, as the browser and SshLink read it`() {
        // A user@REALM login, which SshLink allows, over its own host is no lie.
        assertPlain("ssh://ben@CORP@host.example/", "host.example", "Shown as \u201Chost.example\u201D, opens in Berth: ben@CORP@host.example", LinkLook.Posture.BERTH)
        // Chrome goes to the host after the last @; the caption and the deception check go with it.
        assertWarns("https://google.com@google.com@evil.example/", "google.com", "Shown as \u201Cgoogle.com\u201D, but goes to evil.example")
        assertPlain("https://google.com@evil.example@google.com/", "https://google.com@evil.example@google.com/", "Goes to google.com")
        assertEquals("b.google.com", LinkLook.hostOf("https://a@evil.example@b.google.com/"))
    }

    @Test
    fun `a backslash is a slash in a web or file address, as the browser that opens it reads it`() {
        // Chrome and Android's Uri end the authority at the backslash: this goes to evil.example, `/@google.com/` its path.
        assertWarns("https://evil.example\\@google.com/", "google.com", "Shown as \u201Cgoogle.com\u201D, but goes to evil.example")
        assertEquals("evil.example", LinkLook.hostOf("https://evil.example\\@google.com/"))
        assertPlain("https://evil.example\\@google.com/", "https://evil.example\\@google.com/", "Goes to evil.example")
        // The same reading clears a false alarm: this goes to google.com, the rest is its path.
        assertPlain("https://google.com\\.evil.example/", "google.com", "Shown as \u201Cgoogle.com\u201D, goes to google.com")
        // Backslashes for the scheme's own slashes still name the host, and a text drawn the same way is the address itself.
        assertPlain("https:\\\\evil.example/", "click here", "Shown as \u201Cclick here\u201D, goes to evil.example")
        assertPlain("https:\\\\evil.example/", "https:\\\\evil.example/", "Goes to evil.example")
        // In a file address a raw backslash is a separator too, while an encoded one is a character of the name.
        assertEquals("A file on homelab: /home/ben/notes.txt", look("file://homelab/home\\ben\\notes.txt", "notes.txt").caption)
        assertEquals("/home/ben/back\\slash.txt", look("file://homelab/home/ben/back%5Cslash.txt", "back\\slash.txt").path)
    }

    @Test
    fun `the same host in another case or under www is not a lie`() {
        assertPlain("https://github.com/berth", "GitHub.com", "Shown as \u201CGitHub.com\u201D, goes to github.com")
        assertPlain("https://www.github.com/berth", "github.com/berth", "Goes to www.github.com")
        assertPlain("https://github.com:443/berth", "github.com", "Shown as \u201Cgithub.com\u201D, goes to github.com")
    }

    @Test
    fun `a claim that is the parent of the link's host is the same site, and a stranger's host wearing it is not`() {
        assertPlain("https://gist.github.com/x", "github.com", "Shown as \u201Cgithub.com\u201D, goes to gist.github.com")
        assertPlain("https://www.docs.github.com/", "github.com", "Shown as \u201Cgithub.com\u201D, goes to www.docs.github.com")
        // The host must end in `.` and the claim, not merely in the claim's letters: evil.example can carry google.com, not be under it.
        assertWarns("https://google.com.evil.example/", "google.com", "Shown as \u201Cgoogle.com\u201D, but goes to google.com.evil.example")
        assertWarns("https://notgithub.com/", "github.com", "Shown as \u201Cgithub.com\u201D, but goes to notgithub.com")
        // `www.` shorn from a claim must leave a domain: www.com is no parent of every .com host, www.google.com of google's is.
        assertWarns("https://evil.com/", "www.com", "Shown as \u201Cwww.com\u201D, but goes to evil.com")
        assertPlain("https://mail.google.com/", "www.google.com", "Shown as \u201Cwww.google.com\u201D, goes to mail.google.com")
    }

    @Test
    fun `a claim of a platform over a page any customer of it can put there warns, and one below the platform is the site`() {
        // The Public Suffix List's private section, bundled (UserContentHosts): the label under github.io is a stranger's.
        assertWarns("https://evil.github.io/login", "github.io", "Shown as \u201Cgithub.io\u201D, but goes to evil.github.io")
        assertWarns("https://www.evil.github.io/login", "github.io", "Shown as \u201Cgithub.io\u201D, but goes to www.evil.github.io")
        assertPlain("https://evil.github.io/login", "evil.github.io", "Shown as \u201Cevil.github.io\u201D, goes to evil.github.io")
        assertPlain("https://docs.evil.github.io/x", "evil.github.io", "Shown as \u201Cevil.github.io\u201D, goes to docs.evil.github.io")
        assertWarns("https://evil.web.app/", "web.app", "Shown as \u201Cweb.app\u201D, but goes to evil.web.app")
        // The platform's own name and every name above it are the platform's: the bucket is the customer's label.
        assertWarns("https://evil-bucket.s3.amazonaws.com/login.html", "amazonaws.com", "Shown as \u201Camazonaws.com\u201D, but goes to evil-bucket.s3.amazonaws.com")
        assertWarns("https://evil-bucket.s3.amazonaws.com/login.html", "s3.amazonaws.com", "Shown as \u201Cs3.amazonaws.com\u201D, but goes to evil-bucket.s3.amazonaws.com")
        assertPlain("https://evil-bucket.s3.amazonaws.com/login.html", "evil-bucket.s3.amazonaws.com", "Shown as \u201Cevil-bucket.s3.amazonaws.com\u201D, goes to evil-bucket.s3.amazonaws.com")
        // A `*.` entry (*.compute.amazonaws.com) makes the region the platform's too.
        assertWarns("https://i-0abc.eu-west-1.compute.amazonaws.com/", "amazonaws.com", "Shown as \u201Camazonaws.com\u201D, but goes to i-0abc.eu-west-1.compute.amazonaws.com")
        assertWarns("https://i-0abc.eu-west-1.compute.amazonaws.com/", "eu-west-1.compute.amazonaws.com", "Shown as \u201Ceu-west-1.compute.amazonaws.com\u201D, but goes to i-0abc.eu-west-1.compute.amazonaws.com")
        // Pages under a path on Google's own hosts, which the section has no line for and the list carries beside it.
        assertWarns("https://sites.google.com/view/paypal-login", "google.com", "Shown as \u201Cgoogle.com\u201D, but goes to sites.google.com")
        assertWarns("https://docs.google.com/forms/d/e/1FAI/viewform", "google.com", "Shown as \u201Cgoogle.com\u201D, but goes to docs.google.com")
        // A parent that is no platform keeps the rule: gist.github.com and mail.google.com are their owners' pages.
        assertPlain("https://mail.google.com/", "google.com", "Shown as \u201Cgoogle.com\u201D, goes to mail.google.com")
        assertPlain("https://gist.github.com/x", "github.com", "Shown as \u201Cgithub.com\u201D, goes to gist.github.com")
        // A Unicode entry of the section is matched by its punycode, the form the host is read in.
        assertWarns("https://evil.h\u00E4kkinen.fi/", "h\u00E4kkinen.fi", "Shown as \u201Ch\u00E4kkinen.fi\u201D, but goes to evil.xn--hkkinen-5wa.fi")
    }

    @Test
    fun `a host outside ASCII is named by its punycode, so a homograph reads as what it is`() {
        // Cyrillic а: two strings the eye cannot tell apart, until the caption names the host as the resolver reads it.
        assertWarns("https://\u0430pple.com/", "apple.com", "Shown as \u201Capple.com\u201D, but goes to xn--pple-43d.com")
        assertPlain("https://\u0430pple.com/", "https://\u0430pple.com/", "Goes to xn--pple-43d.com")
        assertPlain("https://\u0430pple.com/", "\u0430pple.com", "Goes to xn--pple-43d.com")
        assertEquals("xn--pple-43d.com", LinkLook.hostOf("https://\u0430pple.com/"))
        assertEquals("www.xn--pple-43d.com", LinkLook.hostOf("HTTPS://WWW.\u0410pple.com/"))
        // The text in another script claims a host all the same, and is measured against where the link goes.
        assertWarns("https://evil.example/", "\u0430pple.com", "Shown as \u201C\u0430pple.com\u201D, but goes to evil.example")
        assertEquals("xn--pple-43d.com", LinkLook.hostClaim("\u0430pple.com", "https://evil.example/"))
        assertPlain("https://xn--mnchen-3ya.de/", "m\u00FCnchen.de", "Shown as \u201Cm\u00FCnchen.de\u201D, goes to xn--mnchen-3ya.de")
        // UTS #46, non-transitional, as the browser that opens the tap reads it: ß stands and is encoded, so the
        // caption names the host the tap resolves; IDNA2003 mapped it to ss and would have named strasse.de, a
        // registrable name of its own, and read the two as one host.
        assertEquals("xn--strae-oqa.de", LinkLook.asciiHost("stra\u00DFe.de"))
        assertPlain("https://stra\u00DFe.de/", "stra\u00DFe.de", "Goes to xn--strae-oqa.de")
        assertWarns("https://stra\u00DFe.de/", "strasse.de", "Shown as \u201Cstrasse.de\u201D, but goes to xn--strae-oqa.de")
        // The other postures name their hosts the same way.
        assertPlain("ssh://root@\u0430pple.com:2222/", "the box", "Shown as \u201Cthe box\u201D, opens in Berth: root@xn--pple-43d.com:2222", LinkLook.Posture.BERTH)
        assertPlain("ssh://root@[::1]:2222/", "the box", "Shown as \u201Cthe box\u201D, opens in Berth: root@[::1]:2222", LinkLook.Posture.BERTH)
        assertEquals("A file on xn--mnchen-3ya: /etc/hosts", look("file://m\u00FCnchen/etc/hosts", "hosts").caption)
        assertPlain("mailto:ben@\u0430pple.com", "ben@\u0430pple.com", "Mails ben@xn--pple-43d.com")
        assertWarns("mailto:ben@\u0430pple.com", "ben@apple.com", "Shown as \u201Cben@apple.com\u201D, but mails ben@xn--pple-43d.com")
        // ASCII is only lowercased; a bracketed literal is left alone; a name the browser refuses too stands as it is, lowercased.
        assertEquals("github.com", LinkLook.asciiHost("GitHub.com"))
        assertEquals("[2001:db8::1]", LinkLook.asciiHost("[2001:DB8::1]"))
        val leadingMark = "\u0301x.com"
        assertEquals(leadingMark, LinkLook.asciiHost(leadingMark))
    }

    @Test
    fun `a host is encoded under the browser's profile, not STD3's or DNS's lengths`() {
        // Chrome's WHATWG host parser runs UTS #46 without STD3's character set, so a _ label beside a homograph
        // is still punycoded; under STD3 the whole host was refused and the caption named the Cyrillic а as it is.
        assertEquals("my_shop.xn--pple-43d.com", LinkLook.asciiHost("my_shop.\u0430pple.com"))
        assertPlain("https://my_shop.\u0430pple.com/", "click here", "Shown as \u201Cclick here\u201D, goes to my_shop.xn--pple-43d.com")
        assertWarns("https://my_shop.\u0430pple.com/", "https://my_shop.apple.com/", "Shown as \u201Chttps://my_shop.apple.com/\u201D, but goes to my_shop.xn--pple-43d.com")
        // Nor does it check DNS's lengths or a label's hyphens: a label past 63 bytes is encoded all the same.
        assertEquals("xn--4c" + "a".repeat(60) + ".com", LinkLook.asciiHost("\u00C4".repeat(60) + ".com"))
        assertEquals("xn--ab--c-kra.de", LinkLook.asciiHost("ab--c\u00E4.de"))
        // But a host with nothing left once mapped, a lone soft hyphen, is refused as Chrome refuses it: the caption
        // names no host where it read "goes to " and nothing, and the panel keeps the address as it came.
        assertEquals("\u00AD", LinkLook.asciiHost("\u00AD"))
        assertNull(LinkLook.hostOf("https://\u00AD/"))
        assertPlain("https://\u00AD/", "https://\u00AD/", "A web link")
        assertPlain("https://\u00AD/", "click here", "Shown as \u201Cclick here\u201D, a web link")
        assertEquals("https://\u00AD/", LinkLook.withAsciiHost("https://\u00AD/"))
    }

    @Test
    fun `the panel's address is the link's as it came, its host in ASCII once the caption says but`() {
        // The sheet's largest text and its warning agree: a Cyrillic а cannot read as apple's over a caption naming xn--pple-43d.com.
        assertEquals("https://xn--pple-43d.com/id", look("https://\u0430pple.com/id", "apple.com").address)
        assertEquals("mailto:ben@xn--pple-43d.com", look("mailto:ben@\u0430pple.com", "ben@apple.com").address)
        // A host the text names truly, or claims nothing over, stands as written, since that is what is opened.
        assertEquals("https://\u0430pple.com/id", look("https://\u0430pple.com/id", "\u0430pple.com").address)
        assertEquals("https://\u0430pple.com/id", look("https://\u0430pple.com/id", "click here").address)
        assertEquals("https://m\u00FCnchen.de/", look("https://m\u00FCnchen.de/", "m\u00FCnchen.de").address)
        // An ASCII host is drawn as it is whether the caption warns or not.
        assertEquals("https://evil.example/login", look("https://evil.example/login", "https://github.com/berth/releases").address)
        // Only the host is redrawn: the scheme's case, the path and a query outside ASCII stand, the host is the one after the last @,
        // a mailbox's domain is its host, and a host the browser refuses is left as it came.
        assertEquals("HTTPS://xn--pple-43d.com/Id?q=\u0430", LinkLook.withAsciiHost("HTTPS://\u0410pple.com/Id?q=\u0430"))
        assertEquals("https://user@xn--pple-43d.com/", LinkLook.withAsciiHost("https://user@\u0430pple.com/"))
        assertEquals("mailto:ben@xn--pple-43d.com?subject=hi", LinkLook.withAsciiHost("mailto:ben@\u0430pple.com?subject=hi"))
        val leadingMark = "https://\u0301x.com/"
        assertEquals(leadingMark, LinkLook.withAsciiHost(leadingMark))
        assertEquals("a bare word", LinkLook.withAsciiHost("a bare word"))
    }

    @Test
    fun `an IPv6 literal keeps its brackets`() {
        assertPlain("https://[::1]:8080/", "the dashboard", "Shown as \u201Cthe dashboard\u201D, goes to [::1]")
        assertPlain("https://[2001:db8::1]/x", "https://[2001:db8::1]/x", "Goes to [2001:db8::1]")
        assertEquals("[2001:db8::1]", LinkLook.hostOf("http://user@[2001:DB8::1]:80/"))
    }

    @Test
    fun `a long text is cut in the caption, the address panel showing the rest`() {
        val text = "https://accounts.google.com/signin/v2/identifier?service=mail&passive=true"
        assertWarns("https://evil.example/", text, "Shown as \u201C${text.take(40)}\u2026\u201D, but goes to evil.example")
    }

    // ---- the scheme table ------------------------------------------------------------------------------------

    @Test
    fun `a web link opens, its caption naming the host`() {
        assertPlain("https://caddyserver.com/docs/", "the docs", "Shown as \u201Cthe docs\u201D, goes to caddyserver.com")
        assertPlain("http://192.168.1.20:8080/", "the dashboard", "Shown as \u201Cthe dashboard\u201D, goes to 192.168.1.20")
    }

    @Test
    fun `a file link is a file on the remote, its path decoded, with nothing to open`() {
        val look = look("file://homelab/home/ben/my%20notes.txt", "'my notes.txt'")
        assertEquals("A file on homelab: /home/ben/my notes.txt", look.caption)
        assertEquals(LinkLook.Posture.FILE, look.posture)
        assertEquals("/home/ben/my notes.txt", look.path)
        assertFalse(look.warning)
        // No host: what `ls` prints on a machine with none set, and what a tool prints for its own machine.
        assertEquals("A file: /etc/hosts", look("file:///etc/hosts", "hosts").caption)
        assertEquals("/etc/hosts", look("file:///etc/hosts", "hosts").path)
        assertEquals("A file on localhost: /var/log/syslog", look("file://localhost/var/log/syslog", "syslog").caption)
        // A query or fragment is not the path.
        assertEquals("/srv/www/index.html", look("file://homelab/srv/www/index.html?x=1#top", "index.html").path)
        // Text that claims another site over a file link is still a lie, in a sheet that opens nothing.
        assertWarns("file://homelab/home/ben/notes.txt", "google.com", "Shown as \u201Cgoogle.com\u201D, but a file on homelab: /home/ben/notes.txt", LinkLook.Posture.FILE)
        assertEquals("\u2713 done.txt", look("file://homelab/%E2%9C%93%20done.txt", "done").path?.trimStart('/'))
    }

    @Test
    fun `an ssh or sftp link says Berth is what opens it, and for whom`() {
        assertPlain("ssh://root@evil.example:2222/", "prod box", "Shown as \u201Cprod box\u201D, opens in Berth: root@evil.example:2222", LinkLook.Posture.BERTH)
        assertPlain("ssh://root@evil.example:2222/", "root@evil.example:2222", "Opens in Berth: root@evil.example:2222", LinkLook.Posture.BERTH)
        assertPlain("ssh://homelab", "homelab", "Opens in Berth: homelab", LinkLook.Posture.BERTH)
        // A password in the address is nobody's caption; a fingerprint parameter is Berth's to read, not to show.
        assertPlain("ssh://root:hunter2@homelab/", "the box", "Shown as \u201Cthe box\u201D, opens in Berth: root@homelab", LinkLook.Posture.BERTH)
        assertPlain("sftp://ben@homelab/srv/www;fingerprint=SHA256:abc", "www", "Shown as \u201Cwww\u201D, opens in Berth: ben@homelab", LinkLook.Posture.BERTH)
        assertEquals("homelab", LinkLook.hostOf("sftp://ben@homelab;fingerprint=SHA256:abc"))
        // The draft URI keeps the fingerprint parameter in the userinfo, where SshLink reads it; the caption keeps the host and port.
        assertPlain("ssh://ben;fingerprint=SHA256%3Aabc@host.example:2222/", "the box", "Shown as \u201Cthe box\u201D, opens in Berth: ben@host.example:2222", LinkLook.Posture.BERTH)
        assertPlain("ssh://ben:hunter2;fingerprint=SHA256%3Aabc@host.example", "host.example", "Shown as \u201Chost.example\u201D, opens in Berth: ben@host.example", LinkLook.Posture.BERTH)
        assertPlain("ssh://ben@homelab;fingerprint=SHA256:abc", "homelab", "Shown as \u201Chomelab\u201D, opens in Berth: ben@homelab", LinkLook.Posture.BERTH)
        // Dressed as a site, an ssh link is a lie like any other.
        assertWarns("ssh://root@evil.example/", "github.com", "Shown as \u201Cgithub.com\u201D, but opens in Berth: root@evil.example", LinkLook.Posture.BERTH)
    }

    @Test
    fun `a mailto link opens with the address as its caption, and a mailbox as the text claims that mailbox`() {
        assertPlain("mailto:ben@homelab.lan", "ben@homelab.lan", "Mails ben@homelab.lan")
        assertPlain("mailto:ben@homelab.lan?subject=hi", "mailto:ben@homelab.lan", "Mails ben@homelab.lan")
        assertPlain("mailto:ben@homelab.lan", "Ben", "Shown as \u201CBen\u201D, mails ben@homelab.lan")
        assertPlain("mailto:support@bank.com", "bank.com", "Shown as \u201Cbank.com\u201D, mails support@bank.com")
        // The address percent-encoded in the link, or in the text, is the same mailbox: decoded before it is compared.
        assertPlain("mailto:ben%40homelab.lan", "ben@homelab.lan", "Mails ben@homelab.lan")
        assertPlain("mailto:ben@homelab.lan", "mailto:ben%40homelab.lan", "Mails ben@homelab.lan")
        assertPlain("mailto:Ben@Homelab.lan", "ben@homelab.lan", "Mails Ben@Homelab.lan")
        assertWarns("mailto:attacker@evil.example", "support@bank.com", "Shown as \u201Csupport@bank.com\u201D, but mails attacker@evil.example")
        assertWarns("mailto:attacker@evil.example", "bank.com", "Shown as \u201Cbank.com\u201D, but mails attacker@evil.example")
    }

    @Test
    fun `any other scheme is another app's to open, said so, with the weight on Cancel`() {
        assertPlain("upi://pay?pa=attacker@bank&am=5000", "invoice", "Shown as \u201Cinvoice\u201D, another app on this phone would open this upi link", LinkLook.Posture.OTHER_APP)
        assertPlain("market://details?id=com.evil.app", "market://details?id=com.evil.app", "Another app on this phone would open this market link", LinkLook.Posture.OTHER_APP)
        assertPlain("tel:+15551234", "+15551234", "Another app on this phone would open this tel link", LinkLook.Posture.OTHER_APP)
        assertPlain("sms:+15551234?body=hi", "text us", "Shown as \u201Ctext us\u201D, another app on this phone would open this sms link", LinkLook.Posture.OTHER_APP)
        assertPlain("whatsapp://send?text=hi", "chat", "Shown as \u201Cchat\u201D, another app on this phone would open this whatsapp link", LinkLook.Posture.OTHER_APP)
        assertPlain("intent://scan/#Intent;scheme=zxing;end", "scan", "Shown as \u201Cscan\u201D, another app on this phone would open this intent link", LinkLook.Posture.OTHER_APP)
        assertPlain("ftp://mirror.example/pub/", "the mirror", "Shown as \u201Cthe mirror\u201D, another app on this phone would open this ftp link", LinkLook.Posture.OTHER_APP)
        // Dressed as a site, it is a lie on top: the caption says so and the sheet is already weighted to Cancel.
        assertWarns("javascript:alert(1)", "google.com", "Shown as \u201Cgoogle.com\u201D, but another app on this phone would open this javascript link", LinkLook.Posture.OTHER_APP)
        assertWarns("market://details?id=com.evil.app", "https://play.google.com/store", "Shown as \u201Chttps://play.google.com/store\u201D, but another app on this phone would open this market link", LinkLook.Posture.OTHER_APP)
        // The first segment of an opaque scheme is not a host the caption names.
        assertFalse(look("upi://pay?pa=x", "pay").caption.contains("goes to"))
    }

    // ---- what a file link hands to the shell ----------------------------------------------------------------

    @Test
    fun `a path is one word for the shell, plain when it can be and quoted when it must`() {
        assertEquals("/home/ben/notes.txt", LinkLook.shellQuote("/home/ben/notes.txt"))
        assertEquals("/srv/www/v1.2-beta+3/index.html", LinkLook.shellQuote("/srv/www/v1.2-beta+3/index.html"))
        assertEquals("'/home/ben/my notes.txt'", LinkLook.shellQuote("/home/ben/my notes.txt"))
        assertEquals("'/home/ben/it'\\''s.txt'", LinkLook.shellQuote("/home/ben/it's.txt"))
        assertEquals("'\$HOME/x'", LinkLook.shellQuote("\$HOME/x"))
        assertEquals("'/home/ben/a;rm -rf ~'", LinkLook.shellQuote("/home/ben/a;rm -rf ~"))
        assertEquals("'/tmp/caf\u00E9'", LinkLook.shellQuote("/tmp/caf\u00E9"))
        assertEquals("''", LinkLook.shellQuote(""))
    }

    @Test
    fun `percent runs decode as UTF-8 and anything malformed stands`() {
        assertEquals("/home/ben/my notes.txt", LinkLook.percentDecode("/home/ben/my%20notes.txt"))
        assertEquals("\u2713", LinkLook.percentDecode("%E2%9C%93"))
        assertEquals("100%", LinkLook.percentDecode("100%"))
        assertEquals("%zz%2", LinkLook.percentDecode("%zz%2"))
        assertEquals("a+b", LinkLook.percentDecode("a+b"))
    }
}
