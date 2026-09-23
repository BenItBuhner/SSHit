package app.berth.android.ui.io

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A picked document is read up to the 2 MB limit and no further, and a refusal says why in a sentence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TextFilesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `a document at the limit reads whole, and one a byte over it is too big`() {
        val atLimit = File(tmp.root, "at-limit.conf").apply { writeBytes(ByteArray(MAX_TEXT_BYTES) { 'a'.code.toByte() }) }
        val read = readDocument(context, Uri.fromFile(atLimit))
        assertEquals(MAX_TEXT_BYTES, (read as PickedText.Read).text.length)

        val over = File(tmp.root, "over.conf").apply { writeBytes(ByteArray(MAX_TEXT_BYTES + 1) { 'a'.code.toByte() }) }
        assertEquals(PickedText.TooBig, readDocument(context, Uri.fromFile(over)))
    }

    @Test
    fun `an endless stream is read one byte past the limit and no further`() {
        var served = 0
        val endless = object : InputStream() {
            override fun read(): Int = 'x'.code.also { served++ }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                b.fill('x'.code.toByte(), off, off + len)
                served += len
                return len
            }
        }
        assertNull(readBounded(endless, 1000))
        assertEquals(1001, served)
    }

    @Test
    fun `a document that cannot be opened is unreadable`() {
        assertEquals(PickedText.Unreadable, readDocument(context, Uri.fromFile(File(tmp.root, "missing.itermcolors"))))
    }

    @Test
    fun `a refusal names the limit and what the file was picked as`() {
        assertEquals("That file is over 2 MB, too big for a theme.", PickedText.TooBig.refusal("a theme"))
        assertEquals("Berth could not read that file.", PickedText.Unreadable.refusal("a theme"))
        assertNull(PickedText.Read("palette = 0=#000000", "dracula").refusal("a theme"))
    }
}
