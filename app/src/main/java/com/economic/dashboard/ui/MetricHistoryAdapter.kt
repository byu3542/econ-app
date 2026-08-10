package com.economic.dashboard.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.economic.dashboard.R
import com.economic.dashboard.models.EconomicDataPoint
import com.economic.dashboard.utils.DeltaFormatter
import java.util.Locale

/**
 * DATE/VALUE/CHANGE history table for a metric popup's DATA → TABLE pane
 * (mockup 1c). Generalized from the single-purpose [FedFundsHistoryAdapter]
 * (kept as-is — it's a different feature, the Fed Funds hero card, not one
 * of the 13 metric popups): parameterized by format string instead of a
 * hardcoded "%.2f%%", and reuses [DeltaFormatter] instead of ad-hoc
 * Color.parseColor calls.
 *
 * @param valueFmt    format string for the VALUE column, e.g. "%.1f%%"
 * @param deltaFmt    format string passed to [DeltaFormatter.format] for CHANGE
 * @param goodWhenDown true when a decrease is favorable (CPI, unemployment, ...)
 */
class MetricHistoryAdapter(
    private val valueFmt: String,
    private val deltaFmt: String,
    private val goodWhenDown: Boolean
) : RecyclerView.Adapter<MetricHistoryAdapter.ViewHolder>() {

    /** Newest-first, set by [setData]. */
    private val items = ArrayList<EconomicDataPoint>()

    /**
     * True when the currently-shown rows include two or more readings in the
     * same calendar month — i.e. this series (mortgage rates, VIX, yields,
     * stock indices, ...) updates more than once a month, so MM-YYYY alone
     * would collapse distinct rows onto the same-looking date. Recomputed
     * from the actual dates on every [setData] rather than a per-metric flag,
     * so it stays correct automatically if a series' cadence ever changes
     * and for every timeframe window, not just one hardcoded case.
     */
    private var showDay = false

    /** @param rows oldest-first (as returned by EconomicViewModel.filterBy*) */
    fun setData(rows: List<EconomicDataPoint>) {
        items.clear()
        items.addAll(rows.asReversed())
        showDay = hasSubMonthlyCadence(rows)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_metric_history_row, parent, false)
        return ViewHolder(v)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvDate.text = formatDate(item.date)
        holder.tvValue.text = String.format(Locale.US, valueFmt, item.value)

        val ctx = holder.itemView.context
        if (position < items.size - 1) {
            val previous = items[position + 1] // older (chronologically before item)
            val delta = item.value - previous.value
            holder.tvChange.text = DeltaFormatter.format(delta, deltaFmt)
            holder.tvChange.setTextColor(DeltaFormatter.color(ctx, delta, goodWhenDown))
        } else {
            holder.tvChange.text = "—"
            holder.tvChange.setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.sheet_text_secondary))
        }
    }

    override fun getItemCount(): Int = items.size

    private fun formatDate(date: String): String {
        if (date.length < 7) return date
        val month = date.substring(5, 7)
        val year = date.substring(0, 4)
        if (showDay && date.length >= 10) {
            val day = date.substring(8, 10)
            return "$month-$day-$year"
        }
        return "$month-$year"
    }

    /** Distinct "YYYY-MM" keys fewer than the row count ⇒ some month has 2+ readings. */
    private fun hasSubMonthlyCadence(rows: List<EconomicDataPoint>): Boolean {
        val months = HashSet<String>()
        for (r in rows) {
            if (r.date.length < 7) continue
            months.add(r.date.substring(0, 7))
        }
        return months.isNotEmpty() && months.size < rows.size
    }

    class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val tvDate: TextView = v.findViewById(R.id.tvHistoryDate)
        val tvValue: TextView = v.findViewById(R.id.tvHistoryValue)
        val tvChange: TextView = v.findViewById(R.id.tvHistoryChange)
    }
}
