package com.economic.dashboard.utils

import android.content.Context
import com.economic.dashboard.ui.views.SegmentedView

/**
 * Controller for `view_timeframe_selector.xml` (TICKET-11 / IOS-05 / TICKET-55).
 *
 * Wires a [SegmentedView] whose segment tags are month windows: restores the
 * remembered range for [attach]'s chart, persists each user pick, and
 * notifies the caller so it can rebuild that chart. Works purely off the
 * tags, so the same control can be included above several charts on one
 * screen without id collisions.
 */
object TimeframeSelector {

    /** Fired when the user picks a new range; `months` is the window. */
    fun interface OnTimeframeChange {
        fun onMonths(months: Int)
    }

    /**
     * @param selector the included control
     * @param chartId  stable per-chart slug used for persistence (e.g. "gdp")
     * @param cb       invoked immediately with the remembered range, then again
     *                 on each user change
     */
    @JvmStatic
    fun attach(selector: SegmentedView?, chartId: String, cb: OnTimeframeChange?) {
        if (selector == null) return
        val ctx: Context = selector.context
        val stored = SettingsManager.getChartTimeframeMonths(ctx, chartId)
        val offered = selector.segments.map { parseTag(it.tag) }.filter { it > 0 }
        // A remembered window the row doesn't offer (e.g. a global default set
        // before 2Y existed here) would leave no segment active and the thumb
        // unsized; show the nearest window at or above it instead. Not
        // persisted — the user's pick is, on tap.
        val active = when {
            stored in offered -> stored
            else -> offered.filter { it >= stored }.minOrNull() ?: offered.maxOrNull() ?: stored
        }

        selector.onSelect = SegmentedView.OnSelect { tag ->
            val months = parseTag(tag)
            if (months <= 0) return@OnSelect
            SettingsManager.setChartTimeframeMonths(ctx, chartId, months)
            cb?.onMonths(months)
        }
        selector.select(active.toString(), animate = false)
        cb?.onMonths(active)
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
