package com.moqi.im.engine

/** Selects through segmented candidates before literal text is inserted. Runs on the engine thread. */
internal object CompositionCommitter {
    fun commit(initial: MoqiImeResult?, selectFirst: () -> MoqiImeResult): MoqiImeResult {
        if (initial != null && (!initial.success || initial.composition.isBlank())) {
            return initial.copy(commit = "")
        }
        val committed = StringBuilder()
        var previous: MoqiImeResult? = initial
        repeat(64) {
            val result = selectFirst()
            if (!result.success) {
                // Earlier selections may already have committed text. Never discard that payload.
                return previous?.copy(commit = committed.toString(), error = result.error) ?: result
            }
            if (!result.handled && result.commit.isEmpty() && previous != null) {
                return checkNotNull(previous).copy(commit = committed.toString())
            }
            committed.append(result.commit)
            if (result.composition.isBlank() ||
                (result.commit.isEmpty() && result.composition == previous?.composition)) {
                return result.copy(commit = committed.toString())
            }
            previous = result
        }
        // A malformed/non-progressing engine must not keep the worker in an infinite loop.
        return checkNotNull(previous).copy(commit = committed.toString())
    }
}
