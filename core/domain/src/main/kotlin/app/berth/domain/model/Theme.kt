package app.berth.domain.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json

/** Serializes 0xRRGGBB ints as `#rrggbb` strings so theme JSON is readable and importable. */
object HexColorSerializer : KSerializer<Int> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("HexColor", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Int) = encoder.encodeString(toHex(value))
    override fun deserialize(decoder: Decoder): Int = parse(decoder.decodeString()) ?: error("bad color")

    fun toHex(rgb: Int): String = "#%06x".format(rgb and 0xFFFFFF)

    fun parse(text: String): Int? {
        val t = text.trim().removePrefix("#")
        return when (t.length) {
            6 -> t.toIntOrNull(16)
            3 -> t.toIntOrNull(16)?.let { v ->
                val r = (v shr 8) and 0xF
                val g = (v shr 4) and 0xF
                val b = v and 0xF
                (r * 17 shl 16) or (g * 17 shl 8) or (b * 17)
            }
            else -> null
        }
    }
}

typealias HexColor = @Serializable(with = HexColorSerializer::class) Int

/**
 * A terminal colour theme, independent of the interface theme. The shape matches Berth theme
 * JSON (UX spec E3); [bold] null means "inherit".
 */
@Serializable
data class TerminalTheme(
    val id: String,
    val name: String,
    val ansi: List<HexColor>,
    val background: HexColor,
    val foreground: HexColor,
    val cursor: HexColor,
    @SerialName("cursor_text") val cursorText: HexColor,
    val selection: HexColor,
    val bold: HexColor? = null,
    val links: HexColor,
    @SerialName("suggested_accent") val suggestedAccent: HexColor? = null,
    val builtIn: Boolean = false,
) {
    init {
        require(ansi.size == 16) { "a terminal theme needs exactly 16 ANSI colours" }
    }

    fun toJson(): String = json.encodeToString(serializer(), this)

    /** The colour in [slot]; null only for [ThemeSlot.Bold] when it inherits. */
    fun color(slot: ThemeSlot): Int? = when (slot) {
        is ThemeSlot.Ansi -> ansi[slot.index]
        ThemeSlot.Background -> background
        ThemeSlot.Foreground -> foreground
        ThemeSlot.Cursor -> cursor
        ThemeSlot.CursorText -> cursorText
        ThemeSlot.Selection -> selection
        ThemeSlot.Bold -> bold
        ThemeSlot.Links -> links
    }

    /** A copy with [slot] set to [rgb]; null clears [ThemeSlot.Bold] back to inherit and is ignored elsewhere. */
    fun with(slot: ThemeSlot, rgb: Int?): TerminalTheme {
        val v = rgb?.and(0xFFFFFF)
        return when (slot) {
            is ThemeSlot.Ansi -> if (v == null) this else copy(ansi = ansi.toMutableList().also { it[slot.index] = v })
            ThemeSlot.Background -> if (v == null) this else copy(background = v)
            ThemeSlot.Foreground -> if (v == null) this else copy(foreground = v)
            ThemeSlot.Cursor -> if (v == null) this else copy(cursor = v)
            ThemeSlot.CursorText -> if (v == null) this else copy(cursorText = v)
            ThemeSlot.Selection -> if (v == null) this else copy(selection = v)
            ThemeSlot.Bold -> copy(bold = v)
            ThemeSlot.Links -> if (v == null) this else copy(links = v)
        }
    }

    /** An editable copy under a fresh id; stock themes are duplicated rather than edited (UX spec C19). */
    fun duplicate(newId: String, newName: String = "$name copy"): TerminalTheme = copy(id = newId, name = newName, builtIn = false)

    /** True when the background is dark enough that light text reads on it. */
    val isDark: Boolean get() = ColorMath.luminance(background) < 0.4

    companion object {
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

        fun fromJson(text: String): TerminalTheme = json.decodeFromString(serializer(), text)

        const val BERTH_DARK_ID = "berth-dark"
        const val BERTH_LIGHT_ID = "berth-light"
        const val CATPPUCCIN_MOCHA_ID = "catppuccin-mocha"
        const val GRUVBOX_DARK_ID = "gruvbox-dark"
        const val NORD_ID = "nord"
        const val SOLARIZED_DARK_ID = "solarized-dark"

        val BERTH_DARK = TerminalTheme(
            id = BERTH_DARK_ID,
            name = "Berth Dark",
            ansi = listOf(
                0x1F1D1A, 0xD9776B, 0x8FB573, 0xE0A458, 0x7A9CD6, 0xC387B8, 0x7AD3C6, 0xC9C4BB,
                0x5C5853, 0xE89486, 0xA6CB8C, 0xEBBB79, 0x9BB6E6, 0xD6A3CC, 0x98E0D5, 0xF3EFE8,
            ),
            background = 0x121110,
            foreground = 0xECE8E1,
            cursor = 0xE0A458,
            cursorText = 0x121110,
            selection = 0x3A3630,
            links = 0x7A9CD6,
            suggestedAccent = 0xE0A458,
            builtIn = true,
        )

        val BERTH_LIGHT = TerminalTheme(
            id = BERTH_LIGHT_ID,
            name = "Berth Light",
            ansi = listOf(
                0x1E1C19, 0xB4483A, 0x4F7F3A, 0xB5782E, 0x3F63A8, 0x8A4E80, 0x1F8C7E, 0x8B867E,
                0x5B5751, 0xC95E4F, 0x5F9448, 0xC98A3E, 0x557BC0, 0xA06498, 0x2FA394, 0x1E1C19,
            ),
            background = 0xF5F2EC,
            foreground = 0x1E1C19,
            cursor = 0xB5782E,
            cursorText = 0xF5F2EC,
            selection = 0xD8D2C8,
            links = 0x3F63A8,
            suggestedAccent = 0xB5782E,
            builtIn = true,
        )

        val CATPPUCCIN_MOCHA = TerminalTheme(
            id = CATPPUCCIN_MOCHA_ID,
            name = "Catppuccin Mocha",
            ansi = listOf(
                0x45475A, 0xF38BA8, 0xA6E3A1, 0xF9E2AF, 0x89B4FA, 0xF5C2E7, 0x94E2D5, 0xBAC2DE,
                0x585B70, 0xF38BA8, 0xA6E3A1, 0xF9E2AF, 0x89B4FA, 0xF5C2E7, 0x94E2D5, 0xA6ADC8,
            ),
            background = 0x1E1E2E,
            foreground = 0xCDD6F4,
            cursor = 0xF5E0DC,
            cursorText = 0x1E1E2E,
            selection = 0x585B70,
            links = 0x89B4FA,
            suggestedAccent = 0xCBA6F7,
            builtIn = true,
        )

        val GRUVBOX_DARK = TerminalTheme(
            id = GRUVBOX_DARK_ID,
            name = "Gruvbox Dark",
            ansi = listOf(
                0x282828, 0xCC241D, 0x98971A, 0xD79921, 0x458588, 0xB16286, 0x689D6A, 0xA89984,
                0x928374, 0xFB4934, 0xB8BB26, 0xFABD2F, 0x83A598, 0xD3869B, 0x8EC07C, 0xEBDBB2,
            ),
            background = 0x282828,
            foreground = 0xEBDBB2,
            cursor = 0xEBDBB2,
            cursorText = 0x282828,
            selection = 0x504945,
            links = 0x83A598,
            suggestedAccent = 0xFE8019,
            builtIn = true,
        )

        val NORD = TerminalTheme(
            id = NORD_ID,
            name = "Nord",
            ansi = listOf(
                0x3B4252, 0xBF616A, 0xA3BE8C, 0xEBCB8B, 0x81A1C1, 0xB48EAD, 0x88C0D0, 0xE5E9F0,
                0x4C566A, 0xBF616A, 0xA3BE8C, 0xEBCB8B, 0x81A1C1, 0xB48EAD, 0x8FBCBB, 0xECEFF4,
            ),
            background = 0x2E3440,
            foreground = 0xD8DEE9,
            cursor = 0xD8DEE9,
            cursorText = 0x2E3440,
            selection = 0x434C5E,
            links = 0x88C0D0,
            suggestedAccent = 0x88C0D0,
            builtIn = true,
        )

        val SOLARIZED_DARK = TerminalTheme(
            id = SOLARIZED_DARK_ID,
            name = "Solarized Dark",
            ansi = listOf(
                0x073642, 0xDC322F, 0x859900, 0xB58900, 0x268BD2, 0xD33682, 0x2AA198, 0xEEE8D5,
                0x002B36, 0xCB4B16, 0x586E75, 0x657B83, 0x839496, 0x6C71C4, 0x93A1A1, 0xFDF6E3,
            ),
            background = 0x002B36,
            foreground = 0x839496,
            cursor = 0x839496,
            cursorText = 0x002B36,
            selection = 0x073642,
            links = 0x268BD2,
            suggestedAccent = 0xB58900,
            builtIn = true,
        )

        /** Stock themes: Berth's own pair plus a small curated set. They cannot be deleted, only duplicated. */
        val builtIns: List<TerminalTheme> = listOf(BERTH_DARK, BERTH_LIGHT, CATPPUCCIN_MOCHA, GRUVBOX_DARK, NORD, SOLARIZED_DARK)
    }
}

/** One editable colour of a [TerminalTheme]; the palette has sixteen, the rest are named. */
sealed interface ThemeSlot {
    val title: String

    data class Ansi(val index: Int) : ThemeSlot {
        init {
            require(index in 0..15)
        }
        override val title: String get() = ANSI_NAMES[index]
    }

    data object Background : ThemeSlot { override val title = "Background" }
    data object Foreground : ThemeSlot { override val title = "Foreground" }
    data object Cursor : ThemeSlot { override val title = "Cursor" }
    data object CursorText : ThemeSlot { override val title = "Cursor text" }
    data object Selection : ThemeSlot { override val title = "Selection" }
    data object Bold : ThemeSlot { override val title = "Bold" }
    data object Links : ThemeSlot { override val title = "Links" }

    companion object {
        val ANSI_NAMES = listOf(
            "Black", "Red", "Green", "Yellow", "Blue", "Magenta", "Cyan", "White",
            "Bright black", "Bright red", "Bright green", "Bright yellow", "Bright blue", "Bright magenta", "Bright cyan", "Bright white",
        )
        val named: List<ThemeSlot> = listOf(Background, Foreground, Cursor, CursorText, Selection, Bold, Links)
    }
}

/** sRGB helpers for the colour panel: HSL round-trips, relative luminance and WCAG contrast. */
object ColorMath {
    fun red(rgb: Int): Int = (rgb shr 16) and 0xFF
    fun green(rgb: Int): Int = (rgb shr 8) and 0xFF
    fun blue(rgb: Int): Int = rgb and 0xFF

    fun rgb(r: Int, g: Int, b: Int): Int = (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    /** Hue 0..360, saturation and lightness 0..1. */
    fun toHsl(rgb: Int): FloatArray {
        val r = red(rgb) / 255f
        val g = green(rgb) / 255f
        val b = blue(rgb) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val l = (max + min) / 2f
        if (max == min) return floatArrayOf(0f, 0f, l)
        val d = max - min
        val s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
        var h = when (max) {
            r -> (g - b) / d + (if (g < b) 6f else 0f)
            g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        }
        h *= 60f
        return floatArrayOf(h, s, l)
    }

    fun fromHsl(h: Float, s: Float, l: Float): Int {
        val hh = ((h % 360f) + 360f) % 360f / 360f
        val ss = s.coerceIn(0f, 1f)
        val ll = l.coerceIn(0f, 1f)
        if (ss == 0f) {
            val v = Math.round(ll * 255f)
            return rgb(v, v, v)
        }
        val q = if (ll < 0.5f) ll * (1f + ss) else ll + ss - ll * ss
        val p = 2f * ll - q
        fun channel(t0: Float): Int {
            var t = t0
            if (t < 0f) t += 1f
            if (t > 1f) t -= 1f
            val v = when {
                t < 1f / 6f -> p + (q - p) * 6f * t
                t < 1f / 2f -> q
                t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
                else -> p
            }
            return Math.round(v * 255f)
        }
        return rgb(channel(hh + 1f / 3f), channel(hh), channel(hh - 1f / 3f))
    }

    /** WCAG relative luminance, 0 (black) to 1 (white). */
    fun luminance(rgb: Int): Double {
        fun lin(c: Int): Double {
            val v = c / 255.0
            return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * lin(red(rgb)) + 0.7152 * lin(green(rgb)) + 0.0722 * lin(blue(rgb))
    }

    /** WCAG contrast ratio between two colours, 1..21. */
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a) + 0.05
        val lb = luminance(b) + 0.05
        return if (la > lb) la / lb else lb / la
    }

    /** Linear blend of [a] toward [b] by [t] in 0..1, per channel. */
    fun mix(a: Int, b: Int, t: Float): Int {
        val tt = t.coerceIn(0f, 1f)
        fun ch(x: Int, y: Int) = Math.round(x * (1f - tt) + y * tt)
        return rgb(ch(red(a), red(b)), ch(green(a), green(b)), ch(blue(a), blue(b)))
    }
}

enum class InterfaceVariant { DARK, TRUE_BLACK, LIGHT, SYSTEM }

enum class InterfaceContrast { STANDARD, HIGH }

enum class Density { COMFORTABLE, COMPACT }

/** Accent presets from the design system, as 0xRRGGBB. */
enum class AccentPreset(val rgb: Int) {
    COPPER(0xE0A458), VERDIGRIS(0x5FB3A1), SLATE(0x7A9CD6), MOSS(0x8FB573),
    ROSE(0xE27D8F), MAUVE(0xC387B8), BONE(0xC9C4BB),
}

/** Interface theme: tone, accent, contrast and density are independent of the terminal palette. */
@Serializable
data class InterfaceTheme(
    val variant: InterfaceVariant = InterfaceVariant.DARK,
    /** 0 = warm graphite, 1 = cool slate. */
    val tone: Float = 0.15f,
    val accent: HexColor = AccentPreset.COPPER.rgb,
    /** Use the system (Material You) accent on API 31+ instead of [accent]. */
    val materialYou: Boolean = false,
    val contrast: InterfaceContrast = InterfaceContrast.STANDARD,
    val density: Density = Density.COMFORTABLE,
    val useSystemFont: Boolean = false,
    /** Multiplies every entry of the radius scale (A5); nested radii stay concentric because all scale together. */
    val radiusScale: Float = 1f,
) {
    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

        fun fromJson(text: String): InterfaceTheme = json.decodeFromString(serializer(), text)

        val DEFAULT = InterfaceTheme()

        const val MIN_RADIUS_SCALE = 0.5f
        const val MAX_RADIUS_SCALE = 1.4f

        /** Starting points for the appearance editor; each is the full set of interface choices. */
        val presets: List<Pair<String, InterfaceTheme>> = listOf(
            "Graphite" to InterfaceTheme(),
            "Slate" to InterfaceTheme(tone = 0.85f, accent = AccentPreset.SLATE.rgb),
            "Black" to InterfaceTheme(variant = InterfaceVariant.TRUE_BLACK, tone = 0.15f, accent = AccentPreset.COPPER.rgb),
            "Moss" to InterfaceTheme(tone = 0.3f, accent = AccentPreset.MOSS.rgb, radiusScale = 0.7f),
            "Paper" to InterfaceTheme(variant = InterfaceVariant.LIGHT, tone = 0.1f, accent = AccentPreset.COPPER.rgb),
        )
    }
}

/**
 * Terminal font settings; bundled families are referenced by name, imported ones by file. The
 * terminal's size is its own, independent of the system's font size (spec A11: the interface
 * follows the system scale, the terminal does not), unless [followSystemScale] has it follow too.
 */
@Serializable
data class TerminalFont(
    val family: String = "JetBrains Mono",
    val sizeSp: Int = 13,
    val lineHeight: Float = 1.2f,
    val ligatures: Boolean = true,
    val nerdFontFallback: Boolean = true,
    val boldAsBright: Boolean = false,
    val cursorShape: String = "block",
    val cursorBlink: Boolean = false,
    val followSystemScale: Boolean = false,
) {
    companion object {
        const val MIN_SIZE_SP = 9
        const val MAX_SIZE_SP = 24
    }
}
