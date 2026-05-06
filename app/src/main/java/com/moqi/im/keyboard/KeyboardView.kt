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
        QWERTY_CN, QWERTY_EN, T9_CN, T9_EN, VOICE
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

    private var isShifted: Boolean = false
    private var onKeyListener: ((Int, Boolean) -> Unit)? = null

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
            Layout.VOICE -> voiceRows()
        }
        requestLayout()
        invalidate()
    }

    fun setShifted(shifted: Boolean) {
        isShifted = shifted
        invalidate()
    }

    fun setOnKeyListener(listener: (Int, Boolean) -> Unit) {
        onKeyListener = listener
    }

    fun showVoiceMode() {
        setLayout(Layout.VOICE)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val screenHeight = resources.displayMetrics.heightPixels
        val desiredHeight = (screenHeight * 0.28).toInt()
        keyGap = (width * 0.008f)

        val colsPerRow = when (currentLayout) {
            Layout.T9_CN, Layout.T9_EN -> 3
            Layout.VOICE -> 2
            else -> 10
        }
        val totalGapWidth = keyGap * (colsPerRow + 1)
        keyWidth = (width - totalGapWidth) / colsPerRow
        val rowCount = when (currentLayout) {
            Layout.T9_CN, Layout.T9_EN -> 6
            Layout.VOICE -> 2
            else -> 4
        }
        keyHeight = (desiredHeight - keyGap * (rowCount + 1)) / rowCount

        calculateKeyRects(width)
        setMeasuredDimension(width, desiredHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        updatePaintColors()

        if (currentLayout == Layout.VOICE) {
            drawVoiceMode(canvas)
            return
        }

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
        labelPaint.textSize = if (currentLayout == Layout.T9_CN || currentLayout == Layout.T9_EN) 32f else 42f
        subLabelPaint.color = if (dark) 0xFF9090AA.toInt() else 0xFF606080.toInt()
        subLabelPaint.textSize = 14f
        specialKeyPaint.color = if (dark) 0xFFE0E0E8.toInt() else 0xFF1A1A2E.toInt()
        specialKeyPaint.textSize = 28f
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

        val textHeight = textPaint.fontMetrics.let { it.descent - it.ascent }
        canvas.drawText(text, rect.centerX(), rect.centerY() + textHeight / 3, textPaint)

        if (key.subLabel != null && !isSpecialKey(key)) {
            subLabelPaint.color = if (dark) 0xFF9090AA.toInt() else 0xFF606080.toInt()
            val subHeight = subLabelPaint.fontMetrics.let { it.descent - it.ascent }
            canvas.drawText(key.subLabel, rect.right - 6f * resources.displayMetrics.density, rect.top + subHeight, subLabelPaint)
        }
    }

    private fun drawVoiceMode(canvas: Canvas) {
        // Voice layout is now rendered as keys, not custom drawing
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val hit = findKeyAt(event.x, event.y)
                if (hit != pressedKey) {
                    pressedKey = hit
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                pressedKey?.let { (row, col) ->
                    rows.getOrNull(row)?.getOrNull(col)?.let { key ->
                        onKeyListener?.invoke(key.keyCode, isShifted)
                    }
                }
                pressedKey = null
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedKey = null
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
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
            val rowWidth = row.sumOf { (it.widthFactor * keyWidth + keyGap * (it.widthFactor - 1f).coerceAtLeast(0f)).toDouble() }.toFloat()
            var x = (totalWidth - rowWidth) / 2f
            row.map { key ->
                val w = key.widthFactor * keyWidth + keyGap * (key.widthFactor - 1f).coerceAtLeast(0f)
                val rect = RectF(x, rowIdx * (keyHeight + keyGap) + keyGap, x + w, (rowIdx + 1) * keyHeight + rowIdx * keyGap + keyGap)
                x += w + keyGap
                rect
            }
        }
    }

    private fun isSpecialKey(key: KeyDefinition): Boolean =
        key.keyCode < 0 || key.isSticky

    private fun qwertyCnRows(): List<List<KeyDefinition>> = listOf(
        rowOf("qwertyuiop"),
        rowOf("asdfghjkl"),
        rowOfWithExtras("zxcvbnm"),
        bottomRowCn()
    )

    private fun qwertyEnRows(): List<List<KeyDefinition>> = listOf(
        rowOf("qwertyuiop"),
        rowOf("asdfghjkl"),
        rowOfWithExtras("zxcvbnm"),
        bottomRowEn()
    )

    private fun t9CnRows(): List<List<KeyDefinition>> = listOf(
        listOf(
            KeyDefinition("1", KeyEvent.KEYCODE_1, 1f, subLabel = ""),
            KeyDefinition("2\nabc", KeyCode.T9_2, 1f, subLabel = "abc"),
            KeyDefinition("3\ndef", KeyCode.T9_3, 1f, subLabel = "def")
        ),
        listOf(
            KeyDefinition("4\nghi", KeyCode.T9_4, 1f, subLabel = "ghi"),
            KeyDefinition("5\njkl", KeyCode.T9_5, 1f, subLabel = "jkl"),
            KeyDefinition("6\nmno", KeyCode.T9_6, 1f, subLabel = "mno")
        ),
        listOf(
            KeyDefinition("7\npqrs", KeyCode.T9_7, 1f, subLabel = "pqrs"),
            KeyDefinition("8\ntuv", KeyCode.T9_8, 1f, subLabel = "tuv"),
            KeyDefinition("9\nwxyz", KeyCode.T9_9, 1f, subLabel = "wxyz")
        ),
        listOf(
            KeyDefinition("中/英", KeyCode.MODE_SWITCH, 1f),
            KeyDefinition("0", KeyCode.T9_0, 1f),
            KeyDefinition("⌫", KeyCode.DELETE, 1f, isRepeatable = true)
        ),
        listOf(
            KeyDefinition("，", KeyCode.COMMA, 1f),
            KeyDefinition("空格", KeyCode.SPACE, 1f),
            KeyDefinition("。", KeyCode.PERIOD, 1f)
        ),
        listOf(
            KeyDefinition("🎤", KeyCode.VOICE, 1f),
            KeyDefinition("ABC", KeyCode.SWITCH_TO_QWERTY, 1f),
            KeyDefinition("↵", KeyCode.ENTER, 1f)
        )
    )

    private fun t9EnRows(): List<List<KeyDefinition>> = listOf(
        listOf(
            KeyDefinition("1", KeyEvent.KEYCODE_1, 1f, subLabel = ""),
            KeyDefinition("2\nabc", KeyCode.T9_2, 1f, subLabel = "abc"),
            KeyDefinition("3\ndef", KeyCode.T9_3, 1f, subLabel = "def")
        ),
        listOf(
            KeyDefinition("4\nghi", KeyCode.T9_4, 1f, subLabel = "ghi"),
            KeyDefinition("5\njkl", KeyCode.T9_5, 1f, subLabel = "jkl"),
            KeyDefinition("6\nmno", KeyCode.T9_6, 1f, subLabel = "mno")
        ),
        listOf(
            KeyDefinition("7\npqrs", KeyCode.T9_7, 1f, subLabel = "pqrs"),
            KeyDefinition("8\ntuv", KeyCode.T9_8, 1f, subLabel = "tuv"),
            KeyDefinition("9\nwxyz", KeyCode.T9_9, 1f, subLabel = "wxyz")
        ),
        listOf(
            KeyDefinition("En/中", KeyCode.MODE_SWITCH, 1f),
            KeyDefinition("0\n_", KeyCode.T9_0, 1f, subLabel = "_"),
            KeyDefinition("⌫", KeyCode.DELETE, 1f, isRepeatable = true)
        ),
        listOf(
            KeyDefinition(",", KeyCode.COMMA, 1f),
            KeyDefinition("Space", KeyCode.SPACE, 1f),
            KeyDefinition(".", KeyCode.PERIOD, 1f)
        ),
        listOf(
            KeyDefinition("🎤", KeyCode.VOICE, 1f),
            KeyDefinition("键盘", KeyCode.SWITCH_TO_QWERTY, 1f),
            KeyDefinition("↵", KeyCode.ENTER, 1f)
        )
    )

    private fun rowOf(chars: String): List<KeyDefinition> =
        chars.map { KeyDefinition(it.toString(), charToKeyCode(it)) }

    private fun rowOfWithExtras(chars: String): List<KeyDefinition> = listOf(
        KeyDefinition("⇧", KeyCode.SHIFT, 1.5f, isSticky = true),
        *chars.map { KeyDefinition(it.toString(), charToKeyCode(it)) }.toTypedArray(),
        KeyDefinition("⌫", KeyCode.DELETE, 1.5f, isRepeatable = true)
    )

    private fun bottomRowCn(): List<KeyDefinition> = listOf(
        KeyDefinition("中/英", KeyCode.MODE_SWITCH, 1.5f),
        KeyDefinition("，", KeyCode.COMMA, 1f),
        KeyDefinition("空格", KeyCode.SPACE, 5f),
        KeyDefinition("。", KeyCode.PERIOD, 1f),
        KeyDefinition("🎤", KeyCode.VOICE, 1f),
        KeyDefinition("9键", KeyCode.SWITCH_TO_T9, 1f),
        KeyDefinition("↵", KeyCode.ENTER, 1.5f)
    )

    private fun bottomRowEn(): List<KeyDefinition> = listOf(
        KeyDefinition("中/英", KeyCode.MODE_SWITCH, 1.5f),
        KeyDefinition(",", KeyCode.COMMA, 1f),
        KeyDefinition("Space", KeyCode.SPACE, 5f),
        KeyDefinition(".", KeyCode.PERIOD, 1f),
        KeyDefinition("🎤", KeyCode.VOICE, 1f),
        KeyDefinition("9键", KeyCode.SWITCH_TO_T9, 1f),
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
            KeyDefinition("9键", KeyCode.SWITCH_TO_T9, 4f)
        ),
        listOf(
            KeyDefinition("26键", KeyCode.SWITCH_TO_QWERTY, 1.5f),
            KeyDefinition("🎤 语音输入", KeyCode.VOICE, 4f),
            KeyDefinition("↵", KeyCode.ENTER, 1.5f)
        )
    )
}