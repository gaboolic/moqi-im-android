package com.moqi.im.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.OverScroller
import com.moqi.im.BuildConfig
import com.moqi.im.R

class KeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Layout {
        QWERTY_CN, QWERTY_EN, T9_CN, T9_EN, NUMBER, SYMBOL, EMOJI, VOICE
    }

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val subLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val specialKeyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sidePanelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var keyWidth: Float = 0f
    private var keyHeight: Float = 0f
    private var keyGap: Float = 0f
    private var currentLayout: Layout = Layout.QWERTY_CN
    private var currentSymbolPage: SymbolPage = SymbolPage.COMMON
    private var currentEmojiMode: EmojiMode = EmojiMode.EMOJI
    private var currentEmojiCategoryIndex: Int = 0
    private var t9PinyinOptions: List<String> = emptyList()
    private var t9SidePanelRect = RectF()
    private var t9SidePanelScrollOffset: Float = 0f

    private var rows: List<List<KeyDefinition>> = emptyList()
    private var keyRects: List<List<RectF>> = emptyList()
    private var pressedKey: Pair<Int, Int>? = null
    private var repeatingKey: Pair<Int, Int>? = null
    private var repeatRunnable: Runnable? = null
    private var touchStartKey: Pair<Int, Int>? = null
    private var touchStartX: Float = 0f
    private var touchStartY: Float = 0f
    private var lastSidePanelY: Float = 0f
    private var swipeTriggered: Boolean = false
    private var t9PinyinScrollTriggered: Boolean = false
    private var spaceLongPressRunnable: Runnable? = null
    private var spaceLongPressTriggered: Boolean = false
    private val viewConfiguration = ViewConfiguration.get(context)
    private val sidePanelScroller = OverScroller(context)
    private var sidePanelVelocityTracker: VelocityTracker? = null

    private var shiftState: ShiftState = ShiftState.LOWER
    private var onKeyListener: ((Int, Boolean, String?) -> Unit)? = null
    private var onSpaceLongPressListener: ((Boolean) -> Unit)? = null

    enum class ShiftState {
        LOWER, TEMP_UPPER, LOCKED_UPPER
    }

    private enum class SymbolPage {
        COMMON, ENGLISH, CHINESE, WEB
    }

    private enum class EmojiMode {
        EMOJI, KAOMOJI
    }

    private data class EmojiCategory(
        val name: String,
        val items: List<String>
    )

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
            Layout.EMOJI -> emojiRows()
            Layout.VOICE -> voiceRows()
        }
        requestLayout()
        invalidate()
    }

    fun setShifted(shifted: Boolean) {
        setShiftState(
            if (shifted) ShiftState.TEMP_UPPER else ShiftState.LOWER
        )
    }

    fun setShiftState(state: ShiftState) {
        shiftState = state
        invalidate()
    }

    fun setOnKeyListener(listener: (Int, Boolean, String?) -> Unit) {
        onKeyListener = listener
    }

    fun setOnSpaceLongPressListener(listener: (Boolean) -> Unit) {
        onSpaceLongPressListener = listener
    }

    fun isDirectCommitLayout(): Boolean =
        currentLayout == Layout.NUMBER || currentLayout == Layout.SYMBOL || currentLayout == Layout.EMOJI

    fun setT9PinyinOptions(options: List<String>) {
        if (options != t9PinyinOptions) {
            t9SidePanelScrollOffset = 0f
            if (!sidePanelScroller.isFinished) {
                sidePanelScroller.abortAnimation()
            }
        }
        t9PinyinOptions = options
        clampT9SidePanelScroll()
        if (isT9Layout()) {
            rows = when (currentLayout) {
                Layout.T9_EN -> t9EnRows()
                else -> t9CnRows()
            }
            requestLayout()
            invalidate()
        }
    }

    fun showVoiceMode() {
        setLayout(Layout.VOICE)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val desiredHeight = MeasureSpec.getSize(heightMeasureSpec).takeIf { it > 0 }
            ?: (resources.displayMetrics.heightPixels * 0.28f).toInt()
        keyGap = (width * 0.008f).coerceAtLeast(6f * resources.displayMetrics.density)
        val rowCount = rows.size.coerceAtLeast(1)
        keyHeight = (desiredHeight - keyGap * (rowCount + 1)) / rowCount

        calculateKeyRects(width)
        setMeasuredDimension(width, desiredHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        updatePaintColors()

        drawT9SidePanel(canvas)
        for ((rowIdx, row) in keyRects.withIndex()) {
            for ((colIdx, rect) in row.withIndex()) {
                if (isT9SidePanelCell(rowIdx, colIdx)) continue
                val key = rows.getOrNull(rowIdx)?.getOrNull(colIdx) ?: continue
                drawKey(canvas, rect, key, pressedKey == Pair(rowIdx, colIdx))
            }
        }
    }

    private fun drawT9SidePanel(canvas: Canvas) {
        if (!isT9Layout() || t9SidePanelRect.isEmpty) return
        val dark = isDarkMode
        val items = t9SideItems()
        val cornerRadius = dp(8f)
        keyPaint.color = if (dark) 0xFF2A2A42.toInt() else 0xFFFFFFFF.toInt()
        canvas.drawRoundRect(t9SidePanelRect, cornerRadius, cornerRadius, keyPaint)

        val itemHeight = t9SidePanelRect.height() / T9_VISIBLE_SIDE_ITEMS
        val baselineOffset = -(sidePanelTextPaint.descent() + sidePanelTextPaint.ascent()) / 2f
        canvas.save()
        canvas.clipRect(t9SidePanelRect)
        items.forEachIndexed { index, key ->
            val centerY = t9SidePanelRect.top + itemHeight * index + itemHeight / 2f - t9SidePanelScrollOffset
            if (centerY + itemHeight / 2f < t9SidePanelRect.top || centerY - itemHeight / 2f > t9SidePanelRect.bottom) {
                return@forEachIndexed
            }
            canvas.drawText(key.label, t9SidePanelRect.centerX(), centerY + baselineOffset, sidePanelTextPaint)
        }
        canvas.restore()
        if (items.size > T9_VISIBLE_SIDE_ITEMS) {
            val trackLeft = t9SidePanelRect.right - dp(4f)
            val trackTop = t9SidePanelRect.top + dp(8f)
            val trackBottom = t9SidePanelRect.bottom - dp(8f)
            val maxScroll = maxT9SidePanelScroll().coerceAtLeast(1f)
            val thumbHeight = ((trackBottom - trackTop) * t9SidePanelRect.height() / (items.size * itemHeight)).coerceAtLeast(dp(18f))
            val thumbTop = trackTop + (trackBottom - trackTop - thumbHeight) * t9SidePanelScrollOffset / maxScroll
            iconPaint.color = if (dark) 0xFF4A5158.toInt() else 0xFFD0D5DC.toInt()
            canvas.drawRoundRect(
                RectF(trackLeft, thumbTop, trackLeft + dp(2.5f), thumbTop + thumbHeight),
                dp(2f),
                dp(2f),
                iconPaint
            )
        }
    }

    private fun updatePaintColors() {
        val dark = isDarkMode
        labelPaint.color = if (dark) 0xFFE0E0E8.toInt() else 0xFF1A1A2E.toInt()
        labelPaint.textSize = when {
            isT9Layout() -> sp(MAIN_LETTER_TEXT_SIZE_SP)
            currentLayout == Layout.NUMBER -> dp(30f)
            currentLayout == Layout.EMOJI && currentEmojiMode == EmojiMode.KAOMOJI -> sp(KAOMOJI_TEXT_SIZE_SP)
            currentLayout == Layout.EMOJI -> sp(EMOJI_TEXT_SIZE_SP)
            currentLayout == Layout.SYMBOL -> sp(SYMBOL_TEXT_SIZE_SP)
            else -> sp(MAIN_LETTER_TEXT_SIZE_SP)
        }
        labelPaint.textAlign = Paint.Align.CENTER
        subLabelPaint.color = if (dark) 0xFF9090AA.toInt() else 0xFF606080.toInt()
        subLabelPaint.textSize = if (isNumberOrSymbolLayout() || currentLayout == Layout.EMOJI) dp(13f) else dp(12f)
        subLabelPaint.textAlign = Paint.Align.CENTER
        specialKeyPaint.color = if (dark) 0xFFE0E0E8.toInt() else 0xFF1A1A2E.toInt()
        specialKeyPaint.textSize = dp(16f)
        specialKeyPaint.textAlign = Paint.Align.CENTER
        sidePanelTextPaint.color = if (dark) 0xFFE0E0E8.toInt() else 0xFF1A1A2E.toInt()
        sidePanelTextPaint.textSize = sp(T9_SIDE_PANEL_TEXT_SIZE_SP)
        sidePanelTextPaint.textAlign = Paint.Align.CENTER
        iconPaint.color = if (dark) 0xFFE0E0E8.toInt() else 0xFF1A1A2E.toInt()
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

        if (key.keyCode == KeyCode.SHIFT) {
            drawShiftKey(canvas, rect)
            return
        }

        val textPaint = if (isSpecialKey(key)) specialKeyPaint else labelPaint
        val text = if (isSpecialKey(key)) key.label else {
            if (shouldDisplayUppercaseLetters() && key.keyCode >= KeyEvent.KEYCODE_A && key.keyCode <= KeyEvent.KEYCODE_Z) {
                key.label.uppercase()
            } else {
                key.label
            }
        }

        val textBaseline = if (key.swipeText.isNullOrBlank() || isSpecialKey(key)) {
            rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
        } else {
            rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f - rect.height() * 0.08f - dp(1f)
        }
        canvas.drawText(text, rect.centerX(), textBaseline, textPaint)

        val subLabel = key.subLabel
        if (subLabel != null && !isSpecialKey(key)) {
            subLabelPaint.color = if (dark) 0xFF9090AA.toInt() else 0xFF606080.toInt()
            val subBaseline = rect.bottom - dp(7f)
            canvas.drawText(subLabel, rect.centerX(), subBaseline, subLabelPaint)
        }
    }

    private fun drawShiftKey(canvas: Canvas, rect: RectF) {
        val locked = shiftState == ShiftState.LOCKED_UPPER
        val active = shiftState != ShiftState.LOWER
        val cx = rect.centerX()
        val cy = rect.centerY() - dp(1f)
        val unit = rect.height().coerceAtMost(rect.width()) * 0.15f
        val path = Path().apply {
            moveTo(cx, cy - unit * 1.45f)
            lineTo(cx - unit * 1.55f, cy + unit * 0.2f)
            lineTo(cx - unit * 0.72f, cy + unit * 0.2f)
            lineTo(cx - unit * 0.72f, cy + unit * 1.45f)
            lineTo(cx + unit * 0.72f, cy + unit * 1.45f)
            lineTo(cx + unit * 0.72f, cy + unit * 0.2f)
            lineTo(cx + unit * 1.55f, cy + unit * 0.2f)
            close()
        }
        iconPaint.style = if (active) Paint.Style.FILL else Paint.Style.STROKE
        iconPaint.strokeWidth = dp(2.2f)
        canvas.drawPath(path, iconPaint)
        if (locked) {
            iconPaint.style = Paint.Style.STROKE
            iconPaint.strokeWidth = dp(1.8f)
            val underlineY = rect.bottom - dp(13f)
            canvas.drawLine(cx - unit * 1.35f, underlineY, cx + unit * 1.35f, underlineY, iconPaint)
        }
        iconPaint.style = Paint.Style.FILL
    }

    private fun drawVoiceMode(canvas: Canvas) {
        // Voice layout is now rendered as keys, not custom drawing
    }

    override fun computeScroll() {
        if (sidePanelScroller.computeScrollOffset()) {
            t9SidePanelScrollOffset = sidePanelScroller.currY.toFloat().coerceIn(0f, maxT9SidePanelScroll())
            postInvalidateOnAnimation()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val sidePanelStarted = touchStartKey?.let { isT9SidePanelCell(it.first, it.second) } == true
        if (sidePanelStarted) {
            ensureSidePanelVelocityTracker().addMovement(event)
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!sidePanelScroller.isFinished) {
                    sidePanelScroller.abortAnimation()
                }
                val hit = findKeyAt(event.x, event.y)
                touchStartKey = hit
                touchStartX = event.x
                touchStartY = event.y
                lastSidePanelY = event.y
                swipeTriggered = false
                t9PinyinScrollTriggered = false
                pressedKey = hit
                if (hit?.let { isT9SidePanelCell(it.first, it.second) } == true) {
                    ensureSidePanelVelocityTracker().addMovement(event)
                }
                startKeyRepeatIfNeeded(hit)
                startSpaceLongPressIfNeeded(hit)
                postInvalidateOnAnimation()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (handleT9PinyinOptionScroll(event.x, event.y)) {
                    postInvalidateOnAnimation()
                    return true
                }
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
                if (t9PinyinScrollTriggered) {
                    // Option scrolling only changes the visible pinyin list; it must not select or input.
                } else if (swipeTriggered && startKey != null) {
                    dispatchSwipeKey(startKey)
                } else if (!spaceLongPressWasActive) {
                    pressedKey?.let { keyPos ->
                        if (!repeatWasActive) {
                            dispatchKey(keyPos)
                        }
                    }
                }
                stopSpaceLongPress(endVoice = spaceLongPressWasActive)
                if (t9PinyinScrollTriggered) {
                    flingT9SidePanelIfNeeded()
                }
                recycleSidePanelVelocityTracker()
                clearTouchTracking()
                stopKeyRepeat()
                pressedKey = null
                postInvalidateOnAnimation()
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                stopSpaceLongPress(endVoice = spaceLongPressTriggered)
                if (!sidePanelScroller.isFinished) {
                    sidePanelScroller.abortAnimation()
                }
                recycleSidePanelVelocityTracker()
                clearTouchTracking()
                stopKeyRepeat()
                pressedKey = null
                postInvalidateOnAnimation()
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

    private fun handleT9PinyinOptionScroll(x: Float, y: Float): Boolean {
        val items = t9SideItems()
        if (items.size <= T9_VISIBLE_SIDE_ITEMS) return false
        val startKey = touchStartKey ?: return false
        if (!isT9SidePanelCell(startKey.first, startKey.second)) return false
        val dx = x - touchStartX
        val dy = y - touchStartY
        if (kotlin.math.abs(dy) < 1f || kotlin.math.abs(dy) < kotlin.math.abs(dx)) {
            return false
        }
        val nextOffset = (t9SidePanelScrollOffset + lastSidePanelY - y).coerceIn(0f, maxT9SidePanelScroll())
        lastSidePanelY = y
        t9SidePanelScrollOffset = nextOffset
        t9PinyinScrollTriggered = true
        pressedKey = startKey
        stopKeyRepeat()
        stopSpaceLongPress(endVoice = spaceLongPressTriggered)
        return true
    }

    private fun ensureSidePanelVelocityTracker(): VelocityTracker {
        val tracker = sidePanelVelocityTracker ?: VelocityTracker.obtain().also {
            sidePanelVelocityTracker = it
        }
        return tracker
    }

    private fun recycleSidePanelVelocityTracker() {
        sidePanelVelocityTracker?.recycle()
        sidePanelVelocityTracker = null
    }

    private fun flingT9SidePanelIfNeeded() {
        val maxScroll = maxT9SidePanelScroll().toInt()
        if (maxScroll <= 0) return
        val tracker = sidePanelVelocityTracker ?: return
        tracker.computeCurrentVelocity(1000, viewConfiguration.scaledMaximumFlingVelocity.toFloat())
        val velocityY = -tracker.yVelocity.toInt()
        if (kotlin.math.abs(velocityY) < viewConfiguration.scaledMinimumFlingVelocity) return
        sidePanelScroller.fling(
            0,
            t9SidePanelScrollOffset.toInt(),
            0,
            velocityY,
            0,
            0,
            0,
            maxScroll
        )
        postInvalidateOnAnimation()
    }

    private fun clampT9SidePanelScroll() {
        t9SidePanelScrollOffset = t9SidePanelScrollOffset.coerceIn(0f, maxT9SidePanelScroll())
    }

    private fun maxT9SidePanelScroll(): Float {
        if (t9SidePanelRect.isEmpty) return 0f
        val itemHeight = t9SidePanelRect.height() / T9_VISIBLE_SIDE_ITEMS
        return (t9SideItems().size * itemHeight - t9SidePanelRect.height()).coerceAtLeast(0f)
    }

    private fun isT9SidePanelCell(row: Int, col: Int): Boolean = isT9Layout() && col == 0 && row in 0..2

    private fun clearTouchTracking() {
        touchStartKey = null
        touchStartX = 0f
        touchStartY = 0f
        lastSidePanelY = 0f
        swipeTriggered = false
        t9PinyinScrollTriggered = false
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
        if (isT9SidePanelCell(keyPos.first, keyPos.second)) {
            t9SideItemAt(touchStartY)?.let { key ->
                onKeyListener?.invoke(key.keyCode, shiftState != ShiftState.LOWER, key.commitText)
            }
            return
        }
        val key = keyAt(keyPos) ?: return
        if (handleSymbolCategoryKey(key.keyCode)) return
        if (handleEmojiCategoryKey(key.keyCode)) return
        val directText = if (isDirectCommitLayout() && !isSpecialKey(key) && key.keyCode >= 0) {
            key.commitText ?: key.label
        } else {
            key.commitText
        }
        onKeyListener?.invoke(key.keyCode, shiftState != ShiftState.LOWER, directText)
    }

    private fun dispatchSwipeKey(keyPos: Pair<Int, Int>) {
        val key = keyAt(keyPos) ?: return
        val swipeText = key.swipeText ?: return
        onKeyListener?.invoke(key.keyCode, shiftState != ShiftState.LOWER, swipeText)
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
        t9SidePanelRect = if (isT9Layout() && keyRects.size >= 3 && keyRects[0].isNotEmpty()) {
            RectF(
                keyRects[0][0].left,
                keyRects[0][0].top,
                keyRects[0][0].right,
                keyRects[2][0].bottom
            )
        } else {
            RectF()
        }
    }

    companion object {
        private const val KEY_REPEAT_INITIAL_DELAY_MS = 350L
        private const val KEY_REPEAT_INTERVAL_MS = 70L
        private const val SPACE_LONG_PRESS_DELAY_MS = 280L
        private const val SWIPE_INPUT_THRESHOLD_DP = 36f
        private const val MAIN_LETTER_TEXT_SIZE_SP = 21f
        private const val SYMBOL_TEXT_SIZE_SP = 23f
        private const val EMOJI_TEXT_SIZE_SP = 25f
        private const val KAOMOJI_TEXT_SIZE_SP = 13f
        private const val T9_SIDE_PANEL_TEXT_SIZE_SP = 14f
        private const val T9_VISIBLE_SIDE_ITEMS = 4
    }

    private fun isSpecialKey(key: KeyDefinition): Boolean =
        (key.keyCode < 0 && !isT9InputKey(key.keyCode) && !isT9PunctuationKey(key.keyCode)) || key.isSticky

    private fun isT9Layout(): Boolean =
        currentLayout == Layout.T9_CN || currentLayout == Layout.T9_EN

    private fun isNumberOrSymbolLayout(): Boolean =
        currentLayout == Layout.NUMBER || currentLayout == Layout.SYMBOL

    private fun shouldDisplayUppercaseLetters(): Boolean =
        currentLayout == Layout.QWERTY_CN || shiftState != ShiftState.LOWER

    private fun isT9InputKey(keyCode: Int): Boolean =
        keyCode in KeyCode.T9_POUND..KeyCode.T9_1

    private fun isT9PunctuationKey(keyCode: Int): Boolean =
        isT9Layout() && (keyCode == KeyCode.COMMA || keyCode == KeyCode.PERIOD)

    private fun handleSymbolCategoryKey(keyCode: Int): Boolean {
        val page = when (keyCode) {
            KeyCode.SYMBOL_COMMON -> SymbolPage.COMMON
            KeyCode.SYMBOL_ENGLISH -> SymbolPage.ENGLISH
            KeyCode.SYMBOL_CHINESE -> SymbolPage.CHINESE
            KeyCode.SYMBOL_WEB -> SymbolPage.WEB
            KeyCode.SYMBOL_PREV -> previousSymbolPage()
            KeyCode.SYMBOL_NEXT -> nextSymbolPage()
            else -> return false
        }
        currentSymbolPage = page
        rows = symbolRows()
        requestLayout()
        invalidate()
        return true
    }

    private fun handleEmojiCategoryKey(keyCode: Int): Boolean {
        when (keyCode) {
            KeyCode.EMOJI_MODE -> currentEmojiMode = EmojiMode.EMOJI
            KeyCode.KAOMOJI_MODE -> currentEmojiMode = EmojiMode.KAOMOJI
            KeyCode.EMOJI_PREV -> {
                val size = currentEmojiCategories().size
                currentEmojiCategoryIndex = (currentEmojiCategoryIndex - 1 + size) % size
            }
            KeyCode.EMOJI_NEXT -> {
                val size = currentEmojiCategories().size
                currentEmojiCategoryIndex = (currentEmojiCategoryIndex + 1) % size
            }
            else -> return false
        }
        currentEmojiCategoryIndex = currentEmojiCategoryIndex.coerceIn(0, currentEmojiCategories().lastIndex)
        rows = emojiRows()
        requestLayout()
        invalidate()
        return true
    }

    private fun previousSymbolPage(): SymbolPage {
        val pages = SymbolPage.values()
        val index = pages.indexOf(currentSymbolPage)
        return pages[(index - 1 + pages.size) % pages.size]
    }

    private fun nextSymbolPage(): SymbolPage {
        val pages = SymbolPage.values()
        val index = pages.indexOf(currentSymbolPage)
        return pages[(index + 1) % pages.size]
    }

    private fun qwertyCnRows(): List<List<KeyDefinition>> = listOf(
        rowOf("qwertyuiop", "1234567890"),
        rowOf("asdfghjkl", listOf("~", "!", "@", "#", "%", "“”", "（）", "*", "_")),
        rowOfWithExtras("zxcvbnm", listOf("`", "+", "-", "?", "：", "；", "/")),
        bottomRowCn()
    )

    private fun qwertyEnRows(): List<List<KeyDefinition>> = listOf(
        rowOf("qwertyuiop", "1234567890"),
        rowOf("asdfghjkl", listOf("~", "!", "@", "#", "%", "\"\"", "()", "*", "_")),
        rowOfWithExtras("zxcvbnm", listOf("`", "+", "-", "?", ":", ";", "/")),
        bottomRowEn()
    )

    private fun t9CnRows(): List<List<KeyDefinition>> = listOf(
        listOf(
            t9SideKey(0, "，", KeyCode.COMMA),
            t9Key("1", KeyCode.T9_1, "1"),
            t9Key("ABC", KeyCode.T9_2, "2"),
            t9Key("DEF", KeyCode.T9_3, "3"),
            KeyDefinition("⌫", KeyCode.DELETE, 0.72f, isRepeatable = true)
        ),
        listOf(
            t9SideKey(1, "。", KeyCode.PERIOD),
            t9Key("GHI", KeyCode.T9_4, "4"),
            t9Key("JKL", KeyCode.T9_5, "5"),
            t9Key("MNO", KeyCode.T9_6, "6"),
            KeyDefinition("重输", KeyCode.RETYPE, 0.72f)
        ),
        listOf(
            t9SideKey(2, "?", '?'.code),
            t9Key("PQRS", KeyCode.T9_7, "7"),
            t9Key("TUV", KeyCode.T9_8, "8"),
            t9Key("WXYZ", KeyCode.T9_9, "9"),
            t9Key("0", KeyCode.T9_0, "0", 0.72f)
        ),
        listOf(
            KeyDefinition("中/英", KeyCode.MODE_SWITCH, 0.66f),
            KeyDefinition("123", KeyCode.NUMBER_LAYOUT, 0.66f),
            KeyDefinition(spaceBarLabel("空格"), KeyCode.SPACE, 2.16f),
            KeyDefinition("符", KeyCode.SYMBOL_LAYOUT, 0.66f),
            KeyDefinition("↵", KeyCode.ENTER, 0.66f)
        )
    )

    private fun t9EnRows(): List<List<KeyDefinition>> = listOf(
        listOf(
            t9SideKey(0, ",", KeyCode.COMMA),
            t9Key("1", KeyCode.T9_1, "1"),
            t9Key("ABC", KeyCode.T9_2, "2"),
            t9Key("DEF", KeyCode.T9_3, "3"),
            KeyDefinition("⌫", KeyCode.DELETE, 0.72f, isRepeatable = true)
        ),
        listOf(
            t9SideKey(1, ".", KeyCode.PERIOD),
            t9Key("GHI", KeyCode.T9_4, "4"),
            t9Key("JKL", KeyCode.T9_5, "5"),
            t9Key("MNO", KeyCode.T9_6, "6"),
            KeyDefinition("Redo", KeyCode.RETYPE, 0.72f)
        ),
        listOf(
            t9SideKey(2, "?", '?'.code),
            t9Key("PQRS", KeyCode.T9_7, "7"),
            t9Key("TUV", KeyCode.T9_8, "8"),
            t9Key("WXYZ", KeyCode.T9_9, "9"),
            t9Key("0", KeyCode.T9_0, "0", 0.72f)
        ),
        listOf(
            KeyDefinition("En/中", KeyCode.MODE_SWITCH, 0.66f),
            KeyDefinition("123", KeyCode.NUMBER_LAYOUT, 0.66f),
            KeyDefinition(spaceBarLabel("Space"), KeyCode.SPACE, 2.16f),
            KeyDefinition("符", KeyCode.SYMBOL_LAYOUT, 0.66f),
            KeyDefinition("↵", KeyCode.ENTER, 0.66f)
        )
    )

    private fun t9Key(label: String, keyCode: Int, digit: String, widthFactor: Float = 1f): KeyDefinition =
        KeyDefinition(label, keyCode, widthFactor, subLabel = digit.takeUnless { it == label }, swipeText = digit)

    private fun t9SideKey(index: Int, fallbackLabel: String, fallbackKeyCode: Int): KeyDefinition =
        t9SideItems().getOrNull(index) ?: KeyDefinition(fallbackLabel, fallbackKeyCode, 0.72f)

    private fun t9SideItems(): List<KeyDefinition> {
        if (t9PinyinOptions.isNotEmpty()) {
            return t9PinyinOptions.map { option ->
                KeyDefinition(option, KeyCode.T9_PINYIN_OPTION, 0.72f, commitText = option)
            }
        }
        return listOf("，", "。", "？", "：", "！", "…", "；", "、", ".", "-", "@").map { symbol ->
            KeyDefinition(symbol, symbol.singleOrNull()?.code ?: symbol.first().code, 0.72f, commitText = symbol)
        }
    }

    private fun t9SideItemAt(y: Float): KeyDefinition? {
        if (!isT9Layout() || !t9SidePanelRect.contains(t9SidePanelRect.centerX(), y)) return null
        val itemHeight = t9SidePanelRect.height() / T9_VISIBLE_SIDE_ITEMS
        val index = ((y - t9SidePanelRect.top + t9SidePanelScrollOffset) / itemHeight).toInt()
        return t9SideItems().getOrNull(index)
    }

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

    private fun symbolRows(): List<List<KeyDefinition>> {
        val symbols = when (currentSymbolPage) {
            SymbolPage.COMMON -> listOf(
                listOf("-", "，", "。", "?"),
                listOf("!", "✓", "×", "@"),
                listOf(".", "~", "#", "_"),
                listOf("'", ".com", ":", "*")
            )
            SymbolPage.ENGLISH -> listOf(
                listOf(",", ".", "?", "!"),
                listOf(";", ":", "'", "\""),
                listOf("-", "_", "(", ")"),
                listOf("@", "#", "$", "&")
            )
            SymbolPage.CHINESE -> listOf(
                listOf("，", "。", "？", "！"),
                listOf("；", "：", "、", "……"),
                listOf("“”", "（）", "《", "》"),
                listOf("【", "】", "￥", "·")
            )
            SymbolPage.WEB -> listOf(
                listOf(".com", "www.", "https://", "/"),
                listOf("@", ".", "_", "-"),
                listOf(":", "#", "?", "="),
                listOf("&", "%", "+", "*")
            )
        }
        return listOf(
            symbolCategoryRow("常用", KeyCode.SYMBOL_COMMON, SymbolPage.COMMON, symbols[0]),
            symbolCategoryRow("英文", KeyCode.SYMBOL_ENGLISH, SymbolPage.ENGLISH, symbols[1]),
            symbolCategoryRow("中文", KeyCode.SYMBOL_CHINESE, SymbolPage.CHINESE, symbols[2]),
            symbolCategoryRow("网络", KeyCode.SYMBOL_WEB, SymbolPage.WEB, symbols[3]),
            listOf(
                KeyDefinition("返回", KeyCode.RETURN_TO_TEXT, 1f),
                KeyDefinition("123", KeyCode.NUMBER_LAYOUT, 1f),
                KeyDefinition("⌃", KeyCode.SYMBOL_PREV, 1f),
                KeyDefinition("⌄", KeyCode.SYMBOL_NEXT, 1f),
                KeyDefinition("⌫", KeyCode.DELETE, 1f, isRepeatable = true)
            )
        )
    }

    private fun symbolCategoryRow(
        label: String,
        keyCode: Int,
        page: SymbolPage,
        symbols: List<String>
    ): List<KeyDefinition> = listOf(
        KeyDefinition(label, keyCode, 0.72f, isSticky = currentSymbolPage == page),
        *symbols.map { symbolKey(it) }.toTypedArray()
    )

    private fun symbolKey(label: String): KeyDefinition {
        val keyCode = when (label) {
            ".com" -> KeyCode.TEXT_DOT_COM
            else -> label.singleOrNull()?.code ?: label.first().code
        }
        return KeyDefinition(label, keyCode, 1f, commitText = label)
    }

    private fun emojiRows(): List<List<KeyDefinition>> {
        val category = currentEmojiCategories()[currentEmojiCategoryIndex.coerceIn(0, currentEmojiCategories().lastIndex)]
        val itemRows = category.items.take(15).chunked(5).map { row ->
            row.map { emojiKey(it) }
        }
        return listOf(
            listOf(
                KeyDefinition("Emoji", KeyCode.EMOJI_MODE, 1f, isSticky = currentEmojiMode == EmojiMode.EMOJI),
                KeyDefinition("颜文字", KeyCode.KAOMOJI_MODE, 1f, isSticky = currentEmojiMode == EmojiMode.KAOMOJI),
                KeyDefinition("‹", KeyCode.EMOJI_PREV, 0.72f),
                KeyDefinition(category.name, KeyCode.EMOJI_NEXT, 1.36f, isSticky = true),
                KeyDefinition("›", KeyCode.EMOJI_NEXT, 0.72f)
            ),
            itemRows.getOrElse(0) { emptyList() },
            itemRows.getOrElse(1) { emptyList() },
            itemRows.getOrElse(2) { emptyList() },
            listOf(
                KeyDefinition("返回", KeyCode.RETURN_TO_TEXT, 1f),
                KeyDefinition("123", KeyCode.NUMBER_LAYOUT, 1f),
                KeyDefinition("符", KeyCode.SYMBOL_LAYOUT, 1f),
                KeyDefinition("⌫", KeyCode.DELETE, 1f, isRepeatable = true)
            )
        )
    }

    private fun emojiKey(label: String): KeyDefinition =
        KeyDefinition(label, label.first().code, 1f, commitText = label)

    private fun currentEmojiCategories(): List<EmojiCategory> =
        if (currentEmojiMode == EmojiMode.KAOMOJI) kaomojiCategories else emojiCategories

    private val emojiCategories: List<EmojiCategory> = listOf(
        EmojiCategory("黄脸", listOf("😀", "😂", "😃", "😄", "😁", "😆", "😅", "🤣", "😊", "🙂", "🙃", "😉", "😍", "😘", "😋")),
        EmojiCategory("组合", listOf("🫶", "🫰", "🙏", "💪", "👏", "👍", "🎉", "✨", "🔥", "💯", "❤️‍🔥", "🌈", "⭐", "🌟", "💫")),
        EmojiCategory("人物", listOf("👶", "👧", "🧒", "👦", "👩", "🧑", "👨", "👵", "🧓", "👴", "👮", "👷", "💂", "🕵️", "🧙")),
        EmojiCategory("手势", listOf("👍", "👎", "👌", "🤌", "🤏", "✌️", "🤞", "🤟", "🤘", "🤙", "👈", "👉", "👆", "👇", "🙏")),
        EmojiCategory("动物", listOf("🐶", "🐱", "🐭", "🐹", "🐰", "🦊", "🐻", "🐼", "🐨", "🐯", "🦁", "🐮", "🐷", "🐸", "🐵")),
        EmojiCategory("植物", listOf("🌵", "🎄", "🌲", "🌳", "🌴", "🌱", "🌿", "☘️", "🍀", "🎍", "🪴", "🍃", "🍂", "🍁", "🌾")),
        EmojiCategory("食物", listOf("🍎", "🍊", "🍌", "🍉", "🍇", "🍓", "🍒", "🥝", "🍅", "🥑", "🍞", "🍔", "🍟", "🍕", "🍜")),
        EmojiCategory("心形", listOf("❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍", "🤎", "💔", "❣️", "💕", "💞", "💓", "💗")),
        EmojiCategory("节日", listOf("🎉", "🎊", "🎈", "🎁", "🎂", "🧧", "🏮", "🎄", "🎅", "🤶", "🧑‍🎄", "🎃", "🧨", "✨", "🎇")),
        EmojiCategory("运动", listOf("⚽", "🏀", "🏈", "⚾", "🥎", "🎾", "🏐", "🏉", "🥏", "🎱", "🏓", "🏸", "🥅", "🏒", "🏑")),
        EmojiCategory("交通", listOf("🚗", "🚕", "🚙", "🚌", "🚎", "🏎️", "🚓", "🚑", "🚒", "🚚", "🚲", "🛵", "🏍️", "🚄", "✈️")),
        EmojiCategory("元素", listOf("☀️", "🌙", "⭐", "⚡", "🔥", "💧", "🌊", "❄️", "☁️", "🌧️", "🌈", "🌪️", "🌍", "🌋", "🪐")),
        EmojiCategory("标志", listOf("✅", "☑️", "✔️", "❌", "⭕", "❗", "❓", "⚠️", "🚫", "♻️", "🔰", "🔱", "⚜️", "🔆", "🔅")),
        EmojiCategory("物品", listOf("⌚", "📱", "💻", "⌨️", "🖱️", "📷", "🎧", "🎤", "📦", "💡", "🔦", "🔑", "✂️", "🧰", "🪄")),
        EmojiCategory("国旗", listOf("🇨🇳", "🇭🇰", "🇲🇴", "🇹🇼", "🇯🇵", "🇰🇷", "🇺🇸", "🇬🇧", "🇫🇷", "🇩🇪", "🇮🇹", "🇪🇸", "🇷🇺", "🇨🇦", "🇦🇺"))
    )

    private val kaomojiCategories: List<EmojiCategory> = listOf(
        EmojiCategory("开心", listOf("ヽ(✿ﾟ▽ﾟ)ノ", "o(*￣▽￣*)o", "(p≧w≦q)", "╰(*°▽°*)╯", "(*^▽^*)", "o(￣▽￣)ｄ", "♪(^∇^*)", "ヾ(≧▽≦*)o", "o(*≧▽≦)ツ", "(＾－＾)V", "^O^", "q(≧▽≦q)", "~(￣▽￣)~*", "(๑•̀ㅂ•́)و✧", "(✿◡‿◡)")),
        EmojiCategory("喜欢", listOf("(≧∇≦)ﾉ", "(´▽`ʃ♡ƪ)", "（づ￣3￣）づ╭❤～", "(* ￣3)(ε￣ *)", "(　ﾟ∀ﾟ) ﾉ♡", "Σ>―(〃°ω°〃)♡→", "(づ￣ ³￣)づ", "(u‿ฺu✿ฺ)", "(*/ω＼*)", "\\(//?//)\\", "(〃'▽'〃)", "(๑❛ᴗ❛๑)", "(◡ᴗ◡✿)", "(︶.̮︶✽)", "(｡･ω･｡)ﾉ♡")),
        EmojiCategory("哭泣", listOf("(ノへ￣、)", "┭┮﹏┭┮", "(´；ω；`)", "ヽ(；▽；)ノ", "(┳＿┳)...", "╥﹏╥...", "┗( T﹏T )┛", "(；′⌒`)", "ε(┬┬﹏┬┬)3", "o(TヘTo)", "(T_T)", "QAQ", "TAT", "ಥ_ಥ", "(。﹏。*)")),
        EmojiCategory("生气", listOf("(ー`´ー)", "o(￣ヘ￣o＃)", "(#`O′)", "凸(艹皿艹 )", "(╬￣皿￣)＝○", "╭∩╮(︶︿︶）╭∩╮", "o(一︿一+)o", "(╯‵□′)╯︵┻━┻", "┻━┻︵╰(‵□′)╯︵┻━┻", "(╬▔皿▔)╯", "(# ﾟДﾟ)", "(； ･`д･´)", "(눈益눈)", "ヽ(#`Д´)ﾉ", "٩(๑`^´๑)۶")),
        EmojiCategory("惊讶", listOf("w(ﾟДﾟ)w", "Σ( ° △ °|||)︴", "(⊙﹏⊙)", "(＠_＠;)", "(°ー°〃)", "Σ(⊙▽⊙\"a", "(￣△￣；)", "(⊙_⊙)?", "Σ(っ °Д °;)っ", "━━(￣ー￣*|||━━", "(◎_x)", "o(°▽、°o)", "┌(。Д。)┐", "X﹏X", "(ﾟДﾟ*)ﾉ")),
        EmojiCategory("无奈", listOf("╮(￣▽￣\")╭", "┑(￣Д ￣)┍", "ㄟ( ▔, ▔ )ㄏ", "¯\\_(ツ)_/¯", "(ーー゛)", "(￣_,￣ )", "~~( ﹁ ﹁ ) ~~~", "( ﹁ ﹁ ) ~→", "(ー_ー)!!", "(′゜c_，゜` )", "( ￣ー￣)", "(＠￣ー￣＠)", "┌( ´_ゝ` )┐", "(☆-ｖ-)", "(´ｰ∀ｰ`)")),
        EmojiCategory("动作", listOf("ヾ(￣▽￣)Bye~Bye~", "Hi~ o(*￣▽￣*)ブ", "o(*≧▽≦)ツ┏━┓", "(～￣▽￣)→", "▄︻┻┳═一……", "(╯‵□′)╯炸弹！•••*～●", "↑↑↓↓←→←→ＢＡ", "ヽ(゜▽゜　)－C", "z(-_-z)).....((s-_-)s", "_〆(´Д｀ )", "○|￣|_", "(ง •̀_•́)ง", "╭( ･ㅂ･)و ̑̑", "ｍ(＿　＿)ｍ", "(*°▽°*)八(*°▽°*)♪")),
        EmojiCategory("动物", listOf("o( =•ω•= )m", "≡ω≡", "（ΦωΦ）", "(*￣(エ)￣)", "(=ﾟωﾟ)=", "(ﾟωﾟ)", "(oﾟωﾟo)", "(*´ω`*)", "( ^ω^)", "(・ω・)", "(｀･ω･)", "(`・ω・´)", "(´・ω・`)", "ヾ(´ωﾟ｀)", "（<ゝω・）☆"))
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
        KeyDefinition("中/英", KeyCode.MODE_SWITCH, 1.2f),
        KeyDefinition("，", KeyCode.COMMA, 1f),
        KeyDefinition(spaceBarLabel("空格"), KeyCode.SPACE, 6.5f),
        KeyDefinition("。", KeyCode.PERIOD, 1f),
        KeyDefinition("符", KeyCode.SYMBOL_LAYOUT, 1f),
        KeyDefinition("↵", KeyCode.ENTER, 1.3f)
    )

    private fun bottomRowEn(): List<KeyDefinition> = listOf(
        KeyDefinition("中/英", KeyCode.MODE_SWITCH, 1.2f),
        KeyDefinition(",", KeyCode.COMMA, 1f),
        KeyDefinition(spaceBarLabel("Space"), KeyCode.SPACE, 6.5f),
        KeyDefinition(".", KeyCode.PERIOD, 1f),
        KeyDefinition("符", KeyCode.SYMBOL_LAYOUT, 1f),
        KeyDefinition("↵", KeyCode.ENTER, 1.3f)
    )

    private fun charToKeyCode(ch: Char): Int = when (ch) {
        in 'a'..'z' -> KeyEvent.KEYCODE_A + (ch - 'a')
        in '0'..'9' -> KeyEvent.KEYCODE_0 + (ch - '0')
        else -> ch.code
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun spaceBarLabel(base: String): String =
        if (BuildConfig.VOICE_INPUT_ENABLED) "$base 🎤" else base

    private fun sp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        value,
        resources.displayMetrics
    )

    private fun voiceRows(): List<List<KeyDefinition>> = listOf(
        listOf(
            KeyDefinition("返回键盘", KeyCode.EXIT_VOICE, 4f),
            KeyDefinition("中/英", KeyCode.MODE_SWITCH, 4f),
            KeyDefinition("符", KeyCode.SYMBOL_LAYOUT, 4f)
        ),
        listOf(
            KeyDefinition("长按空格语音", KeyCode.EXIT_VOICE, 4f),
            KeyDefinition("↵", KeyCode.ENTER, 1.5f)
        )
    )
}