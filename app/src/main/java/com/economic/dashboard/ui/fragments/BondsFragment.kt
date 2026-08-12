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
import com.economic.dashboard.databinding.FragmentBondsBinding
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

class BondsFragment : Fragment() {

    private lateinit var viewModel: EconomicViewModel
    private var binding: FragmentBondsBinding? = null
    private lateinit var skeleton: SkeletonController
    private lateinit var cardBaa: CardView
    private lateinit var cardHy: CardView
    private lateinit var tvBaaValue: TextView
    private lateinit var tvHyValue: TextView
    /** Roots of the included view_status_badge rows (R2-TICKET-03). */
    private lateinit var statusBaa: View
    private lateinit var statusHy: View
    private lateinit var swappableChart: LineChart
    private lateinit var tvChartTitle: TextView
    private var activeCard = "baa"
    private var currentBaaData: List<EconomicDataPoint>? = null
    private var currentHyData: List<EconomicDataPoint>? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val b = FragmentBondsBinding.inflate(inflater, container, false)
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
        RetryChip.wire(this, view, viewModel,
            EconomicViewModel.KEY_BAA, EconomicViewModel.KEY_HY)

        cardBaa = b.cardBaa; cardHy = b.cardHy
        AskAnalyst.wireCardLongPress(
            cardBaa, requireActivity(), "the BAA corporate bond spread")
        AskAnalyst.wireCardLongPress(
            cardHy, requireActivity(), "the high-yield bond spread")
        tvBaaValue = b.tvBaaValue; tvHyValue = b.tvHyValue
        statusBaa = b.statusBaa.root; statusHy = b.statusHy.root
        swappableChart = b.swappableChart; tvChartTitle = b.tvChartTitle
        AskAnalyst.attachChartExplain(
            swappableChart, requireActivity(), "corporate bond spreads (BAA and high-yield) over time")

        styleChart(swappableChart); ChartHelper.declutterDark(swappableChart); ChartHelper.attachCrosshair(swappableChart); addAllBenchmarks(swappableChart) // TICKET-23

        // TICKET-11: inline range switcher redraws the active bond-spread series.
        val tfSel: LinearLayout? = view.findViewById(R.id.timeframeSelector)
        TimeframeSelector.attach(tfSel, "bonds") { buildSwappableChart() }

        // P6 TICKET-35: tap inactive = swap chart (existing), tap active = metric popup.
        cardBaa.setOnClickListener { onCardTap("baa") }
        cardHy.setOnClickListener { onCardTap("hy") }
        PressFeedback.attach(cardBaa)
        PressFeedback.attach(cardHy)
        setActiveCard("baa")

        viewModel.getBaaSpreadData().observe(viewLifecycleOwner) { data ->
            if (data != null) {
                currentBaaData = data; updateBaaCard(data); buildSwappableChart()
                if (::skeleton.isInitialized) skeleton.reveal()
            }
        }
        viewModel.getHySpreadData().observe(viewLifecycleOwner) { data ->
            if (data != null) {
                currentHyData = data; updateHyCard(data); buildSwappableChart()
                if (::skeleton.isInitialized) skeleton.reveal()
            }
        }
    }

    private fun updateBaaCard(data: List<EconomicDataPoint>) {
        val rows = EconomicViewModel.filterBySeries(data, "BAA Corporate Spread")
        if (rows.isEmpty()) return
        val latest = rows[rows.size - 1].value
        tvBaaValue.text = NumberFormatUtil.percent(latest) // TICKET-19
        val result = Benchmarks.bondSpread(latest, 2.5, 3.5)
        BenchmarkStyle.applyBadge(statusBaa, requireContext(), result)
    }

    private fun updateHyCard(data: List<EconomicDataPoint>) {
        val rows = EconomicViewModel.filterBySeries(data, "High Yield Spread")
        if (rows.isEmpty()) return
        val latest = rows[rows.size - 1].value
        tvHyValue.text = NumberFormatUtil.percent(latest) // TICKET-19
        val result = Benchmarks.bondSpread(latest, 5.0, 8.0)
        BenchmarkStyle.applyBadge(statusHy, requireContext(), result)
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
        // R3-TICKET-01: border geometry lives in BenchmarkStyle so it tracks the card radius token.
        cardBaa.foreground = if ("baa" == which) BenchmarkStyle.activeCardBorder(
            requireContext(), R.dimen.radius_card_kpi) else null
        cardHy.foreground = if ("hy" == which) BenchmarkStyle.activeCardBorder(
            requireContext(), R.dimen.radius_card_kpi) else null
        tvChartTitle.text = if ("hy" == which) "High Yield Spread" else "BAA Corporate Spread"
    }

    private fun buildSwappableChart() {
        if (!::swappableChart.isInitialized) return
        val data: List<EconomicDataPoint>?
        val seriesName: String
        val lineColor: String
        if ("hy" == activeCard) { data = currentHyData; seriesName = "High Yield Spread"; lineColor = "#F57C00" }
        else { data = currentBaaData; seriesName = "BAA Corporate Spread"; lineColor = "#1976D2" }
        if (data == null) return
        var rows = EconomicViewModel.filterBySeries(data, seriesName)
        if (rows.isEmpty()) return
        rows = EconomicViewModel.filterByTimeframe(requireContext(), rows, "bonds")
        val entries = ArrayList<Entry>()
        val dateLabels = ArrayList<String>()
        for (i in rows.indices) {
            entries.add(Entry(i.toFloat(), rows[i].value.toFloat()))
            val d = rows[i].date
            dateLabels.add(if (d.length >= 10) d.substring(5, 7) + "/" + d.substring(8, 10) + "/" + d.substring(2, 4) else d)
        }
        swappableChart.xAxis.setValueFormatter(object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                val idx = value.toInt()
                return if ((idx % 20 == 0 || idx == dateLabels.size - 1) && idx >= 0 && idx < dateLabels.size) dateLabels[idx] else ""
            }
        })
        swappableChart.axisLeft.setValueFormatter(object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String = String.format(Locale.US, "%.2f%%", value)
        })
        val ds = LineDataSet(entries, seriesName)
        ChartHelper.styleLineSeries(ds, Color.parseColor(lineColor)) // IOS-07
        swappableChart.setData(LineData(ds)); swappableChart.animateX(500); swappableChart.invalidate()
        // P6 TICKET-37: TalkBack announces the visible series, not the static XML label.
        swappableChart.contentDescription =
            seriesName + " trend, " + NumberFormatUtil.percent(rows[rows.size - 1].value)
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

    private fun addAllBenchmarks(chart: LineChart) {
        addLine(chart, 1.5f, "BAA Tight", "#6FA97A"); addLine(chart, 2.5f, "BAA Normal", "#5B8DB8")
        addLine(chart, 3.5f, "BAA Elevated", "#D98E4F"); addLine(chart, 3f, "HY Tight", "#6FA97A")
        addLine(chart, 5f, "HY Normal", "#5B8DB8"); addLine(chart, 8f, "HY Elevated", "#D98E4F")
        chart.axisLeft.setDrawLimitLinesBehindData(true)
    }

    private fun addLine(chart: LineChart, value: Float, label: String, color: String) {
        val l = LimitLine(value, label)
        // Explicit setters on MPAndroidChart throughout (migration lesson 12) —
        // the library cannot be built against here, so accessor pairs are never
        // assumed. Property syntax is used only for getter-only reads like
        // chart.xAxis / chart.axisLeft.
        l.setLineColor(Color.parseColor(color)); l.setLineWidth(1f)
        l.setTextColor(Color.parseColor(color)); l.setTextSize(9f); l.enableDashedLine(8f, 4f, 0f)
        chart.axisLeft.addLimitLine(l)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }
}
