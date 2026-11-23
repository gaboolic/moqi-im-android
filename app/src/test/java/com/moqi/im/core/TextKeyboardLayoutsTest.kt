package com.moqi.im.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextKeyboardLayoutsTest {
    @Test fun defaultsRemainQwerty() {
        val layouts = TextKeyboardLayouts()
        assertFalse(layouts.isT9(false))
        assertFalse(layouts.isT9(true))
    }

    @Test fun chineseT9ReturnsImmediatelyAfterEnglish() {
        val layouts = TextKeyboardLayouts()
        layouts.applySchema("rime_frost_t9")
        assertFalse(layouts.isT9(true))
        assertTrue(layouts.isT9(false))
        layouts.applySchema("")
        assertTrue(layouts.isT9(false))
    }

    @Test fun schemaCallbackDoesNotReplaceEnglishT9() {
        val layouts = TextKeyboardLayouts()
        layouts.select(true, true)
        layouts.applySchema("rime_frost")
        assertTrue(layouts.isT9(true))
        assertFalse(layouts.isT9(false))
    }

    @Test fun wubiUsesItsSchemaInsteadOfEnglishLayout() {
        val layouts = TextKeyboardLayouts()
        layouts.applySchema("rime_frost_t9")
        layouts.select(true, true)
        layouts.applySchema("wubi86")
        assertFalse(layouts.isT9(false))
        assertTrue(layouts.isT9(true))
    }

    @Test fun strokeIsSeparateFromChinesePinyinAndEnglishT9() {
        val layouts = TextKeyboardLayouts()
        layouts.select(true, true)
        layouts.applySchema(TextKeyboardLayouts.STROKE_SCHEMA_ID)
        assertTrue(layouts.isChineseStroke)
        assertFalse(layouts.isT9(false))
        assertTrue(layouts.isT9(true))
        layouts.applySchema("")
        assertTrue(layouts.isChineseStroke)
        layouts.applySchema("rime_frost_t9")
        assertFalse(layouts.isChineseStroke)
        assertTrue(layouts.isT9(false))
    }

    @Test fun fourteenKeyDoesNotReuseNineKeyState() {
        val layouts = TextKeyboardLayouts()
        layouts.select(true, true)
        layouts.applySchema(TextKeyboardLayouts.FOURTEEN_KEY_SCHEMA_ID)
        assertTrue(layouts.isChineseFourteenKey)
        assertFalse(layouts.isT9(false))
        assertFalse(layouts.isChineseStroke)
        assertTrue(layouts.isT9(true))
        layouts.applySchema(TextKeyboardLayouts.STROKE_SCHEMA_ID)
        assertFalse(layouts.isChineseFourteenKey)
        assertTrue(layouts.isChineseStroke)
    }

    @Test fun selectingChineseLayoutLeavesEnglishUnchanged() {
        val layouts = TextKeyboardLayouts()
        layouts.select(true, true)
        layouts.select(false, true)
        layouts.select(false, false)
        assertFalse(layouts.isT9(false))
        assertTrue(layouts.isT9(true))
    }
}
