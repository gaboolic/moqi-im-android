package com.moqi.im.keyboard

import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
class StrokeKeyboardViewTest {
    private val context = RuntimeEnvironment.getApplication()
    private val keys = mutableListOf<Pair<Int, String?>>()
    private val view = KeyboardView(context).apply {
        setOnKeyListener { code, _, text -> keys.add(code to text) }
    }

    @Test
    fun strokeKeysHaveNaturalOrderAndEmitOnlyDedicatedEvents() {
        show(KeyboardView.Layout.T9_CN)
        view.setT9PinyinOptions(listOf("ni"))
        show(KeyboardView.Layout.T9_STROKE)
        view.setT9PinyinOptions(listOf("hao"))
        assertFalse(view.isDirectCommitLayout())
        val strokeKeys = rows()[0].subList(1, 4) + rows()[1].subList(1, 3)
        assertEquals(listOf("一", "丨", "丿", "丶", "𠃋"), strokeKeys.map { it.label })
        assertEquals(listOf("1", "2", "3", "4", "5"), strokeKeys.map { it.swipeText })
        assertTrue(strokeKeys.none { it.isRepeatable })

        tap(0, 1)
        tap(0, 2)
        tap(0, 3)
        tap(1, 1)
        tap(1, 2)
        assertEquals(
            listOf(KeyCode.STROKE_HENG, KeyCode.STROKE_SHU, KeyCode.STROKE_PIE, KeyCode.STROKE_NA, KeyCode.STROKE_ZHE)
                .map { it to null },
            keys,
        )
    }

    @Test
    fun sidePanelKeepsFourPunctuationKeysEvenWithPinyinOptions() {
        show(KeyboardView.Layout.T9_CN)
        view.setT9PinyinOptions(listOf("ni", "ning", "nin", "mi", "min"))
        ReflectionHelpers.setField(view, "t9SidePanelScrollOffset", 25f)
        show(KeyboardView.Layout.T9_STROKE)
        view.setT9PinyinOptions(listOf("hao"), showFallback = false)
        val panel = ReflectionHelpers.getField<RectF>(view, "t9SidePanelRect")
        assertFalse(panel.isEmpty)
        for (i in 0..3) {
            val downTime = SystemClock.uptimeMillis()
            val y = panel.top + (i + 0.5f) * panel.height() / 4f
            touchAt(panel.centerX(), y, MotionEvent.ACTION_DOWN, downTime)
            touchAt(panel.centerX(), y, MotionEvent.ACTION_UP, downTime)
        }
        tap(1, 4)
        tap(2, 4)
        assertEquals(listOf('，', '。', '？', '！').map { it.code to it.toString() } +
            listOf(KeyCode.RETYPE to null, '0'.code to "0"), keys)
    }

    @Test
    fun sixAndSevenTapAsStrokeActions() {
        show(KeyboardView.Layout.T9_STROKE)
        assertEquals("通配" to "6", rows()[1][3].let { it.label to it.swipeText })
        assertEquals("分词" to "7", rows()[2][1].let { it.label to it.swipeText })
        tap(1, 3)
        tap(2, 1)
        assertEquals(listOf(KeyCode.STROKE_WILDCARD to null, KeyCode.STROKE_SEPARATOR to null), keys)
    }

    @Test
    fun strokeSwipeEmitsDigitInsteadOfStroke() {
        show(KeyboardView.Layout.T9_STROKE)
        val rects = ReflectionHelpers.getField<List<List<RectF>>>(view, "keyRects")
        for (rect in listOf(rects[0][1], rects[1][3], rects[2][1])) {
            val downTime = SystemClock.uptimeMillis()
            touchAt(rect.centerX(), rect.centerY(), MotionEvent.ACTION_DOWN, downTime)
            touchAt(rect.centerX(), rect.centerY() + 160f, MotionEvent.ACTION_MOVE, downTime)
            touchAt(rect.centerX(), rect.centerY() + 160f, MotionEvent.ACTION_UP, downTime)
        }
        assertEquals(listOf(KeyCode.STROKE_HENG to "1", KeyCode.STROKE_WILDCARD to "6",
            KeyCode.STROKE_SEPARATOR to "7"), keys)
    }

    @Test
    fun strokeKeysDoNotStartLetterPopupAndDeleteStillRepeats() {
        show(KeyboardView.Layout.T9_STROKE)
        val downTime = SystemClock.uptimeMillis()
        touch(0, 1, MotionEvent.ACTION_DOWN, downTime)
        assertNull(ReflectionHelpers.getField<Runnable?>(view, "t9LetterLongPressRunnable"))
        touch(0, 1, MotionEvent.ACTION_UP, downTime)
        touch(0, 4, MotionEvent.ACTION_DOWN, downTime)
        ReflectionHelpers.getField<Runnable>(view, "repeatRunnable").run()
        touch(0, 4, MotionEvent.ACTION_UP, downTime)
        assertEquals(listOf(KeyCode.STROKE_HENG, KeyCode.DELETE, KeyCode.DELETE), keys.map { it.first })
        assertNull(ReflectionHelpers.getField<Runnable?>(view, "repeatRunnable"))
    }

    @Test
    fun panelKeysRetainLiteralInputAndReturnEvents() {
        show(KeyboardView.Layout.T9_STROKE)
        tap(3, 0)
        tap(3, 1)
        tap(3, 4)
        show(KeyboardView.Layout.SYMBOL)
        tap(0, 2)
        tap(4, 0)
        show(KeyboardView.Layout.NUMBER)
        tap(0, 1)
        tap(3, 1)
        assertEquals(
            listOf(
                KeyCode.SYMBOL_LAYOUT to null,
                KeyCode.NUMBER_LAYOUT to null,
                KeyCode.ENTER to null,
                '，'.code to "，",
                KeyCode.RETURN_TO_TEXT to null,
                '1'.code to "1",
                KeyCode.RETURN_TO_TEXT to null,
            ),
            keys,
        )
    }

    @Test
    fun strokeBottomRowUsesTheSharedEditorOrderAndReset() {
        val row = KeyboardBottomRowLayout.Row.STROKE
        try {
            KeyboardBottomRowLayout.save(context, row, row.defaultOrder.reversed())
            show(KeyboardView.Layout.T9_STROKE)
            assertEquals(KeyCode.ENTER, rows().last().first().keyCode)
            KeyboardBottomRowLayout.resetAll(context)
            show(KeyboardView.Layout.T9_STROKE)
            assertEquals(KeyCode.SYMBOL_LAYOUT, rows().last().first().keyCode)
        } finally {
            KeyboardBottomRowLayout.reset(context, row)
        }
    }

    private fun show(layout: KeyboardView.Layout) {
        view.setLayout(layout)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 1080, 720)
    }

    private fun rows(): List<List<KeyDefinition>> = ReflectionHelpers.getField(view, "rows")

    private fun tap(row: Int, col: Int) {
        val downTime = SystemClock.uptimeMillis()
        touch(row, col, MotionEvent.ACTION_DOWN, downTime)
        touch(row, col, MotionEvent.ACTION_UP, downTime)
    }

    private fun touch(row: Int, col: Int, action: Int, downTime: Long) {
        val rect = ReflectionHelpers.getField<List<List<RectF>>>(view, "keyRects")[row][col]
        touchAt(rect.centerX(), rect.centerY(), action, downTime)
    }

    private fun touchAt(x: Float, y: Float, action: Int, downTime: Long) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        try {
            view.onTouchEvent(event)
        } finally {
            event.recycle()
        }
    }
}
