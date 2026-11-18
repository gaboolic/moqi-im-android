package com.moqi.im.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidatePagerTest {
    @Test
    fun selectionUsesEnginePageEvenBeforePrefetchCallbackReachesUi() {
        val engine = FakeEngine()
        val pager = engine.pager()
        // The worker already advanced, but the UI still has page 0 and has not
        // processed the load callback. Selection must not trust the UI counter.
        val delayedLoadResult = pager.loadPage(1)
        assertEquals("page1-item0", delayedLoadResult.candidates.first())

        val selected = pager.selectCandidate(0, 2)

        assertEquals("page0-item2", selected.commit)
        assertEquals(listOf("next:1", "previous:0", "select:0:2"), engine.operations)
    }

    @Test
    fun selectionNavigatesAndSelectsWithoutIntermediateUiCallbacks() {
        val engine = FakeEngine()
        val pager = engine.pager()
        (1..5).forEach { pager.loadPage(it) }
        engine.operations.clear()

        assertEquals("page2-item1", pager.selectCandidate(2, 1).commit)
        assertEquals(
            listOf("previous:4", "previous:3", "previous:2", "select:2:1"),
            engine.operations,
        )
    }

    @Test
    fun collapsedCandidateBarSelectsOriginalPageAfterFivePrefetchedPages() {
        val engine = FakeEngine()
        val pager = engine.pager()
        (1..5).forEach { pager.loadPage(it) }
        // Collapsing only restores the cached base-page UI. A normal selection
        // explicitly targets that base page, regardless of the prefetched page.
        assertEquals("page0-item1", pager.selectCandidate(0, 1).commit)
    }

    @Test
    fun reopeningUsesAbsoluteCachedPageRatherThanIncrementingEnginePage() {
        val engine = FakeEngine()
        val pager = engine.pager()
        (1..5).forEach { pager.loadPage(it) }
        // Reopened UI requests its page 1 again, with the engine still at 5.
        assertEquals("page1-item0", pager.loadPage(1).candidates.first())
        assertEquals(1, engine.page)
        assertEquals("page1-item2", pager.selectCandidate(1, 2).commit)
    }

    @Test
    fun repeatedPageRequestReturnsTheSameSnapshotWithoutAnotherPageTurn() {
        val engine = FakeEngine()
        val pager = engine.pager()
        val first = pager.loadPage(1)
        val operations = engine.operations.toList()
        assertEquals(first, pager.loadPage(1))
        assertEquals(operations, engine.operations)
    }

    @Test
    fun spaceOrOtherNormalActionRunsOnCandidateBarPageNotPrefetchedPage() {
        val engine = FakeEngine()
        val pager = engine.pager()
        pager.loadPage(3)
        val result = pager.onBasePage { engine.select(0) }
        assertEquals("page0-item0", result.commit)
    }

    @Test
    fun inputRefreshRebasesPagingForNewCandidates() {
        val engine = FakeEngine()
        val pager = engine.pager()
        pager.loadPage(3)
        pager.onBasePage {
            assertEquals(0, engine.page)
            engine.page = 0
            engine.prefix = "new-"
            engine.result()
        }
        assertEquals("new-page1-item0", pager.loadPage(1).candidates.first())
        assertEquals("new-page0-item2", pager.selectCandidate(0, 2).commit)
    }

    @Test
    fun endOfCandidatesDoesNotAdvanceTrackedPage() {
        val engine = FakeEngine(lastPage = 2)
        val pager = engine.pager()
        pager.loadPage(2)
        assertFalse(pager.loadPage(3).handled)
        assertEquals("page1-item0", pager.selectCandidate(1, 0).commit)
        assertEquals(1, engine.operations.count { it == "previous:1" })
    }

    @Test
    fun failedNavigationNeverSelectsSameIndexOnWrongPage() {
        val engine = FakeEngine()
        val pager = engine.pager()
        pager.loadPage(2)
        engine.failBackward = true

        val result = pager.selectCandidate(0, 1)

        assertFalse(result.success)
        assertFalse(result.handled)
        assertEquals("", result.commit)
        assertTrue(engine.operations.none { it.startsWith("select:") })
        // Failed results are not applied by the UI. Retrying still targets the
        // original base, and must not mistake the failed page for the UI base.
        engine.failBackward = false
        assertEquals("page0-item1", pager.selectCandidate(0, 1).commit)
    }

    @Test
    fun partiallyCompletedNavigationKeepsActualOffsetAfterFailure() {
        val engine = FakeEngine()
        val pager = engine.pager()
        pager.loadPage(3)
        engine.failBackwardAt = 1
        assertFalse(pager.selectCandidate(0, 0).success)
        assertEquals(1, engine.page)
        engine.failBackwardAt = null
        assertEquals("page0-item0", pager.selectCandidate(0, 0).commit)
    }

    @Test
    fun selectionFailureDoesNotRebaseTheUnchangedUi() {
        val engine = FakeEngine()
        val pager = engine.pager()
        pager.loadPage(4)
        engine.failSelection = true
        assertFalse(pager.selectCandidate(2, 0).success)
        engine.failSelection = false
        assertEquals("page0-item1", pager.selectCandidate(0, 1).commit)
    }

    @Test
    fun unhandledNavigationAlsoAbortsSelection() {
        val engine = FakeEngine(lastPage = 1)
        val pager = engine.pager()
        val result = pager.selectCandidate(2, 0)
        assertFalse(result.handled)
        assertTrue(engine.operations.none { it.startsWith("select:") })
    }

    @Test
    fun resetDropsOffsetFromPreviousComposition() {
        val engine = FakeEngine()
        val pager = engine.pager()
        pager.loadPage(4)
        engine.page = 0
        pager.reset()
        assertEquals("page1-item0", pager.loadPage(1).candidates.first())
    }

    @Test
    fun partialSelectionStartsNewBaseForRemainingComposition() {
        val engine = FakeEngine()
        val pager = engine.pager(partialSelection = true)
        pager.loadPage(4)
        val result = pager.selectCandidate(2, 0)
        assertTrue(result.showCandidates)
        assertEquals("remaining-page0-item0", result.candidates.first())
        assertEquals("remaining-page1-item0", pager.loadPage(1).candidates.first())
        assertEquals("remaining-page0-item1", pager.selectCandidate(0, 1).commit)
    }

    private class FakeEngine(private val lastPage: Int = 8) {
        var page = 0
        var prefix = ""
        var failBackward = false
        var failBackwardAt: Int? = null
        var failSelection = false
        val operations = mutableListOf<String>()

        fun pager(partialSelection: Boolean = false) = CandidatePager(
            changePage = { backward ->
                when {
                    backward && (failBackward || page == failBackwardAt) ->
                        result().copy(success = false, handled = false, error = "page failure")
                    (backward && page == 0) || (!backward && page == lastPage) -> result().copy(handled = false)
                    else -> {
                        page += if (backward) -1 else 1
                        operations += "${if (backward) "previous" else "next"}:$page"
                        result()
                    }
                }
            },
            select = { index ->
                if (failSelection) {
                    result().copy(success = false, handled = false, error = "select failure")
                } else {
                    val selected = select(index)
                    if (partialSelection) {
                        prefix = "remaining-"
                        result().copy(commit = selected.commit)
                    } else selected
                }
            },
        )

        fun result(): MoqiImeResult {
            val candidates = (0..2).map { "${prefix}page$page-item$it" }
            return MoqiImeResult(
                success = true,
                handled = true,
                composition = "pin'yin",
                commit = "",
                candidates = candidates,
                candidateEntries = candidates.map { CandidateEntry(it, "") },
                showCandidates = true,
                message = "",
                error = "",
            )
        }

        fun select(index: Int): MoqiImeResult {
            operations += "select:$page:$index"
            val commit = result().candidates[index]
            page = 0
            return result().copy(
                composition = "", commit = commit,
                candidates = emptyList(), candidateEntries = emptyList(), showCandidates = false,
            )
        }
    }
}
