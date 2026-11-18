package com.moqi.im.core

/** 英文九键待提交字符，首击和后续连击都属于有效输入。 */
internal class T9MultiTap {
    private var key: Int? = null
    private var characters = ""
    private var index = 0

    fun press(nextKey: Int, nextCharacters: String): String? {
        require(nextCharacters.isNotEmpty())
        if (key == nextKey && index < characters.lastIndex) {
            index++
            return null
        }
        val previous = take()
        key = nextKey
        characters = nextCharacters
        return previous
    }

    fun take(): String? {
        val result = if (key == null) null else characters[index].toString()
        clear()
        return result
    }

    fun clear() {
        key = null
        characters = ""
        index = 0
    }
}
