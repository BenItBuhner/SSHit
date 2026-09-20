package app.berth.android.ui.security

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.berth.android.security.SecurityCenter
import app.berth.android.ui.components.BerthButton
import app.berth.android.ui.components.ButtonKind
import app.berth.android.ui.components.EmptyState
import app.berth.android.ui.components.ScreenHeader
import app.berth.android.ui.theme.Berth
import kotlinx.coroutines.launch

/**
 * What the app shows while locked (spec C20, App lock): the content of [LockActivity]'s window,
 * which lies over the main one so no frame of the Stage, no sheet and no prompt shows through. The
 * system prompt runs as soon as the screen appears; when the user backs out of it, the button runs
 * it again. A device whose screen lock was removed after the app lock went on cannot pass the
 * prompt at all, so beside Unlock a second button opens the system's security settings, the one
 * place the way out is; turning the app lock off for such a device would be a downgrade.
 */
@Composable
fun LockScreen(security: SecurityCenter, modifier: Modifier = Modifier) {
    val c = Berth.colors
    val failure by security.lock.failure.collectAsState()
    val authenticating by security.lock.authenticating.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val deviceSecure = security.deviceSecure

    LaunchedEffect(Unit) { security.lock.unlock() }

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
