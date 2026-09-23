package app.berth.domain.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.text.Normalizer
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The files a terminal theme travels in (spec A10). Every format imports; [exports] is false for
 * base16, whose sixteen colours are syntax roles rather than ANSI slots, so a terminal palette
 * cannot be written back as one without inventing the roles it never had.
 */
enum class ThemeFormat(val label: String, val extension: String, val exports: Boolean = true) {
    BERTH("Berth JSON", "json"),
    ITERM2("iTerm2", "itermcolors"),
    GHOSTTY("Ghostty", ""),
    WINDOWS_TERMINAL("Windows Terminal", "json"),
    TERMUX("Termux", "properties"),
    BASE16("base16", "yaml", exports = false),
}

/**
 * Import and export of terminal theme text: Berth JSON, Windows Terminal and Gogh schemes, iTerm2
 * `.itermcolors`, Ghostty theme files, base16 YAML and Termux `colors.properties`.
 */
object TerminalThemes {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val prettyJson = Json { prettyPrint = true }

    /** Stable id from a display name: `Catppuccin Mocha` becomes `catppuccin-mocha`, `Rosé Pine` becomes `rose-pine`. */
    fun slug(name: String): String = Normalizer.normalize(name, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        .lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "theme" }

    /** Every colour slot of a theme, in the editor's order. */
    val slots: List<ThemeSlot> = (0..15).map { ThemeSlot.Ansi(it) } + ThemeSlot.named

    /** The format of [text], or null when it is none Berth reads. JSON is Berth's own or a scheme, told apart on import. */
    fun detect(text: String): ThemeFormat? {
        val t = text.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        return when {
            t.startsWith("{") || t.startsWith("[") -> {
                val root = runCatching { json.parseToJsonElement(t) }.getOrNull()
                val first = (root as? JsonObject) ?: (root as? JsonArray)?.firstOrNull() as? JsonObject
                when {
                    first == null -> null
                    "ansi" in first -> ThemeFormat.BERTH
                    "black" in first && "brightBlack" in first -> ThemeFormat.WINDOWS_TERMINAL
                    else -> null
                }
            }
            t.startsWith("<") && "Ansi 0 Color" in t -> ThemeFormat.ITERM2
            GHOSTTY_PALETTE.containsMatchIn(t) -> ThemeFormat.GHOSTTY
            BASE16_KEY.containsMatchIn(t) -> ThemeFormat.BASE16
            TERMUX_COLOR.containsMatchIn(t) -> ThemeFormat.TERMUX
            else -> null
        }
    }

    /**
     * Parses [text] into a theme, or returns null with no exception when it is not a theme. The
     * name is the file's own where the format carries one (Berth JSON, schemes, base16), else
     * [name] (the file name, for iTerm2, Ghostty and Termux), else "Imported theme". A missing `id`
     * is derived from the name (with [idSuffix] appended when given), `"bold": "inherit"` maps to
     * null, and the result is never marked built in.
     */
    fun import(text: String, idSuffix: String? = null, name: String? = null): TerminalTheme? {
        val theme = when (detect(text)) {
            ThemeFormat.BERTH -> (runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject)?.let(::fromBerth)
            ThemeFormat.WINDOWS_TERMINAL -> (runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject)?.let(::fromScheme)
            ThemeFormat.ITERM2 -> fromITerm(text)
            ThemeFormat.GHOSTTY -> fromGhostty(text)
            ThemeFormat.BASE16 -> fromBase16(text)
            ThemeFormat.TERMUX -> fromTermux(text)
            null -> null
        } ?: return null
        val named = if (theme.name.isBlank()) theme.copy(name = name?.takeIf { it.isNotBlank() } ?: "Imported theme") else theme
        val id = named.id.ifBlank { slug(named.name) } + (idSuffix?.let { "-$it" } ?: "")
        return named.copy(id = id, builtIn = false)
    }

    /** One theme from a file or an object, or every theme from a JSON array of them; anything else yields an empty list. */
    fun importAll(text: String, name: String? = null): List<TerminalTheme> {
        val root = if (detect(text) in JSON_FORMATS) runCatching { json.parseToJsonElement(text) }.getOrNull() else null
        return when (root) {
            is JsonArray -> root.mapIndexedNotNull { i, el ->
                runCatching { el.jsonObject }.getOrNull()?.let { import(it.toString(), idSuffix = if (i == 0) null else i.toString()) }
            }
            else -> listOfNotNull(import(text, name = name))
        }
    }

    /**
     * The theme's display name from a file name: the extension off, and an all-lowercase stem such as
     * `gruvbox-light` set as words. Termux's own `colors.properties` names no theme, so it gives null.
     */
    fun nameFromFile(fileName: String?): String? {
        val base = fileName?.substringAfterLast('/')?.trim() ?: return null
        val stem = FILE_EXTENSIONS.fold(base) { acc, ext -> if (acc.endsWith(".$ext", ignoreCase = true)) acc.dropLast(ext.length + 1) else acc }.trim()
        if (stem.isBlank() || stem.equals("colors", ignoreCase = true)) return null
        return if (stem == stem.lowercase()) {
            stem.split('-', '_', ' ').filter { it.isNotEmpty() }.joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
        } else stem
    }

    /** The file name an export suggests: the theme's name, with the format's extension where it has one. */
    fun fileName(theme: TerminalTheme, format: ThemeFormat): String {
        val stem = when (format) {
            ThemeFormat.ITERM2, ThemeFormat.GHOSTTY -> theme.name.replace(Regex("[/\\\\:*?\"<>|]"), " ").trim().ifEmpty { "theme" }
            else -> slug(theme.name)
        }
        return if (format.extension.isEmpty()) stem else "$stem.${format.extension}"
    }

    /** [theme] as a file of [format]. */
    fun export(theme: TerminalTheme, format: ThemeFormat): String = when (format) {
        ThemeFormat.BERTH -> theme.toJson()
        ThemeFormat.ITERM2 -> toITerm(theme)
        ThemeFormat.GHOSTTY -> toGhostty(theme)
        ThemeFormat.WINDOWS_TERMINAL -> toScheme(theme)
        ThemeFormat.TERMUX -> toTermux(theme)
        ThemeFormat.BASE16 -> throw IllegalArgumentException("base16 is read, never written")
    }

    /**
     * The colours of [theme] a [format] file would not carry: the slots that read back different after
     * an export and an import. Empty means the export is lossless (spec A10 offers only those). A slot
     * the format has no place for still survives when the theme's value is what an import derives
     * for it, such as a link colour that is the palette's blue.
     */
    fun dropped(theme: TerminalTheme, format: ThemeFormat): List<ThemeSlot> {
        if (!format.exports) return slots
        val back = import(export(theme, format), name = theme.name) ?: return slots
        return slots.filter { back.color(it) != theme.color(it) }
    }

    // ---- JSON: Berth and Windows Terminal / Gogh ------------------------------------------------------

    private fun color(e: JsonElement?): Int? = (e as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(HexColorSerializer::parse)

    private fun fromBerth(o: JsonObject): TerminalTheme? {
        val ansi = (o["ansi"] as? JsonArray)?.map { color(it) ?: return null } ?: return null
        if (ansi.size != 16) return null
        val background = color(o["background"]) ?: return null
        val foreground = color(o["foreground"]) ?: return null
        return TerminalTheme(
            id = (o["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "",
            name = (o["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "",
            ansi = ansi,
            background = background,
            foreground = foreground,
            cursor = color(o["cursor"]) ?: foreground,
            cursorText = color(o["cursor_text"]) ?: color(o["cursorText"]) ?: background,
            selection = color(o["selection"]) ?: defaultSelection(background, foreground),
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
            name = (o["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "",
            ansi = ansi,
            background = background,
            foreground = foreground,
            cursor = color(o["cursorColor"]) ?: foreground,
            cursorText = background,
            selection = color(o["selectionBackground"]) ?: defaultSelection(background, foreground),
            links = ansi[4],
        )
    }

    private fun toScheme(t: TerminalTheme): String = prettyJson.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("name", t.name)
            put("background", hex(t.background))
            put("foreground", hex(t.foreground))
            put("cursorColor", hex(t.cursor))
            put("selectionBackground", hex(t.selection))
            SCHEME_ORDER.forEachIndexed { i, key -> put(key, hex(t.ansi[i])) }
        },
    )

    // ---- iTerm2 .itermcolors (an XML property list) ---------------------------------------------------

    private val ITERM_FIELD = Regex("<key>([^<]+)</key>\\s*<(real|integer|string)>([^<]*)</\\2>")

    /**
     * Each `<key>…</key>` followed by a `<dict>`, with the dictionary's body up to the first `</dict>`,
     * found by one forward walk: a lazy regex over the same shape rescans to the end of the text from
     * every unclosed `<dict>`, and a doctored file of them held the parse for minutes.
     */
    private fun iTermEntries(text: String): Map<String, String> {
        val entries = HashMap<String, String>()
        var at = 0
        while (true) {
            val open = text.indexOf("<key>", at)
            if (open < 0) break
            val keyStart = open + "<key>".length
            val keyEnd = text.indexOf('<', keyStart)
            if (keyEnd < 0) break
            at = keyStart
            if (keyEnd == keyStart || !text.startsWith("</key>", keyEnd)) continue
            var body = keyEnd + "</key>".length
            while (body < text.length && text[body].isWhitespace()) body++
            at = body
            if (!text.startsWith("<dict>", body)) continue
            val close = text.indexOf("</dict>", body + "<dict>".length)
            if (close < 0) break
            entries[text.substring(keyStart, keyEnd).trim()] = text.substring(body + "<dict>".length, close)
            at = close + "</dict>".length
        }
        return entries
    }

    private fun fromITerm(text: String): TerminalTheme? {
        val entries = iTermEntries(text).mapValues { (_, body) -> iTermColor(body) }
        // A profile with separate light and dark colours writes `Ansi 0 Color (Dark)` and `(Light)` instead; the dark set stands in.
        fun colors(key: String): Int? = entries[key] ?: entries["$key (Dark)"] ?: entries["$key (Light)"]
        val ansi = (0..15).map { colors("Ansi $it Color") ?: return null }
        val background = colors("Background Color") ?: return null
        val foreground = colors("Foreground Color") ?: return null
        return TerminalTheme(
            id = "",
            name = "",
            ansi = ansi,
            background = background,
            foreground = foreground,
            cursor = colors("Cursor Color") ?: foreground,
            cursorText = colors("Cursor Text Color") ?: background,
            selection = colors("Selection Color") ?: defaultSelection(background, foreground),
            bold = colors("Bold Color"),
            links = colors("Link Color") ?: ansi[4],
        )
    }

    /** One colour dictionary: components in 0..1, converted from Display P3 when the file says so, else read as sRGB. */
    private fun iTermColor(body: String): Int? {
        val fields = ITERM_FIELD.findAll(body).associate { it.groupValues[1].trim() to it.groupValues[3].trim() }
        val r = fields["Red Component"]?.toDoubleOrNull() ?: return null
        val g = fields["Green Component"]?.toDoubleOrNull() ?: return null
        val b = fields["Blue Component"]?.toDoubleOrNull() ?: return null
        val (sr, sg, sb) = if (fields["Color Space"].equals("P3", ignoreCase = true)) displayP3ToSrgb(r, g, b) else Triple(r, g, b)
        fun channel(v: Double) = (v.coerceIn(0.0, 1.0) * 255.0).roundToInt()
        return ColorMath.rgb(channel(sr), channel(sg), channel(sb))
    }

    /** Display P3 to sRGB: both use the sRGB transfer curve; the primaries differ, and what P3 has beyond sRGB is clipped. */
    private fun displayP3ToSrgb(r: Double, g: Double, b: Double): Triple<Double, Double, Double> {
        fun lin(v: Double) = if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        fun enc(v: Double): Double {
            val c = v.coerceIn(0.0, 1.0)
            return if (c <= 0.0031308) c * 12.92 else 1.055 * c.pow(1 / 2.4) - 0.055
        }
        val lr = lin(r)
        val lg = lin(g)
        val lb = lin(b)
        return Triple(
            enc(1.2249401 * lr - 0.2249401 * lg),
            enc(-0.0420569 * lr + 1.0420569 * lg),
            enc(-0.0196376 * lr - 0.0786360 * lg + 1.0982736 * lb),
        )
    }

    private fun toITerm(t: TerminalTheme): String = buildString {
        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        appendLine("""<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">""")
        appendLine("""<plist version="1.0">""")
        appendLine("<dict>")
        fun entry(key: String, rgb: Int) {
            appendLine("\t<key>$key</key>")
            appendLine("\t<dict>")
            appendLine("\t\t<key>Alpha Component</key>\n\t\t<real>1</real>")
            appendLine("\t\t<key>Blue Component</key>\n\t\t<real>${ColorMath.blue(rgb) / 255.0}</real>")
            appendLine("\t\t<key>Color Space</key>\n\t\t<string>sRGB</string>")
            appendLine("\t\t<key>Green Component</key>\n\t\t<real>${ColorMath.green(rgb) / 255.0}</real>")
            appendLine("\t\t<key>Red Component</key>\n\t\t<real>${ColorMath.red(rgb) / 255.0}</real>")
            appendLine("\t</dict>")
        }
        t.ansi.forEachIndexed { i, rgb -> entry("Ansi $i Color", rgb) }
        entry("Background Color", t.background)
        t.bold?.let { entry("Bold Color", it) }
        entry("Cursor Color", t.cursor)
        entry("Cursor Text Color", t.cursorText)
        entry("Foreground Color", t.foreground)
        entry("Link Color", t.links)
        entry("Selection Color", t.selection)
        appendLine("</dict>")
        appendLine("</plist>")
    }

    // ---- Ghostty theme files (key = value) ------------------------------------------------------------

    // The line patterns below take `[ \t]*` where the whitespace stays on one line: a `\s*` after `^`
    // crosses newlines, so every line start of a long blank run rescans the rest of the run.
    private val GHOSTTY_PALETTE = Regex("^[ \\t]*palette[ \\t]*=[ \\t]*\\d+[ \\t]*=", RegexOption.MULTILINE)

    private fun fromGhostty(text: String): TerminalTheme? {
        val palette = arrayOfNulls<Int>(16)
        val values = HashMap<String, Int>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val key = line.substringBefore('=').trim()
            val value = line.substringAfter('=', "").trim().trim('"')
            if (key == "palette") {
                val index = value.substringBefore('=').trim().toIntOrNull() ?: continue
                val rgb = HexColorSerializer.parse(value.substringAfter('=', "").trim()) ?: continue
                if (index in 0..15) palette[index] = rgb
            } else {
                HexColorSerializer.parse(value)?.let { values[key] = it }
            }
        }
        val ansi = palette.map { it ?: return null }
        val background = values["background"] ?: return null
        val foreground = values["foreground"] ?: return null
        return TerminalTheme(
            id = "",
            name = "",
            ansi = ansi,
            background = background,
            foreground = foreground,
            cursor = values["cursor-color"] ?: foreground,
            cursorText = values["cursor-text"] ?: background,
            selection = values["selection-background"] ?: defaultSelection(background, foreground),
            bold = values["bold-color"],
            links = ansi[4],
        )
    }

    private fun toGhostty(t: TerminalTheme): String = buildString {
        t.ansi.forEachIndexed { i, rgb -> appendLine("palette = $i=${hex(rgb)}") }
        appendLine("background = ${hex(t.background)}")
        appendLine("foreground = ${hex(t.foreground)}")
        appendLine("cursor-color = ${hex(t.cursor)}")
        appendLine("cursor-text = ${hex(t.cursorText)}")
        appendLine("selection-background = ${hex(t.selection)}")
        t.bold?.let { appendLine("bold-color = ${hex(it)}") }
    }

    // ---- base16 YAML (tinted-theming's `palette:` layout and the flat original) -----------------------

    private val BASE16_KEY = Regex("^[ \\t]*base0[0-9A-Fa-f][ \\t]*:", RegexOption.MULTILINE)
    private val BASE16_ENTRY = Regex("^[ \\t]*(base0[0-9A-Fa-f])[ \\t]*:[ \\t]*[\"']?#?([0-9A-Fa-f]{6})[\"']?", RegexOption.MULTILINE)
    // Greedy up to a quote, a comment or the line's end, trimmed after: a lazy capture ahead of optional
    // spaces backtracks over every split of a long run of them.
    private val BASE16_NAME = Regex("^(?:name|scheme)[ \\t]*:[ \\t]*[\"']?([^\"'#\\r\\n]*)", RegexOption.MULTILINE)

    /**
     * base16's roles on the terminal, as base16-shell sets them: the background and foreground
     * (base00, base05) as black and white, base03 (comments) as bright black, base07 as bright
     * white, and the six accents in both the normal and the bright row.
     */
    private val BASE16_ANSI = listOf(0x00, 0x08, 0x0B, 0x0A, 0x0D, 0x0E, 0x0C, 0x05, 0x03, 0x08, 0x0B, 0x0A, 0x0D, 0x0E, 0x0C, 0x07)

    private fun fromBase16(text: String): TerminalTheme? {
        val base = BASE16_ENTRY.findAll(text).associate { it.groupValues[1].substring(4).toInt(16) to it.groupValues[2].toInt(16) }
        if ((0..15).any { it !in base }) return null
        val ansi = BASE16_ANSI.map { base.getValue(it) }
        return TerminalTheme(
            id = "",
            name = BASE16_NAME.findAll(text).map { it.groupValues[1].trim() }.firstOrNull { it.isNotEmpty() } ?: "",
            ansi = ansi,
            background = base.getValue(0x00),
            foreground = base.getValue(0x05),
            cursor = base.getValue(0x05),
            cursorText = base.getValue(0x00),
            selection = base.getValue(0x02),
            links = base.getValue(0x0D),
        )
    }

    // ---- Termux colors.properties ---------------------------------------------------------------------

    private val TERMUX_COLOR = Regex("^[ \\t]*color\\d{1,2}[ \\t]*[=:]", RegexOption.MULTILINE)

    private fun fromTermux(text: String): TerminalTheme? {
        val values = HashMap<String, Int>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) continue
            val cut = line.indexOfFirst { it == '=' || it == ':' }
            if (cut <= 0) continue
            HexColorSerializer.parse(line.substring(cut + 1).trim())?.let { values[line.substring(0, cut).trim()] = it }
        }
        val ansi = (0..15).map { values["color$it"] ?: return null }
        val background = values["background"] ?: return null
        val foreground = values["foreground"] ?: return null
        return TerminalTheme(
            id = "",
            name = "",
            ansi = ansi,
            background = background,
            foreground = foreground,
            cursor = values["cursor"] ?: foreground,
            cursorText = background,
            selection = defaultSelection(background, foreground),
            links = ansi[4],
        )
    }

    private fun toTermux(t: TerminalTheme): String = buildString {
        appendLine("foreground=${hex(t.foreground)}")
        appendLine("background=${hex(t.background)}")
        appendLine("cursor=${hex(t.cursor)}")
        t.ansi.forEachIndexed { i, rgb -> appendLine("color$i=${hex(rgb)}") }
    }

    // ---- shared -------------------------------------------------------------------------------------------

    /** The selection a format without one gets: a quarter of the way from the background to the text. */
    private fun defaultSelection(background: Int, foreground: Int): Int = ColorMath.mix(background, foreground, 0.25f)

    private fun hex(rgb: Int): String = HexColorSerializer.toHex(rgb)

    private val JSON_FORMATS = setOf(ThemeFormat.BERTH, ThemeFormat.WINDOWS_TERMINAL)

    private val FILE_EXTENSIONS = listOf("itermcolors", "properties", "yaml", "yml", "json", "conf", "config", "txt")
}
