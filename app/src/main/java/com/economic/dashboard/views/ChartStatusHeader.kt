package com.economic.dashboard.views

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.economic.dashboard.R
import com.economic.dashboard.ui.BenchmarkStyle
import com.economic.dashboard.ui.Benchmarks

/**
 * 2026-10-16 unified chart header: the analyst chart card's status pill
 * (tier dot + word) over the latest value, shared by every chart in the app.
 * Layout is `view_chart_header.xml`; the chat card includes it in XML, the
 * metric sheet and tab pages get it from [attachAbove] so their nine
 * differing chart layouts need no edits.
 */
object ChartStatusHeader {

    /** Binds an inflated `view_chart_header` block. Tier color goes on the
     *  dot, never the text. A null grade hides the pill. */
    @JvmStatic
    fun bind(block: View, grade: Benchmarks.Result?, pillText: String?, latest: String) {
        val pill: View = block.findViewById(R.id.llChartPill)
        if (grade == null) {
            pill.visibility = View.GONE
        } else {
            pill.visibility = View.VISIBLE
            BenchmarkStyle.tintDot(block.findViewById(R.id.viewChartPillDot), block.context, grade.tier)
            block.findViewById<TextView>(R.id.tvChartPill).text = pillText ?: grade.word
        }
        block.findViewById<TextView>(R.id.tvChartValue).text = latest
    }

    /**
     * Returns the header block above [chart], inserting it on first call:
     * as the previous sibling in a vertical LinearLayout, or by wrapping the
     * chart in one when the parent is anything else (the sheet's card).
     */
    @JvmStatic
    fun attachAbove(chart: View): View {
        (chart.getTag(R.id.llChartHeaderBlock) as? View)?.let { return it }
        val ctx = chart.context
        val row = FrameLayout(ctx)
        val block = LayoutInflater.from(ctx).inflate(R.layout.view_chart_header, row, false)
        row.addView(block, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END))
        val padH = ctx.resources.getDimensionPixelSize(R.dimen.space_xl)
        val padT = ctx.resources.getDimensionPixelSize(R.dimen.space_sm)
        row.setPadding(padH, padT, padH, 0)
        val rowLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        val parent = chart.parent as ViewGroup
        if (parent is LinearLayout && parent.orientation == LinearLayout.VERTICAL) {
            parent.addView(row, parent.indexOfChild(chart), rowLp)
        } else {
            val index = parent.indexOfChild(chart)
            val chartLp = chart.layoutParams
            parent.removeViewAt(index)
            val column = LinearLayout(ctx)
            column.orientation = LinearLayout.VERTICAL
            column.addView(row, rowLp)
            column.addView(chart, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, chartLp.height))
            // The column takes the chart's place, margins and width.
            val columnLp = if (chartLp is ViewGroup.MarginLayoutParams)
                ViewGroup.MarginLayoutParams(chartLp) else ViewGroup.MarginLayoutParams(chartLp)
            columnLp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            parent.addView(column, index, columnLp)
        }
        chart.setTag(R.id.llChartHeaderBlock, block)
        return block
    }
}
