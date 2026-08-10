package com.economic.dashboard.utils

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.economic.dashboard.R
import com.economic.dashboard.ui.views.SegmentedControl

/**
 * Controller for `view_timeframe_selector.xml` (TICKET-11 / IOS-05).
 *
 * Wires the segmented control's buttons, springs a sliding thumb to the
 * active range (SegmentedControl), persists the choice per chart, and
 * notifies the caller so it can rebuild that chart. Works purely off each
 * segment's `android:tag` (the month window), so the same control can
 * be included above several charts on one screen without id collisions.
 */
object TimeframeSelector {

    /** Fired when the user picks a new range; `months` is the window. */
    fun interface OnTimeframeChange {
        fun onMonths(months: Int)
    }

    /**
     * @param selector the included control's root LinearLayout
     * @param chartId  stable per-chart slug used for persistence (e.g. "gdp")
     * @param cb       invoked immediately with the remembered range, then again
     *                 on each user change
     */
    @JvmStatic
    fun attach(selector: LinearLayout?, chartId: String, cb: OnTimeframeChange?) {
        if (selector == null) return
        val ctx: Context = selector.context
        val active = SettingsManager.getChartTimeframeMonths(ctx, chartId)
        // IOS-05: the sliding thumb is a sibling in view_timeframe_selector.xml's
        // wrapping FrameLayout, not a child of the segment row itself.
        val parent = selector.parent
        val thumb: View? = if (parent is View) parent.findViewById(R.id.timeframeThumb) else null

        for (i in 0 until selector.childCount) {
            val child = selector.getChildAt(i)
            if (child !is TextView) continue
            val seg: TextView = child
            val months = parseTag(seg.tag)
            if (months <= 0) continue
            seg.setOnClickListener { v ->
                SettingsManager.setChartTimeframeMonths(ctx, chartId, months)
                restyle(selector, thumb, months, true)
                HapticUtil.selection(v) // IOS-09
                cb?.onMonths(months)
            }
        }
        restyle(selector, thumb, active, false)
        cb?.onMonths(active)
    }

    /**
     * Text-colors the active segment and springs the thumb under it.
     * `animate=false` (first attach) snaps instead — segment widths
     * aren't known until layout, so the snap runs in a post().
     */
    private fun restyle(selector: LinearLayout, thumb: View?, activeMonths: Int, animate: Boolean) {
        val ctx = selector.context
        val navy = ContextCompat.getColor(ctx, R.color.header_navy)
        val inactive = ContextCompat.getColor(ctx, R.color.text_secondary)
        var activeSeg: TextView? = null

        for (i in 0 until selector.childCount) {
            val child = selector.getChildAt(i)
            if (child !is TextView) continue
            val on = parseTag(child.tag) == activeMonths
            child.setTextColor(if (on) navy else inactive)
            if (on) activeSeg = child
        }

        if (thumb == null || activeSeg == null) return
        val target = activeSeg
        if (animate) {
            SegmentedControl.slideTo(thumb, target)
        } else {
            // Not a plain post {} — this selector may still be inside a GONE
            // pane (DATA tab isn't open yet) when attach() first runs, so the
            // segment has zero width on the first layout pass. See
            // SegmentedControl.snapToWhenReady for why that matters.
            SegmentedControl.snapToWhenReady(selector, thumb, target)
        }
    }

    private fun parseTag(tag: Any?): Int {
        if (tag == null) return -1
        return try {
            tag.toString().toInt()
        } catch (e: NumberFormatException) {
            -1
        }
    }
}
