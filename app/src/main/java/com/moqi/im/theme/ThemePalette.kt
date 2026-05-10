package com.moqi.im.theme

import android.content.Context
import android.graphics.Color

data class ThemePalette(
    val id: String,
    val backgroundColor: Int,
    val candidateBackgroundColor: Int,
    val textColor: Int,
    val buttonColor: Int = SPECIAL_KEY_COLOR,
) {
    companion object {
        const val PREFS_NAME = "moqi_im_prefs"
        const val PREF_KEY = "keyboard_theme"
        const val DEFAULT_ID = "default"

        val SPECIAL_KEY_COLOR: Int = Color.rgb(224, 224, 232)

        private val palettes = mapOf(
            "default" to ThemePalette(
                id = "default",
                backgroundColor = Color.rgb(240, 240, 245),
                candidateBackgroundColor = Color.rgb(240, 240, 245),
                textColor = Color.rgb(68, 64, 60),
            ),
            "wallgray" to fromRime("wallgray", "#94a3b8", "#d6d3d1", "#44403c"),
            "orange" to fromRime("orange", "#ea580c", "#fed7aa", "#7c2d12"),
            "redplum" to fromRime("redplum", "#6f1028", "#f8efea", "#3f1d24"),
            "shacheng" to fromRime("shacheng", "#d4af5a", "#2f2424", "#f6dda0"),
            "globe" to fromRime("globe", "#f4c542", "#67d4ff", "#083344"),
            "soymilk" to fromRime("soymilk", "#a3ad6a", "#f4efe6", "#4b4b43"),
            "chrysanthemum" to fromRime("chrysanthemum", "#d6a823", "#f7f1dc", "#5b4a1d"),
            "qinhuangdao" to fromRime("qinhuangdao", "#5fa7c7", "#d9edf4", "#1f4f68"),
            "bubblegum" to fromRime("bubblegum", "#7ee7d8", "#ffd6eb", "#7a2e67"),
            "pepsi" to fromRime("pepsi", "#f8fafc", "#1d4ed8", "#ffffff"),
        )

        fun current(context: Context): ThemePalette {
            val themeId = context
                .getSharedPreferences(PREFS_NAME, 0)
                .getString(PREF_KEY, DEFAULT_ID)
                ?: DEFAULT_ID
            return palettes[themeId] ?: palettes.getValue(DEFAULT_ID)
        }

        fun backgroundColor(context: Context): Int = current(context).backgroundColor

        private fun fromRime(
            id: String,
            highlightColor: String,
            candidateBackgroundColor: String,
            textColor: String,
        ) = ThemePalette(
            id = id,
            backgroundColor = Color.parseColor(highlightColor),
            candidateBackgroundColor = Color.parseColor(candidateBackgroundColor),
            textColor = Color.parseColor(textColor),
        )
    }
}
