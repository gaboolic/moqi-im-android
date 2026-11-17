package com.moqi.im.keyboard

import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.widget.LinearLayout
import com.moqi.im.core.MoqiInputMethodService
import com.moqi.im.engine.MoqiImeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NumberKeyboardSpaceTest {
    private lateinit var service: MoqiInputMethodService
    private lateinit var keyboard: KeyboardView
    private lateinit var candidates: CandidateView
    private lateinit var connection: BaseInputConnection

    @Before
    fun setUp() {
        // Attach the service without starting the native input engine.
        service = Robolectric.buildService(MoqiInputMethodService::class.java).get()
        keyboard = KeyboardView(service, null).apply { setLayout(KeyboardView.Layout.NUMBER) }
        candidates = CandidateView(service, null).apply {
            layoutParams = LinearLayout.LayoutParams(300, 50)
            setOnExpandedChangedListener { expanded ->
                invokeService("setCandidateExpanded", Boolean::class.javaPrimitiveType!!, expanded)
            }
        }
        connection = BaseInputConnection(View(service), true)
        ReflectionHelpers.setField(service, "keyboardView", keyboard)
        ReflectionHelpers.setField(service, "candidateView", candidates)
        ReflectionHelpers.setField(service, "mStartedInputConnection", connection)
    }

    @Test
    fun numberSpaceCommitsLiterallyWithoutEngineAndKeepsLayout() {
        repeat(2) { invokeService("handleSpace") }
        assertEquals("  ", connection.editable.toString())
        assertNumberLayout()
    }

    @Test
    fun clearingCollapsedCandidatesDoesNotEmitCollapse() {
        val changes = mutableListOf<Boolean>()
        candidates.setOnExpandedChangedListener { changes += it }
        candidates.setCandidateEntries(emptyList())
        candidates.setCandidateEntries(emptyList())
        assertTrue(changes.isEmpty())
    }

    @Test
    fun clearingExpandedCandidatesCollapsesWithoutReplacingNumberLayout() {
        ReflectionHelpers.setField(candidates, "expanded", true)
        keyboard.visibility = View.GONE
        candidates.setCandidateEntries(emptyList())
        assertEquals(View.VISIBLE, keyboard.visibility)
        assertEquals(false, ReflectionHelpers.getField<Boolean>(candidates, "expanded"))
        assertNumberLayout()
    }

    @Test
    fun engineWhitespaceCommitIsNotDiscardedAndKeepsNumberLayout() {
        val result = MoqiImeResult(
            success = true, handled = true, composition = "", commit = " \n",
            candidates = emptyList(), candidateEntries = emptyList(),
            showCandidates = false, message = "", error = ""
        )
        invokeService("applyMoqiResult", MoqiImeResult::class.java, result)
        assertEquals(" \n", connection.editable.toString())
        assertNumberLayout()
    }

    private fun assertNumberLayout() {
        assertEquals(KeyboardView.Layout.NUMBER, ReflectionHelpers.getField<KeyboardView.Layout>(keyboard, "currentLayout"))
    }

    private fun invokeService(name: String) {
        MoqiInputMethodService::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(service)
    }

    private fun invokeService(name: String, type: Class<*>, value: Any) {
        MoqiInputMethodService::class.java.getDeclaredMethod(name, type).apply { isAccessible = true }.invoke(service, value)
    }
}
