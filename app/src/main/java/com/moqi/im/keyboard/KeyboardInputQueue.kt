package com.moqi.im.keyboard

/** Serializes keys behind a composition commit; only a session reset may discard queued input. */
class KeyboardInputQueue {
    private val pending = ArrayDeque<() -> Unit>()
    var paused = false
        private set
    private var generation = 0L

    fun dispatch(input: () -> Unit) {
        if (paused) pending.addLast(input) else input()
    }

    fun pause(): Long {
        paused = true
        return generation
    }

    fun isCurrent(token: Long): Boolean = token == generation

    fun resume(token: Long) {
        if (!isCurrent(token)) return
        paused = false
        while (!paused && pending.isNotEmpty()) pending.removeFirst().invoke()
    }

    fun clear() {
        generation++
        paused = false
        pending.clear()
    }
}
