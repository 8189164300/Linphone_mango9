/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.ui.main.crm.fragment

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.LruCache
import android.util.TypedValue
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.OverScroller
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import java.time.Instant
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.linphone.R
import org.linphone.mango9.Mango9Appointment
import org.linphone.mango9.Mango9AppointmentAppearance
import org.linphone.mango9.Mango9CalendarMode
import org.linphone.mango9.Mango9CalendarPresentation
import org.linphone.mango9.Mango9CalendarScreenState
import org.linphone.mango9.Mango9CalendarViewport

/** A virtualized calendar, not one Android View/SDK object per appointment. */
class Mango9CalendarView(context: Context) : View(context) {
    var onEvent: (Mango9Appointment) -> Unit = {}
    var onDay: (LocalDate) -> Unit = {}
    var onCreate: (Instant) -> Unit = {}
    var onOutsideHours: () -> Unit = {}
    var onCrowdedDay: (LocalDate) -> Unit = {}
    var onZoom: (Float) -> Unit = {}
    var onViewportChanged: (Mango9CalendarViewport) -> Unit = {}
    internal var clock: Clock = Clock.systemUTC()
    private val density = resources.displayMetrics.density

    private fun sp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private val scaled = sp(14f) / 14f

    private fun dp(value: Float) = value * density

    private val navy = context.getColor(R.color.mango9_calendar_accent)
    private val ink = context.getColor(R.color.mango9_calendar_ink)
    private val muted = context.getColor(R.color.mango9_calendar_secondary)
    private val line = context.getColor(R.color.mango9_calendar_separator)
    private val surface = context.getColor(R.color.mango9_calendar_surface)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val labels = LruCache<String, StaticLayout>(256)
    private var dateLabels = Mango9CalendarDateLabels(context)
    private val scroller = OverScroller(context)
    private var scope: CoroutineScope? = null
    private var layoutJob: Job? = null
    private var generation = 0L
    private var state: Mango9CalendarScreenState? = null
    private var frame: Frame? = null
    private var offset = 0f
    private var dayOffset = 0f
    private var horizontalDrag = false
    private var dragStarted = false
    private var pendingViewport: Mango9CalendarViewport? = null
    private var zoom = 68f
    internal val isLayoutReady: Boolean get() = frame != null && layoutJob?.isActive != true
    private val header get() = maxOf(dp(55f), scaled * 36f)
    private val gutter get() = maxOf(dp(48f), scaled * 33f)
    private val hourHeight get() = dp(zoom)
    private val minimumEventHeight get() = maxOf(dp(22f), scaled * 14f + dp(5f))

    private data class Block(val bounds: RectF, val event: Mango9Appointment)

    private data class Day(val date: LocalDate, val events: List<Mango9Appointment>, val closed: List<Pair<Double, Double>>, val blocks: List<Block>)

    private data class Frame(val state: Mango9CalendarScreenState, val width: Int, val height: Int, val hourHeight: Float, val rowHeight: Float, val columnWidth: Float, val days: List<Day>) {
        val scrollUnit: Float get() = if (state.mode == Mango9CalendarMode.Month) rowHeight else hourHeight
    }

    private data class Target(val bounds: RectF, val text: String, val click: () -> Unit, val hold: (() -> Unit)? = null, val tap: (() -> Unit)? = click)

    private val targets = ArrayList<Target>()
    private val accessibility = object : ExploreByTouchHelper(this) {
        override fun getVirtualViewAt(x: Float, y: Float): Int = targets.indexOfLast {
            it.bounds.contains(x, y)
        }.takeIf { it >= 0 } ?: INVALID_ID

        override fun getVisibleVirtualViews(ids: MutableList<Int>) { ids.addAll(targets.indices) }

        override fun onPopulateNodeForVirtualView(id: Int, node: AccessibilityNodeInfoCompat) {
            val item = targets.getOrNull(id)
            node.contentDescription = item?.text ?: context.getString(R.string.mango9_calendar_title)
            node.setBoundsInParent(
                item?.bounds?.let {
                Rect(it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt())
            } ?: Rect(0, 0, 1, 1)
            )
            node.className = "android.widget.Button"
            node.isClickable = true
            node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
            if (item?.hold != null) node.addAction(AccessibilityNodeInfoCompat.ACTION_LONG_CLICK)
        }

        override fun onPerformActionForVirtualView(id: Int, action: Int, arguments: Bundle?): Boolean {
            val item = targets.getOrNull(id) ?: return false
            when (action) {
                AccessibilityNodeInfoCompat.ACTION_CLICK -> item.click()
                AccessibilityNodeInfoCompat.ACTION_LONG_CLICK -> (item.hold ?: return false).invoke()
                else -> return false
            }
            sendEventForVirtualView(id, AccessibilityEvent.TYPE_VIEW_CLICKED)
            return true
        }
    }
    private val scaleDetector =
        ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (state?.mode == Mango9CalendarMode.Month) return false
            setZoom(zoom * detector.scaleFactor, detector.focusY)
            return true
        }
    }
        )
    private val gestures = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true); dragStarted = false; horizontalDrag = false; return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (!scaleDetector.isInProgress) targets.lastOrNull { it.bounds.contains(e.x, e.y) }?.tap?.invoke()
            performClick()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (!scaleDetector.isInProgress) targets.lastOrNull { it.bounds.contains(e.x, e.y) }?.hold?.invoke()
        }

        override fun onScroll(first: MotionEvent?, second: MotionEvent, dx: Float, dy: Float): Boolean {
            if (!scaleDetector.isInProgress) {
                if (!dragStarted) {
                    horizontalDrag = maxDayOffset() > 0 && kotlin.math.abs(dx) > kotlin.math.abs(dy); dragStarted = true
                }
                if (horizontalDrag) moveDayOffset(dayOffset + dx) else moveOffset(offset + dy)
            }
            return true
        }

        override fun onFling(first: MotionEvent?, second: MotionEvent, vx: Float, vy: Float): Boolean {
            if (!scaleDetector.isInProgress) {
                scroller.fling(dayOffset.toInt(), offset.toInt(), if (horizontalDrag) -vx.toInt() else 0, if (horizontalDrag) 0 else -vy.toInt(), 0, maxDayOffset().toInt(), 0, maxOffset().toInt())
                postInvalidateOnAnimation()
            }
            return true
        }
    }
    )

    init {
        setBackgroundColor(surface)
        isHorizontalScrollBarEnabled = true
        isFocusable = true
        isClickable = true
        ViewCompat.setAccessibilityDelegate(this, accessibility)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun bind(scope: CoroutineScope) { this.scope = scope }

    fun captureViewport(): Mango9CalendarViewport? = pendingViewport ?: frame?.let {
        Mango9CalendarViewport.at(it.state, offset / it.scrollUnit, if (it.state.mode == Mango9CalendarMode.Week) dayOffset / it.columnWidth else 0f)
    }

    /** Only call before submitting a new view's first snapshot, using its original account owner. */
    fun restoreViewport(value: Mango9CalendarViewport?) { if (frame == null) pendingViewport = value }

    fun resetViewport() {
        generation++; layoutJob?.cancel(); scroller.forceFinished(true)
        state = null; frame = null; pendingViewport = null; offset = 0f; dayOffset = 0f
        targets.clear(); labels.evictAll(); accessibility.invalidateRoot(); invalidate()
    }

    fun submit(value: Mango9CalendarScreenState) {
        val previous = state
        val retained = captureViewport()?.takeIf { it.matches(value) }
        state = value
        if (previous != null && (previous.events === value.events || previous.events == value.events) && previous.date == value.date && previous.mode == value.mode &&
            previous.displayZone == value.displayZone && previous.firstWeekday == value.firstWeekday && previous.hours == value.hours
        ) {
            // Time, the date highlight and the user's clock format can change
            // without a new event list. Repaint without rebuilding placements.
            invalidate(); accessibility.invalidateRoot()
            return
        }
        pendingViewport = retained
        prepare()
    }

    fun setZoom(value: Float, focusY: Float = header) {
        if (!value.isFinite()) return
        val next = value.coerceIn(44f, 180f)
        if (next == zoom) return
        val retained = captureViewport()
        val focus = (focusY - header).coerceAtLeast(0f)
        val oldHeight = hourHeight
        zoom = next
        pendingViewport = retained?.let {
            if (it.mode == Mango9CalendarMode.Month) it else it.copy(position = (it.position + focus / oldHeight - focus / hourHeight).coerceAtLeast(0f))
        }
        labels.evictAll(); targets.clear(); accessibility.invalidateRoot()
        prepare(); onZoom(zoom)
    }

    private fun prepare() {
        val value = state ?: return
        val worker = scope ?: return
        if (width <= 0) return
        pendingViewport = captureViewport()?.takeIf { it.matches(value) }
        val request = ++generation
        layoutJob?.cancel()
        scroller.forceFinished(true)
        // Never leave hit targets from another account, date or geometry active.
        targets.clear(); frame = null; labels.evictAll(); accessibility.invalidateRoot(); invalidate()
        val viewWidth = width
        val viewHeight = height
        val hours = hourHeight
        val minHeight = minimumEventHeight
        val timeGutter = gutter
        val viewHeader = header
        val minRowHeight = maxOf(dp(92f), scaled * 61f)
        layoutJob = worker.launch {
            val result = withContext(Dispatchers.Default) {
                val dates = Mango9CalendarPresentation.days(value.date, value.mode, value.firstWeekday)
                val fittedWidth = (viewWidth - timeGutter).coerceAtLeast(1f) / dates.size
                // Native horizontal pan keeps enlarged week dates and event
                // titles readable without turning seven days into a Day view.
                val columnWidth = if (value.mode == Mango9CalendarMode.Week && scaled / density >= 1.3f) maxOf(fittedWidth, dp(110f), scaled * 72f) else fittedWidth
                Frame(
                    value, viewWidth, viewHeight, hours, maxOf(minRowHeight, (viewHeight - viewHeader) / (dates.size / 7).coerceAtLeast(1)), columnWidth,
                    dates.map { date ->
                    ensureActive()
                    val events = Mango9CalendarPresentation.eventsOn(value.events, date, value.displayZone)
                    val blocks = if (value.mode == Mango9CalendarMode.Month) {
                        emptyList()
                    } else {
                        Mango9CalendarPresentation.placements(events, date, value.displayZone, columnWidth.toDouble(), hours.toDouble(), minHeight.toDouble())
                            .map {
                                Block(RectF(it.x.toFloat(), it.y.toFloat(), (it.x + it.width).toFloat(), (it.y + it.height).toFloat()), it.event)
                            }
                    }
                    Day(date, events, value.hours?.closedMinutes(date, value.displayZone).orEmpty(), blocks)
                }
                )
            }
            if (request != generation) return@launch
            frame = result
            val retained = pendingViewport?.takeIf { it.matches(value) }
            offset = if (retained != null) {
                retained.position * result.scrollUnit
            } else if (value.mode == Mango9CalendarMode.Month) {
                0f
            } else {
                val today = clock.instant().atZone(value.displayZone)
                (if (result.days.any { it.date == today.toLocalDate() }) (today.hour - 1).coerceAtLeast(0) else 8) * hours
            }
            dayOffset = (
                (
                    retained?.dayPosition ?: result.days.indexOfFirst {
                it.date == value.date
            }.coerceAtLeast(0).toFloat()
                ) * result.columnWidth
            ).coerceIn(0f, maxDayOffset())
            pendingViewport = null
            moveOffset(offset); invalidate(); accessibility.invalidateRoot()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { super.onSizeChanged(w, h, oldw, oldh); prepare() }

    override fun onDetachedFromWindow() {
        generation++; layoutJob?.cancel(); scroller.forceFinished(true); super.onDetachedFromWindow()
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean = accessibility.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = accessibility.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)

    override fun onFocusChanged(gain: Boolean, direction: Int, old: Rect?) {
        super.onFocusChanged(gain, direction, old); accessibility.onFocusChanged(gain, direction, old)
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(event.actionMasked != MotionEvent.ACTION_UP && event.actionMasked != MotionEvent.ACTION_CANCEL)
        scaleDetector.onTouchEvent(event)
        gestures.onTouchEvent(event)
        return true
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            moveDayOffset(scroller.currX.toFloat()); moveOffset(scroller.currY.toFloat()); postInvalidateOnAnimation()
        }
    }

    private fun maxDayOffset(): Float = frame?.takeIf { it.state.mode == Mango9CalendarMode.Week }?.let {
        (it.columnWidth * it.days.size - (width - gutter)).coerceAtLeast(0f)
    } ?: 0f

    private fun moveDayOffset(value: Float) {
        if (frame == null || !value.isFinite()) return
        dayOffset = value.coerceIn(0f, maxDayOffset()); invalidate(); accessibility.invalidateRoot()
        awakenScrollBars(); captureViewport()?.let(onViewportChanged)
    }

    override fun computeHorizontalScrollRange() = (width + maxDayOffset()).toInt()

    override fun computeHorizontalScrollOffset() = dayOffset.toInt()

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.isScrollable = maxDayOffset() > 0 || maxOffset() > 0
        if (dayOffset > 0) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT)
        if (dayOffset < maxDayOffset()) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT)
        if (offset > 0) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP)
        if (offset < maxOffset()) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        val model = frame ?: return super.performAccessibilityAction(action, arguments)
        when (action) {
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id -> if (dayOffset > 0) moveDayOffset(dayOffset - model.columnWidth) else return false
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id -> if (dayOffset < maxDayOffset()) moveDayOffset(dayOffset + model.columnWidth) else return false
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id -> if (offset > 0) moveOffset(offset - (height - header).coerceAtLeast(model.hourHeight)) else return false
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id -> if (offset < maxOffset()) moveOffset(offset + (height - header).coerceAtLeast(model.hourHeight)) else return false
            else -> return super.performAccessibilityAction(action, arguments)
        }
        return true
    }

    private fun maxOffset(): Float = if (state?.mode == Mango9CalendarMode.Month) {
        val rows = (frame?.days?.size ?: 35) / 7
        (rows * monthRowHeight() + header - height).coerceAtLeast(0f)
    } else {
        // The last readable label may extend beyond its true midnight end.
        // This padding is presentation-only and never changes appointment times.
        (24 * (frame?.hourHeight ?: hourHeight) + minimumEventHeight + header - height + dp(12f)).coerceAtLeast(0f)
    }

    private fun moveOffset(value: Float) {
        if (frame == null || !value.isFinite()) return
        offset = value.coerceIn(0f, maxOffset()); invalidate(); accessibility.invalidateRoot()
        captureViewport()?.let(onViewportChanged)
    }

    private fun monthRowHeight() = frame?.rowHeight ?: maxOf(dp(92f), scaled * 61f)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        dateLabels = dateLabels.updated(context)
        targets.clear()
        val model = frame ?: return
        if (model.width != width || model.height != height) return
        if (model.state.mode == Mango9CalendarMode.Month) drawMonth(canvas, model) else drawTimeline(canvas, model)
    }

    private fun drawMonth(canvas: Canvas, model: Frame) {
        val column = width / 7f
        val rowHeight = monthRowHeight()
        val titleHeight = maxOf(dp(28f), scaled * 22)
        val eventHeight = maxOf(dp(20f), scaled * 17)
        val today = clock.instant().atZone(model.state.displayZone).toLocalDate()
        canvas.save(); canvas.clipRect(0f, header, width.toFloat(), height.toFloat())
        model.days.forEachIndexed { index, day ->
            val x = index % 7 * column
            val y = header + index / 7 * rowHeight - offset
            if (y + rowHeight < header || y > height) return@forEachIndexed
            val box = RectF(x, maxOf(y, header), x + column, minOf(y + rowHeight, height.toFloat()))
            targets.add(
                Target(box, day.date.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy")) + ", " + resources.getQuantityString(R.plurals.mango9_calendar_count, day.events.size, day.events.size), {
                    onDay(day.date)
                }, {
                createOnDate(day.date, model.state)
            })
            )
            paint.color = line; canvas.drawRect(x, y, x + column, y + dp(1f), paint)
            if (day.closed.sumOf { it.second - it.first } >= 1439.9) {
                paint.color = context.getColor(R.color.mango9_calendar_closed); canvas.drawRect(x, y, x + column, y + rowHeight, paint)
            }
            label(
                canvas, day.date.dayOfMonth.toString(), x + dp(6f), y + dp(3f), column - dp(10f), titleHeight, 15f,
                if (day.date == today) navy else if (day.date.month != model.state.date.month) muted else ink
            )
            if (day.date == today) {
                paint.color = navy; canvas.drawRect(x + dp(5f), y + titleHeight - dp(2f), x + column - dp(5f), y + titleHeight, paint)
            }
            // One count per day at every density/font size. The entire cell opens
            // that day; tiny event-name hit targets must not compete with it.
            if (day.events.isNotEmpty()) label(canvas, "+${day.events.size}", x + dp(6f), y + titleHeight + dp(4f), column - dp(12f), eventHeight, 13f, navy)
        }
        canvas.restore()
        paint.color = surface; canvas.drawRect(0f, 0f, width.toFloat(), header, paint)
        model.days.take(7).forEachIndexed { i, day -> label(canvas, day.date.format(DateTimeFormatter.ofPattern("EEE")), i * column + dp(3f), dp(12f), column - dp(6f), header - dp(12f), 12f, muted) }
    }

    private fun drawTimeline(canvas: Canvas, model: Frame) {
        val count = model.days.size
        val column = model.columnWidth
        val hh = model.hourHeight
        val currentTime = clock.instant().atZone(model.state.displayZone)
        val today = currentTime.toLocalDate()
        canvas.save(); canvas.clipRect(0f, header, width.toFloat(), height.toFloat())
        for (hour in 0..24) {
            val y = header + hour * hh - offset
            if (y < header - scaled * 16 || y > height + scaled * 16) continue
            paint.color = line; canvas.drawRect(gutter, y, width.toFloat(), y + dp(.5f), paint)
            if (hour < 24) label(canvas, dateLabels.wallTime(java.time.LocalTime.of(hour, 0), compactHour = true), 0f, y, gutter - dp(4f), scaled * 20, 10f, muted)
        }
        canvas.save(); canvas.clipRect(gutter, header, width.toFloat(), height.toFloat())
        model.days.forEachIndexed { index, day ->
            val x = gutter + column * index - dayOffset
            if (x + column <= gutter || x >= width) return@forEachIndexed
            day.closed.forEach { (start, end) ->
                paint.color = context.getColor(R.color.mango9_calendar_closed_shade)
                canvas.drawRect(x, header + (start / 60 * hh).toFloat() - offset, x + column, header + (end / 60 * hh).toFloat() - offset, paint)
            }
            paint.color = line; canvas.drawRect(x, header, x + dp(.5f), height.toFloat(), paint)
            val firstSlot = (offset / hh * 4).toInt().coerceAtLeast(0)
            val lastSlot = ((offset + height - header) / hh * 4).toInt().coerceAtMost(95)
            for (slot in firstSlot..lastSlot) {
                val start = Mango9CalendarPresentation.timeSlot(day.date, slot, model.state.displayZone) ?: continue
                val top = header + slot * hh / 4 - offset
                val bounds =
                    RectF(maxOf(gutter, x), maxOf(header, top), minOf(width.toFloat(), x + column), minOf(height.toFloat(), top + hh / 4))
                val allowed = model.state.hours?.allows(start, start.plusSeconds(1800)) != false
                val name = dateLabels.dateTime(start, model.state.displayZone) +
                    if (allowed) "" else ", " + context.getString(R.string.mango9_calendar_outside_hours)
                val create: () -> Unit = if (allowed) {
                    { onCreate(start) }
                } else {
                    { onOutsideHours() }
                }
                // Touch requires a hold to create, so scrolling/tapping empty
                // space cannot open an editor. TalkBack still has an explicit action.
                targets.add(Target(bounds, name, create, create, tap = null))
            }
            // Narrow overlap lanes aggregate by hour. Every hidden event remains
            // reachable through the paged day agenda, rather than silent truncation.
            val crowded = linkedMapOf<Int, Int>()
            day.blocks.forEach { block ->
                if (block.bounds.width() < dp(28f)) {
                    val hour = (block.bounds.top / hh).toInt().coerceIn(0, 23)
                    crowded[hour] = (crowded[hour] ?: 0) + 1
                } else {
                    val rect = RectF(block.bounds).apply { offset(x, header - offset) }
                    if (rect.bottom >= header && rect.top <= height) drawEvent(canvas, block.event, rect, model.state.displayZone)
                }
            }
            crowded.forEach { (hour, total) ->
                val y = header + hour * hh - offset
                if (y + minimumEventHeight >= header && y < height) {
                    val rect = RectF(x + dp(2f), y, x + column - dp(2f), y + minimumEventHeight)
                    paint.color = context.getColor(R.color.mango9_calendar_badge); canvas.drawRoundRect(rect, dp(3f), dp(3f), paint)
                    label(canvas, "+$total", rect.left + dp(3f), rect.top, rect.width() - dp(6f), rect.height(), 12f, navy)
                    targets.add(
                        Target(
                            RectF(rect).apply {
                        top = maxOf(top, header); bottom = minOf(bottom, height.toFloat()); left = maxOf(left, gutter); right = minOf(right, width.toFloat())
                    },
                        resources.getQuantityString(R.plurals.mango9_calendar_count, total, total), { onCrowdedDay(day.date) }
                        )
                    )
                }
            }
            if (day.date == today) {
                val now = currentTime.toLocalTime()
                val y = header + now.toSecondOfDay() / 3600f * hh - offset
                paint.color = navy; canvas.drawRect(x, y, x + column, y + dp(1.5f), paint)
            }
        }
        canvas.restore()
        canvas.restore()
        paint.color = surface; canvas.drawRect(0f, 0f, width.toFloat(), header, paint)
        canvas.save(); canvas.clipRect(gutter, 0f, width.toFloat(), header)
        model.days.forEachIndexed { index, day ->
            val x = gutter + index * column - dayOffset
            if (x + column <= gutter || x >= width) return@forEachIndexed
            label(canvas, day.date.format(DateTimeFormatter.ofPattern("EEE")), x + dp(3f), dp(2f), column - dp(6f), header / 2, 11f, muted)
            label(canvas, day.date.format(DateTimeFormatter.ofPattern(if (count == 1) "MMM d" else "d")), x + dp(3f), header / 2, column - dp(6f), header / 2 - dp(3f), 14f, navy)
            if (day.date == today) {
                paint.color = navy; canvas.drawRect(x + dp(2f), header - dp(2f), x + column - dp(2f), header, paint)
            }
            targets.add(
                Target(RectF(maxOf(gutter, x), 0f, minOf(width.toFloat(), x + column), header), day.date.toString(), {
                onDay(day.date)
            }, { createOnDate(day.date, model.state) })
            )
        }
        canvas.restore()
    }

    private fun createOnDate(date: LocalDate, state: Mango9CalendarScreenState) {
        val start = Mango9CalendarPresentation.startOnDate(date, state.displayZone, state.hours)
        if (start == null) onOutsideHours() else onCreate(start)
    }

    private fun drawEvent(canvas: Canvas, event: Mango9Appointment, rect: RectF, zone: ZoneId) {
        val rgb = Mango9AppointmentAppearance.rgb(event)
        paint.color = Color.argb(55, Color.red(rgb), Color.green(rgb), Color.blue(rgb))
        canvas.drawRoundRect(rect, dp(3f), dp(3f), paint)
        paint.color = rgb or (0xff shl 24); canvas.drawRect(rect.left, rect.top, rect.left + dp(2f), rect.bottom, paint)
        textPaint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val fitted = Mango9CalendarPresentation.textLayout(
            (rect.width() / density).toDouble(), (rect.height() / density).toDouble(), (sp(14f) / density).toDouble(), 8.0,
            !event.status?.name.isNullOrBlank(), event.isRecurring
        ) { size ->
            textPaint.textSize = (size * density).toFloat(); (textPaint.fontMetrics.descent - textPaint.fontMetrics.ascent) / density.toDouble()
        }
        if (fitted.titleLines > 0) {
            val title = (if (fitted.showRecurrence) "↻ " else "") + event.title
            label(canvas, title, rect.left + dp(4f), rect.top + dp(1f), rect.width() - dp(7f), (fitted.lineHeight * fitted.titleLines * density).toFloat(), 14f, ink, fitted.titleLines, (fitted.fontSize * density).toFloat())
            if (fitted.showStatus) label(canvas, event.status?.name.orEmpty(), rect.left + dp(4f), rect.top + ((fitted.titleLines * fitted.lineHeight + 3) * density).toFloat(), rect.width() - dp(7f), (fitted.lineHeight * density).toFloat(), 14f, muted, fontPixels = (fitted.fontSize * density).toFloat())
        }
        val description = listOf(event.title, dateLabels.dateTime(event.startAt, zone, true), dateLabels.timeRange(event.startAt, event.endAt, zone), event.status?.name, event.contact?.displayName, if (event.isRecurring) context.getString(R.string.mango9_calendar_recurring) else null).filterNotNull().joinToString(", ")
        val visible = RectF(rect).apply {
            top = maxOf(top, header); bottom = minOf(bottom, height.toFloat())
            left = maxOf(left, if (frame?.state?.mode == Mango9CalendarMode.Month) 0f else gutter); right = minOf(right, width.toFloat())
        }
        if (!visible.isEmpty) targets.add(Target(visible, description, { onEvent(event) }))
    }

    private fun label(canvas: Canvas, text: String, x: Float, y: Float, width: Float, height: Float, size: Float, color: Int, lines: Int = 1, fontPixels: Float = sp(size)) {
        if (width < 1 || height < 1) return
        val key = "$text|${width.toInt()}|$height|$fontPixels|$lines|$color"
        val layout = labels.get(key) ?: run {
            val metrics = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = fontPixels; this.color = color; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
            StaticLayout.Builder.obtain(text, 0, text.length, metrics, width.toInt()).setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false).setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END).build().also { labels.put(key, it) }
        }
        canvas.save(); canvas.clipRect(x, y, x + width, y + height); canvas.translate(x, y); layout.draw(canvas); canvas.restore()
    }
}
