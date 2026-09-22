package app.berth.android.ui.components

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The wall clock the interface reads wherever it says how long ago something was or which day it
 * is: the ages on the Hosts rows and the pills (`ageTicker`), the files browser's modified column,
 * the history sheet's day labels, a report's age under Diagnostics. The app leaves it at the
 * system's; a screenshot test provides a fixed one so a frame reads the same on every run. The
 * clocks that stamp records (`TerminalSession`'s environment, `CrashReporter`'s `now`) are their
 * own, and a fixture pins them separately.
 */
val LocalWallClock = staticCompositionLocalOf<() -> Long> { System::currentTimeMillis }
