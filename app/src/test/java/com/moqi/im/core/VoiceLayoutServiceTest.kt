package com.moqi.im.core

import android.os.Looper
import com.moqi.im.BuildConfig
import com.moqi.im.engine.FakeEngineSession
import com.moqi.im.engine.InputMode
import com.moqi.im.engine.ManualEngineExecutor
import com.moqi.im.engine.MoqiImeEngineRunner
import com.moqi.im.keyboard.KeyboardView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class VoiceLayoutServiceTest {
    @Test fun chineseT9EnglishQwertyEmptySchemaBeforeReady() = roundTrip(true, "", false)
    @Test fun chineseT9EnglishQwertyEmptySchemaAfterReady() = roundTrip(true, "", true)
    @Test fun chineseT9EnglishQwertySchemaBeforeReady() = roundTrip(true, "rime_frost_t9", false)
    @Test fun chineseT9EnglishQwertySchemaAfterReady() = roundTrip(true, "rime_frost_t9", true)
    @Test fun chineseQwertyEnglishT9EmptySchemaBeforeReady() = roundTrip(false, "", false)
    @Test fun chineseQwertyEnglishT9EmptySchemaAfterReady() = roundTrip(false, "", true)
    @Test fun chineseQwertyEnglishT9SchemaBeforeReady() = roundTrip(false, "rime_frost", false)
    @Test fun chineseQwertyEnglishT9SchemaAfterReady() = roundTrip(false, "rime_frost", true)

    private fun roundTrip(chineseT9: Boolean, schema: String, readyBeforeVoice: Boolean) {
        // Attach the real service without onCreateInputView/native initialization or audio capture.
        val service = Robolectric.buildService(MoqiInputMethodService::class.java).get()
        val keyboard = KeyboardView(service, null)
        ReflectionHelpers.setField(service, "keyboardView", keyboard)
        val layouts = ReflectionHelpers.getField<TextKeyboardLayouts>(service, "textLayouts")
        layouts.select(false, chineseT9)
        layouts.select(true, !chineseT9)
        val worker = ManualEngineExecutor()
        val sessions = mutableListOf<FakeEngineSession>()
        val runner = MoqiImeEngineRunner("unused", "initial", null, worker) { _, _ ->
            FakeEngineSession(schema).also { sessions += it }
        }
        ReflectionHelpers.setField(service, "engineRunner", runner)
        fun drain() { worker.drain(); shadowOf(Looper.getMainLooper()).idle() }
        fun assertLayout(t9: Boolean, english: Boolean) {
            assertEquals(t9, ReflectionHelpers.getField<Boolean>(service, "isT9Mode"))
            val expected = if (english) {
                if (t9) KeyboardView.Layout.T9_EN else KeyboardView.Layout.QWERTY_EN
            } else {
                if (t9) KeyboardView.Layout.T9_CN else KeyboardView.Layout.QWERTY_CN
            }
            assertEquals(expected, ReflectionHelpers.getField<KeyboardView.Layout>(keyboard, "currentLayout"))
        }
        invoke(service, "switchMode", InputMode::class.java, InputMode.ENGLISH)
        assertLayout(!chineseT9, true)
        if (readyBeforeVoice) drain()
        if (BuildConfig.VOICE_INPUT_ENABLED) {
            // Actual Full enterVoiceMode: denied permission schedules return, never starts recording.
            invoke(service, "enterVoiceMode")
        } else {
            // Lite intentionally disables voice entry; exercise the common switch/exit path too.
            ReflectionHelpers.setField(service, "modeBeforeVoice", InputMode.ENGLISH)
            invoke(service, "switchMode", InputMode::class.java, InputMode.VOICE)
        }
        assertEquals(InputMode.VOICE, ReflectionHelpers.getField<InputMode>(service, "currentMode"))
        if (readyBeforeVoice) drain()
        val countBeforeExit = sessions.sumOf { it.calls.count { call -> call == "reset" } }
        invoke(service, "exitVoiceMode")
        assertEquals(InputMode.ENGLISH, ReflectionHelpers.getField<InputMode>(service, "currentMode"))
        assertLayout(!chineseT9, true) // Must be correct before worker or schema/UI readiness.
        drain()
        assertLayout(!chineseT9, true)
        if (readyBeforeVoice) {
            assertEquals("exactly one reset for exit", countBeforeExit + 1,
                sessions.sumOf { it.calls.count { call -> call == "reset" } })
        }
        invoke(service, "switchMode", InputMode::class.java, InputMode.PINYIN)
        assertLayout(chineseT9, false)
        drain()
        assertLayout(chineseT9, false)
        assertEquals(chineseT9, layouts.isT9(false))
        assertEquals(!chineseT9, layouts.isT9(true))
        runner.close()
        worker.drain()
        // Remove the permission/model delayed return; it is not part of this deterministic round trip.
        ReflectionHelpers.getField<android.os.Handler>(service, "handler").removeCallbacksAndMessages(null)
    }

    private fun invoke(service: MoqiInputMethodService, name: String) {
        MoqiInputMethodService::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(service)
    }
    private fun invoke(service: MoqiInputMethodService, name: String, type: Class<*>, value: Any) {
        MoqiInputMethodService::class.java.getDeclaredMethod(name, type).apply { isAccessible = true }.invoke(service, value)
    }
}
