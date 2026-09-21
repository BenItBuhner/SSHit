package app.berth.android.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.berth.android.session.SessionsSummary
import app.berth.android.ui.AppViewModel
import app.berth.android.ui.components.BerthButton
import app.berth.android.ui.components.ButtonKind
import app.berth.android.ui.components.SectionLabel
import app.berth.android.ui.components.SheetTitle
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthType
import androidx.compose.runtime.DisposableEffect

/**
 * Settings › Connection › Background (spec C20; vision §4.4), and the sheet the app raises once on
 * its own the first time a background connection is lost. It says what is running now, whether the
 * OS is allowed to sleep Berth, offers the exemption, and on the phones that need more than that
 * names the extra step. Nothing here nags: the app raises it once, and this is where the user comes
 * back to it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackgroundSheet(vm: AppViewModel, onDismiss: () -> Unit) {
    val c = Berth.colors
    val context = LocalContext.current
    val summary by vm.notifier.summary.collectAsState()

    // The exemption is a system setting the user changes in another screen; re-read it every time
    // this one comes back, so a grant made and returned from shows at once.
    var exempt by remember { mutableStateOf(BatteryOptimization.isExempt(context)) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) exempt = BatteryOptimization.isExempt(context)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    BerthSheetColumn(onDismiss) {
        SheetTitle("Background", "How Berth stays connected when the screen is off")
        Text(runningLine(summary), style = BerthType.body, color = c.text1, modifier = Modifier.padding(horizontal = 4.dp))
        Text(
            "One foreground service holds every live session and tunnel while Berth is away; its notification lists them and can detach them all. A frozen frame always survives, whatever the OS does to the process.",
            style = BerthType.caption,
            color = c.text3,
            modifier = Modifier.padding(horizontal = 4.dp),
        )

        SectionLabel("Battery optimisation", Modifier.padding(start = 4.dp, top = 4.dp))
        // The one-line status is a state and takes the state's colour; the paragraph that follows when
        // there is none is prose, and its words carry the weight (A1: state colours are for state).
        Text(
            if (exempt) "Berth is exempt: the system leaves it running when the screen is off."
            else "The system may sleep Berth when the screen is off, which drops idle connections. Exempting Berth keeps them up.",
            style = BerthType.caption,
            color = if (exempt) c.live else c.text2,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        if (!exempt) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                BerthButton("Allow", kind = ButtonKind.PRIMARY, onClick = { BatteryOptimization.requestExemption(context) })
                BerthButton("Battery settings", kind = ButtonKind.SECONDARY, onClick = { BatteryOptimization.openSettings(context) })
            }
        }

        val oem = BatteryOptimization.oemStep(Build.MANUFACTURER)
        if (oem != null) {
            SectionLabel("On ${oem.name}", Modifier.padding(start = 4.dp, top = 4.dp))
            Text(oem.step, style = BerthType.caption, color = c.text2, modifier = Modifier.padding(horizontal = 4.dp))
        }

        // Exempt, there is nothing to do here but leave, and Done is the one button. With the two
        // above it there is no third: the handle and the scrim take the sheet down, as on every sheet.
        if (exempt) BerthButton("Done", kind = ButtonKind.PRIMARY, onClick = onDismiss)
    }
}

/** The one line for what is holding the service up now, from the ongoing notification's own count. */
private fun runningLine(summary: SessionsSummary): String {
    val sessions = summary.active
    val tunnels = summary.tunnels
    if (sessions == 0 && tunnels == 0) return "Nothing is running now, so no service is up."
    val parts = buildList {
        if (sessions > 0) add(if (sessions == 1) "1 session" else "$sessions sessions")
        if (tunnels > 0) add(if (tunnels == 1) "1 tunnel" else "$tunnels tunnels")
    }
    val subject = parts.joinToString(" and ")
    val verb = if (sessions + tunnels == 1) "is" else "are"
    return "$subject $verb keeping Berth in the foreground."
}

/** The sheet's scrolling column, shared by the two Data-like sheets so they pad and scroll the same. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BerthSheetColumn(onDismiss: () -> Unit, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    app.berth.android.ui.components.BerthSheet(onDismiss = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/**
 * The battery-optimisation exemption, read and asked for through the platform. The exemption is
 * what stops the OS from sleeping the process; the OEM overlays below it (auto-start managers on
 * Chinese and Samsung skins) are their own setting the platform cannot toggle, so those are named,
 * not tapped.
 */
object BatteryOptimization {
    /** Whether the OS already leaves Berth running when the screen is off. */
    fun isExempt(context: Context): Boolean {
        val power = context.getSystemService(PowerManager::class.java) ?: return false
        return runCatching { power.isIgnoringBatteryOptimizations(context.packageName) }.getOrDefault(false)
    }

    /** Opens the system's exemption dialog for Berth; falls back to the battery-optimisation list if it cannot. */
    fun requestExemption(context: Context) {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (context.packageManager.resolveActivity(direct, 0) != null) {
            runCatching { context.startActivity(direct) }.onSuccess { return }
        }
        openSettings(context)
    }

    /** Opens the battery-optimisation list, where Berth can be found and exempted by hand. */
    fun openSettings(context: Context) {
        val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val intent = if (context.packageManager.resolveActivity(list, 0) != null) list else details
        runCatching { context.startActivity(intent) }
    }

    /** The extra step past the exemption on the skins that have one (vision §4.4); null on stock Android. */
    fun oemStep(manufacturer: String?): OemStep? = when (manufacturer?.lowercase()) {
        "samsung" -> OemStep("Samsung", "Settings › Battery › Background usage limits: keep Berth off \u201CSleeping apps\u201D and \u201CDeep sleeping apps\u201D, and turn its \u201CAllow background activity\u201D on.")
        "xiaomi", "redmi", "poco" -> OemStep("Xiaomi", "Security › Permissions › Autostart: turn Berth on, and set Battery saver for Berth to \u201CNo restrictions\u201D.")
        "huawei", "honor" -> OemStep("Huawei", "Settings › Battery › App launch: turn Berth's automatic management off, then allow auto-launch, secondary launch and background activity.")
        "oneplus" -> OemStep("OnePlus", "Settings › Battery › Battery optimisation: set Berth to \u201CDon't optimise\u201D, and turn off \u201CAdvanced optimisation\u201D › \u201CDeep optimisation\u201D.")
        "oppo", "realme" -> OemStep("Oppo", "Settings › Battery › Berth: allow background running, and add Berth to the startup manager.")
        else -> null
    }

    /** The manufacturer's name as the sheet says it, and the one-paragraph step for that skin. */
    data class OemStep(val name: String, val step: String)
}
