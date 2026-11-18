package com.moqi.im.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class T9MultiTapTest {
    @Test fun changingKeysPreservesEachFirstTap() {
        val state = T9MultiTap()
        assertNull(state.press(2, "2abc"))
        assertEquals("2", state.press(3, "3def"))
        assertEquals("3", state.press(4, "4ghi"))
        assertEquals("4", state.take())
        assertNull(state.take())
    }

    @Test fun repeatedKeySelectsLettersAndStartsNextCharacterAfterLastLetter() {
        val state = T9MultiTap()
        repeat(4) { assertNull(state.press(2, "2abc")) }
        assertEquals("c", state.press(2, "2abc"))
        assertEquals("2", state.take())
    }

    @Test fun timeoutCommitsOnlyOnceAndNextTapStartsAtFirstCharacter() {
        val state = T9MultiTap()
        repeat(2) { state.press(2, "2abc") }
        assertEquals("a", state.take())
        assertNull(state.take())
        assertNull(state.press(2, "2abc"))
        assertEquals("2", state.take())
    }

    @Test fun resetDropsPendingInputAndResetsCycle() {
        val state = T9MultiTap()
        repeat(3) { state.press(2, "2abc") }
        state.clear()
        assertNull(state.take())
        assertNull(state.press(2, "2abc"))
        assertEquals("2", state.take())
    }
}
