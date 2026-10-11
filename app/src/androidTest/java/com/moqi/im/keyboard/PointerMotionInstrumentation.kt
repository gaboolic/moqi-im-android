package com.moqi.im.keyboard

import android.app.Activity
import android.app.Instrumentation
import android.graphics.PointF
import android.graphics.RectF
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent

/** 配置为 instrumentation runner 后可运行；不依赖测试 Activity 或全局触摸注入。 */
class PointerMotionInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        var failures = 0
        val failure = runCatching {
            lateinit var cases: PointerMotionCases
            onMain { cases = PointerMotionCases(targetContext) }?.let { throw it }
            cases.run { name, index, block ->
                val status = Bundle().apply {
                    putString("id", "InstrumentationTestRunner")
                    putString("class", PointerMotionInstrumentation::class.java.name)
                    putString("test", name)
                    putInt("numtests", 8)
                    putInt("current", index)
                }
                sendStatus(1, status)
                val error = onMain(block)
                if (error != null) {
                    failures++
                    status.putString("stack", error.stackTraceToString())
                }
                sendStatus(if (error == null) 0 else -2, status)
            }
        }.exceptionOrNull()
        finish(if (failure == null && failures == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply {
                putString("result", failure?.stackTraceToString() ?:
                    if (failures == 0) "PASS: 8 cases" else "FAIL: $failures cases")
            })
    }

    private fun onMain(block: () -> Unit): Throwable? {
        var failure: Throwable? = null
        runOnMainSync { failure = runCatching(block).exceptionOrNull() }
        return failure
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
        view.layout(0, 0, 1080, 720)
    }

    fun run(record: (String, Int, () -> Unit) -> Unit) {
        record("oldFingerUpFirst", 1) { overlap(oldFirst = true) }
        record("newFingerUpFirst", 2) { overlap(oldFirst = false) }
        record("threeFingers", 3) { threeFingers() }
        record("cancelPending", 4) { cancelPending() }
        record("activeCoordinates", 5) { activeCoordinates() }
        record("deleteRollover", 6) { deleteRollover() }
        record("heldSpaceRelease", 7) { heldSpace(cancel = false) }
        record("heldSpaceCancel", 8) { heldSpace(cancel = true) }
    }

    private fun threeFingers() {
        reset()
        down(4, KeyCode.T9_2)
        down(8, KeyCode.T9_3)
        down(12, KeyCode.T9_4)
        up(8); up(4); up(12)
        expect(KeyCode.T9_2, KeyCode.T9_3, KeyCode.T9_4)
    }

    private fun overlap(oldFirst: Boolean) {
        reset()
        down(7, KeyCode.T9_2)
        down(19, KeyCode.T9_3)
        up(if (oldFirst) 7 else 19)
        up(if (oldFirst) 19 else 7)
        expect(KeyCode.T9_2, KeyCode.T9_3)
    }

    private fun cancelPending() {
        reset()
        down(7, KeyCode.T9_2)
        down(19, KeyCode.T9_3)
        dispatch(MotionEvent.ACTION_CANCEL)
        up(19); up(7)
        expect(KeyCode.T9_2)
        check(field("t9LetterLongPressRunnable") == null)
    }

    private fun activeCoordinates() {
        reset()
        down(7, KeyCode.T9_2)
        down(19, KeyCode.T9_3)
        // MOVE 的索引 0 仍是旧手指；不能把活动键改回旧手指所在的 2。
        dispatch(MotionEvent.ACTION_MOVE)
        up(19); up(7)
        expect(KeyCode.T9_2, KeyCode.T9_3)
    }

    private fun deleteRollover() {
        reset()
        down(7, KeyCode.DELETE)
        (field("repeatRunnable") as Runnable).run()
        down(19, KeyCode.T9_3)
        check(field("repeatRunnable") == null)
        up(7); up(19)
        expect(KeyCode.DELETE, KeyCode.DELETE, KeyCode.T9_3)
    }

    private fun heldSpace(cancel: Boolean) {
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
