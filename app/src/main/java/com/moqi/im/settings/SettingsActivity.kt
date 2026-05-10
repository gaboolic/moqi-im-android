package com.moqi.im.settings

import android.os.Bundle
import android.view.MenuItem
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.moqi.im.R
import com.moqi.im.theme.ThemePalette

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        applyThemeColors()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.settings_title)

        supportFragmentManager
            .beginTransaction()
            .replace(R.id.settings_container, SettingsFragment())
            .commit()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun applyThemeColors() {
        val theme = ThemePalette.current(this)
        findViewById<android.view.View>(R.id.settings_root)?.setBackgroundColor(theme.backgroundColor)
        findViewById<TextView>(R.id.settings_title_text)?.setTextColor(theme.textColor)
    }
}