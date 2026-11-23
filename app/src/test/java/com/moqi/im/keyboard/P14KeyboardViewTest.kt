package com.moqi.im.keyboard

import android.graphics.RectF
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class P14KeyboardViewTest {
    private val events = mutableListOf<Pair<Int, String?>>()
    private val view = KeyboardView(RuntimeEnvironment.getApplication()).apply {
        setOnKeyListener { code, _, text -> events += code to text }
    }

    @Test
    fun pinyinGroupsTapAsDedicatedEventsInQwertyOrder() {
        show(KeyboardView.Layout.T9_CN)
        view.setT9PinyinOptions(listOf("ni"))
        show(KeyboardView.Layout.P14_CN)
        view.setT9PinyinOptions(listOf("hao"), showFallback = false)
        assertFalse(view.isDirectCommitLayout())

        val definitions = rows()
        val groups = listOf(
            definitions[0].take(5),
            definitions[1].take(5),
            definitions[2].subList(1, 5),
        )
        assertEquals(
            listOf(listOf("QW", "ER", "TY", "UI", "OP"), listOf("AS", "DF", "GH", "JK", "L"),
                listOf("ZX", "CV", "BN", "M")),
            groups.map { row -> row.map { it.label } },
        )
        assertEquals(listOf(5, 5, 6, 7), definitions.map { it.size })
        assertEquals(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "`", "!", "@", "/"),
            groups.flatten().map { it.subLabel })
        assertTrue(groups.flatten().none { it.isRepeatable })

        for (column in 0..4) tap(0, column)
        for (column in 0..4) tap(1, column)
        for (column in 1..4) tap(2, column)
        swipeDown(3, 2) // Comma's apostrophe alternate is the Rime separator.
        val expectedCodes = listOf(
            KeyCode.P14_QW, KeyCode.P14_ER, KeyCode.P14_TY, KeyCode.P14_UI, KeyCode.P14_OP,
            KeyCode.P14_AS, KeyCode.P14_DF, KeyCode.P14_GH, KeyCode.P14_JK, KeyCode.P14_L,
            KeyCode.P14_ZX, KeyCode.P14_CV, KeyCode.P14_BN, KeyCode.P14_M, KeyCode.P14_SEPARATOR,
        )
        assertEquals(15, expectedCodes.toSet().size)
        assertEquals(expectedCodes.map { it to null }, events)
    }

    @Test
    fun controlsKeepExistingCodesAndDeleteRepeats() {
        show(KeyboardView.Layout.P14_CN)
        val downTime = SystemClock.uptimeMillis()
        tap(2, 0)
        touch(2, 5, MotionEvent.ACTION_DOWN, downTime)
        ReflectionHelpers.getField<Runnable>(view, "repeatRunnable").run()
        touch(2, 5, MotionEvent.ACTION_UP, downTime)
        for (column in 0..6) tap(3, column)

        assertEquals(
            listOf(KeyCode.SHIFT, KeyCode.DELETE, KeyCode.DELETE, KeyCode.SYMBOL_LAYOUT,
                KeyCode.NUMBER_LAYOUT, KeyCode.COMMA, KeyCode.SPACE, KeyCode.PERIOD, KeyCode.MODE_SWITCH, KeyCode.ENTER)
                .map { it to null },
            events,
        )
    }

    @Test
    fun groupedKeysSwipeAsDigitsOrSymbols() {
        show(KeyboardView.Layout.P14_CN)
        swipeDown(0, 0)
        swipeDown(1, 4)
        for (column in 1..4) swipeDown(2, column)
        assertEquals(listOf(
            KeyCode.P14_QW to "1", KeyCode.P14_L to "0", KeyCode.P14_ZX to "`",
            KeyCode.P14_CV to "!", KeyCode.P14_BN to "@", KeyCode.P14_M to "/",
        ), events)
    }

    @Test
    fun englishKeyboardStaysIndependentAndSymbolPanelFollowsTextLanguage() {
        show(KeyboardView.Layout.QWERTY_EN)
        tap(0, 0)
        show(KeyboardView.Layout.SYMBOL)
        tap(0, 2)
        show(KeyboardView.Layout.P14_CN)
        show(KeyboardView.Layout.SYMBOL)
        tap(0, 2)
        show(KeyboardView.Layout.QWERTY_EN)
        tap(0, 0)

        assertEquals(listOf(KeyEvent.KEYCODE_Q to null, ','.code to ",", '，'.code to "，",
            KeyEvent.KEYCODE_Q to null), events)
    }

    private fun show(layout: KeyboardView.Layout) {
        view.setLayout(layout)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 360, 240)
    }

    private fun rows(): List<List<KeyDefinition>> = ReflectionHelpers.getField(view, "rows")

    private fun tap(row: Int, column: Int) {
        val downTime = SystemClock.uptimeMillis()
        touch(row, column, MotionEvent.ACTION_DOWN, downTime)
        touch(row, column, MotionEvent.ACTION_UP, downTime)
    }

    private fun swipeDown(row: Int, column: Int) {
        val downTime = SystemClock.uptimeMillis()
        touch(row, column, MotionEvent.ACTION_DOWN, downTime)
        touch(row, column, MotionEvent.ACTION_MOVE, downTime, 100f)
        touch(row, column, MotionEvent.ACTION_UP, downTime, 100f)
    }

    private fun touch(row: Int, column: Int, action: Int, downTime: Long, offsetY: Float = 0f) {
        val rect = ReflectionHelpers.getField<List<List<RectF>>>(view, "keyRects")[row][column]
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, rect.centerX(), rect.centerY() + offsetY, 0)
        try {
            view.onTouchEvent(event)
        } finally {
            event.recycle()
        }
    }
}
