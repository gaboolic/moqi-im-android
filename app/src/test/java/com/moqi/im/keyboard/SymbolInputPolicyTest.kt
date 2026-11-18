package com.moqi.im.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.moqi.im.keyboard.SymbolInputPolicy.Route

class SymbolInputPolicyTest {
    @Test
    fun chineseSlashAlwaysReachesEngineBeforePanelOrT9Commit() {
        for (panel in listOf(false, true)) {
            for (t9 in listOf(false, true)) {
                for (composing in listOf(false, true)) {
                    assertEquals(
                        SymbolInputPolicy.Input("/", Route.ENGINE),
                        SymbolInputPolicy.resolve("/", false, panel, t9, composing)
                    )
                }
            }
        }
    }

    @Test
    fun englishSlashRemainsLiteral() {
        assertEquals(Route.DIRECT, SymbolInputPolicy.resolve("/", true, false, false).route)
        assertEquals(Route.DIRECT, SymbolInputPolicy.resolve("/", true, true, false).route)
    }

    @Test
    fun allAsciiPunctuationUsesEngineOutsideLiteralPanels() {
        val punctuation = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"
        for (ch in punctuation) {
            // Colon and semicolon on the Chinese text keyboard intentionally request full-width output.
            val expected = if (ch == ':' || ch == ';') Route.DIRECT else Route.ENGINE
            assertEquals("character=$ch", expected, SymbolInputPolicy.resolve(ch.toString(), false, false, false).route)
        }
    }

    @Test
    fun englishPunctuationIsCompleteAndMatchesDisplayedT9Labels() {
        val expected = listOf(",", ".", "?", ":", "!", "...", ";", "/", ".", "-", "@")
        assertEquals(expected, SymbolInputPolicy.t9Symbols(true))
        for (symbol in expected) {
            assertEquals(SymbolInputPolicy.Input(symbol, Route.DIRECT), SymbolInputPolicy.resolve(symbol, true, false, false))
        }
        val chinese = listOf("，", "。", "？", "：", "！", "…", "；", "、", ".", "-", "@")
        assertEquals(chinese, SymbolInputPolicy.t9Symbols(false))
        assertEquals(expected, chinese.map { SymbolInputPolicy.resolve(it, true, false, false).text })
    }

    @Test
    fun explicitPanelSelectionsKeepTheirExactTextInEitherMode() {
        for (english in listOf(false, true)) {
            for (text in listOf(":", ";", "，", "。", "？", "！", "“”", "（）", "……", "https://", "😀", "(╯°□°）╯")) {
                assertEquals(SymbolInputPolicy.Input(text, Route.DIRECT), SymbolInputPolicy.resolve(text, english, true, false))
            }
        }
    }

    @Test
    fun unicodeTextIsNotSentAsARimeVirtualKey() {
        for (text in listOf("！", "？", "，", "。", "…", "、", "《", "￥")) {
            assertEquals(SymbolInputPolicy.Input(text, Route.DIRECT), SymbolInputPolicy.resolve(text, false, false, false))
        }
    }

    @Test
    fun normalT9SymbolsFinishCurrentWordButSlashDoesNot() {
        for (text in listOf("！", "？", "，", ".", "@", "“”", "（）")) {
            assertEquals(Route.COMMIT_COMPOSITION_THEN_TEXT, SymbolInputPolicy.resolve(text, false, false, true).route)
        }
        assertEquals(Route.ENGINE, SymbolInputPolicy.resolve("/", false, false, true).route)
    }

    @Test
    fun literalTextFinishesExistingCompositionBeforeInsertion() {
        for (panel in listOf(false, true)) {
            for (text in listOf("！", "？", "“”", "（）", "……")) {
                assertEquals(Route.COMMIT_COMPOSITION_THEN_TEXT, SymbolInputPolicy.resolve(text, false, panel, false, true).route)
            }
        }
        assertEquals(Route.ENGINE, SymbolInputPolicy.resolve("!", false, false, false, true).route)
    }

    @Test
    fun pairedSwipeSymbolsKeepCursorPlacementBehavior() {
        assertEquals(SymbolInputPolicy.Input("“”", Route.PAIRED), SymbolInputPolicy.resolve("\"\"", false, false, false))
        assertEquals(SymbolInputPolicy.Input("（）", Route.PAIRED), SymbolInputPolicy.resolve("()", false, false, false))
        assertEquals(SymbolInputPolicy.Input("\"\"", Route.PAIRED), SymbolInputPolicy.resolve("“”", true, false, false))
        assertEquals(SymbolInputPolicy.Input("()", Route.PAIRED), SymbolInputPolicy.resolve("（）", true, false, false))
        assertTrue(SymbolInputPolicy.isPaired("“”"))
    }
}
