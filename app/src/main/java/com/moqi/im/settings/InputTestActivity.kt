package com.moqi.im.settings

import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import com.moqi.im.R

class InputTestActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_input_test)

        val editText = findViewById<EditText>(R.id.input_test_edit)
        editText.requestFocus()
        editText.post {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
            // SHOW_FORCED 已废弃，部分机型（尤其 ColorOS 等）上可能引发异常；IMPLICIT 即可弹出键盘
            imm?.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
        }
    }
}
