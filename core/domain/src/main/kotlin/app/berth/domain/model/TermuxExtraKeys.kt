package app.berth.domain.model

/**
 * Termux `extra-keys` in both directions. Termux writes the value as a JSON-like array of rows with
 * single quotes and bare object keys (`[['ESC','/',{key: 'CTRL', popup: 'ESC'}]]`), either alone or
 * as an `extra-keys = ...` line in `termux.properties`; this parser accepts all of those shapes.
 */
object TermuxExtraKeys {
    private val ALL_FN: List<DeckKeyCode> = DeckKeyCode.entries.filter { it.name.matches(Regex("F\\d+")) }

    /** Parses [text]; returns null when it is not extra-keys syntax. Each Termux row becomes one layer. */
    fun parse(text: String): DeckLayout? {
        val value = extractValue(text) ?: return null
        val root = runCatching { Lenient(value).parseValue() }.getOrNull() ?: return null
        val rows: List<Any?> = when {
            root is List<*> && root.all { it is List<*> } && root.isNotEmpty() -> root
            root is List<*> -> listOf(root)
            else -> return null
        }
        val layers = rows.mapIndexedNotNull { i, row ->
            val keys = (row as List<*>).mapNotNull { entry -> toKey(entry) }
            if (keys.isEmpty()) null else DeckLayer(name = if (rows.size == 1) "Base" else "Row ${i + 1}", keys = keys)
        }
        if (layers.isEmpty()) return null
        val hasArrows = layers.any { l -> l.keys.any { (it.tap as? DeckAction.Key)?.key in ARROWS } }
        return DeckLayout(
            rows = layers.size.coerceIn(1, 2),
            arrows = if (hasArrows) DeckArrows.FOUR_KEYS else DeckArrows.NUB,
            layers = layers,
        )
    }

    /** The `extra-keys` value in `termux.properties` form; every layer is a row. */
    fun export(layout: DeckLayout): String {
        val rows = layout.layers.filter { l -> !(l.keys.size == 1 && l.keys[0].snippets) }.map { layer ->
            layer.keys.flatMap { toTermux(it, layer) }
        }.filter { it.isNotEmpty() }
        return "extra-keys = [" + rows.joinToString(",") { row -> "[" + row.joinToString(",") + "]" } + "]"
    }

    private val ARROWS = setOf(DeckKeyCode.UP, DeckKeyCode.DOWN, DeckKeyCode.LEFT, DeckKeyCode.RIGHT)

    private fun extractValue(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.startsWith("[")) return trimmed
        // Properties form: the value may continue over lines ending in a backslash.
        val lines = trimmed.lines()
        val start = lines.indexOfFirst { it.trimStart().startsWith("extra-keys") && !it.trimStart().startsWith("extra-keys-style") }
        if (start < 0) return null
        val sb = StringBuilder()
        var i = start
        var first = true
        while (i < lines.size) {
            var line = lines[i].trim()
            if (first) {
                line = line.substringAfter('=', "").trim()
                first = false
            }
            val continues = line.endsWith("\\")
            sb.append(if (continues) line.dropLast(1) else line)
            i++
            if (!continues) break
        }
        val value = sb.toString().trim()
        return if (value.startsWith("[")) value else null
    }

    private fun toKey(entry: Any?): DeckKey? = when (entry) {
        is String -> keyFromName(entry)
        is Map<*, *> -> {
            val display = entry["display"] as? String
            val popup = entry["popup"]
            val up: DeckAction? = when (popup) {
                is String -> keyFromName(popup)?.tap
                is Map<*, *> -> (popup["macro"] as? String)?.let { DeckAction.Macro(it) } ?: (popup["key"] as? String)?.let { keyFromName(it)?.tap }
                else -> null
            }
            val macro = entry["macro"] as? String
            val keyName = entry["key"] as? String
            val base = when {
                macro != null -> DeckKey(tap = DeckAction.Macro(macro))
                keyName != null -> keyFromName(keyName)
                else -> null
            }
            base?.copy(up = up ?: base.up, display = display ?: base.display)
        }
        else -> null
    }

    /** Termux key vocabulary; anything unknown is literal text, `SCROLL` has no Berth equivalent. */
    private fun keyFromName(raw: String): DeckKey? {
        val name = raw.trim()
        if (name.isEmpty()) return null
        when (name.uppercase()) {
            "CTRL" -> return DeckKey(tap = DeckAction.Modifier(DeckModifier.CTRL), display = "Ctrl")
            "ALT" -> return DeckKey(tap = DeckAction.Modifier(DeckModifier.ALT), display = "Alt")
            "SHIFT" -> return DeckKey(tap = DeckAction.Modifier(DeckModifier.SHIFT), display = "Shift")
            "FN" -> return DeckKey(hold = DeckAction.Strip(ALL_FN), display = "Fn")
            "BKSP" -> return DeckKey(tap = DeckAction.Key(DeckKeyCode.BACKSPACE))
            "KEYBOARD" -> return DeckKey(tap = DeckAction.App(DeckAppAction.HIDE_KEYBOARD), display = "Kbd")
            "DRAWER" -> return DeckKey(tap = DeckAction.App(DeckAppAction.OPEN_SESSION_SHEET), display = "Sheet")
            "PASTE" -> return DeckKey(tap = DeckAction.App(DeckAppAction.PASTE), display = "Paste")
            "SCROLL" -> return null
            "BACKSLASH" -> return DeckKey(tap = DeckAction.Text("\\"))
            "QUOTE" -> return DeckKey(tap = DeckAction.Text("\""))
            "APOSTROPHE" -> return DeckKey(tap = DeckAction.Text("'"))
        }
        val code = if (name.length > 1) runCatching { DeckKeyCode.valueOf(name.uppercase()) }.getOrNull() else null
        return if (code != null) DeckKey(tap = DeckAction.Key(code)) else DeckKey(tap = DeckAction.Text(name))
    }

    private fun toTermux(key: DeckKey, layer: DeckLayer): List<String> {
        if (key.snippets) return emptyList()
        if (key.nub) return listOf(q("LEFT"), q("DOWN"), q("UP"), q("RIGHT"))
        val tap = key.tap
        val up = key.up
        val display = key.display
        fun obj(vararg fields: Pair<String, String?>): String =
            "{" + fields.filter { it.second != null }.joinToString(", ") { "${it.first}: ${it.second}" } + "}"
        val popup: String? = when (up) {
            is DeckAction.Key -> q(up.key.name)
            is DeckAction.Text -> q(up.text)
            is DeckAction.Modifier -> q(up.modifier.name)
            is DeckAction.Combo -> obj("macro" to q(up.combo))
            is DeckAction.Macro -> obj("macro" to q(expandPrefix(up.macro, layer)))
            null, is DeckAction.Snippet, is DeckAction.App, is DeckAction.Strip -> null
        }
        val primary: String? = when (tap) {
            is DeckAction.Key -> q(if (tap.key == DeckKeyCode.BACKSPACE) "BKSP" else tap.key.name)
            is DeckAction.Modifier -> q(tap.modifier.name)
            is DeckAction.Text -> q(tap.text)
            is DeckAction.App -> when (tap.action) {
                DeckAppAction.HIDE_KEYBOARD -> q("KEYBOARD")
                DeckAppAction.PASTE -> q("PASTE")
                DeckAppAction.OPEN_SESSION_SHEET -> q("DRAWER")
                else -> null
            }
            is DeckAction.Combo, is DeckAction.Macro, is DeckAction.Snippet, is DeckAction.Strip, null -> null
        }
        return when {
            tap is DeckAction.Combo -> listOf(obj("macro" to q(tap.combo), "display" to q(display ?: tap.pretty()), "popup" to popup))
            tap is DeckAction.Macro -> listOf(obj("macro" to q(expandPrefix(tap.macro, layer)), "display" to q(display ?: key.label), "popup" to popup))
            tap is DeckAction.Strip || (tap == null && key.hold is DeckAction.Strip) -> listOf(q("FN"))
            primary == null -> emptyList()
            popup == null && display == null -> listOf(primary)
            else -> listOf(obj("key" to primary, "display" to display?.let(::q), "popup" to popup))
        }
    }

    private fun expandPrefix(macro: String, layer: DeckLayer): String = macro.replace("PREFIX", layer.prefix ?: "CTRL b")

    private fun q(s: String): String = "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"

    /** Enough of a JSON parser to read Termux's relaxed syntax: quotes of either kind, bare words, trailing commas. */
    private class Lenient(private val s: String) {
        private var i = 0

        fun parseValue(): Any? {
            skip()
            if (i >= s.length) throw IllegalArgumentException("empty")
            return when (s[i]) {
                '[' -> parseArray()
                '{' -> parseObject()
                '\'', '"' -> parseString(s[i])
                else -> parseBare()
            }
        }

        private fun parseArray(): List<Any?> {
            expect('[')
            val out = ArrayList<Any?>()
            while (true) {
                skip()
                if (peek() == ']') { i++; return out }
                out.add(parseValue())
                skip()
                when (peek()) {
                    ',' -> i++
                    ']' -> { i++; return out }
                    else -> throw IllegalArgumentException("expected , or ] at $i")
                }
            }
        }

        private fun parseObject(): Map<String, Any?> {
            expect('{')
            val out = LinkedHashMap<String, Any?>()
            while (true) {
                skip()
                if (peek() == '}') { i++; return out }
                val key = when (peek()) {
                    '\'', '"' -> parseString(s[i])
                    else -> parseBare().toString()
                }
                skip()
                expect(':')
                val value = parseValue()
                out[key] = value
                skip()
                when (peek()) {
                    ',' -> i++
                    '}' -> { i++; return out }
                    else -> throw IllegalArgumentException("expected , or } at $i")
                }
            }
        }

        private fun parseString(quote: Char): String {
            expect(quote)
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                when (c) {
                    quote -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) break
                        when (val e = s[i++]) {
                            'n' -> sb.append('\n')
                            't' -> sb.append('\t')
                            'r' -> sb.append('\r')
                            'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
            throw IllegalArgumentException("unterminated string")
        }

        private fun parseBare(): Any? {
            val start = i
            while (i < s.length && s[i] !in ",:]}[{'\"" && !s[i].isWhitespace()) i++
            val word = s.substring(start, i)
            if (word.isEmpty()) throw IllegalArgumentException("unexpected ${peek()} at $i")
            return when (word) {
                "true" -> true
                "false" -> false
                "null" -> null
                else -> word
            }
        }

        private fun skip() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        private fun peek(): Char = if (i < s.length) s[i] else '\u0000'

        private fun expect(c: Char) {
            if (peek() != c) throw IllegalArgumentException("expected $c at $i")
            i++
        }
    }
}
