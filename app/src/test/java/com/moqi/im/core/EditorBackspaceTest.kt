package com.moqi.im.core

import android.view.inputmethod.InputConnection
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class EditorBackspaceTest {
    @Test fun selectedMiddleIsRemovedWithoutTouchingPrecedingCharacter() {
        val editor = Editor("甲乙丙丁", 1, 3)
        assertTrue(EditorBackspace.deleteSelection(editor.connection))
        assertEquals("甲丁", editor.text)
        assertEquals(listOf("getSelectedText", "beginBatchEdit", "finishComposingText",
            "commitText", "endBatchEdit"), editor.calls)
    }

    @Test fun selectAllRemovesEverything() {
        val editor = Editor("测试😀", 0, 4)
        assertTrue(EditorBackspace.deleteSelection(editor.connection))
        assertEquals("", editor.text)
    }

    @Test fun reversedSelectionWorks() {
        val editor = Editor("甲乙丙丁", 3, 1)
        assertTrue(EditorBackspace.deleteSelection(editor.connection))
        assertEquals("甲丁", editor.text)
    }

    @Test fun noSelectionLeavesComposingStateForPinyinEngine() {
        val editor = Editor("你好", 2, 2)
        assertFalse(EditorBackspace.deleteSelection(editor.connection))
        assertEquals("你好", editor.text)
        assertEquals(listOf("getSelectedText"), editor.calls)
    }

    @Test fun rejectedSelectionEditIsStillConsumed() {
        val editor = Editor("甲乙丙", 1, 3, rejectCommit = true)
        assertTrue(EditorBackspace.deleteSelection(editor.connection))
        assertEquals("甲乙丙", editor.text)
        assertEquals("endBatchEdit", editor.calls.last())
        assertFalse(editor.calls.any { it.startsWith("deleteSurrounding") })
    }

    private class Editor(
        var text: String,
        val start: Int,
        val end: Int,
        val rejectCommit: Boolean = false,
    ) {
        val calls = mutableListOf<String>()
        val connection = Proxy.newProxyInstance(
            InputConnection::class.java.classLoader, arrayOf(InputConnection::class.java),
        ) { _, method, args ->
            calls.add(method.name)
            when (method.name) {
                "getSelectedText" -> text.substring(minOf(start, end), maxOf(start, end))
                "commitText" -> commit(args[0].toString())
                else -> true
            }
        } as InputConnection

        private fun commit(value: String): Boolean {
            if (rejectCommit) return false
            text = text.replaceRange(minOf(start, end), maxOf(start, end), value)
            return true
        }
    }
}
