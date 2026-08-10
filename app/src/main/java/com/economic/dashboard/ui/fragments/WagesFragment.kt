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
import com.economic.dashboard.databinding.FragmentWagesBinding
import com.economic.dashboard.models.EconomicDataPoint
import com.economic.dashboard.ui.BenchmarkStyle
import com.economic.dashboard.ui.Benchmarks
import com.economic.dashboard.ui.EconomicViewModel
import com.economic.dashboard.ui.MetricPopupController
import com.economic.dashboard.utils.ChartHelper
import com.economic.dashboard.utils.EdgeToEdgeInsets
import com.economic.dashboard.utils.NumberFormatUtil
import com.economic.dashboard.utils.RetryChip
import com.economic.dashboard.utils.SeriesMath
import com.economic.dashboard.utils.TimeframeSelector
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter

import java.util.Locale

class WagesFragment : Fragment() {

    private var binding: FragmentWagesBinding? = null

    private lateinit var viewModel: EconomicViewModel
    private lateinit var hourlyWageChart: LineChart
    private lateinit var comparisonChart: LineChart

    private lateinit var cardWageYoY: CardView
    private lateinit var tvWageYoYValue: TextView
    /** Root of the included view_status_badge row (R2-TICKET-03). */
    private lateinit var statusWageYoY: View

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val b = FragmentWagesBinding.inflate(inflater, container, false)
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
        RetryChip.wire(this, view, viewModel,
            EconomicViewModel.KEY_WAGES, EconomicViewModel.CACHE_CPI)

        hourlyWageChart = b.hourlyWageChart
        AskAnalyst.attachChartExplain(
            hourlyWageChart, requireActivity(), "average hourly earnings over time")
        comparisonChart = b.comparisonChart
        AskAnalyst.attachChartExplain(
            comparisonChart, requireActivity(), "wage growth compared with inflation")

        cardWageYoY = b.cardWageYoY
        AskAnalyst.wireCardLongPress(
            cardWageYoY, requireActivity(), "real wage growth")
        tvWageYoYValue = b.tvWageYoYValue
        statusWageYoY = b.statusWageYoY.root

        ChartHelper.styleLineChart(hourlyWageChart, "Average Hourly Earnings (Private Sector)", "Month", "Wage ($)")
        ChartHelper.styleLineChart(comparisonChart, "Inflation vs Wage Growth (Indexed to 100)", "Month", "Index")
        // TICKET-08: the comparison chart is multi-series — label each line at
        // its right end in its own colour instead of a detached legend.
        ChartHelper.useDirectLabels(comparisonChart)
        ChartHelper.attachCrosshair(hourlyWageChart) // TICKET-23
        ChartHelper.attachCrosshair(comparisonChart)

        // TICKET-20: inline range switcher — one control drives both Wages charts.
        val tfSel: LinearLayout? = view.findViewById(R.id.timeframeSelector)
        TimeframeSelector.attach(tfSel, "wages") {
            val w = viewModel.getWageData().getValue()
            if (w != null) buildHourlyChart(w)
            buildComparisonIfReady()
        }

        // Add Y-Axis unit labeling
        hourlyWageChart.axisLeft.setValueFormatter(object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                return String.format(Locale.US, "$%.2f", value)
            }
        })

        comparisonChart.axisLeft.setValueFormatter(object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                return String.format(Locale.US, "%.0f", value)
            }
        })

        viewModel.getWageData().observe(viewLifecycleOwner) { wageData ->
            if (wageData != null) {
                buildHourlyChart(wageData)
                calculateRealWageGrowth()
            }
            buildComparisonIfReady()
        }

        viewModel.getCpiData().observe(viewLifecycleOwner) { cpiData ->
            if (cpiData != null) {
                calculateRealWageGrowth()
            }
            buildComparisonIfReady()
        }

        // Click listener to show benchmarks table
        cardWageYoY.setOnClickListener { showWagesBenchmarks() }
    }

    private fun showWagesBenchmarks() {
        val ctx = context ?: return
        MetricPopupController.show(ctx, viewModel, "wages")
    }

    private fun calculateRealWageGrowth() {
        val wageData = viewModel.getWageData().getValue()
        val cpiData = viewModel.getCpiData().getValue()

        if (wageData == null || cpiData == null) return

        val wageRows = EconomicViewModel.filterBySeries(wageData, "Average Hourly Earnings - Private")
        val cpiRows = EconomicViewModel.filterBySeries(cpiData, "CPI-U All Items")

        if (wageRows.size < 13 || cpiRows.size < 13) return

        // Shared with MetricPopupController's "wages" popup — one formula (TICKET-03).
        val spreadRows = SeriesMath.realWageGrowthSpread(wageRows, cpiRows)
        if (spreadRows.isEmpty()) return
        val spread = spreadRows[spreadRows.size - 1].value

        // Display the calculated difference as the main value (TICKET-19)
        tvWageYoYValue.text = NumberFormatUtil.percent(spread)

        // Shared grading + semantic palette (TICKET-01/03/04)
        val result = Benchmarks.realWageGrowth(spread)

        // R2-TICKET-01/02: the badge word's colour is owned by @style/Badge.Status.
        // The card background is @color/card_navy_badge in the layout (R2-TICKET-04).
        BenchmarkStyle.applyBadge(statusWageYoY, requireContext(), result)
    }

    private fun buildHourlyChart(data: List<EconomicDataPoint>) {
        var rows = EconomicViewModel.filterBySeries(data, "Average Hourly Earnings - Private")
        if (rows.isEmpty()) return
        rows = EconomicViewModel.filterByTimeframe(requireContext(), rows, "wages")

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

        hourlyWageChart.xAxis.setValueFormatter(object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                val idx = value.toInt()
                return if (idx >= 0 && idx < dates.size) dates[idx] else ""
            }
        })

        val dataSet = LineDataSet(entries, "Avg Hourly Wage ($)")
        dataSet.setColor(Color.parseColor("#8A6E9E"))
        dataSet.setCircleColor(Color.parseColor("#8A6E9E"))
        dataSet.setLineWidth(3f)
        dataSet.setCircleRadius(4f)
        dataSet.setDrawValues(false)
        dataSet.setMode(LineDataSet.Mode.CUBIC_BEZIER)

        hourlyWageChart.setData(LineData(dataSet))
        hourlyWageChart.invalidate()
    }

    private fun buildComparisonIfReady() {
        val cpiData = viewModel.getCpiData().getValue()
        val wageData = viewModel.getWageData().getValue()

        if (cpiData == null || wageData == null) return

        var cpiRows = EconomicViewModel.filterBySeries(cpiData, "CPI-U All Items")
        val wageRows = EconomicViewModel.filterBySeries(wageData, "Average Hourly Earnings - Private")

        if (cpiRows.isEmpty() || wageRows.isEmpty()) return

        // Window the driving CPI series to the standardized chart time range;
        // wages are matched by date, so trimming CPI drives the x-axis.
        cpiRows = EconomicViewModel.filterByTimeframe(requireContext(), cpiRows, "wages")
        if (cpiRows.isEmpty()) return
        val cpiBase = cpiRows[0].value
        var wageBase = -1.0 // set at the first wage point in the window

        val cpiEntries = ArrayList<Entry>()
        val wageEntries = ArrayList<Entry>()
        val dates = ArrayList<String>()

        for (i in cpiRows.indices) {
            val date = cpiRows[i].date
            val indexedCpi = (cpiRows[i].value / cpiBase) * 100.0
            cpiEntries.add(Entry(i.toFloat(), indexedCpi.toFloat()))

            if (date.length >= 7) {
                val year = date.substring(0, 4)
                val month = date.substring(5, 7)
                dates.add("$month-$year")
            } else {
                dates.add(date)
            }

            for (w in wageRows) {
                if (w.date == date) {
                    if (wageBase < 0) wageBase = w.value
                    val indexedWage = (w.value / wageBase) * 100.0
                    wageEntries.add(Entry(i.toFloat(), indexedWage.toFloat()))
                    break
                }
            }
        }

        comparisonChart.xAxis.setValueFormatter(object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                val idx = value.toInt()
                return if (idx >= 0 && idx < dates.size) dates[idx] else ""
            }
        })

        val cpiSet = LineDataSet(cpiEntries, "Consumer Prices (CPI)")
        cpiSet.setColor(Color.parseColor("#fbbc04"))
        cpiSet.setCircleColor(Color.parseColor("#fbbc04"))
        cpiSet.setLineWidth(3f)
        cpiSet.setCircleRadius(3f)
        cpiSet.setDrawValues(false)

        val wageSet = LineDataSet(wageEntries, "Average Wages")
        wageSet.setColor(Color.parseColor("#8A6E9E"))
        wageSet.setCircleColor(Color.parseColor("#8A6E9E"))
        wageSet.setLineWidth(3f)
        wageSet.setCircleRadius(3f)
        wageSet.setDrawValues(false)

        ChartHelper.labelLineEnd(cpiSet, "CPI")
        ChartHelper.labelLineEnd(wageSet, "Wages")

        val lineData = LineData(cpiSet, wageSet)
        comparisonChart.setData(lineData)
        comparisonChart.invalidate()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }
}
