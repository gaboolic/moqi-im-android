package com.moqi.im.core

/** 语言切换立即使用各自布局；Rime 方案通知只更新中文布局。 */
internal class TextKeyboardLayouts {
    private var chineseT9 = false
    private var englishT9 = false

    fun isT9(english: Boolean): Boolean = if (english) englishT9 else chineseT9

    fun select(english: Boolean, t9: Boolean) {
        if (english) englishT9 = t9 else chineseT9 = t9
    }

    fun applySchema(schemaId: String) {
        if (schemaId.isBlank()) return
        chineseT9 = schemaId.contains("_t9", ignoreCase = true)
    }
}
