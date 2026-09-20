package app.berth.android.screenshots

import app.berth.android.session.AuthResolver
import app.berth.android.session.SessionEnvironment
import app.berth.android.session.TerminalSession
import app.berth.domain.model.AuthMethod
import app.berth.domain.model.Host
import app.berth.domain.model.PersistenceLayer
import app.berth.domain.model.SessionRecord
import app.berth.domain.model.SessionState
import app.berth.domain.model.SwatchColor
import app.berth.domain.model.Workspace
import app.berth.ssh.AcceptAllHostKeys
import app.berth.ssh.HostKeyPolicy
import app.berth.ssh.SshAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.concurrent.TimeUnit

/**
 * The fixture several suites open on: Home with homelab and pi-hole, Work with build box, each a
 * detached tab with a frame, and the hosts they ride. Seeded into [graph] and restored; the
 * caller picks the tab to put on stage, or takes [liveHomelab] for a tab that reads Live.
 */
object StageFixture {
    /**
     * Homelab's tab reading Live with no shell behind it: the record says Live and the screen is
     * the seeded frame, so the Stage shows the tab with its Deck (only a live tab has one) and no
     * sshd is needed; what is typed has nowhere to go. Put on stage beside the strip [seed] fills.
     */
    fun liveHomelab(now: Long = System.currentTimeMillis()): TerminalSession {
        val homelab = host(now, "homelab", "homelab", "192.168.1.20", "ben", SwatchColor.VERDIGRIS, AuthMethod.Password(AuthResolver.passwordSecretId("homelab")))
        val record = record(now, "s-homelab", homelab, Workspace.DEFAULT_ID, 0, 12, "~/srv", "docker compose ps")
            .copy(state = SessionState.LIVE, layer = PersistenceLayer.IN_APP)
        val env = object : SessionEnvironment {
            override suspend fun authFor(host: Host): List<SshAuth> = emptyList()
            override fun hostKeyPolicyFor(host: Host): HostKeyPolicy = AcceptAllHostKeys
            override val networkAvailable: Flow<Unit> = emptyFlow()
            override fun onClipboardText(host: Host, text: String) = Unit
        }
        val session = TerminalSession(record, CoroutineScope(SupervisorJob() + Dispatchers.Default), env) {}
        session.restoreFrame(frame(HOMELAB_LINES))
        return session
    }

    fun seed(graph: TestGraph, now: Long = System.currentTimeMillis()) = runBlocking {
        graph.workspaces.upsert(Workspace(Workspace.DEFAULT_ID, Workspace.DEFAULT_NAME, SwatchColor.COPPER, "H", sortOrder = 0, createdAt = now - TimeUnit.DAYS.toMillis(30)))
        graph.workspaces.upsert(Workspace("ws-work", "Work", SwatchColor.SLATE, "W", sortOrder = 1, createdAt = now - TimeUnit.DAYS.toMillis(20)))
        graph.settings.setCurrentWorkspaceId(Workspace.DEFAULT_ID)
        val homelab = host(now, "homelab", "homelab", "192.168.1.20", "ben", SwatchColor.VERDIGRIS, AuthMethod.Password(AuthResolver.passwordSecretId("homelab")))
        val pihole = host(now, "pi-hole", "pi-hole", "192.168.1.2", "pi", SwatchColor.MOSS, AuthMethod.AskEachTime)
        val build = host(now, "build-box", "build box", "build.internal", "ci", SwatchColor.SLATE, AuthMethod.AskEachTime)
        listOf(homelab, pihole, build).forEach { graph.hosts.upsert(it) }
        graph.sessionRecords.upsert(record(now, "s-homelab", homelab, Workspace.DEFAULT_ID, 0, 12, "~/srv", "docker compose ps"))
        graph.sessionRecords.upsert(record(now, "s-pihole", pihole, Workspace.DEFAULT_ID, 1, 95, "/etc/pihole", "tail -f pihole.log"))
        graph.sessionRecords.upsert(record(now, "s-build", build, "ws-work", 0, 400, "~/work/berth", "./gradlew assembleDebug"))
        graph.sessionRecords.saveFrame("s-homelab", frame(HOMELAB_LINES))
        graph.sessionRecords.saveFrame("s-pihole", frame(listOf("pi@pi-hole:/etc/pihole$ tail -f pihole.log", "Sep 18 20:41:02 dnsmasq[712]: query[A] api.berth.app from 192.168.1.30")))
        graph.sessionRecords.saveFrame("s-build", frame(listOf("ci@build:~/work/berth$ ./gradlew assembleDebug", "BUILD SUCCESSFUL in 1m 12s", "ci@build:~/work/berth$ ")))
        graph.sessions.restore()
    }

    private fun host(now: Long, id: String, name: String, address: String, user: String, color: SwatchColor, auth: AuthMethod) = Host(
        id = id, name = name, color = color, monogram = Host.monogramFor(name), address = address, port = 22, user = user, auth = auth,
        lastConnectedAt = now - TimeUnit.MINUTES.toMillis(18), createdAt = now - TimeUnit.DAYS.toMillis(30),
    )

    private fun record(now: Long, id: String, host: Host, ws: String, order: Int, lastLiveMinutesAgo: Long, cwd: String, lastCommand: String) = SessionRecord(
        id = id, workspaceId = ws, hostId = host.id, hostSnapshot = host, state = SessionState.DETACHED, layer = PersistenceLayer.LOCAL_FRAME, title = host.name,
        cwd = cwd, lastCommand = lastCommand, sortOrder = order, createdAt = now - TimeUnit.HOURS.toMillis(5), lastLiveAt = now - TimeUnit.MINUTES.toMillis(lastLiveMinutesAgo),
    )

    private fun frame(lines: List<String>): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { d ->
            d.writeInt(1)
            d.writeInt(lines.size)
            lines.forEach(d::writeUTF)
        }
        return out.toByteArray()
    }

    private val HOMELAB_LINES = listOf(
        "ben@homelab:~/srv$ docker compose ps",
        "NAME        IMAGE               STATUS        PORTS",
        "caddy       caddy:2             Up 3 days     80/tcp, 443/tcp",
        "gitea       gitea/gitea:1.22    Up 3 days     3000/tcp",
        "postgres    postgres:16         Up 3 days     5432/tcp",
        "ben@homelab:~/srv$ ",
    )
}
