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
        layouts.applySchema("rime_frost_stroke_t9")
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
        layouts.applySchema("rime_frost_14key")
        assertTrue(layouts.isChineseFourteenKey)
        assertFalse(layouts.isT9(false))
        assertFalse(layouts.isChineseStroke)
        assertTrue(layouts.isT9(true))
        layouts.applySchema("rime_frost_stroke_t9")
        assertFalse(layouts.isChineseFourteenKey)
        assertTrue(layouts.isChineseStroke)
    }

    @Test fun strokeMarkerTakesPriorityOverGenericNineKeyMarker() {
        val layouts = TextKeyboardLayouts()
        for (schema in listOf("custom_stroke_t9", "custom_STROKE_T9_variant")) {
            layouts.applySchema(schema)
            assertTrue(schema, layouts.isChineseStroke)
            assertFalse(schema, layouts.isT9(false))
        }
        layouts.applySchema("custom_t9")
        assertFalse(layouts.isChineseStroke)
        assertTrue(layouts.isT9(false))
    }

    @Test fun anySchemaIdContainingFourteenKeyMarkerUsesTheLayout() {
        val layouts = TextKeyboardLayouts()
        for (schema in listOf("moqi_wan_flypymo_14key", "example_14KEY_custom", "example_t9_14key")) {
            layouts.applySchema(schema)
            assertTrue(schema, layouts.isChineseFourteenKey)
            assertFalse(schema, layouts.isT9(false))
        }
        layouts.applySchema("moqi_wan_flypymo")
        assertFalse(layouts.isChineseFourteenKey)
        assertFalse(layouts.isT9(false))
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
