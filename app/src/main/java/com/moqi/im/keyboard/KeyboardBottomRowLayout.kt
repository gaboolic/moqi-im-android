package com.moqi.im.keyboard

import android.content.Context
import com.moqi.im.BuildConfig
import com.moqi.im.theme.ThemePalette

object KeyboardBottomRowLayout {
    private const val SEPARATOR = ","
    private const val PREF_QWERTY_ORDER = "keyboard_bottom_row_qwerty_order"
    private const val PREF_NUMBER_ORDER = "keyboard_bottom_row_number_order"
    private const val PREF_SYMBOL_ORDER = "keyboard_bottom_row_symbol_order"

    enum class Row(
        val prefKey: String,
        val title: String,
        val defaultOrder: List<String>
    ) {
        QWERTY(
            PREF_QWERTY_ORDER,
            "26 键",
            listOf(KEY_SYMBOL, KEY_NUMBER, KEY_COMMA, KEY_SPACE, KEY_PERIOD, KEY_MODE, KEY_ENTER)
        ),
        NUMBER(
            PREF_NUMBER_ORDER,
            "123 数字键盘",
            listOf(KEY_SYMBOL, KEY_RETURN, KEY_ZERO, KEY_SPACE, KEY_ENTER)
        ),
        SYMBOL(
            PREF_SYMBOL_ORDER,
            "符号键盘",
            listOf(KEY_RETURN, KEY_NUMBER, KEY_SYMBOL_PREV, KEY_SYMBOL_NEXT, KEY_DELETE)
        )
    }

    fun order(context: Context, row: Row): List<String> {
        val raw = context
            .getSharedPreferences(ThemePalette.PREFS_NAME, 0)
            .getString(row.prefKey, null)
        return sanitizeOrder(raw?.split(SEPARATOR), row.defaultOrder)
    }

    fun save(context: Context, row: Row, order: List<String>) {
        context
            .getSharedPreferences(ThemePalette.PREFS_NAME, 0)
            .edit()
            .putString(row.prefKey, sanitizeOrder(order, row.defaultOrder).joinToString(SEPARATOR))
            .apply()
    }

    fun reset(context: Context, row: Row) {
        context
            .getSharedPreferences(ThemePalette.PREFS_NAME, 0)
            .edit()
            .remove(row.prefKey)
            .apply()
    }

    fun resetAll(context: Context) {
        context
            .getSharedPreferences(ThemePalette.PREFS_NAME, 0)
            .edit()
            .remove(PREF_QWERTY_ORDER)
            .remove(PREF_NUMBER_ORDER)
            .remove(PREF_SYMBOL_ORDER)
            .apply()
    }

    fun displayLabel(id: String): String = when (id) {
        KEY_SYMBOL -> "符"
        KEY_NUMBER -> "123"
        KEY_COMMA -> "，"
        KEY_SPACE -> "空格"
        KEY_PERIOD -> "。"
        KEY_MODE -> "中/英"
        KEY_ENTER -> "回车"
        KEY_RETURN -> "返回"
        KEY_ZERO -> "0"
        KEY_SYMBOL_PREV -> "⌃"
        KEY_SYMBOL_NEXT -> "⌄"
        KEY_DELETE -> "⌫"
        else -> id
    }

    fun qwertyRow(context: Context, chinese: Boolean): List<KeyDefinition> =
        order(context, Row.QWERTY).map { id ->
            when (id) {
                KEY_SYMBOL -> KeyDefinition("符", KeyCode.SYMBOL_LAYOUT, 1f)
                KEY_NUMBER -> KeyDefinition("123", KeyCode.NUMBER_LAYOUT, 1f)
                KEY_COMMA -> KeyDefinition(if (chinese) "，" else ",", KeyCode.COMMA, 1f)
                KEY_SPACE -> KeyDefinition(spaceBarLabel(if (chinese) "空格" else "Space"), KeyCode.SPACE, 5.1f)
                KEY_PERIOD -> KeyDefinition(if (chinese) "。" else ".", KeyCode.PERIOD, 1f)
                KEY_MODE -> KeyDefinition("中/英", KeyCode.MODE_SWITCH, 1.75f)
                KEY_ENTER -> KeyDefinition("↵", KeyCode.ENTER, 1.3f)
                else -> error("Unsupported QWERTY bottom-row key: $id")
            }
        }

    fun numberRow(context: Context): List<KeyDefinition> =
        order(context, Row.NUMBER).map { id ->
            when (id) {
                KEY_SYMBOL -> KeyDefinition("符", KeyCode.SYMBOL_LAYOUT, 0.72f)
                KEY_RETURN -> KeyDefinition("返回", KeyCode.RETURN_TO_TEXT, 0.72f)
                KEY_ZERO -> KeyDefinition("0", '0'.code, 1.5f)
                KEY_SPACE -> KeyDefinition("空格", KeyCode.SPACE, 0.72f)
                KEY_ENTER -> KeyDefinition("↵", KeyCode.ENTER, 0.72f)
                else -> error("Unsupported number bottom-row key: $id")
            }
        }

    fun symbolRow(context: Context): List<KeyDefinition> =
        order(context, Row.SYMBOL).map { id ->
            when (id) {
                KEY_RETURN -> KeyDefinition("返回", KeyCode.RETURN_TO_TEXT, 1f)
                KEY_NUMBER -> KeyDefinition("123", KeyCode.NUMBER_LAYOUT, 1f)
                KEY_SYMBOL_PREV -> KeyDefinition("⌃", KeyCode.SYMBOL_PREV, 1f)
                KEY_SYMBOL_NEXT -> KeyDefinition("⌄", KeyCode.SYMBOL_NEXT, 1f)
                KEY_DELETE -> KeyDefinition("⌫", KeyCode.DELETE, 1f, isRepeatable = true)
                else -> error("Unsupported symbol bottom-row key: $id")
            }
        }

    private fun sanitizeOrder(rawOrder: List<String>?, defaultOrder: List<String>): List<String> {
        if (rawOrder.isNullOrEmpty()) return defaultOrder
        val known = defaultOrder.toSet()
        val uniqueKnown = rawOrder.filter { it in known }.distinct()
        return uniqueKnown + defaultOrder.filterNot { it in uniqueKnown }
    }

    private fun spaceBarLabel(base: String): String =
        if (BuildConfig.VOICE_INPUT_ENABLED) "$base 🎤" else base

    private const val KEY_SYMBOL = "symbol"
    private const val KEY_NUMBER = "number"
    private const val KEY_COMMA = "comma"
    private const val KEY_SPACE = "space"
    private const val KEY_PERIOD = "period"
    private const val KEY_MODE = "mode"
    private const val KEY_ENTER = "enter"
    private const val KEY_RETURN = "return"
    private const val KEY_ZERO = "zero"
    private const val KEY_SYMBOL_PREV = "symbol_prev"
    private const val KEY_SYMBOL_NEXT = "symbol_next"
    private const val KEY_DELETE = "delete"
}
