package com.economic.dashboard.ui.views

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.economic.dashboard.R
import com.economic.dashboard.models.TraceStep

/**
 * MOT-05 — the status icon on one row of the analyst's task list.
 *
 * - pending: an empty ring;
 * - running: a gold arc that spins once every 800ms;
 * - done: a green disc, then a white check drawn along its path in 240ms
 *   ([PathMeasure.getSegment]);
 * - failed: a red disc with a white cross.
 *
 * One small view on purpose: the Batch 2 tool-call ring (breathe, spin,
 * check) is meant to grow from this rather than become a second design.
 *
 * Only a real state change animates, and only when the caller says motion is
 * on — otherwise the new state is drawn at once with no spin. [stop] and
 * [onDetachedFromWindow] cancel everything so a recycled row never keeps a
 * spinner alive.
 */
class StepStatusView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var state: TraceStep.State = TraceStep.State.PENDING
        private set

    private val density = resources.displayMetrics.density
    private val stroke = 1.5f * density

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = 0xFFFFFFFF.toInt()
    }

    private val pendingColor = ColorUtils.setAlphaComponent(
        ContextCompat.getColor(context, R.color.text_muted), 0x80)
    private val runningColor = ContextCompat.getColor(context, R.color.accent_gold)
    private val doneColor = ContextCompat.getColor(context, R.color.signal_good)
    private val failedColor = ContextCompat.getColor(context, R.color.signal_alert)

    private val arcBounds = RectF()
    private val checkPath = Path()
    private val checkSegment = Path()
    private val checkMeasure = PathMeasure()
    private var checkLength = 0f

    /** 0..1 of the check drawn; 1 when not animating. */
    private var checkProgress = 1f
    private var spinDegrees = 0f
    /** Whether the current state was set with motion allowed (drives re-attach). */
    private var motion = false

    private var spinAnimator: ValueAnimator? = null
    private var checkAnimator: ValueAnimator? = null

    init {
        // The row carries the spoken state; the icon is decoration.
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /**
     * Moves to [newState]. [motionOn] is MotionUtil's gate: off means no spin
     * and no check stroke. [live] is false on a full rebind (scrolling back to
     * a finished row), so the check is not redrawn every time it reappears.
     */
    fun setState(newState: TraceStep.State, motionOn: Boolean, live: Boolean) {
        if (newState == state && motionOn == motion) return
        val changed = newState != state
        stop()
        state = newState
        motion = motionOn
        when (newState) {
            TraceStep.State.RUNNING -> if (motionOn) startSpin()
            TraceStep.State.DONE -> if (motionOn && live && changed) startCheck()
            else -> {}
        }
        invalidate()
    }

    /** Cancels the spinner and lands an in-flight check on its end state. */
    fun stop() {
        spinAnimator?.cancel()
        spinAnimator = null
        checkAnimator?.cancel()
        checkAnimator = null
        checkProgress = 1f
    }

    private fun startSpin() {
        if (!isAttachedToWindow) return // onAttachedToWindow restarts it
        spinAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = SPIN_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { spinDegrees = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun startCheck() {
        checkProgress = 0f
        checkAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = CHECK_MS
            addUpdateListener { checkProgress = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = (SIZE_DP * density).toInt()
        setMeasuredDimension(resolveSize(size, widthMeasureSpec), resolveSize(size, heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val inset = stroke / 2f
        arcBounds.set(inset, inset, w - inset, h - inset)
        checkPath.reset()
        checkPath.moveTo(w * 0.28f, h * 0.52f)
        checkPath.lineTo(w * 0.44f, h * 0.67f)
        checkPath.lineTo(w * 0.72f, h * 0.36f)
        checkMeasure.setPath(checkPath, false)
        checkLength = checkMeasure.length
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (state == TraceStep.State.RUNNING && motion && spinAnimator == null) startSpin()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = Math.min(width, height) / 2f - stroke / 2f
        when (state) {
            TraceStep.State.PENDING -> {
                ringPaint.color = pendingColor
                canvas.drawCircle(cx, cy, r, ringPaint)
            }
            TraceStep.State.RUNNING -> {
                ringPaint.color = runningColor
                canvas.drawArc(arcBounds, spinDegrees - 90f, 270f, false, ringPaint)
            }
            TraceStep.State.DONE -> {
                fillPaint.color = doneColor
                canvas.drawCircle(cx, cy, r + stroke / 2f, fillPaint)
                checkSegment.reset()
                checkMeasure.getSegment(0f, checkLength * checkProgress, checkSegment, true)
                canvas.drawPath(checkSegment, markPaint)
            }
            TraceStep.State.FAILED -> {
                fillPaint.color = failedColor
                canvas.drawCircle(cx, cy, r + stroke / 2f, fillPaint)
                val a = width * 0.35f
                val b = width * 0.65f
                canvas.drawLine(a, a, b, b, markPaint)
                canvas.drawLine(b, a, a, b, markPaint)
            }
        }
    }

    private companion object {
        const val SIZE_DP = 14f
        const val SPIN_MS = 800L
        const val CHECK_MS = 240L
    }
}
