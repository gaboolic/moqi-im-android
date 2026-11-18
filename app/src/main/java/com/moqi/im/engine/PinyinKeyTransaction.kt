package com.moqi.im.engine

/** 必须整个运行在引擎串行队列内，重试首键不能排到后续字母之后。 */
internal object PinyinKeyTransaction {
    data class Outcome(val result: MoqiImeResult, val changedSchema: String = "")

    fun run(
        charCode: Int,
        key: () -> MoqiImeResult,
        currentSchema: () -> String,
        selectSchema: (String) -> MoqiImeResult
    ): Outcome {
        val first = key()
        if (!canRetry(first, charCode)) return Outcome(first)
        if (!currentSchema().contains("double_pinyin", ignoreCase = true)) return Outcome(first)
        val selected = selectSchema("rime_frost")
        if (!selected.success) return Outcome(selected)
        return Outcome(key(), "rime_frost")
    }

    private fun canRetry(result: MoqiImeResult, charCode: Int): Boolean {
        if (!result.success || result.handled) return false
        if (result.composition.isNotEmpty() || result.commit.isNotEmpty()) return false
        return charCode in 'a'.code..'z'.code || charCode in 'A'.code..'Z'.code
    }
}
