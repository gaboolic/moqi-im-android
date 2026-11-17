package com.moqi.im.engine

import org.junit.Assert.*
import org.junit.Test

class CompositionCommitterTest {
    private fun result(composition: String = "", commit: String = "", success: Boolean = true, handled: Boolean = true) =
        MoqiImeResult(success, handled, composition, commit, emptyList(), emptyList(), composition.isNotEmpty(), "", "")

    @Test
    fun alreadyFinishedCompositionDoesNotRepeatPreviousCommit() {
        val output = CompositionCommitter.commit(result(commit = "你好")) { error("Must not select again") }
        assertEquals("", output.commit)
        assertEquals("", output.composition)
    }

    @Test
    fun workerSnapshotCatchesKeysWhoseUiCallbacksHaveNotArrived() {
        val output = CompositionCommitter.commit(result("ni")) { result(commit = "你") }
        assertEquals("你", output.commit)
        assertEquals("", output.composition)
    }

    @Test
    fun selectsAllSegmentsBeforeFinishingComposition() {
        val responses = ArrayDeque(listOf(result("hao", "你"), result(commit = "好")))
        val output = CompositionCommitter.commit(result("nihao")) { responses.removeFirst() }
        assertEquals("你好", output.commit)
        assertEquals("", output.composition)
        assertTrue(responses.isEmpty())
    }

    @Test
    fun selectionCanConfirmASegmentWithoutCommittingYet() {
        val responses = ArrayDeque(listOf(result("你hao"), result(commit = "你好")))
        assertEquals("你好", CompositionCommitter.commit(result("nihao")) { responses.removeFirst() }.commit)
    }

    @Test
    fun unhandledSelectionKeepsRawCompositionInsteadOfPretendingItFinished() {
        val output = CompositionCommitter.commit(result("raw")) { result(handled = false) }
        assertEquals("raw", output.composition)
        assertEquals("", output.commit)
    }

    @Test
    fun failedLaterSelectionPreservesTextAlreadyCommittedByEngine() {
        val responses = ArrayDeque(listOf(result("hao", "你"), result(success = false)))
        val output = CompositionCommitter.commit(result("nihao")) { responses.removeFirst() }
        assertEquals("你", output.commit)
        assertEquals("hao", output.composition)
    }

    @Test
    fun nonProgressingEngineStopsAndLeavesCompositionUnfinished() {
        var calls = 0
        val output = CompositionCommitter.commit(result("raw")) { calls++; result("raw") }
        assertEquals(1, calls)
        assertEquals("raw", output.composition)
    }

    @Test
    fun initialFailureDoesNotSelectOrDuplicateCommit() {
        val output = CompositionCommitter.commit(result(commit = "old", success = false)) { error("Must not select") }
        assertFalse(output.success)
        assertEquals("", output.commit)
    }
}
