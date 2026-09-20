package app.berth.android.ui.prompts

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.berth.android.session.NotificationPrompt
import app.berth.android.session.SessionNotifier
import app.berth.android.ui.components.BerthButton
import app.berth.android.ui.components.ButtonKind
import app.berth.android.ui.components.SheetTitle
import app.berth.android.ui.tabs.NOTICE_BAR_MS
import app.berth.android.ui.tabs.NoticeBar
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthType
import kotlinx.coroutines.delay

/**
 * The notification permission's two moments (spec C1 note, C21): the rationale sheet after the
 * first successful connect, which hands over to the system dialog, and the six-second notice when
 * that dialog was refused. Whatever the notifier says is due is shown; its `on…` calls clear it.
 */
@Composable
fun NotificationPermissionHost(notifier: SessionNotifier) {
    val prompt by notifier.prompt.collectAsState()
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notifier.onPermissionResult(granted)
    }
    if (prompt == NotificationPrompt.Rationale) {
        NotificationRationaleSheet(
            onContinue = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) },
            onNotNow = notifier::onRationaleDeclined,
            onDismiss = notifier::onRationaleDismissed,
        )
    }
    NotificationDeniedNotice(
        visible = prompt == NotificationPrompt.Denied,
        onSettings = {
            notifier.onDeniedNoticeDismissed()
            context.startActivity(notifier.systemSettingsIntent())
        },
        onTimeout = notifier::onDeniedNoticeDismissed,
    )
}

/**
 * Why Berth wants to notify, before the system asks. Only "Not now" is an answer; a swipe or a tap
 * on the scrim just puts the sheet away ([onDismiss]), and the next process asks again.
 */
@Composable
internal fun NotificationRationaleSheet(onContinue: () -> Unit, onNotNow: () -> Unit, onDismiss: () -> Unit) {
    val c = Berth.colors
    PromptSheet(onDismiss = onDismiss) {
        SheetTitle("Allow notifications", "To hear from your sessions while you're in another app.")
        Text(
            "Berth shows one quiet notification while sessions are connected, with Detach all on it. " +
                "A tab that needs you, or a connection that gives up, gets its own.",
            style = BerthType.body,
            color = c.text2,
        )
        Text("You can change this any time in Settings \u203A Notifications.", style = BerthType.caption, color = c.text3)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BerthButton("Continue", onClick = onContinue, kind = ButtonKind.PRIMARY)
            BerthButton("Not now", onClick = onNotNow, kind = ButtonKind.TEXT)
        }
    }
}

/**
 * `Notifications are off · Settings` for six seconds after a refusal, once: the same bar as Reopen,
 * at the bottom of whatever screen is up. The user just said no, so nothing argues; the way back is
 * the action here and the Settings row after.
 */
@Composable
internal fun NotificationDeniedNotice(visible: Boolean, onSettings: () -> Unit, onTimeout: () -> Unit) {
    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        delay(NOTICE_BAR_MS)
        onTimeout()
    }
    Box(Modifier.fillMaxSize()) {
        NoticeBar(
            visible = visible,
            text = "Notifications are off",
            action = "Settings",
            onAction = onSettings,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}
