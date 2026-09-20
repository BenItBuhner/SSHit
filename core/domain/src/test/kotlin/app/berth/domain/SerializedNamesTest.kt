package app.berth.domain

import app.berth.domain.model.AuthMethod
import app.berth.domain.model.Host
import app.berth.domain.model.SessionRecord
import app.berth.domain.model.SessionState
import app.berth.domain.model.SwatchColor
import app.berth.domain.model.TabKind
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The names the stored JSON carries for the sealed kinds, and that every kind reads back under its
 * own. A host snapshot writes its [AuthMethod] and a [SessionRecord] its [TabKind] as an object with
 * the subclass named in a `type` field; the name is the `@SerialName` where there is one (`"ssh"`,
 * `"files"`, `"tunnels"`) and the class's own name where there is not (the auth methods), fixed by the compiler
 * plugin as a string in the serializer, so a build must read what an earlier one wrote whatever R8
 * renames the classes to. The kinds are listed from the sealed serializers' own descriptors, not
 * written out here, so a kind added later is covered as soon as it exists. `./gradlew testR8` runs
 * this class over R8's output with the app's release rules, which is the proof for the phone.
 */
class SerializedNamesTest {
    /** The data layer's configuration (Mappers.dataJson): defaults written, unknown keys read past. */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** The serial names of a sealed interface's subclasses, as its serializer lists them under `value`. */
    private fun <T> KSerializer<T>.sealedNames(): List<String> = descriptor.getElementDescriptor(1).elementNames.toList()

    private val host = Host(
        id = "prod-db",
        name = "prod db",
        color = SwatchColor.entries.first(),
        monogram = "PD",
        address = "10.0.0.15",
        user = "ops",
        jumpHostIds = listOf("bastion", "edge"),
        createdAt = 1_700_000_000_000,
    )

    @Test
    fun `every tab kind reads back from its serial name, which is the id the database stores`() {
        val names = TabKind.serializer().sealedNames()
        // `tunnels` is the name @SerialName gives TabKind.Tunnels; R8 stripping it would leave the class's name here.
        assertTrue(names.containsAll(listOf("ssh", "files", "tunnels")), "the tab kinds are $names")
        for (name in names) {
            val kind = json.decodeFromString(TabKind.serializer(), """{"type":"$name"}""")
            assertEquals(name, kind.id, "the kind named $name in JSON must be the kind stored as $name")
            assertEquals(kind, TabKind.fromId(name))
            assertEquals("""{"type":"$name"}""", json.encodeToString(TabKind.serializer(), kind))
        }
    }

    @Test
    fun `a session record of every kind round-trips with the kind and the host snapshot named`() {
        for (name in TabKind.serializer().sealedNames()) {
            val record = SessionRecord(
                id = "s-$name",
                workspaceId = "w",
                hostId = host.id,
                hostSnapshot = host,
                state = SessionState.DETACHED,
                cwd = "/srv",
                createdAt = 2,
                kind = TabKind.fromId(name),
                customTitle = "rack 2",
            )
            val text = json.encodeToString(SessionRecord.serializer(), record)
            assertTrue(""""kind":{"type":"$name"}""" in text, text)
            assertTrue(""""jumpHostIds":["bastion","edge"]""" in text, text)
            assertEquals(record, json.decodeFromString(SessionRecord.serializer(), text))
        }
    }

    @Test
    fun `a host snapshot names its auth method by the class's own name and carries its jump chain`() {
        // No @SerialName on the auth methods, so the stored name is the class's; renaming one would
        // orphan every saved host that uses it.
        val names = AuthMethod.serializer().sealedNames()
        assertEquals(
            setOf("app.berth.domain.model.AuthMethod.Key", "app.berth.domain.model.AuthMethod.Password", "app.berth.domain.model.AuthMethod.AskEachTime"),
            names.toSet(),
        )
        // Written out rather than read off the classes: under R8 a class's runtime name is its new one.
        val methods = listOf(
            AuthMethod.Key("id-1") to "app.berth.domain.model.AuthMethod.Key",
            AuthMethod.Password("secret-1") to "app.berth.domain.model.AuthMethod.Password",
            AuthMethod.Password() to "app.berth.domain.model.AuthMethod.Password",
            AuthMethod.AskEachTime to "app.berth.domain.model.AuthMethod.AskEachTime",
        )
        for ((auth, typeName) in methods) {
            val text = json.encodeToString(Host.serializer(), host.copy(auth = auth))
            assertTrue(""""auth":{"type":"$typeName"""" in text, text)
            assertEquals(host.copy(auth = auth), json.decodeFromString(Host.serializer(), text))
        }
        // The schema-4 fields ride the snapshot: a tunnels-only host with a chain reads back as one.
        val tunnelsOnly = host.copy(tunnelsOnly = true)
        val stored = json.encodeToString(Host.serializer(), tunnelsOnly)
        assertTrue(""""tunnelsOnly":true""" in stored, stored)
        assertEquals(tunnelsOnly, json.decodeFromString(Host.serializer(), stored))
        // An unknown key in a stored snapshot, as a newer build's field would be, is read past.
        val withExtra = stored.replaceFirst("{", """{"fieldFromALaterBuild":true,""")
        assertEquals(tunnelsOnly, json.decodeFromString(Host.serializer(), withExtra))
    }
}
