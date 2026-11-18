package com.moqi.im.core

import android.view.inputmethod.InputConnection

/** deleteSurroundingText 删除选区之外的字符，已有选区时应替换选区。 */
internal object EditorBackspace {
    fun deleteSelection(connection: InputConnection): Boolean {
        if (connection.getSelectedText(0).isNullOrEmpty()) return false
        connection.beginBatchEdit()
        try {
            connection.finishComposingText()
            connection.commitText("", 1)
        } finally {
            connection.endBatchEdit()
        }
        // 编辑器即使拒绝替换，也不能继续误删选区前的字符。
        return true
    }
}
