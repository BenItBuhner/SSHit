package app.berth.domain.model

/**
 * One command a host ran, and when (spec C16). [hostId] is the [Host.commandHistoryKey] of the host
 * it ran on; [id] is the row's own, so an entry can be deleted from the sheet while an equal
 * command run on another day stays.
 */
data class HostCommand(val id: Long, val hostId: String, val text: String, val at: Long)
