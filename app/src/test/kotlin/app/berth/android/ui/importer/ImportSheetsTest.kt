package app.berth.android.ui.importer

import org.junit.Assert.assertEquals
import org.junit.Test

class ImportSheetsTest {
    @Test
    fun `the import button counts the keys it adds and the ones it replaces apart`() {
        assertEquals("Import 0 keys", importKnownHostsLabel(picked = 0, replacing = 0))
        assertEquals("Import 1 key", importKnownHostsLabel(picked = 1, replacing = 0))
        assertEquals("Import 3 keys", importKnownHostsLabel(picked = 3, replacing = 0))
        assertEquals("Import 2 keys, replace 1", importKnownHostsLabel(picked = 3, replacing = 1))
        assertEquals("Import 1 key, replace 2", importKnownHostsLabel(picked = 3, replacing = 2))
    }

    @Test
    fun `a pick that only replaces says so, since nothing is added beside what is there`() {
        assertEquals("Replace 1 key", importKnownHostsLabel(picked = 1, replacing = 1))
        assertEquals("Replace 2 keys", importKnownHostsLabel(picked = 2, replacing = 2))
    }
}
