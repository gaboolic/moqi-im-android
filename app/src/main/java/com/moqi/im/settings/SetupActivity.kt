package com.moqi.im.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.moqi.im.R

class SetupActivity : AppCompatActivity() {

    private var step = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        findViewById<Button>(R.id.btn_enable_ime).setOnClickListener {
            if (step == 0) {
                val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(intent)
                step = 1
                updateUI()
            } else {
                val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(intent)
            }
        }

        findViewById<Button>(R.id.btn_select_ime).setOnClickListener {
            val inputMethodManager = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            inputMethodManager.showInputMethodPicker()
        }

        findViewById<Button>(R.id.btn_go_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        updateUI()
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    private fun updateUI() {
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
                step = 0
            }
            !selected -> {
                statusText.text = getString(R.string.setup_step2)
                btnEnable.visibility = View.GONE
                btnSelect.visibility = View.VISIBLE
                step = 1
            }
            else -> {
                statusText.text = getString(R.string.setup_done)
                btnEnable.visibility = View.GONE
                btnSelect.visibility = View.GONE
            }
        }
    }

    private fun isImeEnabled(): Boolean {
        val inputMethodManager = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        val enabledIMEs = inputMethodManager.enabledInputMethodList
        return enabledIMEs.any { it.packageName == packageName }
    }

    private fun isImeSelected(): Boolean {
        val inputMethodManager = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        val selectedIMEs = inputMethodManager.currentInputMethodSubtype
        return inputMethodManager.currentInputMethodInfo?.packageName == packageName
    }
}