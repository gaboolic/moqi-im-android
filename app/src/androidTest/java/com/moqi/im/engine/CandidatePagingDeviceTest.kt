package com.moqi.im.engine

import android.test.InstrumentationTestCase
import android.util.Log
import com.moqi.im.moqiAndroidDataDir
import mobilebridge.Mobilebridge

/** Uses the real bundled AAR and the device's deployed Rime schema, not a fake engine. */
@Suppress("DEPRECATION")
class CandidatePagingDeviceTest : InstrumentationTestCase() {
    fun testNirExpandedRareCharacterMatchesCommittedText() {
        val context = instrumentation.targetContext
        val session = MoqiImeSession(
            guid = Mobilebridge.GUIDRime,
            androidDataDir = context.moqiAndroidDataDir().absolutePath,
        )
        try {
            val initial = session.replayText("nir")
            assertTrue("Rime replay failed: ${initial.error}", initial.success)
            assertTrue("nir has no candidates, schema=${session.currentSchemaId()}", initial.showCandidates)
            val pager = CandidatePager(session::changePage, session::selectCandidate)
            pager.onBasePage { initial }
            val pages = mutableListOf(initial.candidateEntries)
            for (pageIndex in 1..5) {
                val result = pager.loadPage(pageIndex)
                assertTrue("Page load failed: ${result.error}", result.success)
                Log.i(TAG, "page=$pageIndex handled=${result.handled} cursor=${result.highlightedCandidateIndex} " +
                    "candidates=${result.candidateEntries.map { it.text }}")
                if (!result.handled) break
                pages += result.candidateEntries
            }
            val pageIndex = pages.indexOfFirst { entries -> entries.any { it.text == RARE_NI } }
            assertTrue("Schema ${session.currentSchemaId()} must expose $RARE_NI for nir: $pages", pageIndex >= 0)
            val localIndex = pages[pageIndex].indexOfFirst { it.text == RARE_NI }
            val selected = pager.selectCandidate(pageIndex, localIndex)
            Log.i(TAG, "selected page=$pageIndex local=$localIndex expected=$RARE_NI actual=${selected.commit}")
            assertTrue("Candidate selection failed: ${selected.error}", selected.success)
            assertEquals("Displayed candidate must match real native commit", RARE_NI, selected.commit)
        } finally {
            session.close()
        }
    }

    companion object {
        private const val RARE_NI = "𠉎"
        private const val TAG = "CandidatePagingDevice"
    }
}
