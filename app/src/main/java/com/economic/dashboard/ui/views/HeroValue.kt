package com.economic.dashboard.ui.views

import android.util.TypedValue
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import com.economic.dashboard.R

/**
 * TICKET-58 — one hero per three-up row (Von Restorff).
 *
 * The Stocks row and Housing's rates row present three equal 34sp values;
 * the card whose series is charted already carries the gold stroke
 * ([com.economic.dashboard.ui.BenchmarkStyle.activeCardBorder]). This moves
 * the value size with the stroke: the active card's value may grow to
 * `text_display_lg` (34sp), its siblings cap at `text_display` (28sp). The
 * values keep their uniform auto-size (R2-TICKET-06) so three still fit a
 * 360dp row; only the ceiling changes.
 */
object HeroValue {

    @JvmStatic
    fun apply(tv: TextView?, hero: Boolean) {
        if (tv == null) return
        val res = tv.resources
        val min = res.getDimensionPixelSize(R.dimen.text_kpi_autosize_min)
        val max = res.getDimensionPixelSize(if (hero) R.dimen.text_display_lg else R.dimen.text_display)
        val step = Math.round(TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 1f, res.displayMetrics)).coerceAtLeast(1)
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
            tv, min, max, step, TypedValue.COMPLEX_UNIT_PX)
    }

    /** Applies [apply] across a row: exactly [heroIndex] is the hero. */
    @JvmStatic
    fun applyRow(values: List<TextView?>, heroIndex: Int) {
        for (i in values.indices) apply(values[i], i == heroIndex)
    }
}
