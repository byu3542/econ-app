package com.economic.dashboard.ui.views

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.dynamicanimation.animation.DynamicAnimation
import com.economic.dashboard.R
import com.economic.dashboard.utils.HapticUtil

/**
 * TICKET-55 — the app's one segmented control.
 *
 * Generalizes the timeframe row (TICKET-11 / IOS-05): N equal-weight, 48dp
 * segments over a track, with a sliding thumb that springs to the active
 * segment ([SegmentedControl]). Replaces the four single-select idioms that
 * used to coexist (MaterialButtonToggleGroup, Chip.Choice, BinarySwitch, and
 * the timeframe row itself), so contrast is decided here from tokens and
 * never by an MDC theme default.
 *
 * Labels and tags come from `app:segmentLabels` / `app:segmentTags`
 * string-arrays (see res/values/arrays.xml) or [setSegments]. [select] moves
 * the thumb without firing [onSelect]; only a user tap notifies.
 *
 * Two emphases: [Emphasis.PRIMARY] is the gold thumb on the recessed track
 * (leaf-tab timeframe rows, Settings, the sheet's DATA / BENCHMARKS row);
 * [Emphasis.SUBTLE] is a divider-toned thumb on a bordered track for rows
 * that should read as subordinate to a primary row above them (the sheet's
 * GRAPH / TABLE and timeframe rows).
 */
class SegmentedView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    enum class Emphasis { PRIMARY, SUBTLE }

    fun interface OnSelect {
        fun onSelect(tag: String)
    }

    /** Fired on user taps only, never by [select]. */
    var onSelect: OnSelect? = null

    /** Tag of the active segment, or null before the first [select]. */
    var selectedTag: String? = null
        private set

    var emphasis: Emphasis = Emphasis.PRIMARY
        set(value) {
            field = value
            applyEmphasis()
        }

    private val track = View(context)
    private val thumb = View(context)
    private val dividers = LinearLayout(context)
    private val row = LinearLayout(context)
    private var textSizePx: Float =
        context.resources.getDimension(R.dimen.text_caption)

    private val heightPx = dp(48)

    init {
        track.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, heightPx)
        addView(track)

        thumb.layoutParams = LayoutParams(0, dp(40)).apply {
            topMargin = dp(4)
            gravity = Gravity.TOP or Gravity.START
        }
        thumb.background = ContextCompat.getDrawable(context, R.drawable.bg_segmented_thumb)
        thumb.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(thumb)

        dividers.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, heightPx)
        dividers.orientation = LinearLayout.HORIZONTAL
        dividers.isBaselineAligned = false
        dividers.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(dividers)

        row.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, heightPx)
        row.orientation = LinearLayout.HORIZONTAL
        row.isBaselineAligned = false
        addView(row)

        var labels: Array<CharSequence>? = null
        var tags: Array<CharSequence>? = null
        var emphasisOrdinal = 0
        if (attrs != null) {
            val a = context.obtainStyledAttributes(attrs, R.styleable.SegmentedView)
            try {
                labels = a.getTextArray(R.styleable.SegmentedView_segmentLabels)
                tags = a.getTextArray(R.styleable.SegmentedView_segmentTags)
                emphasisOrdinal = a.getInt(R.styleable.SegmentedView_segmentEmphasis, 0)
                textSizePx = a.getDimension(R.styleable.SegmentedView_segmentTextSize, textSizePx)
            } finally {
                a.recycle()
            }
        }
        emphasis = if (emphasisOrdinal == 1) Emphasis.SUBTLE else Emphasis.PRIMARY
        if (labels != null) {
            val l = labels.map { it.toString() }
            val t = tags?.map { it.toString() } ?: l
            setSegments(l, t)
        }
    }

    /** The segment TextViews, in order. */
    val segments: List<TextView>
        get() = (0 until row.childCount).mapNotNull { row.getChildAt(it) as? TextView }

    /**
     * Rebuilds the segments. [tags] must be the same length as [labels];
     * clears the selection (call [select] afterwards).
     */
    fun setSegments(labels: List<String>, tags: List<String> = labels) {
        require(labels.size == tags.size) { "labels and tags must pair up" }
        row.removeAllViews()
        dividers.removeAllViews()
        selectedTag = null
        val inactive = ContextCompat.getColor(context, R.color.muted)
        for (i in labels.indices) {
            val seg = TextView(context)
            seg.layoutParams = LinearLayout.LayoutParams(0, heightPx, 1f)
            seg.gravity = Gravity.CENTER
            seg.text = labels[i]
            seg.tag = tags[i]
            seg.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx)
            seg.setTypeface(seg.typeface, android.graphics.Typeface.BOLD)
            seg.setTextColor(inactive)
            seg.isClickable = true
            seg.isFocusable = true
            seg.setOnClickListener { v ->
                val tag = tags[i]
                select(tag, animate = true)
                HapticUtil.selection(v)
                onSelect?.onSelect(tag)
            }
            row.addView(seg)

            if (i > 0) {
                val divider = View(context)
                divider.layoutParams = LinearLayout.LayoutParams(dp(1), dp(20)).apply {
                    gravity = Gravity.CENTER_VERTICAL
                }
                divider.setBackgroundColor(ContextCompat.getColor(context, R.color.line))
                divider.alpha = 0.6f
                dividers.addView(divider)
            }
            val spacer = View(context)
            spacer.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            dividers.addView(spacer)
        }
    }

    /**
     * Makes [tag] the active segment: recolors labels and moves the thumb
     * (spring when [animate], else snap once layout gives the segment a
     * width — see [SegmentedControl.snapToWhenReady]). Does not fire
     * [onSelect]. Unknown tags are ignored.
     */
    fun select(tag: String, animate: Boolean) {
        val active = activeTextColor()
        val inactive = ContextCompat.getColor(context, R.color.muted)
        var target: TextView? = null
        for (seg in segments) {
            val on = seg.tag?.toString() == tag
            seg.setTextColor(if (on) active else inactive)
            seg.isSelected = on
            if (on) target = seg
        }
        val t = target ?: return
        selectedTag = tag
        if (animate) {
            SegmentedControl.slideTo(thumb, t)
        } else {
            SegmentedControl.snapToWhenReady(this, thumb, t)
        }
    }

    /**
     * Disables (or re-enables) one segment: dimmed, not tappable, and
     * described to TalkBack by [reason] instead of its label (TICKET-56).
     */
    fun setSegmentEnabled(tag: String, enabled: Boolean, reason: CharSequence? = null) {
        val seg = segments.firstOrNull { it.tag?.toString() == tag } ?: return
        seg.isEnabled = enabled
        seg.isClickable = enabled
        seg.alpha = if (enabled) 1f else 0.38f
        seg.contentDescription = if (enabled || reason == null) null else "${seg.text}, $reason"
    }

    fun isSegmentEnabled(tag: String): Boolean =
        segments.firstOrNull { it.tag?.toString() == tag }?.isEnabled ?: false

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // Keep the thumb under the active segment when our width changes
        // after the first snap (orientation, split-screen). Skip mid-spring:
        // slideTo owns the thumb until the animation settles.
        val tag = selectedTag ?: return
        val target = segments.firstOrNull { it.tag?.toString() == tag } ?: return
        if (target.width <= 0) return
        if ((thumb.tag as? DynamicAnimation<*>)?.isRunning == true) return
        if (thumb.layoutParams.width != target.width || thumb.translationX != target.left.toFloat()) {
            post { SegmentedControl.snapTo(thumb, target) }
        }
    }

    private fun applyEmphasis() {
        when (emphasis) {
            Emphasis.PRIMARY -> {
                track.background = ContextCompat.getDrawable(context, R.drawable.bg_segmented_track)
                thumb.backgroundTintList = null
            }
            Emphasis.SUBTLE -> {
                track.background = ContextCompat.getDrawable(context, R.drawable.bg_segmented_track_subtle)
                thumb.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(context, R.color.line)
                )
            }
        }
        val tag = selectedTag ?: return
        val active = activeTextColor()
        for (seg in segments) if (seg.tag?.toString() == tag) seg.setTextColor(active)
    }

    /** Label color on the thumb: ink on the accent_soft chip (U10) and on the line-tinted subtle thumb (U11). */
    private fun activeTextColor(): Int = ContextCompat.getColor(
        context,
        R.color.ink
    )

    private fun dp(v: Int): Int =
        Math.round(v * context.resources.displayMetrics.density)
}
