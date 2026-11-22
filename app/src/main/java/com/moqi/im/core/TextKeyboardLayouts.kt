package com.moqi.im.core

/** 语言切换立即使用各自布局；Rime 方案通知只更新中文布局。 */
internal class TextKeyboardLayouts {
    private enum class ChineseLayout { QWERTY, T9_PINYIN, T9_STROKE }

    private var chineseLayout = ChineseLayout.QWERTY
    private var englishT9 = false

    fun isT9(english: Boolean): Boolean = if (english) englishT9 else chineseLayout == ChineseLayout.T9_PINYIN

    val isChineseStroke: Boolean get() = chineseLayout == ChineseLayout.T9_STROKE

    fun select(english: Boolean, t9: Boolean) {
        if (english) {
            englishT9 = t9
        } else {
            chineseLayout = if (t9) ChineseLayout.T9_PINYIN else ChineseLayout.QWERTY
        }
    }

    fun applySchema(schemaId: String) {
        if (schemaId.isBlank()) return
        chineseLayout = when {
            schemaId == STROKE_SCHEMA_ID -> ChineseLayout.T9_STROKE
            schemaId.contains("_t9", ignoreCase = true) -> ChineseLayout.T9_PINYIN
            else -> ChineseLayout.QWERTY
        }
    }

    companion object {
        const val STROKE_SCHEMA_ID = "frost_stroke"
    }
}
