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
import com.economic.dashboard.databinding.FragmentEmploymentBinding
import com.economic.dashboard.models.EconomicDataPoint
import com.economic.dashboard.ui.BenchmarkStyle
import com.economic.dashboard.ui.Benchmarks
import com.economic.dashboard.ui.EconomicViewModel
import com.economic.dashboard.ui.views.SkeletonController
import com.economic.dashboard.utils.ChartHelper
import com.economic.dashboard.utils.EdgeToEdgeInsets
import com.economic.dashboard.utils.NumberFormatUtil
import com.economic.dashboard.utils.TimeframeSelector
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.LimitLine
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter

import java.util.Locale

class EmploymentFragment : Fragment() {

    private var binding: FragmentEmploymentBinding? = null
    private lateinit var skeleton: SkeletonController

    private lateinit var viewModel: EconomicViewModel
    private lateinit var swappableChart: LineChart
    private lateinit var tvChartTitle: TextView

    private lateinit var cardUnemploymentStatus: CardView
    private lateinit var cardLaborParticipation: CardView
    private lateinit var tvUnempValue: TextView
    private lateinit var tvUnempLowInfo: TextView
    private lateinit var tvLaborValue: TextView
    /** Roots of the included view_status_badge rows (R2-TICKET-03). */
    private lateinit var statusUnemp: View
    private lateinit var statusLabor: View

    private var activeCard = "unemployment"
    private var currentUnempData: List<EconomicDataPoint>? = null
    private var currentLaborData: List<EconomicDataPoint>? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val b = FragmentEmploymentBinding.inflate(inflater, container, false)
        binding = b
        // IOS-04: bottom inset ONLY — this fragment lives inside EconomyFragment's
        // ViewPager2, below the host's tab strip, so the host owns the top inset.
        EdgeToEdgeInsets.applyBottomInset(b.root)
        skeleton = SkeletonController.wrap(b.root)
        return skeleton.getRoot()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val b = binding ?: return
        viewModel = ViewModelProvider(requireActivity()).get(EconomicViewModel::class.java)

        // TICKET-18: per-screen retry chip for this screen's series
        val retryChip: TextView? = view.findViewById(R.id.tvRetry)
        val retryKeys = arrayOf(EconomicViewModel.CACHE_EMPLOYMENT)
        viewModel.getFailedSeries().observe(viewLifecycleOwner) { failed ->
            if (retryChip != null) {
                var hit: String? = null
                if (failed != null) for (k in retryKeys) if (failed.contains(k)) { hit = k; break }
                if (hit != null) {
                    val key = hit
                    retryChip.setOnClickListener { viewModel.retrySeries(key) }
                    retryChip.visibility = View.VISIBLE
                } else {
                    retryChip.visibility = View.GONE
                }
            }
        }

        swappableChart = b.swappableChart
        AskAnalyst.attachChartExplain(
            swappableChart, requireActivity(), "U.S. employment data (unemployment rate and related labor series)")
        tvChartTitle = b.tvChartTitle

        styleChart(swappableChart)
        ChartHelper.declutterDark(swappableChart)
        ChartHelper.attachCrosshair(swappableChart) // TICKET-23
        addEmploymentLimitLines(swappableChart)

        // TICKET-11: inline range switcher redraws the active labor series.
        val tfSel: LinearLayout? = view.findViewById(R.id.timeframeSelector)
        TimeframeSelector.attach(tfSel, "employment") { buildSwappableChart() }

        swappableChart.axisLeft.setValueFormatter(object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                return String.format(Locale.US, "%.1f%%", value)
            }
        })

        cardUnemploymentStatus = b.cardUnemploymentStatus
        AskAnalyst.wireCardLongPress(
            cardUnemploymentStatus, requireActivity(), "the unemployment rate")
        tvUnempValue = b.tvUnempValue
        tvUnempLowInfo = b.tvUnempLowInfo
        statusUnemp = b.statusUnemp.root

        cardLaborParticipation = b.cardLaborParticipation
        AskAnalyst.wireCardLongPress(
            cardLaborParticipation, requireActivity(), "labor force participation")
        tvLaborValue = b.tvLaborValue
        statusLabor = b.statusLabor.root

        cardUnemploymentStatus.setOnClickListener {
            activeCard = "unemployment"; setActiveCard("unemployment"); buildSwappableChart()
        }
        cardLaborParticipation.setOnClickListener {
            activeCard = "labor"; setActiveCard("labor"); buildSwappableChart()
        }

        setActiveCard("unemployment")

        viewModel.getEmploymentData().observe(viewLifecycleOwner) { data ->
            if (data != null) {
                currentUnempData = data
                currentLaborData = data
                calculateUnemploymentStatus(data)
                calculateLaborParticipationStatus(data)
                buildSwappableChart()
                if (::skeleton.isInitialized) skeleton.reveal()
            }
        }
    }

    private fun setActiveCard(which: String) {
        // R3-TICKET-01: border geometry lives in BenchmarkStyle so it tracks the card radius token.
        cardUnemploymentStatus.foreground = if ("unemployment" == which) BenchmarkStyle.activeCardBorder(
            requireContext(), R.dimen.radius_card_kpi) else null
        cardLaborParticipation.foreground = if ("labor" == which) BenchmarkStyle.activeCardBorder(
            requireContext(), R.dimen.radius_card_kpi) else null
        tvChartTitle.text = if ("labor" == which) "Labor Participation Rate Trend" else "Unemployment Rate Trend"
    }

    private fun buildSwappableChart() {
        if (!::swappableChart.isInitialized) return
        val data: List<EconomicDataPoint>?
        val seriesName: String
        val lineColor: String
        if ("labor" == activeCard) { data = currentLaborData; seriesName = "Labor Force Participation Rate"; lineColor = "#4285F4" }
        else { data = currentUnempData; seriesName = "Unemployment Rate"; lineColor = "#EA4335" }
        if (data == null || data.isEmpty()) return
        buildChart(data, seriesName, swappableChart, lineColor)
    }

    private fun calculateUnemploymentStatus(data: List<EconomicDataPoint>) {
        val rows = EconomicViewModel.filterBySeries(data, "Unemployment Rate")
        if (rows.isEmpty()) return
        val currentRate = rows[rows.size - 1].value
        var low = Double.MAX_VALUE
        val startIndex = Math.max(0, rows.size - 12)
        for (i in startIndex until rows.size) low = Math.min(low, rows[i].value)
        val riseFromLow = currentRate - low

        tvUnempValue.text = NumberFormatUtil.percent(currentRate, 1) // TICKET-19
        tvUnempLowInfo.text = "12-month low: " + NumberFormatUtil.percent(low, 1) +
            " (Rise: " + NumberFormatUtil.signedPercent(riseFromLow, 1) + ")"

        // Shared with Overview so the same rate can't grade differently by screen (TICKET-03)
        val result = Benchmarks.unemployment(currentRate, riseFromLow)
        // R2-TICKET-02: no setTextColor here — @style/Badge.Status owns the colour.
        BenchmarkStyle.applyBadge(statusUnemp, requireContext(), result)
    }

    private fun calculateLaborParticipationStatus(data: List<EconomicDataPoint>) {
        val rows = EconomicViewModel.filterBySeries(data, "Labor Force Participation Rate")
        if (rows.isEmpty()) return
        val currentRate = rows[rows.size - 1].value
        tvLaborValue.text = NumberFormatUtil.percent(currentRate, 1) // TICKET-19
        val result = Benchmarks.laborForce(currentRate)
        BenchmarkStyle.applyBadge(statusLabor, requireContext(), result)
    }

    private fun addEmploymentLimitLines(chart: LineChart) {
        addLine(chart, 4.5f, "Healthy ≤4.5%", "#6FA97A")
        addLine(chart, 5.5f, "Elevated 5.5%", "#D98E4F")
        addLine(chart, 7.0f, "Recession 7.0%", "#C75B4E")
        addLine(chart, 63.3f, "Pre-COVID 63.3%", "#6FA97A")
        addLine(chart, 62.0f, "Declining 62.0%", "#D98E4F")
        addLine(chart, 61.0f, "Critical 61.0%", "#C75B4E")
        chart.axisLeft.setDrawLimitLinesBehindData(true)
    }

    private fun addLine(chart: LineChart, value: Float, label: String, color: String) {
        val l = LimitLine(value, label)
        l.setLineColor(Color.parseColor(color)); l.setLineWidth(1f)
        l.setTextColor(Color.parseColor(color)); l.setTextSize(9f); l.enableDashedLine(8f, 4f, 0f)
        chart.axisLeft.addLimitLine(l)
    }

    private fun buildChart(data: List<EconomicDataPoint>, series: String, chart: LineChart, hexColor: String) {
        var rows = EconomicViewModel.filterBySeries(data, series)
        if (rows.isEmpty()) return
        rows = EconomicViewModel.filterByTimeframe(requireContext(), rows, "employment")
        val entries = ArrayList<Entry>()
        val dates = ArrayList<String>()
        for (i in rows.indices) {
            entries.add(Entry(i.toFloat(), rows[i].value.toFloat()))
            val d = rows[i].date
            dates.add(if (d.length >= 7) d.substring(5, 7) + "-" + d.substring(0, 4) else d)
        }
        chart.xAxis.setValueFormatter(object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                val idx = value.toInt()
                return if ((idx % 5 == 0 || idx == dates.size - 1) && idx >= 0 && idx < dates.size) dates[idx] else ""
            }
        })
        val ds = LineDataSet(entries, series)
        ChartHelper.styleLineSeries(ds, Color.parseColor(hexColor)) // IOS-07
        chart.setData(LineData(ds)); chart.animateX(500); chart.invalidate()
    }

    private fun styleChart(chart: LineChart) {
        val grid = Color.argb(0x14, 0xFF, 0xFF, 0xFF)
        chart.setBackgroundColor(Color.parseColor("#1C2236")); chart.setDrawGridBackground(false)
        chart.setDrawBorders(false); chart.setTouchEnabled(true); chart.setDragEnabled(true)
        chart.setScaleEnabled(true); chart.setPinchZoom(true); chart.description.setEnabled(false)
        chart.setNoDataText("Loading data..."); chart.setExtraBottomOffset(8f)
        val x = chart.xAxis
        x.setPosition(XAxis.XAxisPosition.BOTTOM); x.setTextColor(Color.parseColor("#5A6A8A")); x.setTextSize(9f)
        x.setDrawGridLines(true); x.setGridColor(grid); x.setLabelRotationAngle(-45f)
        x.setGranularity(1f); x.setLabelCount(6, false); x.setAvoidFirstLastClipping(true)
        val y = chart.axisLeft
        y.setTextColor(Color.parseColor("#8899BB")); y.setTextSize(10f); y.setDrawGridLines(true); y.setGridColor(grid)
        chart.axisRight.setEnabled(false)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }
}
