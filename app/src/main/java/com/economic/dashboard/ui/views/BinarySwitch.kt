package com.economic.dashboard.ui.views

import android.view.View
import android.widget.TextView
import com.economic.dashboard.utils.HapticUtil

/**
 * Generic 2-segment sliding switch (mockup 1c: BENCHMARKS/DATA, TABLE/GRAPH).
 * Sibling to [com.economic.dashboard.utils.TimeframeSelector] but String-tagged
 * and not tied to any persistence — every instance is independent, and unlike
 * `TimeframeSelector` it never looks up its own thumb by id, so two instances
 * can coexist in the same inflated view tree with no id-collision risk.
 * [SegmentedControl] still does the actual spring animation.
 */
object BinarySwitch {

    fun interface OnSelect {
        fun onSelect(tag: String)
    }

    /**
     * @param thumb         the sliding pill, a sibling of [segA]/[segB] in a FrameLayout
     * @param segA, segB    the two segment TextViews; each `tag` is the value passed to [cb]
     * @param initialTag    which segment starts active — must equal one segment's `tag`
     * @param activeColor   text color for the active segment
     * @param inactiveColor text color for the inactive segment
     */
    @JvmStatic
    fun attach(
        thumb: View, segA: TextView, segB: TextView, initialTag: String,
        activeColor: Int, inactiveColor: Int, cb: OnSelect
    ) {
        val segs = listOf(segA, segB)

        fun restyle(activeTag: String, animate: Boolean) {
            var target: TextView? = null
            for (seg in segs) {
                val on = seg.tag?.toString() == activeTag
                seg.setTextColor(if (on) activeColor else inactiveColor)
                if (on) target = seg
            }
            val t = target ?: return
            if (animate) {
                SegmentedControl.slideTo(thumb, t)
            } else {
                // Not a plain post {} — attach() runs while the freshly inflated
                // sheet content hasn't had a layout pass yet, so t.width is 0
                // (and the GRAPH/TABLE switch's pane may not even be visible).
                // See SegmentedControl.snapToWhenReady for why that matters.
                SegmentedControl.snapToWhenReady(thumb, thumb, t)
            }
        }

        for (seg in segs) {
            seg.setOnClickListener { v ->
                val tag = seg.tag?.toString() ?: return@setOnClickListener
                restyle(tag, true)
                HapticUtil.selection(v)
                cb.onSelect(tag)
            }
        }
        restyle(initialTag, false)
        cb.onSelect(initialTag)
    }
}
