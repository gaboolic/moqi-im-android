package com.moqi.im.keyboard

import android.graphics.PointF
import android.graphics.RectF
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Real View regressions, separate from the pure PointerRollover state machine.
 * Long-press/repeat callbacks are invoked manually: these tests do not measure timing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class KeyboardPointerMotionTest {
    private val view = KeyboardView(RuntimeEnvironment.getApplication())
    private val keys = mutableListOf<Pair<Int, String?>>()
    private val voice = mutableListOf<Boolean>()
    private val fingers = linkedMapOf<Int, PointF>()

    init {
        view.setLayout(KeyboardView.Layout.T9_CN)
        view.setOnKeyListener { code, _, text -> keys.add(code to text) }
        view.setOnSpaceLongPressListener { voice.add(it) }
        view.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 1080, 720)
    }

    @After fun cleanUp() {
        if (fingers.isNotEmpty()) event(MotionEvent.ACTION_CANCEL)
        fingers.clear()
    }

    @Test fun geometryRequiresMeasureBeforeLayout() {
        val unmeasured = KeyboardView(RuntimeEnvironment.getApplication())
        unmeasured.setLayout(KeyboardView.Layout.T9_CN)
        unmeasured.layout(0, 0, 1080, 720)
        val rectField = KeyboardView::class.java.getDeclaredField("keyRects")
            .apply { isAccessible = true }
        assertTrue((rectField.get(unmeasured) as List<*>).isEmpty())
        assertTrue((field("keyRects") as List<*>).isNotEmpty())
        assertTrue(center(KeyCode.T9_2).x > 0)
    }

    @Test fun oldFingerUpFirstEmitsEachKeyOnce() {
        down(7, KeyCode.T9_2); down(19, KeyCode.T9_3)
        up(7); up(19)
        assertCodes(KeyCode.T9_2, KeyCode.T9_3)
    }

    @Test fun newFingerUpFirstMoveUsesActivePointerNotIndexZero() {
        down(7, KeyCode.T9_2); down(19, KeyCode.T9_3)
        event(MotionEvent.ACTION_MOVE)
        up(19); up(7)
        assertCodes(KeyCode.T9_2, KeyCode.T9_3)
    }

    @Test fun cancelDropsPendingKeyAndLongPress() {
        down(7, KeyCode.T9_2); down(19, KeyCode.T9_3)
        event(MotionEvent.ACTION_CANCEL)
        up(19); up(7)
        assertCodes(KeyCode.T9_2)
        assertNull(field("t9LetterLongPressRunnable"))
    }

    @Test fun deleteRepeatStopsAtRollover() {
        down(7, KeyCode.DELETE)
        runTimer("repeatRunnable")
        down(19, KeyCode.T9_3)
        assertNull(field("repeatRunnable"))
        up(7); up(19)
        assertCodes(KeyCode.DELETE, KeyCode.DELETE, KeyCode.T9_3)
    }

    @Test fun voiceOwnerReleaseStopsOnceWithoutTyping() {
        down(7, KeyCode.SPACE)
        runTimer("spaceLongPressRunnable")
        down(19, KeyCode.T9_3)
        up(7); up(19)
        assertEquals(listOf(true, false), voice)
        assertTrue(keys.isEmpty())
    }

    @Test fun voiceCancelStopsOnceWithoutTyping() {
        down(7, KeyCode.SPACE)
        runTimer("spaceLongPressRunnable")
        down(19, KeyCode.T9_3)
        event(MotionEvent.ACTION_CANCEL)
        up(19); up(7)
        assertEquals(listOf(true, false), voice)
        assertTrue(keys.isEmpty())
    }

    @Test fun letterPopupKeepsExclusiveOwnership() {
        down(7, KeyCode.T9_2)
        runTimer("t9LetterLongPressRunnable")
        down(19, KeyCode.T9_3)
        up(19); up(7)
        assertEquals(listOf('b'.code to "b"), keys)
    }

    @Test fun swipeCommitsBeforeNextFingerKey() {
        down(7, KeyCode.T9_2)
        val point = fingers.getValue(7)
        move(7, PointF(point.x, point.y + 100 * view.resources.displayMetrics.density))
        down(19, KeyCode.T9_3)
        up(7); up(19)
        assertEquals(2, keys.size)
        assertEquals("2", keys[0].second)
        assertEquals(KeyCode.T9_3, keys[1].first)
    }

    @Test fun sidebarScrollKeepsExclusiveOwnership() {
        down(7, '，'.code)
        val point = fingers.getValue(7)
        move(7, PointF(point.x, point.y - 50))
        down(19, KeyCode.T9_3)
        up(19); up(7)
        assertTrue(keys.isEmpty())
    }

    @Test fun rolloverReplacesLongPressWithNewKeyCallback() {
        down(7, KeyCode.T9_2)
        val old = field("t9LetterLongPressRunnable")
        down(19, KeyCode.T9_3)
        assertNotSame(old, field("t9LetterLongPressRunnable"))
        runTimer("t9LetterLongPressRunnable")
        up(7); up(19)
        assertEquals(listOf(KeyCode.T9_2 to null, 'e'.code to "e"), keys)
    }

    @Test fun qwertyCaiWithOldFingerLiftedFirstKeepsMiddleLetter() {
        useQwerty()
        down(7, KeyEvent.KEYCODE_C); down(19, KeyEvent.KEYCODE_A)
        up(7)
        down(7, KeyEvent.KEYCODE_I)
        up(19); up(7)
        assertCodes(KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_I)
    }

    @Test fun qwertyCaiWithActiveFingerLiftedFirstKeepsMiddleLetter() {
        useQwerty()
        down(7, KeyEvent.KEYCODE_C); down(19, KeyEvent.KEYCODE_A)
        up(19); up(7)
        down(7, KeyEvent.KEYCODE_I); up(7)
        assertCodes(KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_I)
    }

    @Test fun qwertyCaiWithThreeOverlappingFingersEmitsEachLetterOnce() {
        useQwerty()
        down(7, KeyEvent.KEYCODE_C); down(19, KeyEvent.KEYCODE_A); down(23, KeyEvent.KEYCODE_I)
        up(19); up(7); up(23)
        assertCodes(KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_I)
    }

    @Test fun qwertyCaiWithNonOverlappingTapsStaysUnchanged() {
        useQwerty()
        "cai".forEach { letter -> down(7, KeyEvent.KEYCODE_A + (letter - 'a')); up(7) }
        assertCodes(KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_I)
    }

    private fun useQwerty() {
        view.setLayout(KeyboardView.Layout.QWERTY_CN)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 1080, 720)
    }

    private fun field(name: String): Any? = KeyboardView::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(view)

    @Suppress("UNCHECKED_CAST")
    private fun center(code: Int): PointF {
        val rows = field("rows") as List<List<KeyDefinition>>
        val rects = field("keyRects") as List<List<RectF>>
        rows.forEachIndexed { row, entries ->
            val column = entries.indexOfFirst { it.keyCode == code }
            if (column >= 0) return rects[row][column].let { PointF(it.centerX(), it.centerY()) }
        }
        error("Missing key $code")
    }

    private fun down(id: Int, code: Int) {
        fingers[id] = center(code)
        event(if (fingers.size == 1) MotionEvent.ACTION_DOWN else
            MotionEvent.ACTION_POINTER_DOWN or (fingers.keys.indexOf(id) shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
    }

    private fun up(id: Int) {
        event(if (fingers.size == 1) MotionEvent.ACTION_UP else
            MotionEvent.ACTION_POINTER_UP or (fingers.keys.indexOf(id) shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
        fingers.remove(id)
    }

    private fun move(id: Int, point: PointF) {
        fingers[id] = point
        event(MotionEvent.ACTION_MOVE)
    }

    private fun assertCodes(vararg codes: Int) = assertEquals(codes.toList(), keys.map { it.first })

    // Deliberately run the queued callback, not a real hardware/Looper timing check.
    private fun runTimer(name: String) = (field(name) as Runnable).run()

    private fun event(action: Int) {
        val now = SystemClock.uptimeMillis()
        val properties = fingers.keys.map { id ->
            MotionEvent.PointerProperties().apply { this.id = id }
        }.toTypedArray()
        val coordinates = fingers.values.map { point ->
            MotionEvent.PointerCoords().apply { x = point.x; y = point.y }
        }.toTypedArray()
        val event = MotionEvent.obtain(now, now, action, fingers.size, properties, coordinates,
            0, 0, 1f, 1f, 0, 0, 0, 0)
        try { view.onTouchEvent(event) } finally { event.recycle() }
    }
}
