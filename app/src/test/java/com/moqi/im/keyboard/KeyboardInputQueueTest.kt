package com.moqi.im.keyboard

import org.junit.Assert.*
import org.junit.Test

class KeyboardInputQueueTest {
    @Test
    fun consecutiveSymbolsAndNewT9DigitsWaitForEarlierCommit() {
        val queue = KeyboardInputQueue()
        val editor = StringBuilder()
        val completions = ArrayDeque<() -> Unit>()
        var digits = "64"
        fun symbol(text: String) = queue.dispatch {
            val token = queue.pause()
            completions.addLast {
                if (queue.isCurrent(token)) {
                    if (digits == "64") editor.append("你")
                    digits = ""
                    editor.append(text)
                    queue.resume(token)
                }
            }
        }
        symbol("！")
        symbol("？")
        queue.dispatch { digits += "4" }
        assertEquals("", editor.toString())
        assertEquals("64", digits)
        completions.removeFirst().invoke()
        assertEquals("你！", editor.toString())
        assertEquals("", digits)
        completions.removeFirst().invoke()
        assertEquals("你！？", editor.toString())
        assertEquals("4", digits)
        assertFalse(queue.paused)
    }

    @Test
    fun sessionResetInvalidatesCommitAndDropsQueuedOldInput() {
        val queue = KeyboardInputQueue()
        val token = queue.pause()
        var ran = false
        queue.dispatch { ran = true }
        queue.clear()
        assertFalse(queue.isCurrent(token))
        queue.resume(token)
        assertFalse(ran)
        queue.dispatch { ran = true }
        assertTrue(ran)
    }

    @Test
    fun queuedModeSwitchCanInvalidateTokenWithoutDroppingLaterKeys() {
        val queue = KeyboardInputQueue()
        val token = queue.pause()
        val output = StringBuilder()
        queue.dispatch { if (queue.paused) queue.clear(); output.append("EN:") }
        queue.dispatch { output.append("a") }
        queue.resume(token)
        assertEquals("EN:a", output.toString())
    }
}
