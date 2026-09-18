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

    companion object {
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

        fun fromJson(text: String): TerminalTheme = json.decodeFromString(serializer(), text)

        const val BERTH_DARK_ID = "berth-dark"
        const val BERTH_LIGHT_ID = "berth-light"

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

        val builtIns: List<TerminalTheme> = listOf(BERTH_DARK, BERTH_LIGHT)
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
) {
    companion object {
        val DEFAULT = InterfaceTheme()
    }
}

/** Terminal font settings; bundled families are referenced by name, imported ones by file. */
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
) {
    companion object {
        const val MIN_SIZE_SP = 9
        const val MAX_SIZE_SP = 24
    }
}
