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
import com.economic.dashboard.databinding.FragmentBondsBinding
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

class BondsFragment : Fragment() {

    private lateinit var viewModel: EconomicViewModel
    private var binding: FragmentBondsBinding? = null
    private lateinit var skeleton: SkeletonController
    private lateinit var cardBaa: MaterialCardView
    private lateinit var cardHy: MaterialCardView
    // MOT-01: rolling figures
    private lateinit var tvBaaValue: RollingNumberView
    private lateinit var tvHyValue: RollingNumberView
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
            cardBaa, requireActivity(), "the BAA corporate bond spread", "BAA_SPREAD")
        AskAnalyst.wireCardLongPress(
            cardHy, requireActivity(), "the high-yield bond spread", "HY_SPREAD")
        tvBaaValue = b.tvBaaValue; tvHyValue = b.tvHyValue
        statusBaa = b.statusBaa.root; statusHy = b.statusHy.root
        swappableChart = b.swappableChart; tvChartTitle = b.tvChartTitle
        AskAnalyst.attachChartExplain(
            swappableChart, requireActivity(), "corporate bond spreads (BAA and high-yield) over time")

        ChartHelper.stylePanelChart(swappableChart); ChartHelper.declutterPanel(swappableChart); ChartHelper.attachCrosshair(swappableChart); addAllBenchmarks(swappableChart) // TICKET-23

        // TICKET-11: inline range switcher redraws the active bond-spread series.
        val tfSel: SegmentedView? = view.findViewById(R.id.timeframeSelector)
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

    // Same tick filters the active series twice (card + chart); cache per
    // source list, identity-keyed — see EmploymentFragment.rowsFor.
    private var filterSource: List<EconomicDataPoint>? = null
    private val filterCache = HashMap<String, List<EconomicDataPoint>>()

    private fun rowsFor(data: List<EconomicDataPoint>, series: String): List<EconomicDataPoint> {
        if (filterSource !== data) { filterSource = data; filterCache.clear() }
        return filterCache.getOrPut(series) { EconomicViewModel.filterBySeries(data, series) }
    }

    private fun updateBaaCard(data: List<EconomicDataPoint>) {
        val rows = rowsFor(data, "BAA Corporate Spread")
        if (rows.isEmpty()) return
        val latest = rows[rows.size - 1].value
        tvBaaValue.setValue(NumberFormatUtil.percent(latest)) // TICKET-19, MOT-01
        val result = Benchmarks.bondSpread(latest, 2.5, 3.5)
        BenchmarkStyle.applyBadge(statusBaa, requireContext(), result)
    }

    private fun updateHyCard(data: List<EconomicDataPoint>) {
        val rows = rowsFor(data, "High Yield Spread")
        if (rows.isEmpty()) return
        val latest = rows[rows.size - 1].value
        tvHyValue.setValue(NumberFormatUtil.percent(latest)) // TICKET-19, MOT-01
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
        // R3-TICKET-01 / U10: the active card swaps its stroke through BenchmarkStyle.
        BenchmarkStyle.setCardActive(cardBaa, "baa" == which)
        BenchmarkStyle.setCardActive(cardHy, "hy" == which)
        tvChartTitle.text = if ("hy" == which) "High Yield Spread" else "BAA Corporate Spread"
    }

    private fun buildSwappableChart() {
        if (!::swappableChart.isInitialized) return
        val data: List<EconomicDataPoint>?
        val seriesName: String
        val lineColor: Int = ChartPalette.primary(requireContext()) // TICKET-57
        if ("hy" == activeCard) { data = currentHyData; seriesName = "High Yield Spread" }
        else { data = currentBaaData; seriesName = "BAA Corporate Spread" }
        if (data == null) return
        var rows = rowsFor(data, seriesName)
        if (rows.isEmpty()) return
        rows = EconomicViewModel.filterByTimeframe(requireContext(), rows, "bonds")
        val entries = ArrayList<Entry>()
        val dateLabels = ArrayList<String>()
        for (i in rows.indices) {
            entries.add(Entry(i.toFloat(), rows[i].value.toFloat()))
            dateLabels.add(rows[i].date)
        }
        AxisDateFormat.attach(swappableChart, requireContext(), "bonds", dateLabels) // TICKET-57
        swappableChart.axisLeft.setValueFormatter(object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String = String.format(Locale.US, "%.2f%%", value)
        })
        val ds = LineDataSet(entries, seriesName)
        ChartHelper.styleLineSeries(ds, lineColor) // IOS-07
        ChartHelper.setLineData(swappableChart, LineData(ds), 500)
        val latest = rows[rows.size - 1].value
        ChartHelper.showLatest(swappableChart, // unified charts: same grade as the card
            if ("hy" == activeCard) Benchmarks.bondSpread(latest, 5.0, 8.0) else Benchmarks.bondSpread(latest, 2.5, 3.5),
            NumberFormatUtil.percent(latest))
        // P6 TICKET-37: TalkBack announces the visible series, not the static XML label.
        swappableChart.contentDescription =
            seriesName + " trend, " + NumberFormatUtil.percent(rows[rows.size - 1].value)
    }

    private fun addAllBenchmarks(chart: LineChart) {
        ChartHelper.addLimitLine(chart, 1.5f, "BAA Tight", BenchmarkStyle.Tier.GOOD); ChartHelper.addNormalLimitLine(chart, 2.5f, "BAA Normal")
        ChartHelper.addLimitLine(chart, 3.5f, "BAA Elevated", BenchmarkStyle.Tier.ELEVATED); ChartHelper.addLimitLine(chart, 3f, "HY Tight", BenchmarkStyle.Tier.GOOD)
        ChartHelper.addNormalLimitLine(chart, 5f, "HY Normal"); ChartHelper.addLimitLine(chart, 8f, "HY Elevated", BenchmarkStyle.Tier.ELEVATED)
        chart.axisLeft.setDrawLimitLinesBehindData(true)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }
}
