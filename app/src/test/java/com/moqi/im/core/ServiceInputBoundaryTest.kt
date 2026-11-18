package com.moqi.im.core

import android.os.Looper
import android.view.View
import android.view.inputmethod.BaseInputConnection
import com.moqi.im.engine.*
import com.moqi.im.keyboard.KeyboardInputQueue
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
class ServiceInputBoundaryTest {
    @Test fun clearInvalidatesPendingUiAndWorkerResultsAndPausedKeyboardInput() {
        val session = object : FakeEngineSession("rime_frost") {
            override fun keyDown(keyCode: Int, charCode: Int) =
                engineResult(commit = charCode.toChar().toString()).also { calls += "key:${charCode.toChar()}" }
        }
        withService(session) { service, worker, connection ->
            val queue = ReflectionHelpers.getField<KeyboardInputQueue>(service, "keyboardInputQueue")
            submit(service, 'a')
            worker.drain() // Old commit waiting for UI.
            submit(service, 'b') // Old operation still waiting for worker.
            val token = queue.pause()
            queue.dispatch { submit(service, 'x') } // Paused keyboard callback must be discarded too.
            invoke(service, "clearTextEngineState")
            queue.resume(token) // Stale completion cannot drain old input.
            queue.dispatch { submit(service, 'c') }
            worker.drain()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("c", connection.editable.toString())
            assertEquals(listOf("key:a", "key:b", "reset", "key:c"), session.calls)
            assertFalse(queue.paused)
            assertEquals("", ReflectionHelpers.getField<StringBuilder>(service, "composingText").toString())
        }
    }

    @Test fun consumedCommitSurvivesT9ReplayGenerationAndDoesNotRunLiteralFallback() {
        val session = object : FakeEngineSession("rime_frost_t9") {
            override fun keyDown(keyCode: Int, charCode: Int) = engineResult(handled = false, commit = "已提交")
        }
        withService(session) { service, worker, connection ->
            ReflectionHelpers.setField(service, "isT9Mode", true)
            var fallbackCalls = 0
            submit(service, 'a', fallback = { fallbackCalls++ })
            worker.drain()
            ReflectionHelpers.setField(service, "t9ReplayGeneration", 99L)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("已提交", connection.editable.toString())
            assertEquals(0, fallbackCalls)
        }
    }

    @Test fun realServiceSubmitKeepsFallbackFirstLetterAheadOfFollowingInput() {
        val session = FakeEngineSession("double_pinyin_flypy")
        withService(session) { service, worker, _ ->
            "ceshi".forEach { submit(service, it, retry = true) }
            worker.drain()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("ceshi", ReflectionHelpers.getField<StringBuilder>(service, "composingText").toString())
            assertEquals("rime_frost", ReflectionHelpers.getField<String>(service, "currentSchemaId"))
            assertEquals(listOf("key:c:double_pinyin_flypy", "schema:rime_frost", "key:c:rime_frost",
                "key:e:rime_frost", "key:s:rime_frost", "key:h:rime_frost", "key:i:rime_frost"), session.calls)
        }
    }

    private fun withService(session: FakeEngineSession,
        test: (MoqiInputMethodService, ManualEngineExecutor, BaseInputConnection) -> Unit) {
        val service = Robolectric.buildService(MoqiInputMethodService::class.java).get()
        val connection = BaseInputConnection(View(service), true)
        ReflectionHelpers.setField(service, "mStartedInputConnection", connection)
        val worker = ManualEngineExecutor()
        val runner = MoqiImeEngineRunner("unused", "test", null, worker) { _, _ -> session }
        ReflectionHelpers.setField(service, "engineRunner", runner)
        try { test(service, worker, connection) } finally {
            runner.close(); worker.drain()
            ReflectionHelpers.getField<android.os.Handler>(service, "handler").removeCallbacksAndMessages(null)
        }
    }

    private fun submit(service: MoqiInputMethodService, ch: Char, retry: Boolean = false, fallback: (() -> Unit)? = null) {
        MoqiInputMethodService::class.java.getDeclaredMethod("submitMoqiKey",
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, kotlin.jvm.functions.Function0::class.java
        ).apply { isAccessible = true }.invoke(service, 0, ch.code, false, retry, fallback)
    }
    private fun invoke(service: MoqiInputMethodService, name: String) {
        MoqiInputMethodService::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(service)
    }
}
