package com.economic.dashboard.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.economic.dashboard.R
import com.economic.dashboard.models.EconomicDataPoint
import java.util.Locale

class FedFundsHistoryAdapter : RecyclerView.Adapter<FedFundsHistoryAdapter.ViewHolder>() {

    private val items = ArrayList<FedChange>()

    class FedChange(
        @JvmField var date: String,
        @JvmField var rate: Double,
        @JvmField var bpsChange: Double
    )

    fun setData(history: List<EconomicDataPoint>?) {
        items.clear()
        if (history == null || history.size < 2) return

        // history is sorted newest to oldest from FRED if we use sort_order=desc
        // We want to calculate change from previous (older) month.
        for (i in 0 until history.size - 1) {
            val current = history[i]
            val previous = history[i + 1]

            val diff = current.value - previous.value
            val bps = diff * 100.0

            var displayDate = current.date
            if (displayDate.length >= 7) {
                displayDate = displayDate.substring(5, 7) + "-" + displayDate.substring(0, 4)
            }

            items.add(FedChange(displayDate, current.value, bps))
        }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_fed_funds_history, parent, false)
        return ViewHolder(v)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvDate.text = item.date
        holder.tvRate.text = String.format(Locale.US, "%.2f%%", item.rate)

        val sign = if (item.bpsChange >= 0) "+" else ""
        holder.tvChange.text = String.format(Locale.US, "%s%.0f bps", sign, item.bpsChange)

        when {
            item.bpsChange > 0 -> holder.tvChange.setTextColor(Color.parseColor("#D9A099")) // Red-ish for rate hikes (usually bad for markets)
            item.bpsChange < 0 -> holder.tvChange.setTextColor(Color.parseColor("#A9C4AE")) // Green-ish for cuts
            else -> {
                holder.tvChange.setTextColor(Color.parseColor("#BBBBBB"))
                holder.tvChange.text = "0 bps"
            }
        }
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val tvDate: TextView = v.findViewById(R.id.tvHistoryDate)
        val tvRate: TextView = v.findViewById(R.id.tvHistoryRate)
        val tvChange: TextView = v.findViewById(R.id.tvHistoryChange)
    }
}
