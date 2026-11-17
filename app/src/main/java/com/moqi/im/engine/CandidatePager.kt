package com.moqi.im.engine

/**
 * Engine-thread-only paging state, relative to the last result published as the candidate bar.
 * UI callbacks must never be used to track the engine's current page: they can lag behind it.
 */
internal class CandidatePager(
    private val changePage: (backward: Boolean) -> MoqiImeResult,
    private val select: (index: Int) -> MoqiImeResult,
) {
    private var pageIndex = 0
    private var pageResult: MoqiImeResult? = null

    fun reset() {
        pageIndex = 0
        pageResult = null
    }

    private fun rebase(result: MoqiImeResult): MoqiImeResult {
        // Failed results are not applied by the UI, so its original base stays valid.
        if (result.success) {
            pageIndex = 0
            pageResult = result
        }
        return result
    }

    /** Returns a failure rather than selecting on a different page if navigation fails. */
    private fun moveTo(targetPageIndex: Int): MoqiImeResult? {
        require(targetPageIndex >= 0)
        while (pageIndex != targetPageIndex) {
            val backward = targetPageIndex < pageIndex
            val result = changePage(backward)
            if (!result.success || !result.handled || !result.showCandidates || result.candidateEntries.isEmpty()) {
                return result.copy(handled = false)
            }
            pageIndex += if (backward) -1 else 1
            pageResult = result
        }
        return null
    }

    fun loadPage(targetPageIndex: Int): MoqiImeResult {
        moveTo(targetPageIndex)?.let { return it }
        return checkNotNull(pageResult) { "No candidate result available for page $targetPageIndex" }
    }

    /** Execute navigation and selection in ONE executor task, with no UI round trips. */
    fun selectCandidate(targetPageIndex: Int, index: Int): MoqiImeResult {
        moveTo(targetPageIndex)?.let { return rebase(it) }
        return rebase(select(index))
    }

    /** Normal keys/commands must operate on the candidate bar, not the last prefetched page. */
    fun onBasePage(action: () -> MoqiImeResult): MoqiImeResult {
        moveTo(0)?.let { return rebase(it) }
        return rebase(action())
    }
}
