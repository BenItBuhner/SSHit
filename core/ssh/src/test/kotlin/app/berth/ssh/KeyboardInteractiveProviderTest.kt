package app.berth.ssh

import net.schmizz.sshj.userauth.UserAuthException
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The provider as sshj drives it: [KeyboardInteractiveProvider.init] once per request with its name
 * and instruction, then [KeyboardInteractiveProvider.getResponse] once per prompt, and nothing more
 * for a request with none.
 */
class KeyboardInteractiveProviderTest {
    private data class Asked(val name: String, val instruction: String, val prompt: String, val echo: Boolean)

    private val asked = ArrayList<Asked>()
    private val provider = KeyboardInteractiveProvider { name, instruction, prompt, echo ->
        asked += Asked(name, instruction, prompt, echo)
        "answer".toCharArray()
    }

    @Test
    fun `a request with no prompts keeps its instruction for the prompt of the next`() {
        provider.init(null, "", "Berth test server: keyboard-interactive login through PAM")
        provider.init(null, "", "")
        assertEquals("answer", String(provider.getResponse("Password: ", false)))
        assertEquals(listOf(Asked("", "Berth test server: keyboard-interactive login through PAM", "Password: ", false)), asked)
    }

    @Test
    fun `the name comes with the instruction, and requests with no prompts add theirs in the order sent`() {
        provider.init(null, "Two-step sign-in", "Your password first.\n")
        provider.init(null, "", "Then the code from your authenticator app.")
        provider.init(null, "", "")
        provider.getResponse("Verification code: ", true)
        assertEquals(
            listOf(Asked("Two-step sign-in", "Your password first.\nThen the code from your authenticator app.", "Verification code: ", true)),
            asked,
        )
    }

    @Test
    fun `every prompt of one request has that request's text`() {
        provider.init(null, "", "Account and code")
        provider.getResponse("Password: ", false)
        provider.getResponse("Code: ", true)
        assertEquals(listOf("Account and code", "Account and code"), asked.map { it.instruction })
    }

    @Test
    fun `once a prompt has been asked the next request starts afresh`() {
        provider.init(null, "First", "Held for the password")
        provider.getResponse("Password: ", false)
        provider.init(null, "", "")
        provider.getResponse("Code: ", true)
        assertEquals(Asked("", "", "Code: ", true), asked.last())
    }

    @Test
    fun `a cancelled prompt ends the method`() {
        val cancelling = KeyboardInteractiveProvider { _, _, _, _ -> null }
        cancelling.init(null, "", "")
        assertFailsWith<UserAuthException> { cancelling.getResponse("Password: ", false) }
    }
}
