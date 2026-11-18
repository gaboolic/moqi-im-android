package com.moqi.im.engine

/** Boundary to the native session. The runner owns serialization and callback lifetimes. */
internal interface EngineSession {
    fun keyDown(keyCode: Int, charCode: Int = 0): MoqiImeResult
    fun selectCandidate(index: Int): MoqiImeResult
    fun deleteCandidateOnCurrentPage(index: Int): MoqiImeResult
    fun replayText(text: String): MoqiImeResult
    fun changePage(backward: Boolean): MoqiImeResult
    fun command(commandId: Int): MoqiImeResult
    fun schemeSets(): List<String>
    fun currentSchemeSet(): String
    fun selectSchemeSet(name: String): MoqiImeResult
    fun schemaEntries(): List<RimeSchemaEntry>
    fun menuEntries(): List<RimeMenuEntry>
    fun currentSchemaId(): String
    fun selectSchema(schemaId: String): MoqiImeResult
    fun reset(): MoqiImeResult
    fun close()
}
