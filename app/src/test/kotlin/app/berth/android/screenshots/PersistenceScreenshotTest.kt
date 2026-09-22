package app.berth.android.screenshots

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import app.berth.android.ComposeHostRule
import app.berth.android.createBerthComposeRule
import app.berth.android.session.AuthResolver
import app.berth.android.ui.keys.GenerateKeySheet
import app.berth.android.ui.keys.NewKeyPrefill
import app.berth.android.ui.prompts.formatDate
import app.berth.android.ui.components.LocalWallClock
import app.berth.android.ui.settings.ExportBundleSheet
import app.berth.android.ui.settings.IMPORT_DISCLOSURE
import app.berth.android.ui.settings.ImportBundleSheet
import app.berth.android.ui.settings.MIN_BUNDLE_PASSPHRASE
import app.berth.android.ui.settings.PickedFile
import app.berth.android.ui.settings.SettingsScreen
import app.berth.android.ui.settings.defaultTerminalThemeCaption
import app.berth.android.ui.settings.importDisclosure
import app.berth.android.ui.settings.tunnelsHeldOffLine
import app.berth.android.ui.stage.CommandHistorySheet
import app.berth.android.ui.theme.BerthTheme
import app.berth.data.bundle.BerthBundles
import app.berth.data.bundle.BundleCodec
import app.berth.data.bundle.BundleKdf
import app.berth.domain.model.AuthMethod
import app.berth.domain.model.Host
import app.berth.domain.model.Identity
import app.berth.domain.model.InterfaceTheme
import app.berth.domain.model.KeyAlgorithm
import app.berth.domain.model.KeyProtection
import app.berth.domain.model.KeyStorage
import app.berth.domain.model.KnownHostKey
import app.berth.domain.model.Snippet
import app.berth.domain.model.SwatchColor
import app.berth.domain.model.TerminalTheme
import app.berth.domain.model.Tunnel
import app.berth.domain.model.TunnelType
import app.berth.domain.model.Workspace
import app.berth.ssh.SshKeys
import app.berth.ssh.SshSecurity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * The surfaces of the persistence wave (spec C16, C20 Data), each at 1× and at the interface's
 * 1.3× font cap (A11): the History sheet widened to all hosts, Settings › Data with the bundle's
 * rows, the export sheet naming the hardware key that stays, and the import sheet before and
 * after an import that had a key to make again. The import opens a bundle a second phone's
 * storage sealed, so the whole path from another phone's tables to this one's is under the frame.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h914dp-420dpi")
class PersistenceScreenshotTest {
    @get:Rule(order = 0)
    val host = ComposeHostRule()

    @get:Rule(order = 1)
    val compose = createBerthComposeRule()

    private val outDir = File(System.getProperty("user.dir"), "build/outputs/roborazzi")
    private lateinit var graph: TestGraph
    /**
     * The records' clock and the interface's, pinned: the History sheet groups its rows by day
     * against the wall clock, and commands seeded fifty minutes before a run that crossed midnight
     * straddled today and yesterday, an extra day label pushing the seventh row past the lazy
     * list's fold (the gate on this branch, at 00:40 UTC). At 14:07 every seeded row falls where the
     * frame expects it on every run.
     */
    private val now = FIXED_NOW
    /** The zone the rows' times are formatted in, pinned with the clock so the digits are the same on every machine. */
    private val zone = TimeZone.getDefault()

    @Before
    fun setUp() {
        if (System.getProperty("roborazzi.test.record") == null && System.getProperty("roborazzi.test.verify") == null) {
            System.setProperty("roborazzi.test.record", "true")
        }
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        SshSecurity.ensureProviders()
        outDir.mkdirs()
        graph = TestGraph(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        graph.close()
        RuntimeEnvironment.setFontScale(1f)
        TimeZone.setDefault(zone)
    }

    private fun capture(name: String) = compose.captureAudited(File(outDir, "$name.png"))

    private fun themed(content: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalWallClock provides { now }) {
                BerthTheme(InterfaceTheme.DEFAULT) {
                    Box(Modifier.fillMaxSize()) { content() }
                }
            }
        }
    }

    /** The system's largest font size, set before the first composition: interface text then sits at its 1.3× cap. */
    private fun atTheCap() = RuntimeEnvironment.setFontScale(2f)

    private fun waitForText(text: String, substring: Boolean = false) =
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty() }

    // ---- C16: the History sheet across all hosts -----------------------------------------------------------

    @Test
    fun `history across all hosts`() = historyAcrossHosts("history-all-hosts", cap = false)

    @Test
    fun `history across all hosts at the font cap`() {
        atTheCap()
        historyAcrossHosts("history-all-hosts-font-cap", cap = true)
    }

    /**
     * The sheet opens on this host's commands and the chip widens it to every host's, each row
     * then naming its host: a saved host by name, an unsaved quick connect by its login. The list
     * is lazy, so at the cap only the newest rows are composed and the oldest is reached by scrolling.
     */
    private fun historyAcrossHosts(name: String, cap: Boolean) {
        StageFixture.seed(graph, now)
        runBlocking {
            val homelab = graph.hosts.get("homelab")!!
            val pihole = graph.hosts.get("pi-hole")!!
            val build = graph.hosts.get("build-box")!!
            val quick = Host(
                id = Host.QUICK_ID_PREFIX + "vps", name = "root@vps.example.org", color = SwatchColor.RUST, monogram = "RV",
                address = "vps.example.org", user = "root", auth = AuthMethod.AskEachTime, createdAt = now,
            )
            var at = now - TimeUnit.DAYS.toMillis(1) - TimeUnit.HOURS.toMillis(3)
            fun tick(): Long {
                at += TimeUnit.MINUTES.toMillis(7)
                return at
            }
            graph.commandHistory.record(build.commandHistoryKey, "./gradlew assembleDebug", tick())
            graph.commandHistory.record(homelab.commandHistoryKey, "docker compose pull", tick())
            graph.commandHistory.record(homelab.commandHistoryKey, "docker compose up -d", tick())
            graph.commandHistory.record(quick.commandHistoryKey, "apt update && apt upgrade -y", tick())
            at = now - TimeUnit.MINUTES.toMillis(50)
            graph.commandHistory.record(pihole.commandHistoryKey, "pihole -up", tick())
            graph.commandHistory.record(pihole.commandHistoryKey, "tail -f /var/log/pihole.log", tick())
            graph.commandHistory.record(homelab.commandHistoryKey, "docker compose ps", tick())
        }
        val session = graph.sessions.get("s-homelab")!!
        themed { CommandHistorySheet(graph.viewModel, session, onDismiss = {}) }
        waitForText("homelab \u00B7 3 commands")
        compose.onAllNodes(hasContentDescription("Command ", substring = true)).assertCountEquals(3)

        compose.onNodeWithText("All hosts").performClick()
        waitForText("All hosts \u00B7 7 commands")
        waitForText("pi-hole \u00B7 ", substring = true)
        waitForText("root@vps.example.org \u00B7 ", substring = true)
        if (cap) {
            assertTrue(compose.onAllNodes(hasContentDescription("Command ", substring = true)).fetchSemanticsNodes().size in 1..7)
        } else {
            compose.onAllNodes(hasContentDescription("Command ", substring = true)).assertCountEquals(7)
            waitForText("build box \u00B7 ", substring = true)
        }
        capture(name)
        compose.assertNoTextCut("the History sheet across all hosts")
        if (cap) {
            // The oldest command, the build box's, is past the fold at this size; the list scrolls to it.
            compose.onNode(hasScrollAction() and hasAnyDescendant(hasContentDescription("Command ", substring = true)))
                .performScrollToNode(hasContentDescription("Command ./gradlew assembleDebug"))
            waitForText("build box \u00B7 ", substring = true)
        }
    }

    // ---- C20 Data: the bundle's rows and sheets ---------------------------------------------------------------

    @Test
    fun `settings data with the bundle rows`() = settingsData("settings-data-bundle")

    @Test
    fun `settings data with the bundle rows at the font cap`() {
        atTheCap()
        settingsData("settings-data-bundle-font-cap")
    }

    private fun settingsData(name: String) {
        seedThisPhone()
        themed { SettingsScreen(graph.viewModel, onBack = {}, onKnownHosts = {}) }
        compose.onNodeWithText("Import private key").performScrollTo()
        compose.waitForIdle()
        waitForText("Export encrypted bundle")
        waitForText("Import bundle")
        capture(name)
        compose.assertNoTextCut("Settings \u203A Data")
    }

    @Test
    fun `export bundle sheet`() = exportSheet("bundle-export")

    @Test
    fun `export bundle sheet at the font cap`() {
        atTheCap()
        exportSheet("bundle-export-font-cap")
    }

    /**
     * The export sheet with the phone's library behind it: the contents line counts every kind,
     * the hardware key is named as staying, and Export waits on two matching passphrases of length.
     */
    private fun exportSheet(name: String) {
        seedThisPhone()
        themed {
            SettingsScreen(graph.viewModel, onBack = {}, onKnownHosts = {})
            ExportBundleSheet(graph.viewModel, onDismiss = {}, onNotice = {})
        }
        waitForText("Export encrypted bundle")
        waitForText("Pixel key is hardware-backed and stays on this phone", substring = true)
        waitForText("4 hosts \u00B7 2 keys \u00B7 2 workspaces \u00B7 1 snippet \u00B7 1 tunnel \u00B7 1 theme \u00B7 the Deck \u00B7 2 known hosts")
        compose.onNodeWithText("Export").assertIsNotEnabled()
        val fields = compose.onAllNodes(hasSetTextAction())
        fields[0].performTextInput("short")
        waitForText("At least $MIN_BUNDLE_PASSPHRASE characters; longer is better.")
        fields[0].performTextInput("er than eight")
        fields[1].performTextInput("mismatch")
        waitForText("The two don't match yet.")
        compose.onNodeWithText("Export").assertIsNotEnabled()
        capture(name)
        compose.assertNoTextCut("the export sheet")

        // Matching passphrases arm the button.
        fields[1].performTextClearance()
        fields[1].performTextInput("shorter than eight")
        compose.waitUntil(5_000) { compose.onAllNodesWithText("The two don't match yet.").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Export").assertIsEnabled()
    }

    @Test
    fun `import bundle sheet before and after an import with a key to make again`() = importSheet("bundle-import")

    @Test
    fun `import bundle sheet before and after an import with a key to make again at the font cap`() {
        atTheCap()
        importSheet("bundle-import-font-cap")
    }

    /**
     * Another phone's storage seals a bundle with a software key, a hardware key and the hosts on
     * them; this phone opens it. The wrong passphrase is told apart from a bad file; the opened
     * sheet lists what is inside in one panel and names the key that did not travel and the host
     * waiting on it, the tunnel that would listen on every interface and comes in switched off,
     * and the default terminal theme, the Deck and the interface theme as switches. Its known
     * hosts take the `known_hosts` import's shape (A16, C13): one count row for the key this
     * phone holds nothing for; the key that differs from the one this phone trusts for its
     * address as a row that starts unticked with the saved key's facts in the danger tint; the key
     * of a type this phone holds none of for that same address as a row that starts unticked too,
     * its line in the plain tone saying the tick adds it beside the saved key (nit 19); and the
     * key for an address this phone pins as a lock row, read and not offered. Ticking the conflict
     * is the Replace, and the button and the disclosure's last clause say so (nit 20); ticking the
     * other adds, and the button counts that apart. The import then writes every table as the
     * sheet said, puts the bundle's key in the saved one's place and leaves the pin alone, leaves
     * that host asking each time, and ends on the list of keys to make again, which stays until
     * Done, with Make a key beside it opening the New key sheet on the key named. Four frames: the
     * sheet as it opens, its end with the decisions unticked, the same with the conflict ticked and
     * the button naming it, and the report.
     */
    private fun importSheet(name: String) {
        seedThisPhone()
        // This phone pins its key for the lab box: the bundle's key for that address is then held back, not offered.
        val (trustedBefore, pinnedBefore) = runBlocking {
            val keys = graph.knownHosts.items.value
            val pinned = keys.first { it.id == "kh-2" }.copy(pinned = true)
            graph.knownHosts.upsert(pinned)
            keys.first { it.id == "kh-1" } to pinned
        }
        val blob = runBlocking { sealedByAnotherPhone() }
        // Wired as Settings wires it: the report's Make a key closes the import and opens the New key sheet on the key it named.
        var makeKey by mutableStateOf<NewKeyPrefill?>(null)
        themed {
            SettingsScreen(graph.viewModel, onBack = {}, onKnownHosts = {})
            if (makeKey == null) ImportBundleSheet(graph.viewModel, onDismiss = {}, onNotice = {}, initialFile = PickedFile("berth-2026-09-14.berth", blob), onMakeKey = { makeKey = it })
            makeKey?.let { GenerateKeySheet(graph.viewModel, onDismiss = {}, prefill = it) }
        }
        waitForText("berth-2026-09-14.berth \u00B7 ", substring = true)
        compose.onNode(hasSetTextAction()).performTextInput("not the passphrase")
        compose.onNodeWithText("Open").performClick()
        waitForText("That passphrase didn't open the bundle, or the file was changed since it was exported.")

        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNode(hasSetTextAction()).performTextInput(OTHER_PASSPHRASE)
        compose.onNodeWithText("Open").performClick()
        waitForText("IN THIS BUNDLE")
        waitForText("3 hosts")
        waitForText("1 key and 1 hardware key to make again")
        waitForText("3 tunnels")
        waitForText(tunnelsHeldOffLine(1))
        waitForText("it listens on every interface; it stays off until you turn it on", substring = true)
        waitForText(defaultTerminalThemeCaption("Mine", "Berth Dark"))
        waitForText("Replaces this phone's Deck with ", substring = true)
        waitForText("Replaces this phone's look with the bundle's")
        // The four known hosts, each by its standing: the new key counted; the conflict a checkbox that is off, its line in the
        // danger tint; the RSA key for the same address a checkbox that is off, its line plain, since its tick takes nothing
        // away; the pinned endpoint no checkbox at all.
        fun start(fingerprint: String) = "SHA256:" + fingerprint.removePrefix("SHA256:").take(4)
        waitForText("1 known host")
        waitForText("db.internal:2200")
        val conflict = compose.onNode(hasText("Differs from the saved ED25519 key ${start(trustedBefore.fingerprintSha256)}", substring = true) and isToggleable())
        conflict.assertIsOff()
        conflict.assert(hasText("203.0.113.10"))
        conflict.assert(hasText("ED25519 \u00B7 SHA256:", substring = true))
        conflict.assert(hasText("(trusted ${formatDate(trustedBefore.firstSeenAt)}). Ticked, it replaces that key.", substring = true))
        val beside = compose.onNode(hasText("Ticked, it is added beside the saved ED25519 key ${start(trustedBefore.fingerprintSha256)}", substring = true) and isToggleable())
        beside.assertIsOff()
        beside.assert(hasText("203.0.113.10"))
        beside.assert(hasText("RSA \u00B7 SHA256:", substring = true))
        beside.assert(hasText("(trusted ${formatDate(trustedBefore.firstSeenAt)}), which stays.", substring = true))
        compose.onNode(hasText("Pinned to ECDSA P-256 ${start(pinnedBefore.fingerprintSha256)}", substring = true)).assert(hasText("unpin it under Known hosts first.", substring = true))
        compose.onNode(hasText("192.168.1.20") and isToggleable()).assertDoesNotExist()
        compose.onAllNodes(isToggleable() and hasText("SHA256:", substring = true)).assertCountEquals(2)
        compose.onNodeWithText("Import").assertIsEnabled()
        waitForText("Phone key was hardware-backed on the other phone and is not in the file; db-primary will ask each time until you make a key here and pick it.")
        waitForText(IMPORT_DISCLOSURE)
        capture("$name-contents")
        compose.assertNoTextCut("the opened bundle")

        // The decisions stand past the fold, at the end of the panel: the sheet is scrolled to its button, the way a thumb reaches it.
        compose.onNodeWithText("Import").performScrollTo()
        compose.waitForIdle()
        capture("$name-decisions")
        // Ticking the conflict is the Replace decision, and the button and the disclosure's last clause say so before it is taken.
        conflict.performScrollTo().performClick()
        waitForText("Import, replace 1 key")
        conflict.assertIsOn()
        waitForText(importDisclosure(1))
        compose.onAllNodesWithText(IMPORT_DISCLOSURE).assertCountEquals(0)
        // Ticking the RSA key adds it, which the button counts apart from the replace and the disclosure has nothing to say about; unticked again for the frame.
        beside.performScrollTo().performClick()
        waitForText("Import, replace 1 key, add 1")
        beside.assertIsOn()
        waitForText(importDisclosure(1))
        beside.performClick()
        waitForText("Import, replace 1 key")
        beside.assertIsOff()
        compose.onNodeWithText("Import, replace 1 key").performScrollTo()
        compose.waitForIdle()
        capture("$name-replace")
        compose.assertNoTextCut("the opened bundle with a conflict ticked")
        compose.onNodeWithText("Import, replace 1 key").performClick()
        waitForText("MAKE AGAIN IN KEYS")
        // Two known hosts in the line, the replaced one said so; the pinned endpoint's key and the unticked RSA key were not taken and are not counted.
        waitForText("Imported 3 hosts, 1 key, 1 workspace, 2 snippets, 3 tunnels, 1 theme, 2 known hosts (1 replaced), the Deck, the interface theme and the default terminal theme.")
        waitForText("db-primary asks each time until you pick a key", substring = true)
        compose.onNodeWithText("Done").assertExists()
        capture("$name-recreate")
        compose.assertNoTextCut("the import's report")

        // Make a key opens the New key sheet on the key the report named: its name, hardware-backed as it was, the rest the user's.
        compose.onNodeWithText("Make a key").performClick()
        waitForText("New key")
        assertEquals(NewKeyPrefill("Phone key", KeyAlgorithm.ECDSA_P256, hardware = true), makeKey)
        compose.onNode(hasSetTextAction() and hasText("Phone key")).assertExists()
        compose.onNode(hasText("ECDSA P-256, hardware") and isSelectable()).assertIsSelected()
        compose.onNode(hasText("Ed25519") and isSelectable()).assertIsNotSelected()

        runBlocking {
            val hosts = graph.hosts.items.value.associateBy { it.id }
            assertEquals(AuthMethod.Key("id-laptop-other"), hosts.getValue("h-web").auth)
            assertEquals("the host on the hardware key asks each time", AuthMethod.AskEachTime, hosts.getValue("h-db").auth)
            assertEquals(AuthMethod.Password("host-password:h-nas"), hosts.getValue("h-nas").auth)
            assertEquals("hunter2", graph.secrets.get("host-password:h-nas")!!.toString(Charsets.UTF_8))
            assertNotNull("the software key came with its private half", graph.identities.privateKey("id-laptop-other"))
            assertTrue("the hardware key was not written", graph.identities.items.value.none { it.id == "id-phone-other" })
            assertTrue(graph.workspaces.items.value.any { it.id == "w-work-other" })
            assertEquals(setOf("s-disk", "s-tail"), graph.snippets.items.value.map { it.id }.filter { it.startsWith("s-") }.toSet())
            val tunnels = graph.tunnels.items.value.associateBy { it.id }
            assertTrue(tunnels.containsKey("t-socks"))
            assertEquals("the tunnel on every interface came in switched off", false, tunnels.getValue("t-open").enabled)
            assertEquals("the tunnel's bind is as the bundle had it", "*", tunnels.getValue("t-open").bindAddress)
            val knownHosts = graph.knownHosts.items.value
            // The tick was the Replace: the saved key for 203.0.113.10 is gone and the bundle's stands alone for the address.
            assertTrue("the saved key was replaced", knownHosts.none { it.id == trustedBefore.id })
            val web = knownHosts.single { it.endpoint == trustedBefore.endpoint }
            assertEquals("k-web", web.id)
            assertTrue("the bundle's key stands in the saved one's place", web.publicKeyBase64 != trustedBefore.publicKeyBase64)
            // The RSA key, unticked, was not added beside it; the pin stands and the bundle's key for its address is nowhere; the new endpoint's key was written.
            assertTrue("the unticked RSA key was kept out", knownHosts.none { it.id == "k-web-rsa" })
            assertEquals("this phone's pin is as it was", pinnedBefore, knownHosts.single { it.endpoint == pinnedBefore.endpoint })
            assertTrue("the pinned endpoint took no other key", knownHosts.none { it.id == "k-lab" })
            assertEquals("db.internal", knownHosts.single { it.id == "k-db" }.host)
            assertEquals(3, knownHosts.size)
            assertTrue(graph.settings.terminalThemes.first().any { it.id == "mine" && !it.builtIn })
            assertEquals("the third switch was on: new terminals open in the bundle's theme", "mine", graph.settings.defaultTerminalThemeId.first())
        }
    }

    // ---- fixtures -----------------------------------------------------------------------------------------------

    /** This phone's library: hosts on a software key, a hardware key and a password, a workspace, a snippet, a tunnel, a theme and two known hosts. */
    private fun seedThisPhone() = runBlocking {
        val ed = SshKeys.generate(KeyAlgorithm.ED25519)
        val ec = SshKeys.generate(KeyAlgorithm.ECDSA_P256)
        graph.identities.insert(
            Identity("id-laptop", "laptop ed25519", KeyAlgorithm.ED25519, KeyStorage.SOFTWARE_ENCRYPTED, KeyProtection.PASSPHRASE, SshKeys.openSshPublic(ed.public, "ben@laptop"), SshKeys.fingerprintSha256(ed.public), "ben@laptop", createdAt = now - TimeUnit.DAYS.toMillis(200)),
            SshKeys.openSshPrivate(ed, "ben@laptop", "correct horse".toCharArray()).toByteArray(),
        )
        graph.identities.insert(
            Identity("id-phone", "Pixel key", KeyAlgorithm.ECDSA_P256, KeyStorage.ANDROID_KEYSTORE, KeyProtection.BIOMETRIC, SshKeys.openSshPublic(ec.public, "berth@pixel"), SshKeys.fingerprintSha256(ec.public), "berth@pixel", keystoreAlias = "berth-id-phone", createdAt = now - TimeUnit.DAYS.toMillis(12)),
            null,
        )
        graph.hosts.upsert(host("prod-api", "prod-api", "203.0.113.10", "deploy", SwatchColor.COPPER, AuthMethod.Key("id-laptop")))
        graph.hosts.upsert(host("homelab", "homelab", "192.168.1.20", "ben", SwatchColor.VERDIGRIS, AuthMethod.Password(AuthResolver.passwordSecretId("homelab"))))
        graph.secrets.put(AuthResolver.passwordSecretId("homelab"), "homelab-password".toByteArray())
        graph.hosts.upsert(host("pi-hole", "pi-hole", "192.168.1.2", "pi", SwatchColor.MOSS, AuthMethod.Key("id-phone")))
        graph.hosts.upsert(host("vps", "vps", "vps.example.org", "root", SwatchColor.RUST, AuthMethod.AskEachTime))
        graph.workspaces.upsert(Workspace(Workspace.DEFAULT_ID, Workspace.DEFAULT_NAME, SwatchColor.COPPER, "H", sortOrder = 0, createdAt = now - TimeUnit.DAYS.toMillis(30)))
        graph.workspaces.upsert(Workspace("ws-work", "Work", SwatchColor.SLATE, "W", sortOrder = 1, createdAt = now - TimeUnit.DAYS.toMillis(20)))
        graph.settings.setCurrentWorkspaceId(Workspace.DEFAULT_ID)
        graph.snippets.upsert(Snippet("sn-logs", "caddy logs", "journalctl -u caddy -n 50 --no-pager", hostId = "homelab"))
        graph.tunnels.upsert(Tunnel("tn-web", "prod-api", TunnelType.LOCAL, "127.0.0.1", 8080, "localhost", 80))
        graph.settings.upsertTerminalTheme(TerminalTheme.BERTH_LIGHT.copy(id = "paper", name = "Paper", builtIn = false))
        graph.knownHosts.upsert(KnownHostKey("kh-1", "203.0.113.10", 22, "ssh-ed25519", SshKeys.openSshPublic(ed.public).split(" ")[1], SshKeys.fingerprintSha256(ed.public), now - TimeUnit.DAYS.toMillis(90), now - TimeUnit.HOURS.toMillis(2)))
        graph.knownHosts.upsert(KnownHostKey("kh-2", "192.168.1.20", 22, "ecdsa-sha2-nistp256", SshKeys.openSshPublic(ec.public).split(" ")[1], SshKeys.fingerprintSha256(ec.public), now - TimeUnit.DAYS.toMillis(40), now - TimeUnit.MINUTES.toMillis(18)))
    }

    /**
     * Another phone's storage, filled and sealed: a software key with its private half, a hardware
     * key without one, three hosts (one on each key, one on a password), a workspace, two snippets,
     * three tunnels (one bound to every interface), four known hosts (one for an address this
     * phone trusts under a different key of the same type, one for that address on RSA, a type
     * this phone holds none of for it, one for an address this phone pins under a different key,
     * one for an address this phone holds nothing for), a theme of its own and the Deck. A light
     * key derivation keeps the test quick; the container is the same.
     */
    private suspend fun sealedByAnotherPhone(): ByteArray {
        val other = TestStorage()
        val ed = SshKeys.generate(KeyAlgorithm.ED25519)
        val ec = SshKeys.generate(KeyAlgorithm.ECDSA_P256)
        val db = SshKeys.generate(KeyAlgorithm.ED25519)
        val rsa = SshKeys.generate(KeyAlgorithm.RSA_3072)
        other.identities.insert(
            Identity("id-laptop-other", "old laptop", KeyAlgorithm.ED25519, KeyStorage.SOFTWARE_ENCRYPTED, KeyProtection.PASSPHRASE, SshKeys.openSshPublic(ed.public, "ben@old-laptop"), SshKeys.fingerprintSha256(ed.public), "ben@old-laptop", createdAt = 1),
            SshKeys.openSshPrivate(ed, "ben@old-laptop", "correct horse".toCharArray()).toByteArray(),
        )
        other.identities.insert(
            Identity("id-phone-other", "Phone key", KeyAlgorithm.ECDSA_P256, KeyStorage.ANDROID_KEYSTORE, KeyProtection.BIOMETRIC, SshKeys.openSshPublic(ec.public, "berth@old-phone"), SshKeys.fingerprintSha256(ec.public), "berth@old-phone", keystoreAlias = "berth.identity.id-phone-other", createdAt = 2),
            null,
        )
        other.hosts.upsert(Host(id = "h-web", name = "prod-web", color = SwatchColor.VERDIGRIS, monogram = "PW", address = "203.0.113.10", user = "deploy", auth = AuthMethod.Key("id-laptop-other"), tags = listOf("prod"), createdAt = 3))
        other.hosts.upsert(Host(id = "h-db", name = "db-primary", color = SwatchColor.SLATE, monogram = "DB", address = "db.internal", port = 2200, user = "postgres", auth = AuthMethod.Key("id-phone-other"), jumpHostIds = listOf("h-web"), createdAt = 4))
        other.hosts.upsert(Host(id = "h-nas", name = "nas", color = SwatchColor.MOSS, monogram = "NA", address = "10.0.0.5", user = "admin", auth = AuthMethod.Password("host-password:h-nas"), createdAt = 5))
        other.secrets.put("host-password:h-nas", "hunter2".toByteArray())
        other.workspaces.upsert(Workspace("w-work-other", "Work", SwatchColor.PLUM, "WK", sortOrder = 1, createdAt = 6, reconnectAtLaunch = true))
        other.snippets.upsert(Snippet("s-disk", "disk", "df -h", tags = listOf("ops")))
        other.snippets.upsert(Snippet("s-tail", "tail", "tail -f {{file}}", hostId = "h-web", workspaceId = "w-work-other", pinnedToDeck = true))
        other.tunnels.upsert(Tunnel("t-web", "h-web", TunnelType.LOCAL, bindPort = 8080, destinationHost = "localhost", destinationPort = 80))
        other.tunnels.upsert(Tunnel("t-socks", "h-db", TunnelType.DYNAMIC, bindPort = 1080, enabled = false))
        // Bound to every interface on the other phone: this phone takes it switched off.
        other.tunnels.upsert(Tunnel("t-open", "h-nas", TunnelType.LOCAL, bindAddress = "*", bindPort = 9090, destinationHost = "localhost", destinationPort = 9090))
        // The other phone's keys for the same addresses differ from this phone's: prod-api's is the conflict, and the other phone met
        // prod-api on RSA as well, a type this phone holds none of for it; the lab box's meets a pin; db-primary's is new here.
        other.knownHosts.upsert(KnownHostKey("k-web", "203.0.113.10", 22, "ssh-ed25519", SshKeys.openSshPublic(ed.public).split(" ")[1], SshKeys.fingerprintSha256(ed.public), 7, 8, pinned = true))
        other.knownHosts.upsert(KnownHostKey("k-web-rsa", "203.0.113.10", 22, "ssh-rsa", SshKeys.openSshPublic(rsa.public).split(" ")[1], SshKeys.fingerprintSha256(rsa.public), 7, 8))
        other.knownHosts.upsert(KnownHostKey("k-lab", "192.168.1.20", 22, "ecdsa-sha2-nistp256", SshKeys.openSshPublic(ec.public).split(" ")[1], SshKeys.fingerprintSha256(ec.public), 7, 8))
        other.knownHosts.upsert(KnownHostKey("k-db", "db.internal", 2200, "ssh-ed25519", SshKeys.openSshPublic(db.public).split(" ")[1], SshKeys.fingerprintSha256(db.public), 7, 8))
        other.settings.upsertTerminalTheme(TerminalTheme.BERTH_LIGHT.copy(id = "mine", name = "Mine", builtIn = false))
        other.settings.setDefaultTerminalTheme("mine")
        val bundles = BerthBundles(other.hosts, other.identities, other.workspaces, other.snippets, other.tunnels, other.knownHosts, other.settings, other.secrets, BundleCodec())
        return bundles.export(OTHER_PASSPHRASE.toCharArray(), exportedAt = now - TimeUnit.DAYS.toMillis(6), appVersion = "1.0", cost = BundleKdf(memoryKiB = 1024, iterations = 1, parallelism = 1))
    }

    private fun host(id: String, name: String, address: String, user: String, color: SwatchColor, auth: AuthMethod) = Host(
        id = id, name = name, color = color, monogram = Host.monogramFor(name), address = address, port = 22, user = user, auth = auth,
        lastConnectedAt = now - TimeUnit.MINUTES.toMillis(18), createdAt = now - TimeUnit.DAYS.toMillis(30),
    )

    private companion object {
        const val OTHER_PASSPHRASE = "moving day 2026"
    }
}
