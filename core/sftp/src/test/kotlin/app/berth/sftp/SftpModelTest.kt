package app.berth.sftp

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SftpModelTest {
    @Test
    fun `permission text covers the nine bits and the special bits`() {
        assertEquals("-rw-r--r--", SftpPermissions.text(SftpFileType.REGULAR, 0b110_100_100))
        assertEquals("drwxr-xr-x", SftpPermissions.text(SftpFileType.DIRECTORY, 0b111_101_101))
        assertEquals("lrwxrwxrwx", SftpPermissions.text(SftpFileType.SYMLINK, 0b111_111_111))
        assertEquals("-rwsrwsrwt", SftpPermissions.text(SftpFileType.REGULAR, 0b111_111_111_111))
        assertEquals("-rwsr-sr-t", SftpPermissions.text(SftpFileType.REGULAR, 0b111_111_101_101))
        assertEquals("-rwSr-Sr-T", SftpPermissions.text(SftpFileType.REGULAR, 0b111_110_100_100))
        assertEquals("?---------", SftpPermissions.text(SftpFileType.OTHER, 0))
    }

    @Test
    fun `octal keeps three digits unless a special bit needs a fourth`() {
        assertEquals("644", SftpPermissions.octal(0b110_100_100))
        assertEquals("000", SftpPermissions.octal(0))
        assertEquals("4755", SftpPermissions.octal(SftpPermissions.SETUID or 0b111_101_101))
        assertEquals("1777", SftpPermissions.octal(SftpPermissions.STICKY or 0b111_111_111))
    }

    @Test
    fun `octal parsing accepts up to four digits and rejects the rest`() {
        assertEquals(0b110_100_100, SftpPermissions.parseOctal("644"))
        assertEquals(0b110_100_100, SftpPermissions.parseOctal("0644"))
        assertEquals(SftpPermissions.SETUID or 0b111_101_101, SftpPermissions.parseOctal("4755"))
        assertEquals(0, SftpPermissions.parseOctal("0"))
        assertNull(SftpPermissions.parseOctal(""))
        assertNull(SftpPermissions.parseOctal("778"))
        assertNull(SftpPermissions.parseOctal("07555"))
        assertNull(SftpPermissions.parseOctal("rw-"))
    }

    @Test
    fun `paths normalise, join, split and crumb`() {
        assertEquals("/", SftpPaths.normalize("/"))
        assertEquals("/", SftpPaths.normalize("///"))
        assertEquals("/home/demo", SftpPaths.normalize("/home//demo/"))
        assertEquals("/home", SftpPaths.normalize("/home/demo/.."))
        assertEquals("/", SftpPaths.normalize("/.."))
        assertEquals("/etc/ssh", SftpPaths.normalize("/etc/./ssh/./"))
        assertEquals(".", SftpPaths.normalize(""))

        assertEquals("/home/demo", SftpPaths.join("/home", "demo"))
        assertEquals("/demo", SftpPaths.join("/", "demo"))

        assertEquals("/home", SftpPaths.parent("/home/demo"))
        assertEquals("/", SftpPaths.parent("/home"))
        assertEquals("/", SftpPaths.parent("/"))
        assertEquals("demo", SftpPaths.name("/home/demo/"))
        assertEquals("/", SftpPaths.name("/"))

        assertEquals(listOf("/" to "/", "home" to "/home", "demo" to "/home/demo"), SftpPaths.crumbs("/home/demo"))
        assertEquals(listOf("/" to "/"), SftpPaths.crumbs("/"))
    }

    @Test
    fun `names with separators, control characters or the dot entries are refused`() {
        assertTrue(SftpPaths.isValidName("notes.txt"))
        assertTrue(SftpPaths.isValidName(".hidden"))
        assertTrue(SftpPaths.isValidName("my report (1).pdf"))
        assertTrue(SftpPaths.isValidName("caf\u00e9 \u2014 \u5B89\u5168.txt"), "anything a user can type is a name")
        assertFalse(SftpPaths.isValidName(""))
        assertFalse(SftpPaths.isValidName("."))
        assertFalse(SftpPaths.isValidName(".."))
        assertFalse(SftpPaths.isValidName("a/b"))
        assertFalse(SftpPaths.isValidName("a\u0000b"))
        assertFalse(SftpPaths.isValidName("a\nb.txt"), "a newline inside quotes drops the shell to its continuation prompt")
        assertFalse(SftpPaths.isValidName("a\tb"))
        assertFalse(SftpPaths.isValidName("a\u001b[2Jb"), "an escape moves the shell's cursor")
        assertFalse(SftpPaths.isValidName("a\u007fb"))
    }

    @Test
    fun `entries read their type through symlinks`() {
        val link = SftpEntry("to-dir", "/x/to-dir", SftpFileType.SYMLINK, 0, 0, 0b111_111_111, linkTarget = SftpFileType.DIRECTORY)
        assertTrue(link.isDirectory)
        assertFalse(link.isRegularFile)
        assertTrue(link.isSymlink)
        val dangling = link.copy(linkTarget = null)
        assertFalse(dangling.isDirectory)
        assertFalse(dangling.isRegularFile)
        assertEquals("lrwxrwxrwx", dangling.permissionText)
    }
}
