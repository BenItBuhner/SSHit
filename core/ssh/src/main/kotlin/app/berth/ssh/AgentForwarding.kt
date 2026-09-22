package app.berth.ssh

import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.SSHPacket
import net.schmizz.sshj.connection.Connection
import net.schmizz.sshj.connection.ConnectionException
import net.schmizz.sshj.connection.channel.OpenFailException
import net.schmizz.sshj.connection.channel.forwarded.AbstractForwardedChannel
import net.schmizz.sshj.connection.channel.forwarded.AbstractForwardedChannelOpener
import net.schmizz.sshj.connection.channel.forwarded.ConnectListener
import net.schmizz.sshj.connection.channel.direct.SessionChannel
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

/**
 * A session channel that can ask for agent forwarding before its shell starts, the request
 * OpenSSH's `ForwardAgent` sends. sshj has the request's plumbing but no call for it.
 */
internal class AgentSessionChannel(conn: Connection, charset: Charset) : SessionChannel(conn, charset) {
    /** True when the server agreed; a server with `AllowAgentForwarding no` (or none of it) answers false. */
    fun requestAgentForwarding(timeoutMillis: Long): Boolean = try {
        sendChannelRequest(SshAgent.FORWARDING_REQUEST, true, Buffer.PlainBuffer()).await(timeoutMillis, TimeUnit.MILLISECONDS)
        true
    } catch (_: ConnectionException) {
        false
    }
}

/**
 * Takes the `auth-agent@openssh.com` channels the server opens, one for each program on the
 * remote that connects to the forwarded socket, and gives each to the connection's current
 * agent ([agent]) on a thread of its own. With no agent, a closed one, or a full one, the open
 * is refused. Attached only once a shell has asked for forwarding, so a server that opens an
 * agent channel unasked finds no one to open it.
 */
internal class AgentChannelOpener(conn: Connection, private val agent: () -> SshAgent?) :
    AbstractForwardedChannelOpener(SshAgent.CHANNEL_TYPE, conn) {

    private val listener = ConnectListener { chan ->
        val a = agent()
        if (a == null || !a.tryOpenChannel()) {
            chan.reject(OpenFailException.Reason.ADMINISTRATIVELY_PROHIBITED, "no agent")
            return@ConnectListener
        }
        try {
            chan.confirm()
            a.serve(chan.inputStream, chan.outputStream)
        } finally {
            a.channelClosed()
            runCatching { chan.close() }
        }
    }

    override fun handleOpen(buf: SSHPacket) {
        val chan = try {
            AgentChannel(conn, buf.readUInt32AsInt(), buf.readUInt32(), buf.readUInt32())
        } catch (e: Buffer.BufferException) {
            throw ConnectionException(e)
        }
        callListener(listener, chan)
    }

    /** An agent channel carries no originator, unlike a forwarded TCP connection. */
    private class AgentChannel(conn: Connection, recipient: Int, remoteWindow: Long, remoteMaxPacket: Long) :
        AbstractForwardedChannel(conn, SshAgent.CHANNEL_TYPE, recipient, remoteWindow, remoteMaxPacket, "", 0)
}
