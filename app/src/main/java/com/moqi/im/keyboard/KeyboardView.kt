package com.moqi.im.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import com.moqi.im.R

class KeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Layout {
        QWERTY_CN, QWERTY_EN, T9_CN, T9_EN, NUMBER, SYMBOL, VOICE
    }

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val subLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val specialKeyPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var keyWidth: Float = 0f
    private var keyHeight: Float = 0f
    private var keyGap: Float = 0f
    private var currentLayout: Layout = Layout.QWERTY_CN

    private var rows: List<List<KeyDefinition>> = emptyList()
    private var keyRects: List<List<RectF>> = emptyList()
    private var pressedKey: Pair<Int, Int>? = null
    private var repeatingKey: Pair<Int, Int>? = null
    private var repeatRunnable: Runnable? = null
    private var touchStartKey: Pair<Int, Int>? = null
    private var touchStartX: Float = 0f
    private var touchStartY: Float = 0f
    private var swipeTriggered: Boolean = false
    private var spaceLongPressRunnable: Runnable? = null
    private var spaceLongPressTriggered: Boolean = false

    private var isShifted: Boolean = false
    private var onKeyListener: ((Int, Boolean, String?) -> Unit)? = null
    private var onSpaceLongPressListener: ((Boolean) -> Unit)? = null

    private val isDarkMode: Boolean
        get() = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES

    init {
        setLayout(Layout.QWERTY_CN)
    }

    fun setLayout(layout: Layout) {
        currentLayout = layout
        rows = when (layout) {
            Layout.QWERTY_CN -> qwertyCnRows()
            Layout.QWERTY_EN -> qwertyEnRows()
            Layout.T9_CN -> t9CnRows()
            Layout.T9_EN -> t9EnRows()
            Layout.NUMBER -> numberRows()
            Layout.SYMBOL -> symbolRows()
            Layout.VOICE -> voiceRows()
        }
        requestLayout()
        invalidate()
    }

    fun setShifted(shifted: Boolean) {
        isShifted = shifted
        invalidate()
    }

    fun setOnKeyListener(listener: (Int, Boolean, String?) -> Unit) {
        onKeyListener = listener
    }

    fun setOnSpaceLongPressListener(listener: (Boolean) -> Unit) {
        onSpaceLongPressListener = listener
    }

    fun showVoiceMode() {
        setLayout(Layout.VOICE)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val desiredHeight = MeasureSpec.getSize(heightMeasureSpec).takeIf { it > 0 }
            ?: (resources.displayMetrics.heightPixels * 0.28f).toInt()
        keyGap = (width * 0.006f).coerceAtLeast(4f * resources.displayMetrics.density)
        val rowCount = rows.size.coerceAtLeast(1)
        keyHeight = (desiredHeight - keyGap * (rowCount + 1)) / rowCount

        calculateKeyRects(width)
        setMeasuredDimension(width, desiredHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        updatePaintColors()

        for ((rowIdx, row) in keyRects.withIndex()) {
            for ((colIdx, rect) in row.withIndex()) {
                val key = rows.getOrNull(rowIdx)?.getOrNull(colIdx) ?: continue
                drawKey(canvas, rect, key, pressedKey == Pair(rowIdx, colIdx))
            }
        }
    }

    private fun updatePaintColors() {
        val dark = isDarkMode
        labelPaint.color = if (dark) 0xFFE0E0E8.toInt() else 0xFF1A1A2E.toInt()
        labelPaint.textSize = if (isT9Layout() || isNumberOrSymbolLayout()) 38f else 42f
        labelPaint.textAlign = Paint.Align.CENTER
        subLabelPaint.color = if (dark) 0xFF9090AA.toInt() else 0xFF606080.toInt()
        subLabelPaint.textSize = if (isT9Layout() || isNumberOrSymbolLayout()) 16f else 14f
        subLabelPaint.textAlign = Paint.Align.CENTER
        specialKeyPaint.color = if (dark) 0xFFE0E0E8.toInt() else 0xFF1A1A2E.toInt()
        specialKeyPaint.textSize = 28f
        specialKeyPaint.textAlign = Paint.Align.CENTER
        val bgColor = if (dark) 0xFF1A1A2E.toInt() else 0xFFF0F0F5.toInt()
        setBackgroundColor(bgColor)
    }

    private fun drawKey(canvas: Canvas, rect: RectF, key: KeyDefinition, pressed: Boolean) {
        val cornerRadius = 8f * resources.displayMetrics.density
        val dark = isDarkMode

        val bgColor = when {
            pressed -> if (dark) 0xFF4A4A5E.toInt() else 0xFFC0C0CC.toInt()
            key.isSticky -> if (dark) 0xFF3A3A5E.toInt() else 0xFFD0D0D8.toInt()
            isSpecialKey(key) -> if (dark) 0xFF2A2A3E.toInt() else 0xFFE0E0E8.toInt()
            else -> if (dark) 0xFF2A2A42.toInt() else 0xFFFFFFFF.toInt()
        }

        keyPaint.color = bgColor
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, keyPaint)

        val textPaint = if (isSpecialKey(key)) specialKeyPaint else labelPaint
        val text = if (isSpecialKey(key)) key.label else {
            if (isShifted && key.keyCode >= KeyEvent.KEYCODE_A && key.keyCode <= KeyEvent.KEYCODE_Z) {
                key.label.uppercase()
            } else {
                key.label
            }
        }

        val textBaseline = if (key.swipeText.isNullOrBlank() || isSpecialKey(key)) {
            rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
        } else {
            rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f - rect.height() * 0.08f
        }
        canvas.drawText(text, rect.centerX(), textBaseline, textPaint)

        val subLabel = key.subLabel ?: key.swipeText
        if (subLabel != null && !isSpecialKey(key)) {
            subLabelPaint.color = if (dark) 0xFF9090AA.toInt() else 0xFF606080.toInt()
            val subBaseline = if (isT9Layout()) {
                rect.top + 18f * resources.displayMetrics.density
            } else {
                rect.bottom - 10f * resources.displayMetrics.density
            }
            canvas.drawText(subLabel, rect.centerX(), subBaseline, subLabelPaint)
        }
    }

    private fun drawVoiceMode(canvas: Canvas) {
        // Voice layout is now rendered as keys, not custom drawing
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hit = findKeyAt(event.x, event.y)
                touchStartKey = hit
                touchStartX = event.x
                touchStartY = event.y
                swipeTriggered = false
                pressedKey = hit
                startKeyRepeatIfNeeded(hit)
                startSpaceLongPressIfNeeded(hit)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (updateSwipeState(event.x, event.y)) {
                    invalidate()
                    return true
                }
                val hit = findKeyAt(event.x, event.y)
                if (hit == pressedKey) return true
                stopSpaceLongPress(endVoice = true)
                stopKeyRepeat()
                pressedKey = hit
                startKeyRepeatIfNeeded(hit)
                startSpaceLongPressIfNeeded(hit)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                val repeatWasActive = repeatRunnable != null
                val spaceLongPressWasActive = spaceLongPressTriggered
                val startKey = touchStartKey
                if (swipeTriggered && startKey != null) {
                    dispatchSwipeKey(startKey)
                } else if (!spaceLongPressWasActive) {
                    pressedKey?.let { keyPos ->
                        if (!repeatWasActive) {
                            dispatchKey(keyPos)
                        }
                    }
                }
                stopSpaceLongPress(endVoice = spaceLongPressWasActive)
                clearTouchTracking()
                stopKeyRepeat()
                pressedKey = null
                invalidate()
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                stopSpaceLongPress(endVoice = spaceLongPressTriggered)
                clearTouchTracking()
                stopKeyRepeat()
                pressedKey = null
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun updateSwipeState(x: Float, y: Float): Boolean {
        if (swipeTriggered) {
            return true
        }
        val startKey = touchStartKey ?: return false
        val key = keyAt(startKey) ?: return false
        if (key.swipeText.isNullOrBlank() || key.isRepeatable) {
            return false
        }
        val dx = x - touchStartX
        val dy = y - touchStartY
        if (dy > swipeThresholdPx() && kotlin.math.abs(dy) > kotlin.math.abs(dx)) {
            swipeTriggered = true
            pressedKey = startKey
            stopKeyRepeat()
            stopSpaceLongPress(endVoice = spaceLongPressTriggered)
            return true
        }
        return false
    }

    private fun clearTouchTracking() {
        touchStartKey = null
        touchStartX = 0f
        touchStartY = 0f
        swipeTriggered = false
    }

    private fun swipeThresholdPx(): Float {
        return SWIPE_INPUT_THRESHOLD_DP * resources.displayMetrics.density
    }

    private fun startKeyRepeatIfNeeded(keyPos: Pair<Int, Int>?) {
        stopKeyRepeat()
        val key = keyPos?.let { keyAt(it) } ?: return
        if (!key.isRepeatable) return

        repeatingKey = keyPos
        dispatchKey(keyPos)
        repeatRunnable = object : Runnable {
            override fun run() {
                if (repeatingKey == keyPos && pressedKey == keyPos) {
                    dispatchKey(keyPos)
                    postDelayed(this, KEY_REPEAT_INTERVAL_MS)
                }
            }
        }
        postDelayed(repeatRunnable!!, KEY_REPEAT_INITIAL_DELAY_MS)
    }

    private fun stopKeyRepeat() {
        repeatRunnable?.let { removeCallbacks(it) }
        repeatRunnable = null
        repeatingKey = null
    }

    private fun startSpaceLongPressIfNeeded(keyPos: Pair<Int, Int>?) {
        stopSpaceLongPress(endVoice = false)
        val key = keyPos?.let { keyAt(it) } ?: return
        if (key.keyCode != KeyCode.SPACE || key.isRepeatable) return
        spaceLongPressTriggered = false
        spaceLongPressRunnable = Runnable {
            if (pressedKey == keyPos && touchStartKey == keyPos) {
                spaceLongPressTriggered = true
                onSpaceLongPressListener?.invoke(true)
            }
        }
        postDelayed(spaceLongPressRunnable!!, SPACE_LONG_PRESS_DELAY_MS)
    }

    private fun stopSpaceLongPress(endVoice: Boolean) {
        spaceLongPressRunnable?.let { removeCallbacks(it) }
        spaceLongPressRunnable = null
        if (endVoice) {
            onSpaceLongPressListener?.invoke(false)
        }
        spaceLongPressTriggered = false
    }

    private fun dispatchKey(keyPos: Pair<Int, Int>) {
        val key = keyAt(keyPos) ?: return
        onKeyListener?.invoke(key.keyCode, isShifted, null)
    }

    private fun dispatchSwipeKey(keyPos: Pair<Int, Int>) {
        val key = keyAt(keyPos) ?: return
        val swipeText = key.swipeText ?: return
        onKeyListener?.invoke(key.keyCode, isShifted, swipeText)
    }

    private fun keyAt(keyPos: Pair<Int, Int>): KeyDefinition? {
        val (row, col) = keyPos
        return rows.getOrNull(row)?.getOrNull(col)
    }

    private fun findKeyAt(x: Float, y: Float): Pair<Int, Int>? {
        for ((rowIdx, row) in keyRects.withIndex()) {
            for ((colIdx, rect) in row.withIndex()) {
                if (rect.contains(x, y)) {
                    return Pair(rowIdx, colIdx)
                }
            }
        }
        return null
    }

    private fun calculateKeyRects(totalWidth: Int) {
        keyRects = rows.mapIndexed { rowIdx, row ->
            val totalWeight = row.sumOf { it.widthFactor.toDouble() }.toFloat().coerceAtLeast(1f)
            val rowGapWidth = keyGap * (row.size + 1)
            val unitWidth = (totalWidth - rowGapWidth).coerceAtLeast(0f) / totalWeight
            var x = keyGap
            row.map { key ->
                val w = key.widthFactor * unitWidth
                val rect = RectF(x, rowIdx * (keyHeight + keyGap) + keyGap, x + w, (rowIdx + 1) * keyHeight + rowIdx * keyGap + keyGap)
                x += w + keyGap
                rect
            }
        }
    }

    companion object {
        private const val KEY_REPEAT_INITIAL_DELAY_MS = 350L
        private const val KEY_REPEAT_INTERVAL_MS = 70L
        private const val SPACE_LONG_PRESS_DELAY_MS = 280L
        private const val SWIPE_INPUT_THRESHOLD_DP = 36f
    }

    private fun isSpecialKey(key: KeyDefinition): Boolean =
        (key.keyCode < 0 && !isT9InputKey(key.keyCode) && !isT9PunctuationKey(key.keyCode)) || key.isSticky

    private fun isT9Layout(): Boolean =
        currentLayout == Layout.T9_CN || currentLayout == Layout.T9_EN

    private fun isNumberOrSymbolLayout(): Boolean =
        currentLayout == Layout.NUMBER || currentLayout == Layout.SYMBOL

    private fun isT9InputKey(keyCode: Int): Boolean =
        keyCode in KeyCode.T9_POUND..KeyCode.T9_1

    private fun isT9PunctuationKey(keyCode: Int): Boolean =
        isT9Layout() && (keyCode == KeyCode.COMMA || keyCode == KeyCode.PERIOD)

    private fun qwertyCnRows(): List<List<KeyDefinition>> = listOf(
        rowOf("qwertyuiop", "1234567890"),
        rowOf("asdfghjkl", listOf("@", "*", "+", "-", "=", "/", "#", "(", ")")),
        rowOfWithExtras("zxcvbnm", listOf("'", ":", "\"", "?", "!", "~", "\\")),
        bottomRowCn()
    )

    private fun qwertyEnRows(): List<List<KeyDefinition>> = listOf(
        rowOf("qwertyuiop", "1234567890"),
        rowOf("asdfghjkl", listOf("@", "*", "+", "-", "=", "/", "#", "(", ")")),
        rowOfWithExtras("zxcvbnm", listOf("'", ":", "\"", "?", "!", "~", "\\")),
        bottomRowEn()
    )

    private fun t9CnRows(): List<List<KeyDefinition>> = listOf(
        listOf(
            KeyDefinition("，", KeyCode.COMMA, 0.72f),
            KeyDefinition("1", KeyCode.T9_1, 1f),
            KeyDefinition("ABC", KeyCode.T9_2, 1f, subLabel = "2"),
            KeyDefinition("DEF", KeyCode.T9_3, 1f, subLabel = "3"),
            KeyDefinition("⌫", KeyCode.DELETE, 0.72f, isRepeatable = true)
        ),
        listOf(
            KeyDefinition("。", KeyCode.PERIOD, 0.72f),
            KeyDefinition("GHI", KeyCode.T9_4, 1f, subLabel = "4"),
            KeyDefinition("JKL", KeyCode.T9_5, 1f, subLabel = "5"),
            KeyDefinition("MNO", KeyCode.T9_6, 1f, subLabel = "6"),
            KeyDefinition("重输", KeyCode.RETYPE, 0.72f)
        ),
        listOf(
            KeyDefinition("?", '?'.code, 0.72f),
            KeyDefinition("PQRS", KeyCode.T9_7, 1f, subLabel = "7"),
            KeyDefinition("TUV", KeyCode.T9_8, 1f, subLabel = "8"),
            KeyDefinition("WXYZ", KeyCode.T9_9, 1f, subLabel = "9"),
            KeyDefinition("0", KeyCode.T9_0, 0.72f)
        ),
        listOf(
            KeyDefinition("符", KeyCode.SYMBOL_LAYOUT, 0.66f),
            KeyDefinition("123", KeyCode.NUMBER_LAYOUT, 0.66f),
            KeyDefinition("空格 🎤", KeyCode.SPACE, 1.5f),
            KeyDefinition("中/英", KeyCode.MODE_SWITCH, 0.66f),
            KeyDefinition("...", KeyCode.MENU, 0.66f),
            KeyDefinition("↵", KeyCode.ENTER, 0.66f)
        )
    )

    private fun t9EnRows(): List<List<KeyDefinition>> = listOf(
        listOf(
            KeyDefinition(",", KeyCode.COMMA, 0.72f),
            KeyDefinition("1", KeyCode.T9_1, 1f),
            KeyDefinition("ABC", KeyCode.T9_2, 1f, subLabel = "2"),
            KeyDefinition("DEF", KeyCode.T9_3, 1f, subLabel = "3"),
            KeyDefinition("⌫", KeyCode.DELETE, 0.72f, isRepeatable = true)
        ),
        listOf(
            KeyDefinition(".", KeyCode.PERIOD, 0.72f),
            KeyDefinition("GHI", KeyCode.T9_4, 1f, subLabel = "4"),
            KeyDefinition("JKL", KeyCode.T9_5, 1f, subLabel = "5"),
            KeyDefinition("MNO", KeyCode.T9_6, 1f, subLabel = "6"),
            KeyDefinition("Redo", KeyCode.RETYPE, 0.72f)
        ),
        listOf(
            KeyDefinition("?", '?'.code, 0.72f),
            KeyDefinition("PQRS", KeyCode.T9_7, 1f, subLabel = "7"),
            KeyDefinition("TUV", KeyCode.T9_8, 1f, subLabel = "8"),
            KeyDefinition("WXYZ", KeyCode.T9_9, 1f, subLabel = "9"),
            KeyDefinition("0", KeyCode.T9_0, 0.72f)
        ),
        listOf(
            KeyDefinition("符", KeyCode.SYMBOL_LAYOUT, 0.66f),
            KeyDefinition("123", KeyCode.NUMBER_LAYOUT, 0.66f),
            KeyDefinition("Space 🎤", KeyCode.SPACE, 1.5f),
            KeyDefinition("En/中", KeyCode.MODE_SWITCH, 0.66f),
            KeyDefinition("...", KeyCode.MENU, 0.66f),
            KeyDefinition("↵", KeyCode.ENTER, 0.66f)
        )
    )

    private fun numberRows(): List<List<KeyDefinition>> = listOf(
        listOf(
            KeyDefinition("+", '+'.code, 0.72f),
            KeyDefinition("1", '1'.code, 1f),
            KeyDefinition("2", '2'.code, 1f),
            KeyDefinition("3", '3'.code, 1f),
            KeyDefinition("⌫", KeyCode.DELETE, 0.72f, isRepeatable = true)
        ),
        listOf(
            KeyDefinition("-", '-'.code, 0.72f),
            KeyDefinition("4", '4'.code, 1f),
            KeyDefinition("5", '5'.code, 1f),
            KeyDefinition("6", '6'.code, 1f),
            KeyDefinition(".", '.'.code, 0.72f)
        ),
        listOf(
            KeyDefinition("*", '*'.code, 0.72f),
            KeyDefinition("7", '7'.code, 1f),
            KeyDefinition("8", '8'.code, 1f),
            KeyDefinition("9", '9'.code, 1f),
            KeyDefinition("@", '@'.code, 0.72f)
        ),
        listOf(
            KeyDefinition("符", KeyCode.SYMBOL_LAYOUT, 0.72f),
            KeyDefinition("返回", KeyCode.RETURN_TO_TEXT, 0.72f),
            KeyDefinition("0", '0'.code, 1.5f),
            KeyDefinition("空格", KeyCode.SPACE, 0.72f),
            KeyDefinition("↵", KeyCode.ENTER, 0.72f)
        )
    )

    private fun symbolRows(): List<List<KeyDefinition>> = listOf(
        listOf(
            KeyDefinition("常用", KeyCode.SYMBOL_LAYOUT, 0.72f),
            KeyDefinition("-", '-'.code, 1f),
            KeyDefinition("，", ','.code, 1f),
            KeyDefinition("。", '.'.code, 1f),
            KeyDefinition("?", '?'.code, 1f)
        ),
        listOf(
            KeyDefinition("英文", KeyCode.SYMBOL_LAYOUT, 0.72f),
            KeyDefinition("!", '!'.code, 1f),
            KeyDefinition("✓", '✓'.code, 1f),
            KeyDefinition("×", '×'.code, 1f),
            KeyDefinition("@", '@'.code, 1f)
        ),
        listOf(
            KeyDefinition("中文", KeyCode.SYMBOL_LAYOUT, 0.72f),
            KeyDefinition(".", '.'.code, 1f),
            KeyDefinition("~", '~'.code, 1f),
            KeyDefinition("#", '#'.code, 1f),
            KeyDefinition("_", '_'.code, 1f)
        ),
        listOf(
            KeyDefinition("网络", KeyCode.SYMBOL_LAYOUT, 0.72f),
            KeyDefinition("'", '\''.code, 1f),
            KeyDefinition(".com", KeyCode.TEXT_DOT_COM, 1f),
            KeyDefinition(":", ':'.code, 1f),
            KeyDefinition("*", '*'.code, 1f)
        ),
        listOf(
            KeyDefinition("返回", KeyCode.RETURN_TO_TEXT, 1f),
            KeyDefinition("123", KeyCode.NUMBER_LAYOUT, 1f),
            KeyDefinition("⌃", KeyCode.SYMBOL_LAYOUT, 1f),
            KeyDefinition("⌄", KeyCode.SYMBOL_LAYOUT, 1f),
            KeyDefinition("⌫", KeyCode.DELETE, 1f, isRepeatable = true)
        )
    )

    private fun rowOf(chars: String, swipeChars: String? = null): List<KeyDefinition> {
        return chars.mapIndexed { index, ch ->
            val swipeText = swipeChars?.getOrNull(index)?.toString()
            KeyDefinition(
                label = ch.toString(),
                keyCode = charToKeyCode(ch),
                subLabel = swipeText,
                swipeText = swipeText
            )
        }
    }

    private fun rowOf(chars: String, swipeTexts: List<String>): List<KeyDefinition> {
        return chars.mapIndexed { index, ch ->
            val swipeText = swipeTexts.getOrNull(index)
            KeyDefinition(
                label = ch.toString(),
                keyCode = charToKeyCode(ch),
                subLabel = swipeText,
                swipeText = swipeText
            )
        }
    }

    private fun rowOfWithExtras(chars: String, swipeTexts: List<String> = emptyList()): List<KeyDefinition> = listOf(
        KeyDefinition("⇧", KeyCode.SHIFT, 1.5f, isSticky = true),
        *chars.mapIndexed { index, ch ->
            val swipeText = swipeTexts.getOrNull(index)
            KeyDefinition(
                label = ch.toString(),
                keyCode = charToKeyCode(ch),
                subLabel = swipeText,
                swipeText = swipeText
            )
        }.toTypedArray(),
        KeyDefinition("⌫", KeyCode.DELETE, 1.5f, isRepeatable = true)
    )

    private fun bottomRowCn(): List<KeyDefinition> = listOf(
        KeyDefinition("中/英", KeyCode.MODE_SWITCH, 1.5f),
        KeyDefinition("，", KeyCode.COMMA, 1f),
        KeyDefinition("空格 🎤", KeyCode.SPACE, 6f),
        KeyDefinition("。", KeyCode.PERIOD, 1f),
        KeyDefinition("...", KeyCode.MENU, 1f),
        KeyDefinition("↵", KeyCode.ENTER, 1.5f)
    )

    private fun bottomRowEn(): List<KeyDefinition> = listOf(
        KeyDefinition("中/英", KeyCode.MODE_SWITCH, 1.5f),
        KeyDefinition(",", KeyCode.COMMA, 1f),
        KeyDefinition("Space 🎤", KeyCode.SPACE, 6f),
        KeyDefinition(".", KeyCode.PERIOD, 1f),
        KeyDefinition("...", KeyCode.MENU, 1f),
        KeyDefinition("↵", KeyCode.ENTER, 1.5f)
    )

    private fun charToKeyCode(ch: Char): Int = when (ch) {
        in 'a'..'z' -> KeyEvent.KEYCODE_A + (ch - 'a')
        in '0'..'9' -> KeyEvent.KEYCODE_0 + (ch - '0')
        else -> ch.code
    }

    private fun voiceRows(): List<List<KeyDefinition>> = listOf(
        listOf(
            KeyDefinition("返回键盘", KeyCode.EXIT_VOICE, 4f),
            KeyDefinition("中/英", KeyCode.MODE_SWITCH, 4f),
            KeyDefinition("...", KeyCode.MENU, 4f)
        ),
        listOf(
            KeyDefinition("长按空格语音", KeyCode.EXIT_VOICE, 4f),
            KeyDefinition("↵", KeyCode.ENTER, 1.5f)
        )
    )
}