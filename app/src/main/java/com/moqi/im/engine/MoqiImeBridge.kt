package com.moqi.im.engine

import android.view.KeyEvent
import android.util.Log
import com.moqi.im.util.ImeDebugLog
import mobilebridge.MobileResponse
import mobilebridge.Mobilebridge
import mobilebridge.Session

data class RimeSchemaEntry(
    val id: String,
    val name: String,
    val selected: Boolean
)

data class RimeMenuEntry(
    val group: String,
    val commandId: Int,
    val text: String,
    val checked: Boolean,
    val enabled: Boolean
)

data class CandidateEntry(
    val text: String,
    val comment: String,
    val source: CandidateEntrySource = CandidateEntrySource.RIME,
    val commitText: String = text
)

enum class CandidateEntrySource {
    RIME,
    CLIPBOARD
}

data class MoqiImeResult(
    val success: Boolean,
    val handled: Boolean,
    val composition: String,
    val commit: String,
    val candidates: List<String>,
    val candidateEntries: List<CandidateEntry>,
    val showCandidates: Boolean,
    val message: String,
    val error: String,
    val highlightedCandidateIndex: Int = 0,
)

class MoqiImeSession(
    private val guid: String = Mobilebridge.GUIDMoqi,
    private val androidDataDir: String? = null
) : EngineSession {
    private var session: Session? = null
    private var initError: String = ""

    init {
        runCatching {
            val totalStart = System.nanoTime()
            androidDataDir?.let {
                val start = System.nanoTime()
                Mobilebridge.setAndroidDataDir(it)
                logDuration("setAndroidDataDir", start, "path=$it")
            }
            val createStart = System.nanoTime()
            val created = Mobilebridge.newSession(guid)
            logDuration("newSession", createStart, "guid=$guid")
            session = created
            val initStart = System.nanoTime()
            val initResp = created.init()
            logDuration("init", initStart, "guid=$guid success=${initResp?.success}")
            if (initResp?.success != true) {
                initError = initResp?.error.orEmpty().ifBlank { "moqi-ime init failed" }
                Log.e(TAG, "init failed guid=$guid error=$initError")
                return@runCatching
            }
            ImeDebugLog.d(TAG) { "init success guid=$guid" }
            val activateStart = System.nanoTime()
            val activateResp = created.activate()
            logDuration("activate", activateStart, "guid=$guid success=${activateResp?.success} candidates=${activateResp?.candidateList?.len() ?: 0}")
            if (activateResp?.success != true) {
                initError = activateResp?.error.orEmpty().ifBlank { "moqi-ime activate failed" }
                Log.e(TAG, "activate failed guid=$guid error=$initError")
            } else {
                ImeDebugLog.d(TAG) { "activate success guid=$guid candidates=${activateResp.candidateList?.len() ?: 0}" }
            }
            logDuration("sessionReady", totalStart, "guid=$guid")
        }.onFailure { error ->
            initError = error.message.orEmpty().ifBlank { error::class.java.simpleName }
            Log.e(TAG, "session create failed guid=$guid", error)
            session = null
        }
    }

    override fun keyDown(keyCode: Int, charCode: Int): MoqiImeResult {
        val activeSession = session ?: return initErrorResult()
        return runCatching {
            val start = System.nanoTime()
            val result = activeSession.keyDown(keyCode.toLong(), charCode.toLong()).toResult()
            logDuration("keyDown", start, "keyCode=$keyCode charCode=$charCode success=${result.success} handled=${result.handled} candidates=${result.candidates.size}")
            result
        }.getOrElse { error ->
            Log.e(TAG, "keyDown failed keyCode=$keyCode charCode=$charCode", error)
            error.toResult()
        }
    }

    override fun selectCandidate(index: Int): MoqiImeResult {
        val activeSession = session ?: return initErrorResult()
        return runCatching {
            val start = System.nanoTime()
            val result = activeSession.selectCandidate(index.toLong()).toResult()
            logDuration("selectCandidate", start, "index=$index success=${result.success} candidates=${result.candidates.size}")
            result
        }.getOrElse { error ->
            Log.e(TAG, "selectCandidate failed index=$index", error)
            error.toResult()
        }
    }

    override fun deleteCandidateOnCurrentPage(index: Int): MoqiImeResult {
        val activeSession = session ?: return initErrorResult()
        return runCatching {
            val start = System.nanoTime()
            val result = activeSession.deleteCandidateOnCurrentPage(index.toLong()).toResult()
            logDuration(
                "deleteCandidateOnCurrentPage",
                start,
                "index=$index success=${result.success} candidates=${result.candidates.size}"
            )
            result
        }.getOrElse { error ->
            Log.e(TAG, "deleteCandidateOnCurrentPage failed index=$index", error)
            error.toResult()
        }
    }

    override fun replayText(text: String): MoqiImeResult {
        val activeSession = session ?: return initErrorResult()
        return runCatching {
            val start = System.nanoTime()
            val result = activeSession.replayText(text).toResult()
            logDuration("replayText", start, "length=${text.length} success=${result.success} candidates=${result.candidates.size}")
            result
        }.getOrElse { error ->
            Log.e(TAG, "replayText failed length=${text.length}", error)
            error.toResult()
        }
    }

    override fun changePage(backward: Boolean): MoqiImeResult {
        val activeSession = session ?: return initErrorResult()
        return runCatching {
            val start = System.nanoTime()
            val result = activeSession.changePage(backward).toResult()
            logDuration("changePage", start, "backward=$backward success=${result.success} candidates=${result.candidates.size}")
            result
        }.getOrElse { error ->
            Log.e(TAG, "changePage failed backward=$backward", error)
            error.toResult()
        }
    }

    override fun command(commandId: Int): MoqiImeResult {
        val activeSession = session ?: return initErrorResult()
        return runCatching {
            val start = System.nanoTime()
            val result = activeSession.command(commandId.toLong()).toResult()
            logDuration("command", start, "commandId=$commandId success=${result.success} candidates=${result.candidates.size}")
            result
        }.getOrElse { error ->
            Log.e(TAG, "command failed commandId=$commandId", error)
            error.toResult()
        }
    }

    override fun schemeSets(): List<String> {
        val activeSession = session ?: return emptyList()
        return runCatching {
            val start = System.nanoTime()
            val values = activeSession.schemeSets().toKotlinList()
            logDuration("schemeSets", start, "count=${values.size}")
            values
        }.getOrElse { error ->
            Log.e(TAG, "schemeSets failed", error)
            emptyList()
        }
    }

    override fun currentSchemeSet(): String {
        val activeSession = session ?: return ""
        return runCatching {
            val start = System.nanoTime()
            val value = activeSession.currentSchemeSet().orEmpty()
            logDuration("currentSchemeSet", start, "value=$value")
            value
        }.getOrElse { error ->
            Log.e(TAG, "currentSchemeSet failed", error)
            ""
        }
    }

    override fun selectSchemeSet(name: String): MoqiImeResult {
        val activeSession = session ?: return initErrorResult()
        return runCatching {
            val start = System.nanoTime()
            val result = activeSession.selectSchemeSet(name).toResult()
            logDuration("selectSchemeSet", start, "name=$name success=${result.success}")
            result
        }.getOrElse { error ->
            Log.e(TAG, "selectSchemeSet failed name=$name", error)
            error.toResult()
        }
    }

    override fun schemaEntries(): List<RimeSchemaEntry> {
        val activeSession = session ?: return emptyList()
        return runCatching {
            val start = System.nanoTime()
            val entries = activeSession.schemaEntries().toKotlinList().mapNotNull { it.toSchemaEntry() }
            logDuration("schemaEntries", start, "count=${entries.size}")
            entries
        }.getOrElse { error ->
            Log.e(TAG, "schemaEntries failed", error)
            emptyList()
        }
    }

    override fun menuEntries(): List<RimeMenuEntry> {
        val activeSession = session ?: return emptyList()
        return runCatching {
            val start = System.nanoTime()
            val entries = activeSession.menuEntries().toKotlinList().mapNotNull { it.toMenuEntry() }
            logDuration("menuEntries", start, "count=${entries.size}")
            entries
        }.getOrElse { error ->
            Log.e(TAG, "menuEntries failed", error)
            emptyList()
        }
    }

    override fun currentSchemaId(): String {
        val activeSession = session ?: return ""
        return runCatching {
            val start = System.nanoTime()
            val value = activeSession.currentSchemaID().orEmpty()
            logDuration("currentSchemaID", start, "value=$value")
            value
        }.getOrElse { error ->
            Log.e(TAG, "currentSchemaID failed", error)
            ""
        }
    }

    override fun selectSchema(schemaId: String): MoqiImeResult {
        val activeSession = session ?: return initErrorResult()
        return runCatching {
            val start = System.nanoTime()
            val result = activeSession.selectSchema(schemaId).toResult()
            logDuration("selectSchema", start, "schemaId=$schemaId success=${result.success}")
            result
        }.getOrElse { error ->
            Log.e(TAG, "selectSchema failed schemaId=$schemaId", error)
            error.toResult()
        }
    }

    override fun reset(): MoqiImeResult {
        return keyDown(MoqiImeKeyMapper.VK_ESCAPE)
    }

    override fun close() {
        runCatching {
            session?.close()
        }.onFailure { error ->
            Log.w(TAG, "close failed", error)
        }
        session = null
    }

    private fun initErrorResult(): MoqiImeResult {
        return MoqiImeResult(
            success = false,
            handled = false,
            composition = "",
            commit = "",
            candidates = emptyList(),
            candidateEntries = emptyList(),
            showCandidates = false,
            message = "",
            error = initError.ifBlank { "moqi-ime session is not initialized" }
        )
    }

    companion object {
        private const val TAG = "MoqiImeSession"
        private const val SLOW_LOG_THRESHOLD_MS = 30L

        private fun logDuration(operation: String, startNanos: Long, details: String = "") {
            ImeDebugLog.duration(TAG, operation, startNanos, SLOW_LOG_THRESHOLD_MS) { details }
        }
    }
}

object MoqiImeKeyMapper {
    const val VK_BACK = 0x08
    const val VK_RETURN = 0x0D
    const val VK_ESCAPE = 0x1B
    const val VK_SPACE = 0x20
    const val VK_OEM_1 = 0xBA
    const val VK_OEM_PERIOD = 0xBE

    fun fromAndroidKeyCode(keyCode: Int, shifted: Boolean): Pair<Int, Int>? {
        return when (keyCode) {
            KeyEvent.KEYCODE_A -> printable('a', shifted)
            KeyEvent.KEYCODE_B -> printable('b', shifted)
            KeyEvent.KEYCODE_C -> printable('c', shifted)
            KeyEvent.KEYCODE_D -> printable('d', shifted)
            KeyEvent.KEYCODE_E -> printable('e', shifted)
            KeyEvent.KEYCODE_F -> printable('f', shifted)
            KeyEvent.KEYCODE_G -> printable('g', shifted)
            KeyEvent.KEYCODE_H -> printable('h', shifted)
            KeyEvent.KEYCODE_I -> printable('i', shifted)
            KeyEvent.KEYCODE_J -> printable('j', shifted)
            KeyEvent.KEYCODE_K -> printable('k', shifted)
            KeyEvent.KEYCODE_L -> printable('l', shifted)
            KeyEvent.KEYCODE_M -> printable('m', shifted)
            KeyEvent.KEYCODE_N -> printable('n', shifted)
            KeyEvent.KEYCODE_O -> printable('o', shifted)
            KeyEvent.KEYCODE_P -> printable('p', shifted)
            KeyEvent.KEYCODE_Q -> printable('q', shifted)
            KeyEvent.KEYCODE_R -> printable('r', shifted)
            KeyEvent.KEYCODE_S -> printable('s', shifted)
            KeyEvent.KEYCODE_T -> printable('t', shifted)
            KeyEvent.KEYCODE_U -> printable('u', shifted)
            KeyEvent.KEYCODE_V -> printable('v', shifted)
            KeyEvent.KEYCODE_W -> printable('w', shifted)
            KeyEvent.KEYCODE_X -> printable('x', shifted)
            KeyEvent.KEYCODE_Y -> printable('y', shifted)
            KeyEvent.KEYCODE_Z -> printable('z', shifted)
            KeyEvent.KEYCODE_0 -> digit('0')
            KeyEvent.KEYCODE_1 -> digit('1')
            KeyEvent.KEYCODE_2 -> digit('2')
            KeyEvent.KEYCODE_3 -> digit('3')
            KeyEvent.KEYCODE_4 -> digit('4')
            KeyEvent.KEYCODE_5 -> digit('5')
            KeyEvent.KEYCODE_6 -> digit('6')
            KeyEvent.KEYCODE_7 -> digit('7')
            KeyEvent.KEYCODE_8 -> digit('8')
            KeyEvent.KEYCODE_9 -> digit('9')
            else -> null
        }
    }

    fun printable(ch: Char, shifted: Boolean = false): Pair<Int, Int> {
        val out = if (shifted) ch.uppercaseChar() else ch
        return out.code to out.code
    }

    fun digit(ch: Char): Pair<Int, Int> = ch.code to ch.code

    // The bridge translates Windows virtual keys before CharCode. ASCII punctuation such as
    // '!', '.', '/' would otherwise become PageUp, Delete, Help, etc. Zero has no VK mapping.
    fun symbol(ch: Char): Pair<Int, Int> = 0 to ch.code
}

private fun MobileResponse?.toResult(): MoqiImeResult {
    if (this == null) {
        return MoqiImeResult(
            success = false,
            handled = false,
            composition = "",
            commit = "",
            candidates = emptyList(),
            candidateEntries = emptyList(),
            showCandidates = false,
            message = "",
            error = "empty moqi-ime response"
        )
    }

    val candidates = candidateList.toKotlinList()
    val entries = candidateEntries.toKotlinList().mapNotNull { it.toCandidateEntry() }
    val displayEntries = entries.ifEmpty {
        candidates.map { CandidateEntry(text = it, comment = "") }
    }

    return MoqiImeResult(
        success = success,
        handled = returnValue != 0L,
        composition = compositionString.orEmpty(),
        commit = commitString.orEmpty(),
        candidates = candidates,
        candidateEntries = displayEntries,
        showCandidates = showCandidates,
        message = message.orEmpty(),
        error = error.orEmpty(),
        highlightedCandidateIndex = if (hasCandidateCursor) candidateCursor.toInt() else 0,
    )
}

private fun mobilebridge.StringList?.toKotlinList(): List<String> {
    if (this == null) return emptyList()
    return (0 until len()).map { index -> get(index) }
}

private fun String.toSchemaEntry(): RimeSchemaEntry? {
    val parts = split('\t')
    val id = parts.getOrNull(0).orEmpty()
    if (id.isBlank()) return null
    val name = parts.getOrNull(1).orEmpty().ifBlank { id }
    return RimeSchemaEntry(
        id = id,
        name = name,
        selected = parts.getOrNull(2) == "1"
    )
}

private fun String.toMenuEntry(): RimeMenuEntry? {
    val parts = split('\t')
    val commandId = parts.getOrNull(1)?.toIntOrNull() ?: 0
    val text = parts.getOrNull(2).orEmpty()
    if (text.isBlank()) return null
    return RimeMenuEntry(
        group = parts.getOrNull(0).orEmpty(),
        commandId = commandId,
        text = text,
        checked = parts.getOrNull(3) == "1",
        enabled = parts.getOrNull(4) != "0"
    )
}

private fun String.toCandidateEntry(): CandidateEntry? {
    val parts = split('\t')
    val text = parts.getOrNull(0).orEmpty()
    if (text.isBlank()) return null
    return CandidateEntry(
        text = text,
        comment = parts.getOrNull(1).orEmpty()
    )
}

private fun Throwable.toResult(): MoqiImeResult {
    return MoqiImeResult(
        success = false,
        handled = false,
        composition = "",
        commit = "",
        candidates = emptyList(),
        candidateEntries = emptyList(),
        showCandidates = false,
        message = "",
        error = message.orEmpty().ifBlank { javaClass.simpleName }
    )
}
