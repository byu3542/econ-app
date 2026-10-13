package com.economic.dashboard.ui.views

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.TextPaint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.economic.dashboard.utils.MotionUtil
import com.economic.dashboard.utils.NumberFormatUtil

/**
 * MOT-01 — rolling KPI figures (odometer).
 *
 * One child per character of the formatted value. Digits are a clipped
 * column holding a 0–9 strip that a spring translates vertically; every other
 * character (`. % , - $ +`, spaces, "—") is a static TextView. [setValue]
 * diffs against the current value and rolls only the digits that changed,
 * right to left with a [STAGGER_MS] stagger, so a 4.33 → 4.28 move rolls one
 * column and an unchanged refresh produces no motion at all.
 *
 * - Digit-count / format change (9.98 → 10.02): the row is rebuilt, every
 *   column snaps to its new digit, and only the columns that did not exist
 *   before fade in — nothing rolls from a value it never held.
 * - First paint from a placeholder ("—"): set instantly, like TICKET-27 did.
 * - [MotionUtil.animationsEnabled] false: a plain instant set.
 *
 * Springs reuse [PressFeedback]'s damping/stiffness so the app keeps one
 * motion signature. Children carry tabular figures (IOS-03) so nothing
 * reflows mid-roll.
 *
 * Supports `android:text`, `android:textSize`, `android:textColor`,
 * `android:textStyle`, `android:gravity` and
 * `android:autoSizeMinTextSize` / `android:autoSizeMaxTextSize` (shrink-to-fit
 * when the row is wider than the space it is given).
 */
class RollingNumberView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private var current: String = ""
    private var maxTextPx: Float
    private var minTextPx: Float
    private var appliedTextPx: Float
    private var textColors: ColorStateList? = null
    private var textStyle: Int = Typeface.NORMAL
    private val pending = ArrayList<Runnable>()
    private val measurePaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG)
    // The face the child TextViews resolve from the theme (Plus Jakarta Sans
    // since the Pantrii port U2). Shrink-to-fit must measure with the same
    // face the digits draw in, or the fit is computed for the wrong widths.
    private val themeTypeface: Typeface by lazy { TextView(context).typeface ?: Typeface.DEFAULT }

    init {
        orientation = HORIZONTAL
        clipChildren = true
        val defaultPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 28f, resources.displayMetrics)
        var size = defaultPx
        var minSize = -1f
        var maxSize = -1f
        var initial: CharSequence? = null
        if (attrs != null) {
            size = readDimen(attrs, android.R.attr.textSize, defaultPx)
            minSize = readDimen(attrs, android.R.attr.autoSizeMinTextSize, -1f)
            maxSize = readDimen(attrs, android.R.attr.autoSizeMaxTextSize, -1f)
            context.obtainStyledAttributes(attrs, intArrayOf(android.R.attr.textColor)).also {
                textColors = it.getColorStateList(0)
                it.recycle()
            }
            context.obtainStyledAttributes(attrs, intArrayOf(android.R.attr.textStyle)).also {
                textStyle = it.getInt(0, Typeface.NORMAL)
                it.recycle()
            }
            context.obtainStyledAttributes(attrs, intArrayOf(android.R.attr.text)).also {
                initial = it.getText(0)
                it.recycle()
            }
        }
        maxTextPx = if (maxSize > 0f) maxSize else size
        minTextPx = if (minSize > 0f) minSize.coerceAtMost(maxTextPx) else maxTextPx
        appliedTextPx = maxTextPx
        setValueInstant(initial?.toString() ?: "")
    }

    private fun readDimen(attrs: AttributeSet, attr: Int, fallback: Float): Float {
        val a = context.obtainStyledAttributes(attrs, intArrayOf(attr))
        val v = a.getDimension(0, fallback)
        a.recycle()
        return v
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /** The value currently displayed (target value while a roll is in flight). */
    fun getText(): CharSequence = current

    /**
     * Shows [value], rolling only the digits that changed. Equal value → no-op.
     */
    fun setValue(value: String?) {
        val next = value ?: ""
        if (next == current) return
        contentDescription = next
        if (!MotionUtil.animationsEnabled(context) || !current.any { it.isDigit() }) {
            setValueInstant(next)
            return
        }
        cancelPending()
        if (pattern(next) == pattern(current) && childCount == next.length) {
            rollChangedDigits(next)
        } else {
            rebuildWithFade(next)
        }
        current = next
    }

    /** Sets the max (and, when no range was given, fixed) text size in px. */
    fun setTextSizePx(px: Float) {
        setTextSizeRange(minTextPx.coerceAtMost(px), px)
    }

    /** Shrink-to-fit range in px, like a uniform auto-size TextView. */
    fun setTextSizeRange(minPx: Float, maxPx: Float) {
        maxTextPx = maxPx
        minTextPx = minPx.coerceAtMost(maxPx)
        appliedTextPx = maxTextPx
        forEachText { it.setTextSize(TypedValue.COMPLEX_UNIT_PX, appliedTextPx) }
        requestLayout()
    }

    fun setTextColor(color: Int) {
        textColors = ColorStateList.valueOf(color)
        forEachText { it.setTextColor(color) }
    }

    // ── Diff paths ─────────────────────────────────────────────────────────────

    private fun rollChangedDigits(next: String) {
        val last = next.length - 1
        for (i in next.indices) {
            val ch = next[i]
            if (!ch.isDigit() || ch == current[i]) continue
            val col = getChildAt(i) as? DigitColumn ?: continue
            val target = ch - '0'
            // Right to left: the rightmost column moves first.
            val delay = (last - i) * STAGGER_MS
            if (delay == 0L) {
                col.rollTo(target)
            } else {
                val r = Runnable { col.rollTo(target) }
                pending.add(r)
                postDelayed(r, delay)
            }
        }
    }

    private fun rebuildWithFade(next: String) {
        val oldLen = current.length
        build(next)
        // Columns count from the right: a column at distance >= oldLen from
        // the right edge never existed before, so it fades in.
        for (i in next.indices) {
            val fromRight = next.length - 1 - i
            if (fromRight >= oldLen) {
                val child = getChildAt(i)
                child.alpha = 0f
                child.animate().alpha(1f).setDuration(FADE_MS).start()
            }
        }
    }

    private fun setValueInstant(next: String) {
        cancelPending()
        current = next
        contentDescription = next
        if (pattern(next) == pattern(childrenPattern()) && childCount == next.length) {
            for (i in next.indices) {
                (getChildAt(i) as? DigitColumn)?.snapTo(next[i] - '0')
                getChildAt(i).alpha = 1f
            }
        } else {
            build(next)
        }
    }

    private fun build(text: String) {
        removeAllViews()
        for (ch in text) {
            if (ch.isDigit()) {
                val col = DigitColumn(context)
                col.snapTo(ch - '0')
                addView(col, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            } else {
                val tv = newText()
                tv.text = ch.toString()
                addView(tv, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            }
        }
    }

    private fun childrenPattern(): String {
        val sb = StringBuilder()
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            sb.append(if (c is DigitColumn) '#' else (c as TextView).text.firstOrNull() ?: ' ')
        }
        return sb.toString()
    }

    private fun cancelPending() {
        for (r in pending) removeCallbacks(r)
        pending.clear()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // Land every column on its target so a re-attach never shows a half roll.
        setValueInstant(current)
    }

    // ── Shrink-to-fit ──────────────────────────────────────────────────────────

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val avail = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED
            && avail > 0 && minTextPx < maxTextPx && current.isNotEmpty()) {
            measurePaint.textSize = maxTextPx
            measurePaint.typeface = Typeface.create(themeTypeface, textStyle)
            measurePaint.fontFeatureSettings = "tnum"
            val w = measurePaint.measureText(current)
            val fit = if (w > avail) (maxTextPx * avail / w).coerceAtLeast(minTextPx) else maxTextPx
            if (Math.abs(fit - appliedTextPx) > 0.5f) {
                appliedTextPx = fit
                forEachText { it.setTextSize(TypedValue.COMPLEX_UNIT_PX, fit) }
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    // ── Children ───────────────────────────────────────────────────────────────

    private fun newText(): TextView {
        val tv = TextView(context)
        tv.includeFontPadding = false
        tv.maxLines = 1
        tv.gravity = Gravity.CENTER
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, appliedTextPx)
        tv.setTypeface(tv.typeface, textStyle)
        textColors?.let { tv.setTextColor(it) }
        tv.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        NumberFormatUtil.applyTabularFigures(tv) // IOS-03: fixed-width digits
        return tv
    }

    private fun forEachText(fn: (TextView) -> Unit) {
        for (i in 0 until childCount) {
            when (val c = getChildAt(i)) {
                is DigitColumn -> c.forEachCell(fn)
                is TextView -> fn(c)
            }
        }
    }

    /** One clipped digit position holding a vertical 0–9 strip. */
    private inner class DigitColumn(ctx: Context) : FrameLayout(ctx) {
        private val strip = LinearLayout(ctx)
        private var digit = 0
        private var cellH = 0
        private val roller = SpringAnimation(strip, DynamicAnimation.TRANSLATION_Y).also {
            it.spring = SpringForce()
                .setDampingRatio(DAMPING_RATIO)
                .setStiffness(STIFFNESS)
        }

        init {
            clipChildren = true
            strip.orientation = VERTICAL
            for (d in 0..9) {
                val tv = newText()
                tv.text = d.toString()
                strip.addView(tv, LinearLayout.LayoutParams(
                    LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            }
            addView(strip, FrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }

        fun forEachCell(fn: (TextView) -> Unit) {
            for (i in 0 until strip.childCount) fn(strip.getChildAt(i) as TextView)
        }

        fun snapTo(d: Int) {
            digit = d
            roller.cancel()
            strip.translationY = -digit * cellH.toFloat()
        }

        fun rollTo(d: Int) {
            digit = d
            if (cellH == 0 || !MotionUtil.animationsEnabled(context)) {
                snapTo(d)
                return
            }
            roller.animateToFinalPosition(-digit * cellH.toFloat())
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            strip.measure(unspecified, unspecified)
            val h = strip.measuredHeight / 10
            if (h != cellH) {
                cellH = h
                if (!roller.isRunning) strip.translationY = -digit * cellH.toFloat()
            }
            setMeasuredDimension(strip.measuredWidth, cellH)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            strip.layout(0, 0, strip.measuredWidth, strip.measuredHeight)
        }
    }

    companion object {
        // Same spring as PressFeedback (IOS-01): one motion signature app-wide.
        private const val DAMPING_RATIO = 0.75f
        private const val STIFFNESS = 1200f
        private const val STAGGER_MS = 35L
        private const val FADE_MS = 220L

        /** Digits collapse to '#', so "4.33" and "4.28" share a pattern. */
        private fun pattern(s: String): String =
            s.map { if (it.isDigit()) '#' else it }.joinToString("")
    }
}
