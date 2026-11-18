package com.moqi.im.engine

import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class MoqiImeEngineRunnerTest {
    @Test fun resetDropsOldWorkerAndUiCallbacksButKeepsInitialReadyAndFreshInput() {
        val worker = ManualEngineExecutor()
        val session = FakeEngineSession("rime_frost")
        val ready = mutableListOf<String>()
        val delivered = mutableListOf<String>()
        val runner = MoqiImeEngineRunner("unused", "test", { ready += it }, worker) { _, _ -> session }
        runner.keyDown(0, 'a'.code) { delivered += "old-ui" }
        worker.drain() // initialReady + old result now waiting on UI Handler.
        runner.keyDown(0, 'b'.code) { delivered += "old-worker" }
        runner.resetComposition { delivered += "reset" }
        runner.keyDown(0, 'c'.code) { delivered += it.result.composition }
        worker.drain()
        assertTrue(delivered.isEmpty())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("rime_frost"), ready)
        assertEquals(listOf("reset", "c"), delivered)
        assertEquals(listOf("key:a:rime_frost", "key:b:rime_frost", "reset", "key:c:rime_frost"), session.calls)
        runner.close(); worker.drain()
    }

    @Test fun fallbackFirstKeyIsAtomicEvenWithFollowingKeysAlreadyQueued() {
        val worker = ManualEngineExecutor()
        val session = FakeEngineSession("double_pinyin_flypy")
        val runner = MoqiImeEngineRunner("unused", "test", null, worker) { _, _ -> session }
        val results = mutableListOf<String>()
        val schemas = mutableListOf<String>()
        "ceshi".forEach { letter ->
            runner.keyDownWithPinyinFallback(0, letter.code) { result, schema ->
                results += result.result.composition
                if (schema.isNotEmpty()) schemas += schema
            }
        }
        worker.drain()
        assertEquals("ceshi", session.text)
        assertTrue(results.isEmpty())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("c", "ce", "ces", "cesh", "ceshi"), results)
        assertEquals(listOf("rime_frost"), schemas)
        assertEquals(listOf("key:c:double_pinyin_flypy", "schema:rime_frost", "key:c:rime_frost",
            "key:e:rime_frost", "key:s:rime_frost", "key:h:rime_frost", "key:i:rime_frost"), session.calls)
        runner.close(); worker.drain()
    }

    @Test fun fallbackUsesBasePageAndCommitUsesLatestWorkerResultBeforeUiDelivery() {
        val worker = ManualEngineExecutor()
        val session = object : FakeEngineSession("rime_frost") {
            override fun keyDown(keyCode: Int, charCode: Int) =
                super.keyDown(keyCode, charCode).copy(candidates = listOf("候选"),
                    candidateEntries = listOf(CandidateEntry("候选", "")), showCandidates = true)
            override fun changePage(backward: Boolean) =
                engineResult(candidates = listOf("候选")).also { calls += "page:$backward" }
        }
        val runner = MoqiImeEngineRunner("unused", "test", null, worker) { _, _ -> session }
        val commits = mutableListOf<String>()
        runner.keyDown(0, 'a'.code) {}
        runner.loadCandidatePage(2) {}
        runner.keyDownWithPinyinFallback(0, 'b'.code) { _, _ -> }
        runner.commitComposition { commits += it.result.commit }
        worker.drain()
        assertTrue(commits.isEmpty())
        assertEquals(listOf("key:a:rime_frost", "page:false", "page:false", "page:true", "page:true",
            "key:b:rime_frost", "select:0"), session.calls)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("ab"), commits)
        runner.close(); worker.drain()
    }

    @Test fun successfulResetClearsPagingWithoutNavigatingOldPagesBeforeFreshKey() {
        val worker = ManualEngineExecutor()
        val session = object : FakeEngineSession("rime_frost") {
            override fun changePage(backward: Boolean) =
                engineResult(candidates = listOf("候选")).also { calls += "page:$backward" }
        }
        val runner = MoqiImeEngineRunner("unused", "test", null, worker) { _, _ -> session }
        runner.keyDown(0, 'a'.code) {}
        runner.loadCandidatePage(2) {}
        runner.resetComposition()
        runner.keyDown(0, 'c'.code) {}
        worker.drain()
        assertEquals(listOf("key:a:rime_frost", "page:false", "page:false", "reset", "key:c:rime_frost"), session.calls)
        assertEquals("c", session.text)
        shadowOf(Looper.getMainLooper()).idle()
        runner.close(); worker.drain()
    }
}
