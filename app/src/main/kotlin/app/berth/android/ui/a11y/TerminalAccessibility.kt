package app.berth.android.ui.a11y

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ObserverModifierNode
import androidx.compose.ui.node.SemanticsModifierNode
import androidx.compose.ui.node.invalidateSemantics
import androidx.compose.ui.node.observeReads
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import app.berth.android.session.TerminalSession
import app.berth.android.ui.terminal.TerminalFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/**
 * The tag the terminal canvas carries. Tests and the audit find the terminal by it, since what the
 * canvas says to a screen reader is the screen's own text, not a fixed name.
 */
const val TerminalTag: String = "terminal"

/** How long the output settles before it is announced, so a burst is one announcement of what it left. */
const val OUTPUT_SETTLE_MS: Long = 300L

/**
 * The terminal for a screen reader (spec A11). The canvas node's text is the screen's rows, so
 * TalkBack reads the screen on focus and walks it a row at a time (a row is a paragraph to it);
 * `Read the cursor line` in its actions says the line the cursor is on; and what new frames bring
 * is said through a polite live region once the output settles, a bell as `Bell`. The canvas feeds
 * a copy of each frame it draws through [onFrame]; the text is only built when the accessibility
 * tree asks for it, so a terminal nobody is listening to pays for the copy alone.
 */
class TerminalAccessibility(val announcer: Announcer = Announcer()) {
    private val frame = TerminalFrame()
    private var hasFrame = false
    private var announced: TerminalSpeech.Snapshot? = null

    /** Bumps with every frame the canvas draws; the canvas' semantics node observes it and re-reads the screen. */
    var version: Int by mutableIntStateOf(0)
        private set

    /** The canvas calls this on the main thread with the frame it is about to draw. */
    fun onFrame(front: TerminalFrame) {
        frame.copyFrom(front)
        hasFrame = true
        version++
    }

    /** The screen as one text, a row to a line; empty when nothing has been drawn or the screen is blank. */
    fun screenText(): String = if (hasFrame) TerminalSpeech.screenText(frame) else ""

    /**
     * Says what the frames since the last announcement brought, if anything. Run once the output has
     * settled. A view scrolled into history says nothing and keeps the live screen's changes for the
     * return, since the rows in view are history's, not the screen's.
     */
    fun announceNewOutput() {
        if (!hasFrame || frame.offset != 0) return
        val now = TerminalSpeech.snapshot(frame)
        val text = TerminalSpeech.newOutput(announced, now)
        announced = now
        if (text != null) announcer.announce(text)
    }

    /** Says the line the cursor is on, blank or not; the `Read the cursor line` action. */
    fun readCursorLine() {
        announcer.announce(
            when {
                !hasFrame -> "Nothing on screen"
                frame.offset != 0 -> "Scrolled into history, the cursor is below"
                else -> TerminalSpeech.cursorLine(frame).ifBlank { "Blank line" }
            },
        )
    }
}

/**
 * Text for a screen reader out of a [TerminalFrame], and the choice of what in a new frame is worth
 * saying. Pure functions of the frame, so the rules are tested on their own.
 */
object TerminalSpeech {
    /** An announcement is at most this many rows, the last of what changed. */
    const val MAX_LINES: Int = 8

    /** And at most this many characters, so a wall of output does not hold the reader for a minute. */
    const val MAX_CHARS: Int = 600

    /** Each row's text, trailing blanks trimmed; the unit the change between two frames is measured in. */
    fun rows(frame: TerminalFrame): List<String> = List(frame.rows) { frame.line(it).toText() }

    /**
     * The screen as text: a row soft-wrapped into the next joins it with no break, rows below the
     * last with anything on them are dropped, blank rows above it fold to one blank line, and blank
     * rows at the top go.
     */
    fun screenText(frame: TerminalFrame): String {
        val logical = ArrayList<String>()
        val joined = StringBuilder()
        for (y in 0 until frame.rows) {
            val line = frame.line(y)
            joined.append(line.toText())
            if (line.wrapped && y < frame.rows - 1) continue
            logical += joined.toString()
            joined.setLength(0)
        }
        while (logical.isNotEmpty() && logical.last().isEmpty()) logical.removeAt(logical.lastIndex)
        val out = StringBuilder()
        var afterBlank = true
        for (text in logical) {
            if (text.isEmpty()) {
                if (afterBlank) continue
                afterBlank = true
            } else {
                afterBlank = false
            }
            if (out.isNotEmpty()) out.append('\n')
            out.append(text)
        }
        return out.toString()
    }

    /** The logical line under the cursor: its row with the rows soft-wrapped into it above and out of it below. */
    fun cursorLine(frame: TerminalFrame): String {
        if (frame.rows == 0) return ""
        val cursor = frame.cursorY.coerceIn(0, frame.rows - 1)
        var start = cursor
        while (start > 0 && frame.line(start - 1).wrapped) start--
        var end = cursor
        while (end < frame.rows - 1 && frame.line(end).wrapped) end++
        return (start..end).joinToString("") { frame.line(it).toText() }
    }

    /** What of a frame the next frame is compared against. */
    class Snapshot(
        val rows: List<String>,
        val cursorRow: Int,
        val cursorVisible: Boolean,
        val alternateScreen: Boolean,
        /** Lines in history; the growth between two frames is how far the screen scrolled. */
        val scrollback: Int,
    )

    fun snapshot(frame: TerminalFrame): Snapshot =
        Snapshot(rows(frame), frame.cursorY, frame.cursorVisible, frame.alternateScreen, frame.scrollbackSize)

    /**
     * What [current] brought since [previous], as one announcement, or null for nothing worth saying.
     *
     * On the normal screen the rows are lined up by how far the screen scrolled, and every row with
     * text that was not there before is new output, the last [MAX_LINES] of them at most; the
     * cursor's own row growing from what it was is left out, since that is the keyboard's echo (which
     * the reader speaks as it is typed) or a line printed piecemeal, not a line of output. A first
     * frame says nothing: the reader reads the screen on focus. A program taking the alternate screen
     * or giving it back redraws everything, which is not output either. While a program holds the
     * alternate screen it redraws in place, so only the cursor's line is said, when it moved or changed.
     */
    fun newOutput(previous: Snapshot?, current: Snapshot): String? {
        if (previous == null || previous.alternateScreen != current.alternateScreen) return null
        if (current.alternateScreen) {
            if (!current.cursorVisible) return null
            val line = current.rows.getOrNull(current.cursorRow)?.takeIf { it.isNotBlank() } ?: return null
            val before = previous.rows.getOrNull(previous.cursorRow)
            return if (current.cursorRow != previous.cursorRow || line != before) line else null
        }
        val shift = (current.scrollback - previous.scrollback).coerceAtLeast(0)
        val changed = ArrayList<String>()
        for ((row, text) in current.rows.withIndex()) {
            if (text.isBlank()) continue
            val before = previous.rows.getOrNull(row + shift)
            if (before == text) continue
            val echo = row == current.cursorRow && current.cursorVisible && !before.isNullOrBlank() && text.startsWith(before)
            if (echo) continue
            changed += text
        }
        return trim(changed)
    }

    /** The last of [lines] that fit the announcement's bounds, the last line always, cut if it alone is too long. */
    private fun trim(lines: List<String>): String? {
        if (lines.isEmpty()) return null
        val kept = ArrayList<String>()
        var chars = 0
        for (line in lines.asReversed()) {
            if (kept.size == MAX_LINES || (kept.isNotEmpty() && chars + line.length + 1 > MAX_CHARS)) break
            kept += line
            chars += line.length + 1
        }
        kept.reverse()
        val text = kept.joinToString("\n")
        return if (text.length > MAX_CHARS) text.takeLast(MAX_CHARS) else text
    }
}

/**
 * The canvas' semantics: its text is the screen, re-read whenever [TerminalAccessibility.version]
 * moves, without the canvas recomposing (output never recomposes the canvas, and this keeps it so:
 * the node observes the version and invalidates its own semantics). A blank screen is named
 * `Terminal, blank` instead, so the node is never nameless.
 */
fun Modifier.terminalAccessibility(accessibility: TerminalAccessibility): Modifier =
    this then TerminalAccessibilityElement(accessibility)

private data class TerminalAccessibilityElement(val accessibility: TerminalAccessibility) : ModifierNodeElement<TerminalAccessibilityNode>() {
    override fun create(): TerminalAccessibilityNode = TerminalAccessibilityNode(accessibility)

    override fun update(node: TerminalAccessibilityNode) {
        node.accessibility = accessibility
        node.invalidateSemantics()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "terminalAccessibility"
    }
}

private class TerminalAccessibilityNode(var accessibility: TerminalAccessibility) : Modifier.Node(), SemanticsModifierNode, ObserverModifierNode {
    override fun onAttach() {
        observe()
    }

    private fun observe() {
        observeReads { accessibility.version }
    }

    override fun onObservedReadsChanged() {
        observe()
        invalidateSemantics()
    }

    override fun SemanticsPropertyReceiver.applySemantics() {
        testTag = TerminalTag
        val screen = accessibility.screenText()
        if (screen.isEmpty()) contentDescription = "Terminal, blank" else text = AnnotatedString(screen)
        customActions = listOf(CustomAccessibilityAction("Read the cursor line") { accessibility.readCursorLine(); true })
    }
}

/**
 * The Stage's live region for the terminal on it: new output once it settles ([OUTPUT_SETTLE_MS]),
 * a bell as `Bell`, and the cursor line on request. Composed beside the canvas, over its padding,
 * where the one-dp region covers nothing.
 */
@Composable
fun TerminalAnnouncer(accessibility: TerminalAccessibility, session: TerminalSession, modifier: Modifier = Modifier) {
    LaunchedEffect(accessibility) {
        snapshotFlow { accessibility.version }.collectLatest {
            delay(OUTPUT_SETTLE_MS)
            accessibility.announceNewOutput()
        }
    }
    LaunchedEffect(accessibility, session.id) {
        session.bell.collect { accessibility.announcer.announce("Bell") }
    }
    LiveRegion(accessibility.announcer, modifier)
}
