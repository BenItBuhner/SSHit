package app.berth.android.links

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.IconCompat
import app.berth.android.MainActivity
import app.berth.android.R
import app.berth.domain.model.Host
import app.berth.domain.repository.HostRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The launcher's shortcuts (spec Part B, App shortcuts; A80): a long press on Berth's icon offers
 * the four hosts connected most recently, newest first, and Quick connect. Quick connect is
 * declared in the manifest (`res/xml/shortcuts.xml`), so it is there before any host is; the hosts
 * are published from the host list as it changes, since every connection moves a host's
 * `lastConnectedAt` and a delete takes a host out. A host never connected is not recent. Each host
 * shows as its swatch with its monogram, as it does in the rail, and opens as the host list's
 * Connect would ([Arrival.OpenHost]); a shortcut left over from a list the process did not live to
 * republish lands as a notice rather than a login to nowhere.
 */
@Singleton
class LauncherShortcuts @Inject constructor(
    @ApplicationContext private val context: Context,
    private val hosts: HostRepository,
) {
    /** Publishes the recent hosts on [scope] for as long as it lives, once per change of the four. */
    fun publishFrom(scope: CoroutineScope): Job = scope.launch {
        hosts.observeAll().map(::recent).distinctUntilChanged().collect { publish(it) }
    }

    /** The hosts the launcher offers: those with a connection behind them, newest first, at most [MAX_RECENT]. */
    fun recent(all: List<Host>): List<Host> =
        all.filter { it.lastConnectedAt != null }.sortedByDescending { it.lastConnectedAt }.take(MAX_RECENT)

    /** Replaces the dynamic shortcuts with [recent]; the static Quick connect is the manifest's and stays. */
    fun publish(recent: List<Host>) {
        val shortcuts = recent.mapIndexed { rank, host ->
            ShortcutInfoCompat.Builder(context, hostShortcutId(host.id))
                .setShortLabel(host.name)
                .setLongLabel("${host.user}@${host.address}")
                .setIcon(IconCompat.createWithAdaptiveBitmap(swatch(host)))
                .setRank(rank)
                .setIntent(openHost(context, host.id))
                .build()
        }
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts) }
    }

    /** The launcher's ranking learns from use; called when a host shortcut opens its host. */
    fun used(hostId: String) {
        runCatching { ShortcutManagerCompat.reportShortcutUsed(context, hostShortcutId(hostId)) }
    }

    /**
     * The host's swatch as an adaptive icon: the swatch colour to the edges, the monogram white at
     * 92 % in the app's label face, sized for the launcher's safe zone (the middle two thirds).
     * Internal for the capture of the icons as a launcher masks them: a published shortcut does
     * not hand its icon back.
     */
    internal fun swatch(host: Host): Bitmap {
        val size = ICON_PX
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK or host.color.rgb)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(235, 255, 255, 255)
            typeface = runCatching { ResourcesCompat.getFont(context, R.font.ibm_plex_sans_semibold) }.getOrNull() ?: Typeface.DEFAULT_BOLD
            textSize = size * 0.34f
            textAlign = Paint.Align.CENTER
        }
        val baseline = size / 2f - (paint.ascent() + paint.descent()) / 2f
        canvas.drawText(host.monogram.take(2), size / 2f, baseline, paint)
        return bitmap
    }

    companion object {
        /** How many hosts the launcher is offered (spec: the four most recently connected). */
        const val MAX_RECENT = 4

        /** The intent a host shortcut fires; [MainActivity] reads it as [Arrival.OpenHost]. */
        const val ACTION_OPEN_HOST = "app.berth.android.action.OPEN_HOST"

        /** The intent the static Quick connect shortcut fires (`res/xml/shortcuts.xml`); read as [Arrival.QuickConnect]. */
        const val ACTION_QUICK_CONNECT = "app.berth.android.action.QUICK_CONNECT"
        const val EXTRA_HOST_ID = "app.berth.android.extra.HOST_ID"

        /** The adaptive-icon bitmap's side: 108 dp at xxhdpi, the size the launcher masks from. */
        private const val ICON_PX = 324

        fun hostShortcutId(hostId: String): String = "host:$hostId"

        fun openHost(context: Context, hostId: String): Intent =
            Intent(context, MainActivity::class.java).setAction(ACTION_OPEN_HOST).putExtra(EXTRA_HOST_ID, hostId)
    }
}
