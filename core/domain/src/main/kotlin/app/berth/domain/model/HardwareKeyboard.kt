package app.berth.domain.model

import kotlinx.serialization.Serializable

/**
 * What a hardware keyboard's Alt key does to a printable character (spec A11, C22).
 *
 * [ESC_PREFIX] sends Escape then the key, xterm's `metaSendsEscape`; every shell and editor reads
 * it as Meta, so it is the default. [META] sets the eighth bit of an ASCII character instead,
 * xterm's `eightBitInput`, for the few programs that want a real Meta byte; characters outside
 * ASCII, and every non-printing key, still take the Escape prefix and the CSI modifier parameter.
 */
@Serializable
enum class AltKeyMode { ESC_PREFIX, META }

/** Settings › Hardware keyboard. One document, so a change to any field lands atomically. */
@Serializable
data class HardwareKeyboardSettings(
    /** The app-wide Alt behaviour, over which a host may say otherwise. */
    val altKey: AltKeyMode = AltKeyMode.ESC_PREFIX,
    /** Hosts whose Alt behaviour differs from [altKey]; a host that inherits is absent. */
    val altKeyByHost: Map<String, AltKeyMode> = emptyMap(),
    /**
     * While a hardware keyboard is attached the Deck shrinks to one row of its modifiers and app
     * actions (spec C4, "Hardware keyboard attached"): the letters have a keyboard, Ctrl and Alt
     * latches and Paste still earn their place. Off leaves the Deck as it is.
     */
    val compactDeck: Boolean = true,
) {
    /** The host's own Alt behaviour, or null when it follows [altKey]. */
    fun altKeyOverride(hostId: String): AltKeyMode? = altKeyByHost[hostId]

    /** What Alt does for [hostId], after the host's override and the app-wide choice. */
    fun altKeyFor(hostId: String?): AltKeyMode = hostId?.let { altKeyByHost[it] } ?: altKey

    /** The document with [hostId]'s Alt behaviour set, or following the app-wide choice again when [mode] is null. */
    fun withHostAltKey(hostId: String, mode: AltKeyMode?): HardwareKeyboardSettings = copy(
        altKeyByHost = if (mode == null) altKeyByHost - hostId else altKeyByHost + (hostId to mode),
    )

    /** The document without [hostId]'s override, for a host that was deleted. */
    fun withoutHost(hostId: String): HardwareKeyboardSettings = copy(altKeyByHost = altKeyByHost - hostId)
}
