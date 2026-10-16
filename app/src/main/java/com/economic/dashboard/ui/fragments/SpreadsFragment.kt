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
import com.economic.dashboard.databinding.FragmentSpreadsBinding
import com.economic.dashboard.models.EconomicDataPoint
import com.economic.dashboard.ui.BenchmarkStyle
import com.economic.dashboard.ui.Benchmarks
import com.economic.dashboard.ui.EconomicViewModel
import com.economic.dashboard.ui.MetricPopupController
import com.economic.dashboard.ui.views.PressFeedback
import com.economic.dashboard.ui.views.RollingNumberView
import com.economic.dashboard.ui.views.SegmentedView
import com.economic.dashboard.ui.views.SkeletonController
import com.economic.dashboard.utils.ChartHelper
import com.economic.dashboard.utils.AxisDateFormat
import com.economic.dashboard.utils.ChartPalette
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

class SpreadsFragment : Fragment() {

    private var binding: FragmentSpreadsBinding? = null
    private lateinit var skeleton: SkeletonController

    private lateinit var viewModel: EconomicViewModel
    private lateinit var swappableChart: LineChart
    private lateinit var tvChartTitle: TextView
    private lateinit var cardSpreadYoY: MaterialCardView
    private lateinit var cardSpread3M: MaterialCardView
    // MOT-01: rolling figures
    private lateinit var tvSpreadValue: RollingNumberView
    private lateinit var tvSpread3MValue: RollingNumberView
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
        ChartHelper.stylePanelChart(swappableChart)
        ChartHelper.declutterPanel(swappableChart)
        ChartHelper.attachCrosshair(swappableChart) // TICKET-23
        addSpreadLimitLines(swappableChart)
        swappableChart.axisLeft.setValueFormatter(object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String = String.format(Locale.US, "%.1f%%", value)
        })

        // TICKET-11: inline range switcher redraws the active spread series.
        val tfSel: SegmentedView? = view.findViewById(R.id.timeframeSelector)
        TimeframeSelector.attach(tfSel, "spreads") { buildSwappableChart() }

        cardSpreadYoY = b.cardSpreadYoY
        AskAnalyst.wireCardLongPress(
            // null: the spread is DGS10 minus DGS2 — no catalogued series holds
            // it, and injecting either leg alone would have the model compare a
            // spread against a yield.
            cardSpreadYoY, requireActivity(), "the 10Y-2Y Treasury spread", null)
        tvSpreadValue = b.tvSpreadValue
        statusSpread2Y = b.statusSpread2Y.root
        cardSpread3M = b.cardSpread3M
        AskAnalyst.wireCardLongPress(
            cardSpread3M, requireActivity(), "the 10Y-3M Treasury spread", null)
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
        valTv: RollingNumberView, badgeRow: View, is3M: Boolean
    ) {
        val longRate = EconomicViewModel.getLatest(data, longKey)
        val shortRate = EconomicViewModel.getLatest(data, shortKey)
        if (longRate != null && shortRate != null) {
            val spread = longRate.value - shortRate.value
            valTv.setValue(NumberFormatUtil.percent(spread)) // TICKET-19, MOT-01
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
        // R3-TICKET-01 / U10: the active card swaps its stroke through BenchmarkStyle.
        BenchmarkStyle.setCardActive(cardSpreadYoY, "yoy" == which)
        BenchmarkStyle.setCardActive(cardSpread3M, "3m" == which)
        tvChartTitle.text = if ("3m" == which) "10Y-3M Spread" else "10Y-2Y Spread"
    }

    private fun buildSwappableChart() {
        if (!::swappableChart.isInitialized) return
        val source: List<EconomicDataPoint>?
        val label: String
        val lineColor: Int = ChartPalette.primary(requireContext()) // TICKET-57
        if ("3m" == activeCard) { source = current3MData; label = "10Y-3M Spread (%)" }
        else { source = currentYoyData; label = "10Y-2Y Spread (%)" }
        if (source == null || source.isEmpty()) return
        val data = EconomicViewModel.filterByTimeframe(requireContext(), source, "spreads")
        val entries = ArrayList<Entry>()
        val dateLabels = ArrayList<String>()
        for (i in data.indices) {
            entries.add(Entry(i.toFloat(), data[i].value.toFloat()))
            dateLabels.add(data[i].date)
        }
        AxisDateFormat.attach(swappableChart, requireContext(), "spreads", dateLabels) // TICKET-57
        val ds = LineDataSet(entries, label)
        ChartHelper.styleLineSeries(ds, lineColor) // IOS-07
        ChartHelper.setLineData(swappableChart, LineData(ds), 500)
        val latest = data[data.size - 1].value
        ChartHelper.showLatest(swappableChart, // unified charts: same grade as the card
            if ("3m" == activeCard) Benchmarks.spread3m10y(latest) else Benchmarks.spread10y2y(latest),
            NumberFormatUtil.percent(latest))
        // P6 TICKET-37: TalkBack announces the visible series, not the static XML label.
        swappableChart.contentDescription =
            label + ", " + NumberFormatUtil.percent(data[data.size - 1].value)
    }

    private fun addSpreadLimitLines(chart: LineChart) {
        // TICKET-57: tier-coloured through ChartPalette (colourblind-aware).
        ChartHelper.addLimitLine(chart, 0f, "Inversion", BenchmarkStyle.Tier.ALERT)
        ChartHelper.addLimitLine(chart, -0.5f, "Warning", BenchmarkStyle.Tier.ELEVATED)
        ChartHelper.addLimitLine(chart, -1.5f, "Deep", BenchmarkStyle.Tier.IMBALANCE)
        chart.axisLeft.setDrawLimitLinesBehindData(true)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }
}
