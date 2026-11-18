package com.moqi.im.keyboard

/** Keeps literal panel text separate from punctuation keys interpreted by Rime. */
object SymbolInputPolicy {
    enum class Route { DIRECT, PAIRED, ENGINE, COMMIT_COMPOSITION_THEN_TEXT }

    data class Input(val text: String, val route: Route)

    fun resolve(
        text: String,
        english: Boolean,
        literalPanel: Boolean,
        t9Composing: Boolean,
        composing: Boolean = false
    ): Input {
        // In Chinese mode slash starts Rime commands/symbol codes, even from the symbol panel.
        // Do not commit the first T9 candidate before Rime has seen this key.
        if (!english && text == "/") return Input(text, Route.ENGINE)
        // Explicit panel selections (including Chinese/English categories and emoji) are literal.
        if (literalPanel) return Input(text, if (!english && composing) Route.COMMIT_COMPOSITION_THEN_TEXT else Route.DIRECT)
        val normalized = normalize(text, english)
        if (english) return Input(normalized, if (isPaired(normalized)) Route.PAIRED else Route.DIRECT)
        if (t9Composing && normalized.none { it.isLetterOrDigit() }) {
            return Input(normalized, Route.COMMIT_COMPOSITION_THEN_TEXT)
        }
        // Only printable ASCII is a Rime key. Keep explicit Unicode text literal, but finish
        // the current word before inserting it (otherwise the punctuation precedes the word).
        if (normalized.length == 1 && normalized[0] in ' '..'~') return Input(normalized, Route.ENGINE)
        if (composing) return Input(normalized, Route.COMMIT_COMPOSITION_THEN_TEXT)
        return Input(normalized, if (isPaired(normalized)) Route.PAIRED else Route.DIRECT)
    }

    fun isPaired(text: String): Boolean = text in listOf("“”", "（）", "\"\"", "()")

    fun normalize(text: String, english: Boolean): String = if (english) {
        when (text) {
            "“”" -> "\"\""
            "（）" -> "()"
            "，" -> ","
            "。" -> "."
            "？" -> "?"
            "！" -> "!"
            "：" -> ":"
            "；" -> ";"
            "、" -> "/"
            "…", "……" -> "..."
            else -> text
        }
    } else {
        when (text) {
            "\"\"" -> "“”"
            "()" -> "（）"
            ":" -> "："
            ";" -> "；"
            else -> text
        }
    }

    fun t9Symbols(english: Boolean): List<String> =
        listOf("，", "。", "？", "：", "！", "…", "；", "、", ".", "-", "@").map {
            normalize(it, english)
        }
}
