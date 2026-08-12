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
import com.economic.dashboard.databinding.FragmentSpreadsBinding
import com.economic.dashboard.models.EconomicDataPoint
import com.economic.dashboard.ui.BenchmarkStyle
import com.economic.dashboard.ui.Benchmarks
import com.economic.dashboard.ui.EconomicViewModel
import com.economic.dashboard.ui.MetricPopupController
import com.economic.dashboard.ui.views.PressFeedback
import com.economic.dashboard.ui.views.SkeletonController
import com.economic.dashboard.utils.ChartHelper
import com.economic.dashboard.utils.EdgeToEdgeInsets
import com.economic.dashboard.utils.NumberFormatUtil
import com.economic.dashboard.utils.RetryChip
import com.economic.dashboard.utils.TimeframeSelector
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.LimitLine
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter

import java.util.Locale

class SpreadsFragment : Fragment() {

    private var binding: FragmentSpreadsBinding? = null
    private lateinit var skeleton: SkeletonController

    private lateinit var viewModel: EconomicViewModel
    private lateinit var swappableChart: LineChart
    private lateinit var tvChartTitle: TextView
    private lateinit var cardSpreadYoY: CardView
    private lateinit var cardSpread3M: CardView
    private lateinit var tvSpreadValue: TextView
    private lateinit var tvSpread3MValue: TextView
    /** Roots of the included view_status_badge rows (R2-TICKET-03). */
    private lateinit var statusSpread2Y: View
    private lateinit var statusSpread3M: View
    private var activeCard = "yoy"
    private var currentYoyData: List<EconomicDataPoint>? = null
    private var current3MData: List<EconomicDataPoint>? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val b = FragmentSpreadsBinding.inflate(inflater, container, false)
        binding = b
        // IOS-04: bottom inset ONLY — this fragment lives inside MarketsFragment's
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
        RetryChip.wire(this, view, viewModel, EconomicViewModel.CACHE_TREASURY)

        swappableChart = b.swappableChart
        AskAnalyst.attachChartExplain(
            swappableChart, requireActivity(), "Treasury yield spreads (10Y-2Y and 10Y-3M) over time")
        tvChartTitle = b.tvChartTitle
        styleChart(swappableChart)
        ChartHelper.declutterDark(swappableChart)
        ChartHelper.attachCrosshair(swappableChart) // TICKET-23
        addSpreadLimitLines(swappableChart)
        swappableChart.axisLeft.setValueFormatter(object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String = String.format(Locale.US, "%.1f%%", value)
        })

        // TICKET-11: inline range switcher redraws the active spread series.
        val tfSel: LinearLayout? = view.findViewById(R.id.timeframeSelector)
        TimeframeSelector.attach(tfSel, "spreads") { buildSwappableChart() }

        cardSpreadYoY = b.cardSpreadYoY
        AskAnalyst.wireCardLongPress(
            cardSpreadYoY, requireActivity(), "the 10Y-2Y Treasury spread")
        tvSpreadValue = b.tvSpreadValue
        statusSpread2Y = b.statusSpread2Y.root
        cardSpread3M = b.cardSpread3M
        AskAnalyst.wireCardLongPress(
            cardSpread3M, requireActivity(), "the 10Y-3M Treasury spread")
        tvSpread3MValue = b.tvSpread3MValue
        statusSpread3M = b.statusSpread3M.root

        // P6 TICKET-35: tap inactive = swap chart (existing), tap active = metric popup.
        cardSpreadYoY.setOnClickListener { onCardTap("yoy") }
        cardSpread3M.setOnClickListener { onCardTap("3m") }
        PressFeedback.attach(cardSpreadYoY)
        PressFeedback.attach(cardSpread3M)
        setActiveCard("yoy")

        viewModel.getTreasuryData().observe(viewLifecycleOwner) { data ->
            if (data != null) { updateSpreadIndicators(data); if (::skeleton.isInitialized) skeleton.reveal() }
        }
        viewModel.getCalculatedSpreadData().observe(viewLifecycleOwner) { data ->
            if (data != null) { currentYoyData = data; buildSwappableChart(); if (::skeleton.isInitialized) skeleton.reveal() }
        }
        viewModel.getCalculatedSpread3MData().observe(viewLifecycleOwner) { data ->
            if (data != null) { current3MData = data; buildSwappableChart() }
        }
    }

    private fun updateSpreadIndicators(data: List<EconomicDataPoint>) {
        updateSingleIndicator(data, "10 Year", "2 Year", tvSpreadValue, statusSpread2Y, false)
        updateSingleIndicator(data, "10 Year", "3 Month", tvSpread3MValue, statusSpread3M, true)
    }

    /** @param badgeRow root of an included view_status_badge (R2-TICKET-03). */
    private fun updateSingleIndicator(
        data: List<EconomicDataPoint>, longKey: String, shortKey: String,
        valTv: TextView, badgeRow: View, is3M: Boolean
    ) {
        val longRate = EconomicViewModel.getLatest(data, longKey)
        val shortRate = EconomicViewModel.getLatest(data, shortKey)
        if (longRate != null && shortRate != null) {
            val spread = longRate.value - shortRate.value
            valTv.text = NumberFormatUtil.percent(spread) // TICKET-19
            // Shared 3M/10Y grader (same as Overview) + distinct RECOVERING/FLATTENING tiers (TICKET-03/06)
            val result = if (is3M) Benchmarks.spread3m10y(spread) else Benchmarks.spread10y2y(spread)
            BenchmarkStyle.applyBadge(badgeRow, requireContext(), result)
        }
    }

    // P6 TICKET-35: the "yoy" card is the 10Y-2Y spread ("spread2y" popup);
    // the "3m" card shares the Overview's 10Y-3M "spread" popup.
    private fun onCardTap(which: String) {
        if (activeCard == which) {
            MetricPopupController.show(
                requireContext(), viewModel, if ("3m" == which) "spread" else "spread2y")
        } else {
            activeCard = which; setActiveCard(which); buildSwappableChart()
        }
    }

    private fun setActiveCard(which: String) {
        // R3-TICKET-01: border geometry lives in BenchmarkStyle so it tracks the card radius token.
        cardSpreadYoY.foreground = if ("yoy" == which) BenchmarkStyle.activeCardBorder(
            requireContext(), R.dimen.radius_card_kpi) else null
        cardSpread3M.foreground = if ("3m" == which) BenchmarkStyle.activeCardBorder(
            requireContext(), R.dimen.radius_card_kpi) else null
        tvChartTitle.text = if ("3m" == which) "10Y-3M Spread" else "10Y-2Y Spread"
    }

    private fun buildSwappableChart() {
        if (!::swappableChart.isInitialized) return
        val source: List<EconomicDataPoint>?
        val label: String
        val lineColor: String
        if ("3m" == activeCard) { source = current3MData; label = "10Y-3M Spread (%)"; lineColor = "#E91E63" }
        else { source = currentYoyData; label = "10Y-2Y Spread (%)"; lineColor = "#D98E4F" }
        if (source == null || source.isEmpty()) return
        val data = EconomicViewModel.filterByTimeframe(requireContext(), source, "spreads")
        val entries = ArrayList<Entry>()
        val dateLabels = ArrayList<String>()
        for (i in data.indices) {
            entries.add(Entry(i.toFloat(), data[i].value.toFloat()))
            val d = data[i].date
            dateLabels.add(if (d.length >= 7) d.substring(5, 7) + "-" + d.substring(0, 4) else d)
        }
        swappableChart.xAxis.setValueFormatter(object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                val idx = value.toInt()
                return if ((idx % 5 == 0 || idx == dateLabels.size - 1) && idx >= 0 && idx < dateLabels.size) dateLabels[idx] else ""
            }
        })
        val ds = LineDataSet(entries, label)
        ChartHelper.styleLineSeries(ds, Color.parseColor(lineColor)) // IOS-07
        swappableChart.setData(LineData(ds)); swappableChart.animateX(500); swappableChart.invalidate()
        // P6 TICKET-37: TalkBack announces the visible series, not the static XML label.
        swappableChart.contentDescription =
            label + ", " + NumberFormatUtil.percent(data[data.size - 1].value)
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

    private fun addSpreadLimitLines(chart: LineChart) {
        val zero = LimitLine(0f, "Inversion")
        zero.setLineColor(Color.parseColor("#C75B4E")); zero.setLineWidth(1.2f)
        zero.setTextColor(Color.parseColor("#C75B4E")); zero.setTextSize(9f); zero.enableDashedLine(8f, 4f, 0f)
        val warn = LimitLine(-0.5f, "Warning")
        warn.setLineColor(Color.parseColor("#D98E4F")); warn.setLineWidth(1f)
        warn.setTextColor(Color.parseColor("#D98E4F")); warn.setTextSize(9f); warn.enableDashedLine(8f, 4f, 0f)
        val deep = LimitLine(-1.5f, "Deep")
        deep.setLineColor(Color.parseColor("#8A6E9E")); deep.setLineWidth(1f)
        deep.setTextColor(Color.parseColor("#8A6E9E")); deep.setTextSize(9f); deep.enableDashedLine(8f, 4f, 0f)
        chart.axisLeft.addLimitLine(zero); chart.axisLeft.addLimitLine(warn); chart.axisLeft.addLimitLine(deep)
        chart.axisLeft.setDrawLimitLinesBehindData(true)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }
}
