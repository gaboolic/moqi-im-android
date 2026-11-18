package com.moqi.im.engine

import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque

class PinyinKeyTransactionTest {
    private fun result(handled: Boolean = false, success: Boolean = true, composition: String = "") =
        MoqiImeResult(success, handled, composition, "", emptyList(), emptyList(), false, "", "")

    @Test fun fallbackKeepsFirstLetterBeforeAlreadyQueuedFollowingLetters() {
        val queue = ArrayDeque<() -> Unit>()
        val calls = mutableListOf<String>()
        var schema = "double_pinyin_flypy"
        var text = ""
        for (letter in "ceshi") queue.add {
            PinyinKeyTransaction.run(letter.code,
                key = {
                    calls.add("$letter:$schema")
                    if (schema == "rime_frost") text += letter
                    result(schema == "rime_frost", composition = text)
                },
                currentSchema = { schema },
                selectSchema = { schema = it; result(success = true) })
        }
        while (queue.isNotEmpty()) queue.removeFirst().invoke()
        assertEquals("ceshi", text)
        assertEquals(listOf("c:double_pinyin_flypy", "c:rime_frost", "e:rime_frost",
            "s:rime_frost", "h:rime_frost", "i:rime_frost"), calls)
    }

    @Test fun handledOrComposingKeysAreNeverReplayed() {
        listOf(result(handled = true), result(composition = "ce"), result(success = false)).forEach { first ->
            var count = 0
            val outcome = PinyinKeyTransaction.run('c'.code, { count++; first },
                { error("must not inspect schema") }, { error("must not change schema") })
            assertEquals(1, count)
            assertEquals(first, outcome.result)
        }
    }

    @Test fun schemaFailureDoesNotReplayTheKey() {
        var count = 0
        val outcome = PinyinKeyTransaction.run('c'.code, { count++; result() },
            { "double_pinyin" }, { result(success = false) })
        assertEquals(1, count)
        assertFalse(outcome.result.success)
    }
}
