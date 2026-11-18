package com.moqi.im.theme

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import com.moqi.im.R
import com.moqi.im.keyboard.KeyboardMenuView
import com.moqi.im.settings.SettingsActivity
import com.moqi.im.settings.SettingsFragment
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

// Only secure storage is outside this UI test: Robolectric has no AndroidKeyStore.
@org.robolectric.annotation.Implements(value = com.moqi.im.cloudclipboard.CloudClipboardPrefs::class, isInAndroidSdk = false)
class NoKeystorePasswordShadow {
    @org.robolectric.annotation.Implementation
    fun hasPassword(context: Context): Boolean = false
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34], shadows = [NoKeystorePasswordShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class ThemeSurfaceUiTest(private val themeId: String, private val night: Boolean) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-night={1}")
        fun cases(): List<Array<Any>> = listOf("default", "wallgray", "orange", "redplum", "shacheng", "globe", "soymilk", "chrysanthemum", "qinhuangdao", "bubblegum", "pepsi")
            .flatMap { id -> listOf(false, true).map { arrayOf<Any>(id, it) } }
    }

    private fun select(context: Context, id: String) {
        context.getSharedPreferences(ThemePalette.PREFS_NAME, 0).edit().putString(ThemePalette.PREF_KEY, id).commit()
    }

    private fun prepare() {
        RuntimeEnvironment.setQualifiers(if (night) "night" else "notnight")
        select(RuntimeEnvironment.getApplication(), themeId)
    }

    @Test fun settingsActivityAndEveryBoundPreferenceAreReadableAndMatchMenu() {
        prepare()
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = controller.get()
        activity.supportFragmentManager.executePendingTransactions()
        shadowOf(Looper.getMainLooper()).idle()
        val root = activity.findViewById<View>(R.id.settings_root)
        val surface = (root.background as ColorDrawable).color
        val menu = KeyboardMenuView(activity)
        assertEquals("settings/menu surface", surface, (menu.background as ColorDrawable).color)
        readable(activity.findViewById(R.id.settings_title_text), surface)
        val fragment = activity.supportFragmentManager.findFragmentById(R.id.settings_container) as SettingsFragment
        val list = fragment.listView
        assertEquals(surface, (list.background as ColorDrawable).color)
        @Suppress("UNCHECKED_CAST")
        val adapter = list.adapter as RecyclerView.Adapter<RecyclerView.ViewHolder>
        var summaries = 0
        for (position in 0 until adapter.itemCount) {
            val holder = adapter.createViewHolder(list, adapter.getItemViewType(position))
            adapter.bindViewHolder(holder, position)
            texts(holder.itemView).filter { it.visibility == View.VISIBLE && it.text.isNotEmpty() }.forEach {
                if (it.id == android.R.id.summary) summaries++
                readable(it, surface)
            }
        }
        assertTrue("real preference summaries tested", summaries > 0)
        controller.pause().stop().destroy()
    }

    @Test fun menuTextButtonsAndUrlHintAreReadable() {
        prepare()
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val menu = KeyboardMenuView(activity.get())
        menu.render(KeyboardMenuView.MenuState(schemeSets = listOf("one", "two"), currentSchemeSet = "one"))
        checkMenu(menu)
        activity.pause().stop().destroy()
    }

    @Test fun renderRefreshesRetainedHeaderAndUrlAlongsideNewButtons() {
        prepare()
        val context = RuntimeEnvironment.getApplication()
        select(context, if (themeId == "pepsi") "default" else "pepsi")
        val menu = KeyboardMenuView(context)
        val header = texts(menu).first { it.text.toString() == "墨奇菜单" }
        select(context, themeId)
        menu.render(KeyboardMenuView.MenuState(schemeSets = listOf("one", "two")))
        assertSame(header, texts(menu).first { it.text.toString() == "墨奇菜单" })
        checkMenu(menu)
    }

    @Test fun resumeRefreshesActivityTitleAndPreferenceRows() {
        prepare()
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = controller.get()
        activity.supportFragmentManager.executePendingTransactions()
        controller.pause()
        select(activity, if (themeId == "pepsi") "default" else "pepsi")
        controller.resume()
        shadowOf(Looper.getMainLooper()).idle()
        val menu = KeyboardMenuView(activity)
        val surface = (menu.background as ColorDrawable).color
        assertEquals(surface, (activity.findViewById<View>(R.id.settings_root).background as ColorDrawable).color)
        readable(activity.findViewById(R.id.settings_title_text), surface)
        val fragment = activity.supportFragmentManager.findFragmentById(R.id.settings_container) as SettingsFragment
        @Suppress("UNCHECKED_CAST")
        val adapter = fragment.listView.adapter as RecyclerView.Adapter<RecyclerView.ViewHolder>
        for (position in 0 until adapter.itemCount) {
            val holder = adapter.createViewHolder(fragment.listView, adapter.getItemViewType(position))
            adapter.bindViewHolder(holder, position)
            texts(holder.itemView).filter { it.visibility == View.VISIBLE && it.text.isNotEmpty() }.forEach { readable(it, surface) }
        }
        controller.pause().stop().destroy()
    }

    private fun checkMenu(menu: KeyboardMenuView) {
        val surface = (menu.background as ColorDrawable).color
        texts(menu).forEach { text ->
            val background = if (text is Button) text.backgroundTintList!!.defaultColor else surface
            readable(text, background)
            if (text is EditText) contrast("URL hint", text.currentHintTextColor, background)
        }
    }

    private fun readable(text: TextView, background: Int) = contrast(text.text.toString(), text.currentTextColor, background)
    private fun contrast(label: String, foreground: Int, background: Int) {
        val opaque = ColorUtils.compositeColors(foreground, background)
        val ratio = ColorUtils.calculateContrast(opaque, background)
        assertTrue("$themeId night=$night '$label' contrast=$ratio foreground=${Integer.toHexString(foreground)} background=${Integer.toHexString(background)}", ratio >= 4.5)
    }
    private fun texts(view: View): List<TextView> = (if (view is TextView) listOf(view) else emptyList()) +
        (if (view is ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) } else emptyList())
}
