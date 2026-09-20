package app.berth.android.ui.a11y

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/**
 * Whether the system asks for reduced motion (spec A7): Android's one signal is Remove animations
 * in Accessibility, which sets the animator duration scale to zero, and the same scale set to zero
 * under Developer options. Provided by the theme from [rememberReducedMotion]; a test provides it
 * directly.
 *
 * Under it, the spec's shapes: crossfades of 120 ms replace every transform (a slide, a scale, a
 * placement, a scroll brought about by the app), and the reconnecting arc holds still as a
 * three-quarter arc. The same zero scale also runs the animation clock to its end in a frame, so on
 * a device the fades land in one frame too; the shapes here are what the clock would run and what a
 * test sees, and they keep the app from moving anything the moment the clock has any length. The
 * sheets, drawer and menus Material draws take their motion from a scheme Material keeps internal
 * (1.4.0), so they answer the zero clock only, which puts them in place in a frame as well.
 */
val LocalReducedMotion = staticCompositionLocalOf { false }

/** The system setting, observed: true while the animator duration scale is zero. */
@Composable
fun rememberReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    var reduced by remember(resolver) { mutableStateOf(readReducedMotion(resolver)) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduced = readReducedMotion(resolver)
            }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return reduced
}

fun readReducedMotion(resolver: ContentResolver): Boolean =
    Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

/**
 * The app's motion vocabulary (spec A7), each shape answering [LocalReducedMotion]: durations of
 * 120 ms for a state change, 200 in and 160 out for a sheet, 300 for a container; M3's emphasised
 * decelerate on entry and emphasised accelerate on exit.
 */
object BerthMotion {
    const val STATE_MS = 120
    const val SHEET_IN_MS = 200
    const val SHEET_OUT_MS = 160
    const val CONTAINER_MS = 300

    val emphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val emphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /**
     * The reconnecting arc's angle under reduced motion, in place of the turning transition: still,
     * its open quarter centred at the top, so it reads as the same three-quarter mark paused.
     */
    val staticArc: State<Float> = mutableStateOf(-45f)

    /** The reduced-motion crossfade: 120 ms, linear. */
    fun <T> crossfade(): FiniteAnimationSpec<T> = tween(STATE_MS, easing = LinearEasing)

    /**
     * A transform's spec: [spec] normally, a snap under reduced motion, since a transform that is
     * not interpolated is a state and not a motion (a lifted tab is larger, it does not grow).
     */
    @Composable
    fun <T> transform(spec: FiniteAnimationSpec<T>): FiniteAnimationSpec<T> = if (LocalReducedMotion.current) snap() else spec

    /** Rises a fraction of its height while fading in; a 120 ms fade under reduced motion. */
    @Composable
    fun riseIn(durationMillis: Int = SHEET_IN_MS): EnterTransition =
        if (LocalReducedMotion.current) fadeIn(crossfade())
        else fadeIn(tween(durationMillis, easing = emphasizedDecelerate)) + slideInVertically(tween(durationMillis, easing = emphasizedDecelerate)) { it / 2 }

    /** Sinks a fraction of its height while fading out; a 120 ms fade under reduced motion. */
    @Composable
    fun sinkOut(durationMillis: Int = SHEET_OUT_MS): ExitTransition =
        if (LocalReducedMotion.current) fadeOut(crossfade())
        else fadeOut(tween(durationMillis, easing = emphasizedAccelerate)) + slideOutVertically(tween(durationMillis, easing = emphasizedAccelerate)) { it / 2 }

    /** A screen arriving: in from the end edge a sixth of the way while fading; a fade under reduced motion. */
    @Composable
    fun screenIn(durationMillis: Int = 220): EnterTransition =
        if (LocalReducedMotion.current) fadeIn(crossfade())
        else slideInHorizontally(tween(durationMillis)) { it / 6 } + fadeIn(tween(durationMillis))

    /** A screen leaving the way it came; a fade under reduced motion. */
    @Composable
    fun screenOut(durationMillis: Int = 220, fadeMillis: Int = SHEET_OUT_MS): ExitTransition =
        if (LocalReducedMotion.current) fadeOut(crossfade())
        else slideOutHorizontally(tween(durationMillis)) { it / 6 } + fadeOut(tween(fadeMillis))

    /**
     * A bar or the Deck taking its room: its height grows while it fades in; under reduced motion
     * it fades in at its full height, the room opening in a frame.
     */
    @Composable
    fun unfoldIn(durationMillis: Int = SHEET_IN_MS): EnterTransition =
        if (LocalReducedMotion.current) fadeIn(crossfade())
        else fadeIn(tween(durationMillis, easing = emphasizedDecelerate)) + expandVertically(tween(durationMillis, easing = emphasizedDecelerate))

    @Composable
    fun foldOut(durationMillis: Int = SHEET_OUT_MS): ExitTransition =
        if (LocalReducedMotion.current) fadeOut(crossfade())
        else fadeOut(tween(durationMillis, easing = emphasizedAccelerate)) + shrinkVertically(tween(durationMillis, easing = emphasizedAccelerate))

    /** Content appearing in place: a fade, at the spec's length normally and 120 ms under reduced motion. */
    @Composable
    fun fadeInPlace(durationMillis: Int = SHEET_OUT_MS): EnterTransition =
        fadeIn(if (LocalReducedMotion.current) crossfade() else tween(durationMillis))

    @Composable
    fun fadeOutOfPlace(durationMillis: Int = SHEET_OUT_MS): ExitTransition =
        fadeOut(if (LocalReducedMotion.current) crossfade() else tween(durationMillis))
}
