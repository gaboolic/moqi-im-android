package com.moqi.im.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.VelocityTracker
import android.widget.OverScroller
import com.moqi.im.R
import com.moqi.im.engine.CandidateEntry
import kotlin.math.abs

class CandidateView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(19f)
        textAlign = Paint.Align.LEFT
    }
    private val commentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(17f)
        textAlign = Paint.Align.LEFT
    }
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private var candidates: List<CandidateEntry> = emptyList()
    private var currentPageCandidates: List<CandidateEntry> = emptyList()
    private val expandedCandidates = mutableListOf<ExpandedCandidate>()
    private var expandedGridStartIndex: Int = 0
    private var itemRects: List<RectF> = emptyList()
    private var controlRects: List<RectF> = emptyList()
    private var moreButtonRect = RectF()
    private var pressedIndex: Int = -1
    private var pressedControl: Int = -1
    private var moreButtonPressed = false
    private var expanded = false
    private var scrollOffset = 0f
    private var maxScrollOffset = 0f
    private var pageChangeRequested = false
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var isDragging = false
    private val viewConfiguration = ViewConfiguration.get(context)
    private val touchSlop = viewConfiguration.scaledTouchSlop
    private val minimumFlingVelocity = viewConfiguration.scaledMinimumFlingVelocity
    private val maximumFlingVelocity = viewConfiguration.scaledMaximumFlingVelocity
    private val scroller = OverScroller(context)
    private var velocityTracker: VelocityTracker? = null

    private var onCandidateSelected: ((String) -> Unit)? = null
    private var onCandidateIndexSelected: ((Int) -> Unit)? = null
    private var onExpandedCandidateIndexSelected: ((Int, Int) -> Unit)? = null
    private var onExpandedChanged: ((Boolean) -> Unit)? = null
    private var onCandidatePageChange: ((Boolean) -> Unit)? = null
    private var onExpandedLoadNextPage: (() -> Unit)? = null
    private var onMenuClick: (() -> Unit)? = null

    private val isDarkMode: Boolean
        get() = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES

    init {
        setBackgroundColor(if (isDarkMode) DARK_BG else LIGHT_BG)
    }

    fun setCandidates(candidates: List<String>) {
        setCandidateEntries(candidates.map { CandidateEntry(it, "") })
    }

    fun setCandidateEntries(candidates: List<CandidateEntry>) {
        currentPageCandidates = candidates
        this.candidates = candidates
        expandedCandidates.clear()
        pressedIndex = -1
        pressedControl = -1
        moreButtonPressed = false
        pageChangeRequested = false
        scrollOffset = 0f
        if (candidates.isEmpty()) {
            expanded = false
            onExpandedChanged?.invoke(false)
        }
        requestLayout()
        invalidate()
    }

    fun getFirstCandidate(): String? = candidates.firstOrNull()?.text

    fun setOnCandidateSelectedListener(listener: (String) -> Unit) {
        onCandidateSelected = listener
    }

    fun setOnCandidateIndexSelectedListener(listener: (Int) -> Unit) {
        onCandidateIndexSelected = listener
    }

    fun setOnExpandedCandidateIndexSelectedListener(listener: (Int, Int) -> Unit) {
        onExpandedCandidateIndexSelected = listener
    }

    fun setOnExpandedChangedListener(listener: (Boolean) -> Unit) {
        onExpandedChanged = listener
    }

    fun setOnCandidatePageChangeListener(listener: (Boolean) -> Unit) {
        onCandidatePageChange = listener
    }

    fun setOnExpandedLoadNextPageListener(listener: () -> Unit) {
        onExpandedLoadNextPage = listener
    }

    fun setOnMenuClickListener(listener: () -> Unit) {
        onMenuClick = listener
    }

    fun appendExpandedCandidateEntries(pageIndex: Int, entries: List<CandidateEntry>) {
        if (!expanded || entries.isEmpty()) {
            pageChangeRequested = false
            return
        }
        if (expandedCandidates.any { it.pageIndex == pageIndex }) {
            pageChangeRequested = false
            return
        }
        expandedCandidates += entries.mapIndexed { index, entry ->
            ExpandedCandidate(entry, pageIndex, index)
        }
        candidates = expandedCandidates.map { it.entry }
        pageChangeRequested = false
        requestLayout()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        textPaint.color = if (isDarkMode) 0xFFF3F5F7.toInt() else 0xFF20242A.toInt()
        commentPaint.color = if (isDarkMode) 0xFF9CA3AA.toInt() else 0xFF69727D.toInt()
        dividerPaint.color = if (isDarkMode) 0xFF3A4148.toInt() else 0xFFD7DCE2.toInt()
        highlightPaint.color = if (isDarkMode) 0xFF303942.toInt() else 0xFFE5E9EF.toInt()
        arrowPaint.color = if (isDarkMode) 0xFFB8C0C8.toInt() else 0xFF69727D.toInt()

        canvas.save()
        canvas.clipRect(0f, 0f, moreButtonRect.left, height.toFloat())
        val headerHeight = candidateBarHeight()
        if (!expanded) {
            canvas.translate(-scrollOffset, 0f)
        }
        for ((i, rect) in itemRects.withIndex()) {
            if (i >= candidates.size) break
            if (!expanded && (rect.right < scrollOffset || rect.left > scrollOffset + moreButtonRect.left)) continue
            val offsetY = if (expanded && rect.bottom > headerHeight) scrollOffset else 0f
            val drawLeft = rect.left
            val drawTop = rect.top - offsetY
            val drawRight = rect.right
            val drawBottom = rect.bottom - offsetY
            if (expanded && (drawBottom < headerHeight || drawTop > height)) continue

            val candidate = candidates[i]
            val isSelected = i == pressedIndex

            canvas.save()
            val clipTop = if (expanded && rect.top >= headerHeight) {
                drawTop.coerceAtLeast(headerHeight)
            } else {
                drawTop
            }
            canvas.clipRect(drawLeft + dp(1f), clipTop, drawRight - dp(1f), drawBottom)
            if (isSelected) {
                canvas.drawRoundRect(drawLeft, drawTop, drawRight, drawBottom, dp(6f), dp(6f), highlightPaint)
            }

            val textX = drawLeft + dp(12f)
            val textBaseline = (drawTop + drawBottom) / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(candidate.text, textX, textBaseline, textPaint)
            if (candidate.comment.isNotBlank()) {
                val commentX = textX + textPaint.measureText(candidate.text) + dp(7f)
                canvas.drawText(candidate.comment, commentX, textBaseline, commentPaint)
            }
            canvas.restore()
            if (i < itemRects.lastIndex) {
                val lineTop = if (expanded && rect.top >= headerHeight) {
                    drawTop.coerceAtLeast(headerHeight) + dp(8f)
                } else {
                    drawTop + dp(8f)
                }
                canvas.drawLine(drawRight, lineTop, drawRight, drawBottom - dp(8f), dividerPaint)
            }
        }
        canvas.restore()

        if (candidates.isNotEmpty()) {
            if (moreButtonPressed) {
                canvas.drawRoundRect(moreButtonRect, dp(6f), dp(6f), highlightPaint)
            }
            canvas.drawLine(moreButtonRect.left, dp(8f), moreButtonRect.left, height - dp(8f), dividerPaint)
            if (expanded) {
                drawExpandedControls(canvas)
            } else {
                drawMoreArrow(canvas)
            }
        }

        if (candidates.isEmpty()) {
            commentPaint.color = if (isDarkMode) 0xFF858C94.toInt() else 0xFF8A929C.toInt()
            commentPaint.textAlign = Paint.Align.CENTER
            val baseline = height / 2f - (commentPaint.descent() + commentPaint.ascent()) / 2f
            canvas.drawText("墨奇输入法", moreButtonRect.left / 2f, baseline, commentPaint)
            if (moreButtonPressed) {
                canvas.drawRoundRect(moreButtonRect, dp(6f), dp(6f), highlightPaint)
            }
            canvas.drawLine(moreButtonRect.left, dp(8f), moreButtonRect.left, height - dp(8f), dividerPaint)
            canvas.drawText("...", moreButtonRect.centerX(), baseline, commentPaint)
            commentPaint.textAlign = Paint.Align.LEFT
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
            .takeIf { it > 0 }
            ?.toFloat()
            ?: resources.getDimension(R.dimen.candidate_height)
        calculateItemRects(width, h)
        setMeasuredDimension(width, h.toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        calculateItemRects(w, h.toFloat())
        invalidate()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            val nextOffset = scroller.currY.toFloat().coerceIn(0f, maxScrollOffset)
            val dy = nextOffset - scrollOffset
            scrollOffset = nextOffset
            requestPageChangeAtExpandedEdge(dy)
            postInvalidateOnAnimation()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (expanded) {
            ensureVelocityTracker().addMovement(event)
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!scroller.isFinished) {
                    scroller.abortAnimation()
                }
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                isDragging = false
                pageChangeRequested = false
                pressedControl = findControlAt(event.x, event.y)
                moreButtonPressed = (pressedControl >= 0 || moreButtonRect.contains(event.x, event.y)) &&
                    (candidates.isNotEmpty() || !expanded)
                pressedIndex = if (moreButtonPressed) -1 else findItemAt(touchContentX(event.x), touchContentY(event.y))
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (moreButtonPressed) {
                    if (pressedControl >= 0 && findControlAt(event.x, event.y) != pressedControl) {
                        moreButtonPressed = false
                        pressedControl = -1
                        invalidate()
                    }
                    return true
                }
                if (expanded) {
                    val dy = lastY - event.y
                    if (!isDragging && abs(event.y - downY) > touchSlop) {
                        isDragging = true
                        pressedIndex = -1
                    }
                    if (isDragging) {
                        scrollOffset = (scrollOffset + dy).coerceIn(0f, maxScrollOffset)
                        requestPageChangeAtExpandedEdge(dy)
                        postInvalidateOnAnimation()
                    }
                    lastY = event.y
                    return true
                }
                val dx = lastX - event.x
                if (!isDragging && abs(event.x - downX) > touchSlop) {
                    isDragging = true
                    pressedIndex = -1
                }
                if (isDragging) {
                    scrollOffset = (scrollOffset + dx).coerceIn(0f, maxScrollOffset)
                    postInvalidateOnAnimation()
                }
                lastX = event.x
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (moreButtonPressed) {
                    val control = findControlAt(event.x, event.y)
                    if (expanded && control == pressedControl) {
                        when (control) {
                            CONTROL_COLLAPSE -> setExpanded(false)
                            CONTROL_SCROLL_UP -> scrollExpandedPage(forward = false)
                            CONTROL_SCROLL_DOWN -> scrollExpandedPage(forward = true)
                        }
                    } else if (!expanded && moreButtonRect.contains(event.x, event.y)) {
                        if (candidates.isEmpty()) {
                            onMenuClick?.invoke()
                        } else {
                            setExpanded(true)
                        }
                    }
                    moreButtonPressed = false
                    pressedControl = -1
                    recycleVelocityTracker()
                    invalidate()
                    return true
                }
                val idx = findItemAt(touchContentX(event.x), touchContentY(event.y))
                if (!isDragging && idx in candidates.indices && idx == pressedIndex) {
                    if (expanded) {
                        expandedCandidates.getOrNull(idx)?.let { candidate ->
                            onExpandedCandidateIndexSelected?.invoke(candidate.pageIndex, candidate.pageLocalIndex)
                            onCandidateSelected?.invoke(candidate.entry.text)
                        }
                    } else {
                        onCandidateIndexSelected?.invoke(idx)
                        onCandidateSelected?.invoke(candidates[idx].text)
                    }
                }
                if (expanded && isDragging) {
                    flingIfNeeded()
                }
                pressedIndex = -1
                isDragging = false
                recycleVelocityTracker()
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (!scroller.isFinished) {
                    scroller.abortAnimation()
                }
                pressedIndex = -1
                pressedControl = -1
                moreButtonPressed = false
                pageChangeRequested = false
                isDragging = false
                recycleVelocityTracker()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun calculateItemRects(totalWidth: Int, totalHeight: Float = height.toFloat()) {
        val padding = dp(4f)
        val moreButtonWidth = dp(48f)
        val contentWidth = (totalWidth - moreButtonWidth).coerceAtLeast(0f)
        moreButtonRect = RectF(contentWidth, 0f, totalWidth.toFloat(), totalHeight)
        if (expanded) {
            val columns = 3
            val itemGap = dp(4f)
            val headerHeight = candidateBarHeight()
            val rowHeight = dp(42f)
            val itemWidth = ((contentWidth - padding * 2 - itemGap * (columns - 1)) / columns).coerceAtLeast(dp(62f))
            var x = padding
            val rects = mutableListOf<RectF>()
            expandedGridStartIndex = candidates.indexOfFirst { candidate ->
                val desiredWidth = dp(24f) +
                    textPaint.measureText(candidate.text) +
                    if (candidate.comment.isBlank()) 0f else dp(8f) + commentPaint.measureText(candidate.comment)
                val itemWidthInHeader = desiredWidth.coerceAtLeast(dp(54f))
                if (x + itemWidthInHeader <= contentWidth || rects.isEmpty()) {
                    rects += RectF(x, 0f, x + itemWidthInHeader, headerHeight)
                    x += itemWidthInHeader
                    false
                } else {
                    true
                }
            }.takeIf { it >= 0 } ?: candidates.size
            for (index in expandedGridStartIndex until candidates.size) {
                val gridIndex = index - expandedGridStartIndex
                val row = gridIndex / columns
                val col = gridIndex % columns
                val left = padding + col * (itemWidth + itemGap)
                val top = headerHeight + padding + row * rowHeight
                rects += RectF(left, top, left + itemWidth, top + rowHeight)
            }
            itemRects = rects
            val gridCount = (candidates.size - expandedGridStartIndex).coerceAtLeast(0)
            val rowCount = ((gridCount + columns - 1) / columns).coerceAtLeast(0)
            val contentHeight = headerHeight + padding * 2 + rowCount * rowHeight
            maxScrollOffset = (contentHeight - totalHeight).coerceAtLeast(0f)
            scrollOffset = scrollOffset.coerceIn(0f, maxScrollOffset)
        } else {
            var x = padding
            itemRects = candidates.map { candidate ->
                val desiredWidth = dp(24f) +
                    textPaint.measureText(candidate.text) +
                    if (candidate.comment.isBlank()) 0f else dp(8f) + commentPaint.measureText(candidate.comment)
                val itemWidth = desiredWidth.coerceAtLeast(dp(62f))
                RectF(x, 0f, x + itemWidth, totalHeight).also {
                    x += itemWidth
                }
            }
            maxScrollOffset = (x + padding - contentWidth).coerceAtLeast(0f)
            scrollOffset = scrollOffset.coerceIn(0f, maxScrollOffset)
        }
        calculateControlRects()
    }

    private fun setExpanded(value: Boolean) {
        if (expanded == value) return
        expanded = value
        scrollOffset = 0f
        pageChangeRequested = false
        if (expanded) {
            expandedCandidates.clear()
            expandedCandidates += currentPageCandidates.mapIndexed { index, entry ->
                ExpandedCandidate(entry, 0, index)
            }
            candidates = expandedCandidates.map { it.entry }
        } else {
            candidates = currentPageCandidates
            expandedCandidates.clear()
        }
        onExpandedChanged?.invoke(expanded)
        if (expanded) {
            onExpandedLoadNextPage?.invoke()
        }
        requestLayout()
        invalidate()
    }

    private fun calculateControlRects() {
        if (!expanded || moreButtonRect.isEmpty) {
            controlRects = emptyList()
            return
        }
        val headerHeight = candidateBarHeight().coerceAtMost(moreButtonRect.height())
        val remainingHeight = (moreButtonRect.height() - headerHeight).coerceAtLeast(0f)
        val scrollControlHeight = remainingHeight / 2f
        controlRects = listOf(
            RectF(moreButtonRect.left, 0f, moreButtonRect.right, headerHeight),
            RectF(moreButtonRect.left, headerHeight, moreButtonRect.right, headerHeight + scrollControlHeight),
            RectF(moreButtonRect.left, headerHeight + scrollControlHeight, moreButtonRect.right, moreButtonRect.bottom)
        )
    }

    private fun drawMoreArrow(canvas: Canvas) {
        val cx = moreButtonRect.centerX()
        val cy = moreButtonRect.centerY() + dp(1f)
        val halfWidth = dp(8f)
        val halfHeight = dp(5f)
        val path = Path().apply {
            moveTo(cx - halfWidth, cy - halfHeight)
            lineTo(cx + halfWidth, cy - halfHeight)
            lineTo(cx, cy + halfHeight)
            close()
        }
        canvas.drawPath(path, arrowPaint)
    }

    private fun drawExpandedControls(canvas: Canvas) {
        val labels = listOf("", "⌃", "⌄")
        val baselineOffset = -(commentPaint.descent() + commentPaint.ascent()) / 2f
        commentPaint.textAlign = Paint.Align.CENTER
        commentPaint.color = if (isDarkMode) 0xFFB8C0C8.toInt() else 0xFF69727D.toInt()
        for ((index, rect) in controlRects.withIndex()) {
            if (index == pressedControl && moreButtonPressed) {
                canvas.drawRoundRect(rect, dp(6f), dp(6f), highlightPaint)
            }
            if (index > 0) {
                canvas.drawLine(rect.left + dp(8f), rect.top, rect.right - dp(8f), rect.top, dividerPaint)
            }
            if (index == CONTROL_COLLAPSE) {
                drawCollapseArrow(canvas, rect)
            } else {
                canvas.drawText(labels[index], rect.centerX(), rect.centerY() + baselineOffset, commentPaint)
            }
        }
        commentPaint.textAlign = Paint.Align.LEFT
    }

    private fun drawCollapseArrow(canvas: Canvas, rect: RectF) {
        val cx = rect.centerX()
        val cy = rect.centerY() - dp(1f)
        val halfWidth = dp(8f)
        val halfHeight = dp(5f)
        val path = Path().apply {
            moveTo(cx - halfWidth, cy + halfHeight)
            lineTo(cx + halfWidth, cy + halfHeight)
            lineTo(cx, cy - halfHeight)
            close()
        }
        canvas.drawPath(path, arrowPaint)
    }

    private fun requestPageChangeAtExpandedEdge(dy: Float) {
        if (!expanded || pageChangeRequested) return
        val preloadDistance = height * 1.5f
        if (dy > 0f && maxScrollOffset - scrollOffset <= preloadDistance) {
            pageChangeRequested = true
            onExpandedLoadNextPage?.invoke()
        }
    }

    private fun scrollExpandedPage(forward: Boolean) {
        if (!expanded) return
        if (!scroller.isFinished) {
            scroller.abortAnimation()
        }
        val viewport = height.toFloat().coerceAtLeast(1f)
        val delta = viewport * 0.85f * if (forward) 1f else -1f
        scrollOffset = (scrollOffset + delta).coerceIn(0f, maxScrollOffset)
        if (forward && scrollOffset >= maxScrollOffset - dp(1f)) {
            requestPageChangeAtExpandedEdge(1f)
        }
        postInvalidateOnAnimation()
    }

    private fun ensureVelocityTracker(): VelocityTracker {
        return velocityTracker ?: VelocityTracker.obtain().also { velocityTracker = it }
    }

    private fun recycleVelocityTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    private fun flingIfNeeded() {
        val tracker = velocityTracker ?: return
        tracker.computeCurrentVelocity(1000, maximumFlingVelocity.toFloat())
        val velocityY = tracker.yVelocity
        if (abs(velocityY) < minimumFlingVelocity || maxScrollOffset <= 0f) return
        scroller.fling(
            0,
            scrollOffset.toInt(),
            0,
            -velocityY.toInt(),
            0,
            0,
            0,
            maxScrollOffset.toInt()
        )
        postInvalidateOnAnimation()
    }

    private fun findItemAt(x: Float, y: Float): Int {
        itemRects.forEachIndexed { i, rect ->
            if (rect.contains(x, y)) return i
        }
        return -1
    }

    private fun findControlAt(x: Float, y: Float): Int {
        if (!expanded) return -1
        controlRects.forEachIndexed { index, rect ->
            if (rect.contains(x, y)) return index
        }
        return -1
    }

    private fun touchContentX(x: Float): Float = if (expanded) x else x + scrollOffset

    private fun touchContentY(y: Float): Float {
        if (!expanded) return y
        return if (y <= candidateBarHeight()) y else y + scrollOffset
    }

    private fun candidateBarHeight(): Float = resources.getDimension(R.dimen.candidate_height)

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun sp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        value,
        resources.displayMetrics
    )

    companion object {
        private const val DARK_BG = 0xFF20262C.toInt()
        private const val LIGHT_BG = 0xFFF7F8FA.toInt()
        private const val CONTROL_COLLAPSE = 0
        private const val CONTROL_SCROLL_UP = 1
        private const val CONTROL_SCROLL_DOWN = 2
    }

    private data class ExpandedCandidate(
        val entry: CandidateEntry,
        val pageIndex: Int,
        val pageLocalIndex: Int
    )
}