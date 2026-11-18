package com.moqi.im.engine

import java.util.ArrayDeque
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

/** Deterministic worker queue; the real runner still posts through Android's UI Handler. */
internal class ManualEngineExecutor : AbstractExecutorService() {
    private val tasks = ArrayDeque<Runnable>()
    private var stopped = false
    override fun execute(command: Runnable) { check(!stopped); tasks.add(command) }
    fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    override fun shutdown() { stopped = true }
    override fun shutdownNow(): MutableList<Runnable> = tasks.toMutableList().also { tasks.clear(); stopped = true }
    override fun isShutdown() = stopped
    override fun isTerminated() = stopped && tasks.isEmpty()
    override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated
}

internal fun engineResult(
    handled: Boolean = true, composition: String = "", commit: String = "", success: Boolean = true,
    candidates: List<String> = emptyList(),
) = MoqiImeResult(success, handled, composition, commit, candidates,
    candidates.map { CandidateEntry(it, "") }, candidates.isNotEmpty(), "", "")

/** Fake only at the native-session boundary, not a copy of Service or Runner logic. */
internal open class FakeEngineSession(var schema: String = "") : EngineSession {
    val calls = mutableListOf<String>()
    var text = ""
    override fun keyDown(keyCode: Int, charCode: Int): MoqiImeResult {
        calls += "key:${charCode.toChar()}:$schema"
        if (schema.contains("double_pinyin")) return engineResult(handled = false)
        text += charCode.toChar()
        return engineResult(composition = text)
    }
    override fun reset(): MoqiImeResult { calls += "reset"; text = ""; return engineResult() }
    override fun close() { calls += "close" }
    override fun currentSchemaId() = schema
    override fun selectSchema(schemaId: String): MoqiImeResult {
        calls += "schema:$schemaId"; schema = schemaId; return engineResult()
    }
    override fun selectCandidate(index: Int) = engineResult(commit = text).also { calls += "select:$index"; text = "" }
    override fun changePage(backward: Boolean) = engineResult().also { calls += "page:$backward" }
    override fun replayText(text: String) = engineResult(composition = text).also { this.text = text }
    override fun deleteCandidateOnCurrentPage(index: Int) = engineResult()
    override fun command(commandId: Int) = engineResult()
    override fun schemeSets() = emptyList<String>()
    override fun currentSchemeSet() = ""
    override fun selectSchemeSet(name: String) = engineResult()
    override fun schemaEntries() = emptyList<RimeSchemaEntry>()
    override fun menuEntries() = emptyList<RimeMenuEntry>()
}
