package app.berth.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuickConnectTest {
    @Test
    fun `accepts the usual spellings`() {
        assertEquals(Triple("ben", "10.0.2.2", 2222), AppViewModel.parseQuickConnect("ben@10.0.2.2:2222"))
        assertEquals(Triple("root", "example.com", 22), AppViewModel.parseQuickConnect("example.com"))
        assertEquals(Triple("root", "example.com", 2200), AppViewModel.parseQuickConnect("example.com:2200"))
        assertEquals(Triple("deploy", "host.internal", 22), AppViewModel.parseQuickConnect("ssh://deploy@host.internal/"))
        assertEquals(Triple("ben", "fe80::1", 22), AppViewModel.parseQuickConnect("ben@[fe80::1]"))
    }

    @Test
    fun `rejects nonsense`() {
        assertNull(AppViewModel.parseQuickConnect(""))
        assertNull(AppViewModel.parseQuickConnect("host:99999"))
        assertNull(AppViewModel.parseQuickConnect("a b"))
        assertNull(AppViewModel.parseQuickConnect("user@"))
    }
}
