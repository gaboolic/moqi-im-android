package com.moqi.im.settings

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.moqi.im.R
import android.view.inputmethod.InputMethodManager

class SetupActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        findViewById<Button>(R.id.btn_enable_ime).setOnClickListener {
            openInputMethodSettings()
        }

        findViewById<Button>(R.id.btn_select_ime).setOnClickListener {
            showInputMethodPicker()
        }

        findViewById<Button>(R.id.btn_input_test).setOnClickListener {
            startActivity(Intent(this, InputTestActivity::class.java))
        }

        updateUI()
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    private fun updateUI() {
        try {
            val enabled = isImeEnabled()
            val selected = isImeSelected()

            val statusText = findViewById<TextView>(R.id.tv_setup_status)
            val btnEnable = findViewById<Button>(R.id.btn_enable_ime)
            val btnSelect = findViewById<Button>(R.id.btn_select_ime)

            when {
                !enabled -> {
                    statusText.text = getString(R.string.setup_step1)
                    btnEnable.visibility = View.VISIBLE
                    btnSelect.visibility = View.GONE
                }
                !selected -> {
                    statusText.text = getString(R.string.setup_step2)
                    btnEnable.visibility = View.GONE
                    btnSelect.visibility = View.VISIBLE
                }
                else -> {
                    statusText.text = getString(R.string.setup_done)
                    btnEnable.visibility = View.GONE
                    btnSelect.visibility = View.GONE
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "updateUI failed", e)
            runCatching {
                findViewById<TextView>(R.id.tv_setup_status).text = getString(R.string.setup_step1)
                findViewById<Button>(R.id.btn_enable_ime).visibility = View.VISIBLE
                findViewById<Button>(R.id.btn_select_ime).visibility = View.VISIBLE
            }
        }
    }

    private fun isImeEnabled(): Boolean {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return false
        return try {
            imm.enabledInputMethodList.any { it.packageName == packageName }
        } catch (e: RuntimeException) {
            Log.w(TAG, "isImeEnabled: enabledInputMethodList failed", e)
            false
        }
    }

    private fun isImeSelected(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return false
                imm.currentInputMethodInfo?.packageName == packageName
            } else {
                currentImePackageFromSettings() == packageName
            }
        } catch (e: Throwable) {
            Log.w(TAG, "isImeSelected failed", e)
            false
        }
    }

    /** API 33 及以下无 [InputMethodManager.getCurrentInputMethodInfo]，用系统设置项解析。 */
    private fun currentImePackageFromSettings(): String? {
        val id = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: return null
        return id.substringBefore('/').takeIf { it.isNotEmpty() }
    }

    private fun openInputMethodSettings() {
        val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
    }

    private fun showInputMethodPicker() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        try {
            imm.showInputMethodPicker()
        } catch (e: RuntimeException) {
            Log.e(TAG, "showInputMethodPicker failed", e)
        }
    }

    private companion object {
        private const val TAG = "SetupActivity"
    }
}