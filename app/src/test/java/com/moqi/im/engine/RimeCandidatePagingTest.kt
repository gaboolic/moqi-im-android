package com.moqi.im.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Model the ACTUAL bundled librime contract, not `handled == page changed`:
 * RimeChangePage highlights selected_index +/- page_size. Context::Highlight
 * clamps to the last candidate and reports whether the highlight changed.
 * Thus it can return true without changing the current page. The patched Go
 * bridge must translate that into handled=false by comparing actual PageNo.
 */
class RimeCandidatePagingTest {
    @Test
    fun nirSecondPageRareCharacterDoesNotSelectFirstPageNi() {
        val engine = RimeHighlightEngine(total = 12, pageSize = 5)
        engine.texts[2] = "你"
        engine.texts[7] = "𠉎"
        val pager = engine.pager()
        prefetch(pager)

        assertEquals("𠉎", pager.selectCandidate(1, 2).commit)
    }

    @Test
    fun oldHighlightBooleanContractReproducesReportedNiInsteadOfRareCharacter() {
        val engine = RimeHighlightEngine(total = 12, pageSize = 5)
        engine.texts[2] = "你"
        engine.texts[7] = "𠉎"
        val pager = engine.pager(enforceActualPageContract = false)
        prefetch(pager)
        assertEquals("你", pager.selectCandidate(1, 2).commit)
    }

    @Test
    fun partialLastPageDoesNotBecomeAnExtraCachedPage() {
        val engine = RimeHighlightEngine(total = 12, pageSize = 5)
        assertEquals(2, prefetch(engine.pager()).size)
    }

    @Test
    fun fullLastPageAlsoClampsWithinSamePage() {
        val engine = RimeHighlightEngine(total = 15, pageSize = 5)
        assertEquals(2, prefetch(engine.pager()).size)
    }

    @Test
    fun singlePageHasNoSecondPageEvenWhenHighlightMoves() {
        val engine = RimeHighlightEngine(total = 4, pageSize = 5)
        val pager = engine.pager()
        assertFalse(pager.loadPage(1).handled)
        assertEquals(0, engine.selectedIndex / engine.pageSize)
        assertEquals("candidate-2", pager.selectCandidate(0, 2).commit)
    }

    @Test
    fun identicalCandidateTextAcrossPagesIsNotMistakenForNoPageChange() {
        val engine = RimeHighlightEngine(total = 15, pageSize = 5)
        engine.texts.indices.forEach { engine.texts[it] = "同" }
        val pager = engine.pager()
        assertTrue(pager.loadPage(1).handled)
        assertEquals(1, engine.selectedIndex / engine.pageSize)
        assertTrue(pager.loadPage(2).handled)
        assertEquals(2, engine.selectedIndex / engine.pageSize)
        assertFalse(pager.loadPage(3).handled)
    }

    @Test
    fun clampingOntoANewPartialPageIsStillARealPageTurn() {
        val engine = RimeHighlightEngine(total = 7, pageSize = 5)
        engine.selectedIndex = 3
        val pager = engine.pager()
        assertTrue(pager.loadPage(1).handled)
        assertEquals(1, engine.selectedIndex / engine.pageSize)
        assertEquals(1, engine.snapshot().highlightedCandidateIndex)
        assertEquals("candidate-6", pager.selectCandidate(1, 1).commit)
    }

    private fun prefetch(pager: CandidatePager): List<List<String>> {
        val pages = mutableListOf<List<String>>()
        // Only additional pages: the view already owns the base-page snapshot.
        for (page in 1..10) {
            val result = pager.loadPage(page)
            if (!result.handled) break
            pages += result.candidates
        }
        return pages
    }

    @Test
    fun visibleCursorClippingDoesNotHideAPhantomPageFromBackendPageNumberCheck() {
        val engine = RimeHighlightEngine(total = 18, pageSize = 9, visibleCount = 5)
        engine.selectedIndex = 4
        val pager = engine.pager()
        assertTrue(pager.loadPage(1).handled)
        assertEquals(4, engine.snapshot().highlightedCandidateIndex)
        assertFalse(pager.loadPage(2).handled)
        // Both visible cursors are 4: comparing cursors or text is not the fix.
        assertEquals(4, engine.snapshot().highlightedCandidateIndex)
    }

    private class RimeHighlightEngine(val total: Int, val pageSize: Int, val visibleCount: Int = pageSize) {
        var selectedIndex = 0
        val texts = MutableList(total) { "candidate-$it" }

        fun snapshot(handled: Boolean = true, commit: String = ""): MoqiImeResult {
            val start = selectedIndex / pageSize * pageSize
            val candidates = texts.subList(start, minOf(start + pageSize, start + visibleCount, total)).toList()
            return MoqiImeResult(
                success = true, handled = handled, composition = "nir", commit = commit,
                candidates = candidates, candidateEntries = candidates.map { CandidateEntry(it, "") },
                showCandidates = true, message = "", error = "",
                highlightedCandidateIndex = (selectedIndex % pageSize).coerceAtMost(candidates.lastIndex),
            )
        }

        fun pager(enforceActualPageContract: Boolean = true): CandidatePager {
            val pager = CandidatePager(
                changePage = { backward ->
                    val beforePage = selectedIndex / pageSize
                    val requested = if (backward) {
                        if (selectedIndex <= pageSize) 0 else selectedIndex - pageSize
                    } else selectedIndex + pageSize
                    val next = requested.coerceAtMost(total - 1)
                    val changedHighlight = next != selectedIndex
                    selectedIndex = next
                    snapshot(handled = if (enforceActualPageContract) {
                        selectedIndex / pageSize != beforePage
                    } else changedHighlight)
                },
                select = { localIndex ->
                    val absoluteIndex = selectedIndex / pageSize * pageSize + localIndex
                    snapshot(commit = texts[absoluteIndex])
                },
            )
            pager.onBasePage { snapshot() }
            return pager
        }
    }
}
