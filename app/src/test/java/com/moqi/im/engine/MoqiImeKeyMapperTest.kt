package com.moqi.im.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class MoqiImeKeyMapperTest {
    @Test
    fun punctuationUsesCharCodeWithoutCollidingWithWindowsVirtualKeys() {
        // Regressions include ! -> PageUp, # -> End, % -> Left, * -> Print,
        // + -> Execute, - -> Insert, . -> Delete and / -> Help.
        for (ch in "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~") {
            assertEquals("character=$ch", 0 to ch.code, MoqiImeKeyMapper.symbol(ch))
        }
    }

    @Test
    fun colonAndTildeDoNotUseOemKeysThatOverrideTheirCharCode() {
        assertEquals(0 to ':'.code, MoqiImeKeyMapper.symbol(':'))
        assertEquals(0 to '~'.code, MoqiImeKeyMapper.symbol('~'))
        assertEquals(0 to '/'.code, MoqiImeKeyMapper.symbol('/'))
        assertEquals(0 to '.'.code, MoqiImeKeyMapper.symbol('.'))
    }
}
