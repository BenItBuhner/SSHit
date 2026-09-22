package app.berth.domain

import app.berth.domain.model.RemoteClipboardPolicy
import app.berth.domain.model.SecuritySettings
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The per-host choice to let a forwarded agent sign without asking, kept in the security settings document. */
class SecuritySettingsTest {
    /** The data layer's configuration (Mappers.dataJson): defaults written, unknown keys read past. */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `every host asks until it is set to sign silently, and asks again once cleared`() {
        val start = SecuritySettings()
        assertFalse(start.signsAgentSilently("bastion"))
        val silent = start.withHostAgentSilent("bastion", true)
        assertTrue(silent.signsAgentSilently("bastion"))
        assertFalse(silent.signsAgentSilently("prod-web"), "another host still asks")
        assertEquals(silent, silent.withHostAgentSilent("bastion", true))
        assertFalse(silent.withHostAgentSilent("bastion", false).signsAgentSilently("bastion"))
        assertEquals(start, start.withHostAgentSilent("prod-web", false))
    }

    @Test
    fun `a deleted host leaves no silent signing behind`() {
        val settings = SecuritySettings()
            .withHostAgentSilent("bastion", true)
            .withHostAgentSilent("prod-web", true)
            .withHostRemoteClipboard("bastion", RemoteClipboardPolicy.ALLOW)
        val after = settings.withoutHost("bastion")
        assertFalse(after.signsAgentSilently("bastion"))
        assertTrue(after.signsAgentSilently("prod-web"))
        assertEquals(RemoteClipboardPolicy.INHERIT, after.remoteClipboardPolicy("bastion"))
    }

    @Test
    fun `a document written before the field reads back with every host asking`() {
        val old = """{"appLock":true,"remoteClipboardByHost":{"bastion":"ALLOW"}}"""
        val read = json.decodeFromString(SecuritySettings.serializer(), old)
        assertTrue(read.appLock)
        assertTrue(read.agentSignsSilently.isEmpty())
        val written = SecuritySettings().withHostAgentSilent("bastion", true)
        assertEquals(written, json.decodeFromString(SecuritySettings.serializer(), json.encodeToString(SecuritySettings.serializer(), written)))
    }
}
