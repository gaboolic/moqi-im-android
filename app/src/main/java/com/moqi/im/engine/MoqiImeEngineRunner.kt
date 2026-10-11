package com.moqi.im.engine

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.moqi.im.util.ImeDebugLog
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicLong

class MoqiImeEngineRunner(
    private val androidDataDir: String,
    initialGuid: String,
    private val onInitialReady: ((String) -> Unit)? = null
) {
    data class EngineResult(
        val sequence: Long,
        val result: MoqiImeResult
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(EngineThreadFactory())
    private val requestSequence = AtomicLong(0L)
    private val generation = AtomicLong(0L)
    private val compositionCallbacks = CompositionCallbacks()

    private var session: MoqiImeSession? = null
    private var currentGuid: String = initialGuid

    init {
        val targetGeneration = generation.get()
        executor.execute {
            val newSession = MoqiImeSession(
                guid = initialGuid,
                androidDataDir = androidDataDir
            )
            session = newSession
            val schemaId = newSession.currentSchemaId()
            postIfCurrent(targetGeneration) {
                onInitialReady?.invoke(schemaId)
            }
        }
    }

    fun resetSession(guid: String, onReady: ((String) -> Unit)? = null) {
        val targetGeneration = generation.incrementAndGet()
        executor.execute {
            if (session != null && currentGuid == guid) {
                val result = session?.reset()
                val schemaId = session?.currentSchemaId().orEmpty()
                ImeDebugLog.d(TAG) { "reuse session for reset guid=$guid success=${result?.success}" }
                postIfCurrent(targetGeneration) {
                    onReady?.invoke(schemaId)
                }
                return@execute
            }
            val oldSession = session
            val newSession = MoqiImeSession(guid = guid, androidDataDir = androidDataDir)
            oldSession?.close()
            session = newSession
            currentGuid = guid
            val schemaId = newSession.currentSchemaId()
            postIfCurrent(targetGeneration) {
                onReady?.invoke(schemaId)
            }
        }
    }

    fun resetComposition(callback: ((EngineResult) -> Unit)? = null) {
        // 清空是输入边界：旧请求不得在清空后回写候选或提交到新输入框。
        compositionCallbacks.invalidate()
        submit("resetComposition", callback ?: {}) {
            session?.reset() ?: notReadyResult()
        }
    }

    fun replayText(text: String, callback: (EngineResult) -> Unit) {
        submit("replayText text=$text length=${text.length}", callback) {
            val activeSession = session ?: return@submit notReadyResult()
            activeSession.replayText(text)
        }
    }

    fun keyDown(keyCode: Int, charCode: Int = 0, callback: (EngineResult) -> Unit) {
        submit("keyDown key=$keyCode char=$charCode", callback) {
            session?.keyDown(keyCode, charCode) ?: notReadyResult()
        }
    }

    fun keyDownWithPinyinFallback(keyCode: Int, charCode: Int, callback: (EngineResult, String) -> Unit) {
        var changedSchema = ""
        submit("keyDownWithPinyinFallback key=$keyCode", { callback(it, changedSchema) }) {
            val activeSession = session ?: return@submit notReadyResult()
            val outcome = PinyinKeyTransaction.run(charCode,
                key = { activeSession.keyDown(keyCode, charCode) },
                currentSchema = { activeSession.currentSchemaId() },
                selectSchema = { activeSession.selectSchema(it) })
            changedSchema = outcome.changedSchema
            outcome.result
        }
    }

    fun selectCandidate(index: Int, callback: (EngineResult) -> Unit) {
        submit("selectCandidate index=$index", callback) {
            session?.selectCandidate(index) ?: notReadyResult()
        }
    }

    fun deleteCandidateOnCurrentPage(index: Int, callback: (EngineResult) -> Unit) {
        submit("deleteCandidateOnCurrentPage index=$index", callback) {
            session?.deleteCandidateOnCurrentPage(index) ?: notReadyResult()
        }
    }

    fun changeCandidatePage(backward: Boolean, callback: (EngineResult) -> Unit) {
        submit("changeCandidatePage backward=$backward", callback) {
            session?.changePage(backward) ?: notReadyResult()
        }
    }

    fun command(commandId: Int, callback: (EngineResult) -> Unit) {
        submit("command id=$commandId", callback) {
            session?.command(commandId) ?: notReadyResult()
        }
    }

    fun menuState(callback: (List<RimeMenuEntry>, List<String>, String, List<RimeSchemaEntry>, String) -> Unit) {
        val targetGeneration = generation.get()
        executor.execute {
            val activeSession = session
            val menuEntries = activeSession?.menuEntries().orEmpty()
            val schemeSets = activeSession?.schemeSets().orEmpty()
            val currentSchemeSet = activeSession?.currentSchemeSet().orEmpty()
            val schemas = activeSession?.schemaEntries().orEmpty()
            val schemaId = activeSession?.currentSchemaId().orEmpty()
            postIfCurrent(targetGeneration) {
                callback(menuEntries, schemeSets, currentSchemeSet, schemas, schemaId)
            }
        }
    }

    fun selectSchemeSet(name: String, callback: (EngineResult, String) -> Unit) {
        val requestId = requestSequence.incrementAndGet()
        val targetGeneration = generation.get()
        executor.execute {
            val start = System.nanoTime()
            val activeSession = session
            val result = activeSession?.selectSchemeSet(name) ?: notReadyResult()
            val schemaId = if (result.success && activeSession != null) {
                waitForSchemeSetReady(activeSession, name)
            } else {
                activeSession?.currentSchemaId().orEmpty()
            }
            logDuration("selectSchemeSet", start, "request=$requestId name=$name success=${result.success}")
            postIfCurrent(targetGeneration) {
                callback(EngineResult(requestId, result), schemaId)
            }
        }
    }

    private fun waitForSchemeSetReady(activeSession: MoqiImeSession, name: String): String {
        repeat(SCHEME_SET_READY_ATTEMPTS) {
            val currentSchemeSet = activeSession.currentSchemeSet()
            val schemas = activeSession.schemaEntries()
            if (currentSchemeSet == name && schemas.isNotEmpty()) {
                return ensureCurrentSchemaForSession(activeSession)
            }
            Thread.sleep(SCHEME_SET_READY_DELAY_MS)
        }
        return ensureCurrentSchemaForSession(activeSession)
    }

    private fun ensureCurrentSchemaForSession(activeSession: MoqiImeSession): String {
        val schemas = activeSession.schemaEntries()
        val current = activeSession.currentSchemaId()
        if (schemas.isEmpty()) {
            return current
        }
        if (schemas.any { it.id == current }) {
            return current
        }
        val target = schemas.firstOrNull { it.selected }?.id ?: schemas.first().id
        val result = activeSession.selectSchema(target)
        return if (result.success) activeSession.currentSchemaId().ifBlank { target } else current
    }

    fun selectSchema(schemaId: String, callback: (EngineResult, String) -> Unit) {
        val requestId = requestSequence.incrementAndGet()
        val targetGeneration = generation.get()
        executor.execute {
            val start = System.nanoTime()
            val activeSession = session
            val result = activeSession?.selectSchema(schemaId) ?: notReadyResult()
            val currentSchemaId = activeSession?.currentSchemaId().orEmpty()
            logDuration("selectSchema", start, "request=$requestId schemaId=$schemaId success=${result.success}")
            postIfCurrent(targetGeneration) {
                callback(EngineResult(requestId, result), currentSchemaId)
            }
        }
    }

    fun currentSchemaId(callback: (String) -> Unit) {
        val targetGeneration = generation.get()
        executor.execute {
            val schemaId = session?.currentSchemaId().orEmpty()
            postIfCurrent(targetGeneration) {
                callback(schemaId)
            }
        }
    }

    fun close() {
        generation.incrementAndGet()
        executor.execute {
            session?.close()
            session = null
        }
        executor.shutdown()
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun submit(
        label: String,
        callback: (EngineResult) -> Unit,
        action: () -> MoqiImeResult
    ) {
        val requestId = requestSequence.incrementAndGet()
        val targetGeneration = generation.get()
        val deliver = compositionCallbacks.capture(callback)
        executor.execute {
            val start = System.nanoTime()
            val result = action()
            logDuration(label, start, "request=$requestId success=${result.success} handled=${result.handled}")
            postIfCurrent(targetGeneration) {
                deliver(EngineResult(requestId, result))
            }
        }
    }

    private fun postIfCurrent(targetGeneration: Long, action: () -> Unit) {
        mainHandler.post {
            if (generation.get() == targetGeneration) {
                action()
            } else {
                ImeDebugLog.d(TAG) {
                    "discard stale engine callback generation=$targetGeneration current=${generation.get()}"
                }
            }
        }
    }

    private fun notReadyResult(): MoqiImeResult {
        return MoqiImeResult(
            success = false,
            handled = false,
            composition = "",
            commit = "",
            candidates = emptyList(),
            candidateEntries = emptyList(),
            showCandidates = false,
            message = "",
            error = "moqi-ime engine is not ready"
        )
    }

    private fun logDuration(operation: String, startNanos: Long, details: String) {
        ImeDebugLog.duration(TAG, operation, startNanos, SLOW_LOG_THRESHOLD_MS) { details }
    }

    private class EngineThreadFactory : ThreadFactory {
        override fun newThread(runnable: Runnable): Thread {
            return Thread(runnable, "moqi-ime-engine").apply {
                isDaemon = true
            }
        }
    }

    companion object {
        private const val TAG = "MoqiImeEngineRunner"
        private const val SLOW_LOG_THRESHOLD_MS = 30L
        private const val SCHEME_SET_READY_ATTEMPTS = 120
        private const val SCHEME_SET_READY_DELAY_MS = 150L
    }
}
