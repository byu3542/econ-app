package com.economic.dashboard.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

import com.google.android.material.card.MaterialCardView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider

import com.economic.dashboard.R
import com.economic.dashboard.analyst.AskAnalyst
import com.economic.dashboard.databinding.FragmentEmploymentBinding
import com.economic.dashboard.models.EconomicDataPoint
import com.economic.dashboard.ui.BenchmarkStyle
import com.economic.dashboard.ui.Benchmarks
import com.economic.dashboard.ui.EconomicViewModel
import com.economic.dashboard.ui.MetricPopupController
import com.economic.dashboard.ui.views.SegmentedView
import com.economic.dashboard.ui.views.SkeletonController
import com.economic.dashboard.utils.ChartHelper
import com.economic.dashboard.utils.AxisDateFormat
import com.economic.dashboard.utils.ChartPalette
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

class EmploymentFragment : Fragment() {

    private var binding: FragmentEmploymentBinding? = null
    private lateinit var skeleton: SkeletonController

    private lateinit var viewModel: EconomicViewModel
    private lateinit var swappableChart: LineChart
    private lateinit var tvChartTitle: TextView

    private lateinit var cardUnemploymentStatus: MaterialCardView
    private lateinit var cardLaborParticipation: MaterialCardView
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
        RetryChip.wire(this, view, viewModel, EconomicViewModel.CACHE_EMPLOYMENT)

        swappableChart = b.swappableChart
        AskAnalyst.attachChartExplain(
            swappableChart, requireActivity(), "U.S. employment data (unemployment rate and related labor series)")
        tvChartTitle = b.tvChartTitle

        ChartHelper.stylePanelChart(swappableChart)
        ChartHelper.declutterPanel(swappableChart)
        ChartHelper.attachCrosshair(swappableChart) // TICKET-23
        addEmploymentLimitLines(swappableChart)

        // TICKET-11: inline range switcher redraws the active labor series.
        val tfSel: SegmentedView? = view.findViewById(R.id.timeframeSelector)
        TimeframeSelector.attach(tfSel, "employment") { buildSwappableChart() }

        swappableChart.axisLeft.setValueFormatter(object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                return String.format(Locale.US, "%.1f%%", value)
            }
        })

        cardUnemploymentStatus = b.cardUnemploymentStatus
        AskAnalyst.wireCardLongPress(
            cardUnemploymentStatus, requireActivity(), "the unemployment rate", "LNS14000000")
        tvUnempValue = b.tvUnempValue
        tvUnempLowInfo = b.tvUnempLowInfo
        statusUnemp = b.statusUnemp.root

        cardLaborParticipation = b.cardLaborParticipation
        AskAnalyst.wireCardLongPress(
            cardLaborParticipation, requireActivity(), "labor force participation", "LNS11300000")
        tvLaborValue = b.tvLaborValue
        statusLabor = b.statusLabor.root

        // P6 TICKET-35: tap inactive = swap chart (existing), tap active = metric popup.
        cardUnemploymentStatus.setOnClickListener { onCardTap("unemployment") }
        cardLaborParticipation.setOnClickListener { onCardTap("labor") }

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

    // P6 TICKET-35: card ids here double as MetricPopupRegistry ids.
    private fun onCardTap(which: String) {
        if (activeCard == which) {
            MetricPopupController.show(requireContext(), viewModel, which)
        } else {
            activeCard = which; setActiveCard(which); buildSwappableChart()
        }
    }

    private fun setActiveCard(which: String) {
        // R3-TICKET-01 / U10: the active card swaps its stroke through BenchmarkStyle.
        BenchmarkStyle.setCardActive(cardUnemploymentStatus, "unemployment" == which)
        BenchmarkStyle.setCardActive(cardLaborParticipation, "labor" == which)
        tvChartTitle.text = if ("labor" == which) "Labor Participation Trends" else "Unemployment Trends"
    }

    // The active series gets filtered by both its status card and the chart on
    // every tick; filterBySeries scans+sorts per call, so cache per source list
    // (identity-keyed — each LiveData emission is a new reference).
    private var filterSource: List<EconomicDataPoint>? = null
    private val filterCache = HashMap<String, List<EconomicDataPoint>>()

    private fun rowsFor(data: List<EconomicDataPoint>, series: String): List<EconomicDataPoint> {
        if (filterSource !== data) { filterSource = data; filterCache.clear() }
        return filterCache.getOrPut(series) { EconomicViewModel.filterBySeries(data, series) }
    }

    private fun buildSwappableChart() {
        if (!::swappableChart.isInitialized) return
        val data: List<EconomicDataPoint>?
        val seriesName: String
        val lineColor: Int = ChartPalette.primary(requireContext()) // TICKET-57
        if ("labor" == activeCard) { data = currentLaborData; seriesName = "Labor Force Participation Rate" }
        else { data = currentUnempData; seriesName = "Unemployment Rate" }
        if (data == null || data.isEmpty()) return
        buildChart(data, seriesName, swappableChart, lineColor)
    }

    private fun calculateUnemploymentStatus(data: List<EconomicDataPoint>) {
        val rows = rowsFor(data, "Unemployment Rate")
        if (rows.isEmpty()) return
        val currentRate = rows[rows.size - 1].value
        val riseFromLow = SeriesMath.riseFromTrailingLow(rows, 12)
        val low = currentRate - riseFromLow

        tvUnempValue.text = NumberFormatUtil.percent(currentRate, 1) // TICKET-19
        // TICKET-59: a sentence, not arithmetic in parentheses.
        tvUnempLowInfo.text = if (riseFromLow < 0.05) getString(R.string.unemp_at_low)
            else getString(R.string.unemp_above_low,
                NumberFormatUtil.percent(riseFromLow, 1), NumberFormatUtil.percent(low, 1))

        // Shared with Overview so the same rate can't grade differently by screen (TICKET-03)
        val result = Benchmarks.unemployment(currentRate, riseFromLow)
        // R2-TICKET-02: no setTextColor here — @style/Badge.Status owns the colour.
        BenchmarkStyle.applyBadge(statusUnemp, requireContext(), result)
    }

    private fun calculateLaborParticipationStatus(data: List<EconomicDataPoint>) {
        val rows = rowsFor(data, "Labor Force Participation Rate")
        if (rows.isEmpty()) return
        val currentRate = rows[rows.size - 1].value
        tvLaborValue.text = NumberFormatUtil.percent(currentRate, 1) // TICKET-19
        val result = Benchmarks.laborForce(currentRate)
        BenchmarkStyle.applyBadge(statusLabor, requireContext(), result)
    }

    private fun addEmploymentLimitLines(chart: LineChart) {
        ChartHelper.addLimitLine(chart, 4.5f, "Healthy ≤4.5%", BenchmarkStyle.Tier.GOOD)
        ChartHelper.addLimitLine(chart, 5.5f, "Elevated 5.5%", BenchmarkStyle.Tier.ELEVATED)
        ChartHelper.addLimitLine(chart, 7.0f, "Recession 7.0%", BenchmarkStyle.Tier.ALERT)
        ChartHelper.addLimitLine(chart, 63.3f, "Pre-COVID 63.3%", BenchmarkStyle.Tier.GOOD)
        ChartHelper.addLimitLine(chart, 62.0f, "Declining 62.0%", BenchmarkStyle.Tier.ELEVATED)
        ChartHelper.addLimitLine(chart, 61.0f, "Critical 61.0%", BenchmarkStyle.Tier.ALERT)
        chart.axisLeft.setDrawLimitLinesBehindData(true)
    }

    private fun buildChart(data: List<EconomicDataPoint>, series: String, chart: LineChart, color: Int) {
        var rows = rowsFor(data, series)
        if (rows.isEmpty()) return
        // Unified charts: grade on the full history, same as the card.
        val latest = rows[rows.size - 1].value
        val grade = if (series == "Unemployment Rate")
            Benchmarks.unemployment(latest, SeriesMath.riseFromTrailingLow(rows, 12))
            else Benchmarks.laborForce(latest)
        rows = EconomicViewModel.filterByTimeframe(requireContext(), rows, "employment")
        val entries = ArrayList<Entry>()
        val dates = ArrayList<String>()
        for (i in rows.indices) {
            entries.add(Entry(i.toFloat(), rows[i].value.toFloat()))
            dates.add(rows[i].date)
        }
        AxisDateFormat.attach(chart, requireContext(), "employment", dates) // TICKET-57
        val ds = LineDataSet(entries, series)
        ChartHelper.styleLineSeries(ds, color) // IOS-07
        ChartHelper.setLineData(chart, LineData(ds), 500)
        ChartHelper.showLatest(chart, grade, NumberFormatUtil.percent(latest, 1))
        // P6 TICKET-37: TalkBack announces the visible series, not the static XML label.
        chart.contentDescription =
            series + " trend, " + NumberFormatUtil.percent(rows[rows.size - 1].value, 1)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }
}
