package com.moqi.im.engine

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.ArrayDeque

class CompositionCallbacksTest {
    @Test fun clearRejectsResultAlreadyWaitingOnUiQueue() {
        val gate = CompositionCallbacks()
        val ui = ArrayDeque<() -> Unit>()
        val shown = mutableListOf<String>()
        val old = gate.capture<String> { shown.add(it) }
        ui.add { old("old commit") }
        gate.invalidate()
        val fresh = gate.capture<String> { shown.add(it) }
        ui.add { fresh("new composition") }
        while (ui.isNotEmpty()) ui.removeFirst().invoke()
        assertEquals(listOf("new composition"), shown)
    }

    @Test fun clearRejectsInFlightWorkWithoutDroppingNewKeys() {
        val gate = CompositionCallbacks()
        val worker = ArrayDeque<() -> Unit>()
        val ui = ArrayDeque<() -> Unit>()
        val shown = mutableListOf<String>()
        fun submit(text: String) {
            val deliver = gate.capture<String> { shown.add(it) }
            worker.add { ui.add { deliver(text) } }
        }
        submit("old")
        gate.invalidate()
        submit("n")
        submit("ni")
        while (worker.isNotEmpty()) worker.removeFirst().invoke()
        while (ui.isNotEmpty()) ui.removeFirst().invoke()
        assertEquals(listOf("n", "ni"), shown)
    }
}
