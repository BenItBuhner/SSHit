package app.berth.domain.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** Import of terminal theme text: Berth JSON as written by hand or exported, and Windows Terminal / Gogh schemes. */
object TerminalThemes {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Stable id from a display name: `Catppuccin Mocha` becomes `catppuccin-mocha`. */
    fun slug(name: String): String = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "theme" }

    /**
     * Parses [text] into a theme, or returns null with no exception when it is not a theme. A missing
     * `id` is derived from the name (with [idSuffix] appended when given), `"bold": "inherit"` maps
     * to null, and the result is never marked built in.
     */
    fun import(text: String, idSuffix: String? = null): TerminalTheme? {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
        val theme = when {
            "ansi" in root -> fromBerth(root)
            "black" in root && "brightBlack" in root -> fromScheme(root)
            else -> null
        } ?: return null
        val id = theme.id.ifBlank { slug(theme.name) } + (idSuffix?.let { "-$it" } ?: "")
        return theme.copy(id = id, builtIn = false)
    }

    private fun color(e: JsonElement?): Int? = (e as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(HexColorSerializer::parse)

    private fun fromBerth(o: JsonObject): TerminalTheme? {
        val ansi = (o["ansi"] as? JsonArray)?.map { color(it) ?: return null } ?: return null
        if (ansi.size != 16) return null
        val background = color(o["background"]) ?: return null
        val foreground = color(o["foreground"]) ?: return null
        return TerminalTheme(
            id = (o["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "",
            name = (o["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "Imported theme",
            ansi = ansi,
            background = background,
            foreground = foreground,
            cursor = color(o["cursor"]) ?: foreground,
            cursorText = color(o["cursor_text"]) ?: color(o["cursorText"]) ?: background,
            selection = color(o["selection"]) ?: ColorMath.mix(background, foreground, 0.25f),
            bold = color(o["bold"]),
            links = color(o["links"]) ?: ansi[4],
            suggestedAccent = color(o["suggested_accent"]) ?: color(o["suggestedAccent"]),
        )
    }

    private val SCHEME_ORDER = listOf(
        "black", "red", "green", "yellow", "blue", "purple", "cyan", "white",
        "brightBlack", "brightRed", "brightGreen", "brightYellow", "brightBlue", "brightPurple", "brightCyan", "brightWhite",
    )

    private fun fromScheme(o: JsonObject): TerminalTheme? {
        val ansi = SCHEME_ORDER.map { key -> color(o[key]) ?: color(o[key.replace("purple", "magenta").replace("Purple", "Magenta")]) ?: return null }
        val background = color(o["background"]) ?: return null
        val foreground = color(o["foreground"]) ?: return null
        return TerminalTheme(
            id = "",
            name = (o["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "Imported scheme",
            ansi = ansi,
            background = background,
            foreground = foreground,
            cursor = color(o["cursorColor"]) ?: foreground,
            cursorText = background,
            selection = color(o["selectionBackground"]) ?: ColorMath.mix(background, foreground, 0.25f),
            links = ansi[4],
        )
    }

    /** One theme from an object, or every theme from an array of them; anything else yields an empty list. */
    fun importAll(text: String): List<TerminalTheme> {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return emptyList()
        return when (root) {
            is JsonObject -> listOfNotNull(import(text))
            else -> runCatching { root.jsonArray }.getOrNull()?.mapIndexed { i, el ->
                runCatching { el.jsonObject }.getOrNull()?.let { import(it.toString(), idSuffix = if (i == 0) null else i.toString()) }
            }?.filterNotNull() ?: emptyList()
        }
    }
}
