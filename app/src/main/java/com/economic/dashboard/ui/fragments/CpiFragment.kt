package com.economic.dashboard.ui.fragments

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

import androidx.cardview.widget.CardView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider

import com.economic.dashboard.R
import com.economic.dashboard.analyst.AskAnalyst
import com.economic.dashboard.databinding.FragmentCpiBinding
import com.economic.dashboard.models.EconomicDataPoint
import com.economic.dashboard.ui.BenchmarkStyle
import com.economic.dashboard.ui.Benchmarks
import com.economic.dashboard.ui.EconomicViewModel
import com.economic.dashboard.ui.MetricPopupController
import com.economic.dashboard.utils.ChartHelper
import com.economic.dashboard.utils.EdgeToEdgeInsets
import com.economic.dashboard.utils.NumberFormatUtil
import com.economic.dashboard.utils.RetryChip
import com.economic.dashboard.utils.TimeframeSelector
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter

import java.util.Locale

class CpiFragment : Fragment() {

    private var binding: FragmentCpiBinding? = null

    private lateinit var viewModel: EconomicViewModel
    private lateinit var cpiChart: LineChart
    private lateinit var cpiWChart: LineChart

    private lateinit var cardCpiYoY: CardView
    private lateinit var tvCpiYoYValue: TextView
    private lateinit var tvCpiYoYDate: TextView
    /** Root of the included view_status_badge row (R2-TICKET-03). */
    private lateinit var statusCpiYoY: View

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val b = FragmentCpiBinding.inflate(inflater, container, false)
        binding = b
        // IOS-04: bottom inset ONLY — this fragment lives inside EconomyFragment's
        // ViewPager2, below the host's tab strip, so the host owns the top inset.
        EdgeToEdgeInsets.applyBottomInset(b.root)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val b = binding ?: return
        viewModel = ViewModelProvider(requireActivity()).get(EconomicViewModel::class.java)

        // TICKET-18: per-screen retry chip for this screen's series
        RetryChip.wire(this, view, viewModel, EconomicViewModel.CACHE_CPI)

        cpiChart = b.cpiUChart
        AskAnalyst.attachChartExplain(
            cpiChart, requireActivity(), "the CPI-U consumer price index trend")
        cpiWChart = b.cpiWChart
        AskAnalyst.attachChartExplain(
            cpiWChart, requireActivity(), "the CPI-W consumer price index trend")

        cardCpiYoY = b.cardCpiYoY
        AskAnalyst.wireCardLongPress(
            cardCpiYoY, requireActivity(), "CPI inflation (YoY)")
        tvCpiYoYValue = b.tvCpiYoYValue
        tvCpiYoYDate = b.tvCpiYoYDate
        statusCpiYoY = b.statusCpiYoY.root

        ChartHelper.styleLineChart(cpiChart, "Consumer Price Index (CPI-U All Items)", "Month", "Index Value")
        ChartHelper.styleLineChart(cpiWChart, "CPI-W All Items", "Month", "Index Value")

        // Add Y-Axis unit labeling
        val indexFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                return String.format(Locale.US, "%.0f", value)
            }
        }
        cpiChart.axisLeft.setValueFormatter(indexFormatter)
        cpiWChart.axisLeft.setValueFormatter(indexFormatter)
        ChartHelper.attachCrosshair(cpiChart) // TICKET-23
        ChartHelper.attachCrosshair(cpiWChart)

        // TICKET-20: inline range switcher — one control drives both CPI charts.
        val tfSel: LinearLayout? = view.findViewById(R.id.timeframeSelector)
        TimeframeSelector.attach(tfSel, "cpi") {
            val d = viewModel.getCpiData().getValue()
            if (d != null) {
                buildChart(d, "CPI-U All Items", cpiChart, "#fbbc04")
                buildChart(d, "CPI-W All Items", cpiWChart, "#ff6d00")
            }
        }

        viewModel.getCpiData().observe(viewLifecycleOwner) { data ->
            if (data != null) {
                buildChart(data, "CPI-U All Items", cpiChart, "#fbbc04")
                buildChart(data, "CPI-W All Items", cpiWChart, "#ff6d00")
                calculateYoYInflation(data)
            }
        }

        // Click listener to show benchmarks table
        cardCpiYoY.setOnClickListener { showCpiBenchmarks() }
    }

    private fun showCpiBenchmarks() {
        val ctx = context ?: return
        MetricPopupController.show(ctx, viewModel, "cpi")
    }

    private fun calculateYoYInflation(data: List<EconomicDataPoint>) {
        val cpiRows = EconomicViewModel.filterBySeries(data, "CPI-U All Items")
        if (cpiRows.size < 13) return

        val latest = cpiRows[cpiRows.size - 1]
        val yearAgo = cpiRows[cpiRows.size - 13]

        val yoyChange = ((latest.value - yearAgo.value) / yearAgo.value) * 100.0

        tvCpiYoYValue.text = NumberFormatUtil.percent(yoyChange) // TICKET-19

        // Shared grading + semantic palette (TICKET-01/03/04)
        val result = Benchmarks.cpiYoY(yoyChange)

        // R2-TICKET-01/02: the badge word's colour is owned by @style/Badge.Status
        // in view_status_badge.xml — no setTextColor here. The card background is
        // already @color/card_navy_badge in the layout (R2-TICKET-04).
        BenchmarkStyle.applyBadge(statusCpiYoY, requireContext(), result)

        // Hide date range as requested
        tvCpiYoYDate.visibility = View.GONE
    }

    private fun buildChart(data: List<EconomicDataPoint>, series: String, chart: LineChart, hexColor: String) {
        var rows = EconomicViewModel.filterBySeries(data, series)
        if (rows.isEmpty()) return
        rows = EconomicViewModel.filterByTimeframe(requireContext(), rows, "cpi")

        val entries = ArrayList<Entry>()
        val dates = ArrayList<String>()

        for (i in rows.indices) {
            entries.add(Entry(i.toFloat(), rows[i].value.toFloat()))
            val d = rows[i].date
            if (d.length >= 7) {
                val year = d.substring(0, 4)
                val month = d.substring(5, 7)
                dates.add("$month-$year")
            } else {
                dates.add(d)
            }
        }

        chart.xAxis.setValueFormatter(object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                val idx = value.toInt()
                return if (idx >= 0 && idx < dates.size) dates[idx] else ""
            }
        })

        val dataSet = LineDataSet(entries, series)
        val color = Color.parseColor(hexColor)
        dataSet.setColor(color)
        dataSet.setCircleColor(color)
        dataSet.setLineWidth(3f)
        dataSet.setCircleRadius(4f)
        dataSet.setDrawValues(false)
        dataSet.setMode(LineDataSet.Mode.CUBIC_BEZIER)

        chart.setData(LineData(dataSet))
        chart.invalidate()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }
}
