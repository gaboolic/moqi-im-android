package com.moqi.im.theme

import android.graphics.Color
import androidx.core.graphics.ColorUtils

/** Settings/menu roles only. Rime highlight and keyboard/candidate skin colors stay untouched. */
data class ThemeSurfaceColors(
    val surface: Int,
    val onSurface: Int,
    val button: Int,
    val onButton: Int,
) {
    companion object {
        fun from(palette: ThemePalette): ThemeSurfaceColors {
            val surface = palette.candidateBackgroundColor
            return ThemeSurfaceColors(
                surface = surface,
                onSurface = readableForeground(palette.textColor, surface),
                button = palette.buttonColor,
                onButton = readableForeground(palette.textColor, palette.buttonColor),
            )
        }

        // Preserve the theme's foreground when readable; otherwise use the higher-contrast
        // opaque neutral. Black or white always reaches at least 4.5:1 on an opaque surface.
        private fun readableForeground(preferred: Int, background: Int): Int {
            if (ColorUtils.calculateContrast(preferred, background) >= 4.5) return preferred
            return if (ColorUtils.calculateContrast(Color.BLACK, background) >=
                ColorUtils.calculateContrast(Color.WHITE, background)) Color.BLACK else Color.WHITE
        }
    }
}
