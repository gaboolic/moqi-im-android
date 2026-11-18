package com.moqi.im.keyboard

import android.test.InstrumentationTestCase
import android.graphics.PointF
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent

/** Standard runner tests; no Activity or global touch injection required. */
@Suppress("DEPRECATION")
class PointerMotionInstrumentation : InstrumentationTestCase() {
    fun testOldFingerUpFirst() = onMain { overlap(oldFirst = true) }
    fun testNewFingerUpFirst() = onMain { overlap(oldFirst = false) }
    fun testThreeFingers() = onMain { threeFingers() }
    fun testCancelPending() = onMain { cancelPending() }
    fun testActiveCoordinates() = onMain { activeCoordinates() }
    fun testDeleteRollover() = onMain { deleteRollover() }
    fun testHeldSpaceRelease() = onMain { heldSpace(cancel = false) }
    fun testHeldSpaceCancel() = onMain { heldSpace(cancel = true) }

    private fun onMain(block: PointerMotionCases.() -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            failure = runCatching {
                val cases = PointerMotionCases(instrumentation.targetContext)
                try { cases.block() } finally { cases.dispose() }
            }.exceptionOrNull()
        }
        failure?.let { throw it }
    }
}

private class PointerMotionCases(context: android.content.Context) {
    private val view = KeyboardView(context)
    private val keys = mutableListOf<Int>()
    private val voice = mutableListOf<Boolean>()
    private val fingers = linkedMapOf<Int, PointF>()

    init {
        view.setLayout(KeyboardView.Layout.T9_CN)
        view.setOnKeyListener { code, _, _ -> keys.add(code) }
        view.setOnSpaceLongPressListener { voice.add(it) }
        // KeyboardView initializes keyRects in onMeasure, not in layout.
        view.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(720, android.view.View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 1080, 720)
    }

    fun dispose() {
        // Clear pending long-press/repeat work even when an assertion fails.
        if (fingers.isNotEmpty()) dispatch(MotionEvent.ACTION_CANCEL)
        fingers.clear()
    }

    fun threeFingers() {
        reset()
        down(4, KeyCode.T9_2)
        down(8, KeyCode.T9_3)
        down(12, KeyCode.T9_4)
        up(8); up(4); up(12)
        expect(KeyCode.T9_2, KeyCode.T9_3, KeyCode.T9_4)
    }

    fun overlap(oldFirst: Boolean) {
        reset()
        down(7, KeyCode.T9_2)
        down(19, KeyCode.T9_3)
        up(if (oldFirst) 7 else 19)
        up(if (oldFirst) 19 else 7)
        expect(KeyCode.T9_2, KeyCode.T9_3)
    }

    fun cancelPending() {
        reset()
        down(7, KeyCode.T9_2)
        down(19, KeyCode.T9_3)
        dispatch(MotionEvent.ACTION_CANCEL)
        up(19); up(7)
        expect(KeyCode.T9_2)
        check(field("t9LetterLongPressRunnable") == null)
    }

    fun activeCoordinates() {
        reset()
        down(7, KeyCode.T9_2)
        down(19, KeyCode.T9_3)
        // MOVE 的索引 0 仍是旧手指；不能把活动键改回旧手指所在的 2。
        dispatch(MotionEvent.ACTION_MOVE)
        up(19); up(7)
        expect(KeyCode.T9_2, KeyCode.T9_3)
    }

    fun deleteRollover() {
        reset()
        down(7, KeyCode.DELETE)
        (field("repeatRunnable") as Runnable).run()
        down(19, KeyCode.T9_3)
        check(field("repeatRunnable") == null)
        up(7); up(19)
        expect(KeyCode.DELETE, KeyCode.DELETE, KeyCode.T9_3)
    }

    fun heldSpace(cancel: Boolean) {
        reset()
        down(7, KeyCode.SPACE)
        // 直接触发已排队回调，不依赖测试设备的定时器调度。
        (field("spaceLongPressRunnable") as Runnable).run()
        down(19, KeyCode.T9_3)
        up(19)
        check(voice == listOf(true))
        if (cancel) dispatch(MotionEvent.ACTION_CANCEL)
        up(7)
        expect()
        check(voice == listOf(true, false))
        check(field("spaceLongPressRunnable") == null)
    }

    private fun reset() {
        if (fingers.isNotEmpty()) dispatch(MotionEvent.ACTION_CANCEL)
        fingers.clear()
        keys.clear()
        voice.clear()
    }

    private fun down(id: Int, code: Int) {
        fingers[id] = center(code)
        val index = fingers.keys.indexOf(id)
        dispatch(if (fingers.size == 1) MotionEvent.ACTION_DOWN else
            MotionEvent.ACTION_POINTER_DOWN or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
    }

    private fun up(id: Int) {
        val index = fingers.keys.indexOf(id)
        dispatch(if (fingers.size == 1) MotionEvent.ACTION_UP else
            MotionEvent.ACTION_POINTER_UP or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
        fingers.remove(id)
    }

    private fun expect(vararg expected: Int) {
        check(keys == expected.toList()) { "expected=${expected.toList()}, actual=$keys" }
    }

    private fun field(name: String): Any? = KeyboardView::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(view)

    @Suppress("UNCHECKED_CAST")
    private fun center(code: Int): PointF {
        val rows = field("rows") as List<List<KeyDefinition>>
        val rects = field("keyRects") as List<List<RectF>>
        rows.forEachIndexed { row, entries ->
            val col = entries.indexOfFirst { it.keyCode == code }
            if (col >= 0) return rects[row][col].let { PointF(it.centerX(), it.centerY()) }
        }
        error("missing key $code")
    }

    private fun dispatch(action: Int) {
        val properties = fingers.keys.map { id ->
            MotionEvent.PointerProperties().apply { this.id = id }
        }.toTypedArray()
        val coords = fingers.values.map { point ->
            MotionEvent.PointerCoords().apply { x = point.x; y = point.y }
        }.toTypedArray()
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, fingers.size, properties, coords,
            0, 0, 1f, 1f, 0, 0, 0, 0)
        try { view.onTouchEvent(event) } finally { event.recycle() }
    }
}
