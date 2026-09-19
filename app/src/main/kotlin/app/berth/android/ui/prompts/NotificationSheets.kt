package app.berth.android.ui.prompts

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.berth.android.session.NotificationPrompt
import app.berth.android.session.SessionNotifier
import app.berth.android.ui.components.BerthButton
import app.berth.android.ui.components.ButtonKind
import app.berth.android.ui.components.SheetTitle
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthType

/**
 * The notification permission's two moments (spec C1 note, C21): the rationale sheet after the
 * first successful connect, which hands over to the system dialog, and the one-time notice when
 * that dialog was refused. Whatever the notifier says is due is shown; its `on…` calls clear it.
 */
@Composable
fun NotificationPermissionHost(notifier: SessionNotifier) {
    val prompt by notifier.prompt.collectAsState()
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notifier.onPermissionResult(granted)
    }
    when (prompt) {
        null -> Unit
        NotificationPrompt.Rationale -> NotificationRationaleSheet(
            onContinue = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) },
            onNotNow = notifier::onRationaleDeclined,
        )
        NotificationPrompt.Denied -> NotificationDeniedSheet(
            onOpenSettings = {
                notifier.onDeniedNoticeDismissed()
                context.startActivity(notifier.systemSettingsIntent())
            },
            onDismiss = notifier::onDeniedNoticeDismissed,
        )
    }
}

/** Why Berth wants to notify, in the spec's words, before the system asks. Dismissing is "Not now". */
@Composable
internal fun NotificationRationaleSheet(onContinue: () -> Unit, onNotNow: () -> Unit) {
    val c = Berth.colors
    PromptSheet(onDismiss = onNotNow) {
        SheetTitle("Allow notifications", "To keep sessions alive when you switch apps.")
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

/** Shown once after a refusal: what is lost, and the way back through the system page. */
@Composable
internal fun NotificationDeniedSheet(onOpenSettings: () -> Unit, onDismiss: () -> Unit) {
    val c = Berth.colors
    PromptSheet(onDismiss = onDismiss) {
        SheetTitle("Notifications are off", "Sessions keep running in the background either way.")
        Text(
            "Without them there is no Detach all outside the app, no word when a tab needs you and no warning " +
                "when a connection gives up. Turn them on in the system settings whenever you like.",
            style = BerthType.body,
            color = c.text2,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BerthButton("Open settings", onClick = onOpenSettings, kind = ButtonKind.PRIMARY)
            BerthButton("Dismiss", onClick = onDismiss, kind = ButtonKind.TEXT)
        }
    }
}
