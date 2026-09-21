package app.berth.android.ui.stage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The bundled Public Suffix List private section behind the link sheet's parent-claim rule: that
 * it loads whole from the resource, and how a host finds the platform it stands under, the
 * host itself, a `*.` entry's extra label, the section's Unicode names by their punycode, the
 * path-hosted Google names beside the section, and the hosts the list does not know.
 */
class UserContentHostsTest {
    @Test
    fun `the bundled section loads whole`() {
        // 3,376 lines of the section at the header's commit, 272 of them wildcards, and the three path-hosted names beside them.
        // A refresh of public-suffix-private.txt updates this count with it.
        assertEquals("public-suffix-private.txt did not load whole", 3379, UserContentHosts.size)
    }

    @Test
    fun `a host finds the longest platform it stands under, itself included`() {
        assertEquals("github.io", UserContentHosts.boundaryOf("evil.github.io"))
        assertEquals("github.io", UserContentHosts.boundaryOf("docs.evil.github.io"))
        assertEquals("github.io", UserContentHosts.boundaryOf("github.io"))
        assertEquals("s3.amazonaws.com", UserContentHosts.boundaryOf("evil-bucket.s3.amazonaws.com"))
        assertEquals("web.app", UserContentHosts.boundaryOf("evil.web.app"))
        // The host is read the way the caption names it: lowercased, a closing dot dropped.
        assertEquals("github.io", UserContentHosts.boundaryOf("Evil.GitHub.IO."))
    }

    @Test
    fun `a wildcard entry makes the label under it the platform's too`() {
        // *.compute.amazonaws.com: the region is Amazon's, the instance under it the customer's.
        assertEquals("eu-west-1.compute.amazonaws.com", UserContentHosts.boundaryOf("i-0abc.eu-west-1.compute.amazonaws.com"))
        assertEquals("eu-west-1.compute.amazonaws.com", UserContentHosts.boundaryOf("eu-west-1.compute.amazonaws.com"))
        // The wildcard's own base is not a line of its own.
        assertNull(UserContentHosts.boundaryOf("compute.amazonaws.com"))
        assertNull(UserContentHosts.boundaryOf("amazonaws.com"))
    }

    @Test
    fun `the section's Unicode names match by their punycode, either way they are written`() {
        assertEquals("xn--hkkinen-5wa.fi", UserContentHosts.boundaryOf("evil.h\u00E4kkinen.fi"))
        assertEquals("xn--hkkinen-5wa.fi", UserContentHosts.boundaryOf("evil.xn--hkkinen-5wa.fi"))
    }

    @Test
    fun `Google's path-hosted pages stand beside the section, and their parent does not`() {
        for (host in UserContentHosts.PATH_HOSTED) assertEquals(host, UserContentHosts.boundaryOf(host))
        assertEquals("sites.google.com", UserContentHosts.boundaryOf("www.sites.google.com"))
        assertNull(UserContentHosts.boundaryOf("google.com"))
        assertNull(UserContentHosts.boundaryOf("mail.google.com"))
    }

    @Test
    fun `a host the list does not know, a literal and nothing at all are nobody's platform`() {
        assertNull(UserContentHosts.boundaryOf("gist.github.com"))
        assertNull(UserContentHosts.boundaryOf("git.homelab.lan"))
        assertNull(UserContentHosts.boundaryOf("localhost"))
        assertNull(UserContentHosts.boundaryOf("[2001:db8::1]"))
        assertNull(UserContentHosts.boundaryOf("192.168.1.20"))
        assertNull(UserContentHosts.boundaryOf(""))
    }
}
