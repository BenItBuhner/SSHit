package app.berth.android.ui.security

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.berth.android.security.LockState
import app.berth.android.security.SecurityCenter
import app.berth.android.ui.components.BerthButton
import app.berth.android.ui.components.ButtonKind
import app.berth.android.ui.components.EmptyState
import app.berth.android.ui.components.ScreenHeader
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthTheme
import app.berth.domain.model.InterfaceTheme
import kotlinx.coroutines.launch

/**
 * What the lock window shows: the [LockScreen] while the app is locked and nothing at all
 * otherwise, the moment before that window finishes. Tests stack this over
 * [app.berth.android.ui.AppRoot] the way the two windows stack on a device, so a capture of the
 * lock is a capture of what covers the shell.
 */
@Composable
fun LockWindow(security: SecurityCenter, theme: InterfaceTheme) {
    val state by security.lock.state.collectAsState()
    if (state != LockState.LOCKED) return
    BerthTheme(theme) { LockScreen(security) }
}

/**
 * The main window's own cover while the app is locked: an opaque surface the shell stays composed
 * beneath, so no frame of it is drawn between the moment the lock falls and the moment the lock
 * window lies over it, and nothing under it takes a touch or a back press meanwhile. It carries
 * the lock screen's header and nothing else; the screen itself, with the prompt and the buttons,
 * belongs to the lock window, since sheets, menus and dialogs are windows of their own that would
 * float over anything drawn here.
 */
@Composable
fun LockCover(modifier: Modifier = Modifier) {
    val activity = LocalActivity.current
    BackHandler { activity?.moveTaskToBack(true) }
    Column(
        modifier
            .fillMaxSize()
            .background(Berth.colors.surface0)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            }
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        ScreenHeader("Berth")
    }
}

/**
 * What the app shows while locked (spec C20, App lock): the content of the lock window, which lies
 * over the main one so no frame of the Stage, no sheet and no prompt shows through. The system
 * prompt runs each time the window comes to the front: when it first appears, and again on a
 * return after the system took the prompt down for a Home press; when the user backs out of it,
 * the button runs it again. A device whose screen lock was removed after the app lock went on
 * cannot pass the prompt at all, so beside Unlock a second button opens the system's security
 * settings, the one place the way out is; turning the app lock off for such a device would be a
 * downgrade.
 */
@Composable
fun LockScreen(security: SecurityCenter, modifier: Modifier = Modifier) {
    val c = Berth.colors
    val failure by security.lock.failure.collectAsState()
    val authenticating by security.lock.authenticating.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val deviceSecure = security.deviceSecure

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        // Registering delivers ON_RESUME at once when the window is already up, so the first
        // composition asks too. A prompt still running (the credential screen on Android 10 pauses
        // and resumes this window behind it) is left alone: unlock() declines a second one.
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) scope.launch { security.lock.unlock() }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    Column(
        modifier
            .fillMaxSize()
            .background(c.surface0)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        ScreenHeader("Berth")
        Spacer(Modifier.weight(1f))
        EmptyState(
            title = "Locked",
            body = failure ?: "Your fingerprint, face or screen lock opens Berth. Sessions keep running behind the lock.",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BerthButton(
                    if (authenticating) "Unlocking\u2026" else "Unlock",
                    onClick = { scope.launch { security.lock.unlock() } },
                    kind = ButtonKind.PRIMARY,
                    enabled = !authenticating,
                )
                if (!deviceSecure) {
                    BerthButton(
                        "Open device settings",
                        onClick = { context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                        kind = ButtonKind.SECONDARY,
                    )
                }
            }
        }
        Spacer(Modifier.weight(2f))
    }
}
