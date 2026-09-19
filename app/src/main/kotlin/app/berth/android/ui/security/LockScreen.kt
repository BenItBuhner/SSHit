package app.berth.android.ui.security

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
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
import app.berth.android.security.SecurityCenter
import app.berth.android.ui.components.BerthButton
import app.berth.android.ui.components.ButtonKind
import app.berth.android.ui.components.EmptyState
import app.berth.android.ui.components.ScreenHeader
import app.berth.android.ui.theme.Berth
import kotlinx.coroutines.launch

/**
 * What the app shows while locked (spec C20, App lock). It stands in for the shell rather than
 * covering it, so no frame of the Stage, no sheet and no prompt is behind it. The system prompt
 * runs as soon as the screen appears; when the user backs out of it, the button runs it again.
 */
@Composable
fun LockScreen(security: SecurityCenter, modifier: Modifier = Modifier) {
    val c = Berth.colors
    val failure by security.lock.failure.collectAsState()
    val authenticating by security.lock.authenticating.collectAsState()
    val scope = rememberCoroutineScope()

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
            BerthButton(
                if (authenticating) "Unlocking\u2026" else "Unlock",
                onClick = { scope.launch { security.lock.unlock() } },
                kind = ButtonKind.PRIMARY,
                enabled = !authenticating,
            )
        }
        Spacer(Modifier.weight(2f))
    }
}
