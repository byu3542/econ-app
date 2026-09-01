package com.economic.dashboard.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

import androidx.cardview.widget.CardView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider

import com.economic.dashboard.R
import com.economic.dashboard.analyst.AskAnalyst
import com.economic.dashboard.databinding.FragmentStocksBinding
import com.economic.dashboard.models.EconomicDataPoint
import com.economic.dashboard.ui.BenchmarkStyle
import com.economic.dashboard.ui.Benchmarks
import com.economic.dashboard.ui.EconomicViewModel
import com.economic.dashboard.ui.MetricPopupController
import com.economic.dashboard.ui.views.HeroValue
import com.economic.dashboard.ui.views.PressFeedback
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

class StocksFragment : Fragment() {

    private lateinit var viewModel: EconomicViewModel
    private var binding: FragmentStocksBinding? = null
    private lateinit var skeleton: SkeletonController
    private lateinit var cardSp500: CardView
    private lateinit var cardNasdaq: CardView
    private lateinit var cardVix: CardView
    private lateinit var tvSp500Value: TextView
    private lateinit var tvNasdaqValue: TextView
    private lateinit var tvVixValue: TextView
    /** Roots of the included view_status_badge rows (R2-TICKET-03). */
    private lateinit var statusSp500: View
    private lateinit var statusNasdaq: View
    private lateinit var statusVix: View
    private lateinit var swappableChart: LineChart
    private lateinit var tvChartTitle: TextView
    private var activeCard = "sp500"
    private var currentSp500Data: List<EconomicDataPoint>? = null
    private var currentNasdaqData: List<EconomicDataPoint>? = null
    private var currentVixData: List<EconomicDataPoint>? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val b = FragmentStocksBinding.inflate(inflater, container, false)
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
            EconomicViewModel.KEY_SP500, EconomicViewModel.KEY_NASDAQ,
            EconomicViewModel.CACHE_VIX)

        cardSp500 = b.cardSp500
        AskAnalyst.wireCardLongPress(
            cardSp500, requireActivity(), "the S&P 500 index", "SP500")
        cardNasdaq = b.cardNasdaq
        AskAnalyst.wireCardLongPress(
            cardNasdaq, requireActivity(), "the Nasdaq index", "NASDAQ")
        cardVix = b.cardVix
        AskAnalyst.wireCardLongPress(
            cardVix, requireActivity(), "the VIX volatility index", "VIX")
        tvSp500Value = b.tvSp500Value
        tvNasdaqValue = b.tvNasdaqValue
        tvVixValue = b.tvVixValue
        statusSp500 = b.statusSp500.root
        statusNasdaq = b.statusNasdaq.root
        statusVix = b.statusVix.root
        swappableChart = b.swappableChart
        AskAnalyst.attachChartExplain(
            swappableChart, requireActivity(), "stock market indices (S&P 500, Nasdaq, VIX)")
        tvChartTitle = b.tvChartTitle

        ChartHelper.styleNavyChart(swappableChart, noDataText = "Loading…", xAxisLineToGrid = true)
        ChartHelper.declutterDark(swappableChart)
        ChartHelper.attachCrosshair(swappableChart) // TICKET-23
        addVixBenchmarks(swappableChart)

        // TICKET-11: inline range switcher redraws the active index/series.
        val tfSel: SegmentedView? = view.findViewById(R.id.timeframeSelector)
        TimeframeSelector.attach(tfSel, "stocks") { buildSwappableChart() }

        // P6 TICKET-35: tap an inactive card to swap the chart (existing behavior);
        // tap the already-active card to open its metric popup + Add-alert.
        cardSp500.setOnClickListener { onCardTap("sp500") }
        cardNasdaq.setOnClickListener { onCardTap("nasdaq") }
        cardVix.setOnClickListener { onCardTap("vix") }
        PressFeedback.attach(cardSp500)
        PressFeedback.attach(cardNasdaq)
        PressFeedback.attach(cardVix)

        setActiveCard("sp500")

        viewModel.getSp500Data().observe(viewLifecycleOwner) { data ->
            if (data != null) {
                currentSp500Data = data; updateSp500Card(data); buildSwappableChart()
                if (::skeleton.isInitialized) skeleton.reveal()
            }
        }
        viewModel.getNasdaqData().observe(viewLifecycleOwner) { data ->
            if (data != null) {
                currentNasdaqData = data; updateNasdaqCard(data); buildSwappableChart()
                if (::skeleton.isInitialized) skeleton.reveal()
            }
        }
        viewModel.getVixData().observe(viewLifecycleOwner) { data ->
            if (data != null) {
                currentVixData = data; updateVixCard(data); buildSwappableChart()
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

    private fun updateSp500Card(data: List<EconomicDataPoint>) {
        val rows = rowsFor(data, "S&P 500 Index")
        if (rows.isEmpty()) return
        val latest = rows[rows.size - 1].value
        tvSp500Value.text = NumberFormatUtil.number(latest, 0) // TICKET-19
        val result = Benchmarks.stockLevel(latest, 3500.0, 4000.0)
        BenchmarkStyle.applyBadge(statusSp500, requireContext(), result)
    }

    private fun updateNasdaqCard(data: List<EconomicDataPoint>) {
        val rows = rowsFor(data, "Nasdaq Composite Index")
        if (rows.isEmpty()) return
        val latest = rows[rows.size - 1].value
        tvNasdaqValue.text = NumberFormatUtil.number(latest, 0) // TICKET-19
        val result = Benchmarks.stockLevel(latest, 10000.0, 12000.0)
        BenchmarkStyle.applyBadge(statusNasdaq, requireContext(), result)
    }

    private fun updateVixCard(data: List<EconomicDataPoint>) {
        val rows = rowsFor(data, "VIX Volatility Index")
        if (rows.isEmpty()) return
        val latest = rows[rows.size - 1].value
        tvVixValue.text = NumberFormatUtil.indexPoints(latest, 1) // TICKET-19
        val result = Benchmarks.vix(latest)
        BenchmarkStyle.applyBadge(statusVix, requireContext(), result)
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
        cardSp500.foreground = if ("sp500" == which) BenchmarkStyle.activeCardBorder(
            requireContext(), R.dimen.radius_card_kpi) else null
        cardNasdaq.foreground = if ("nasdaq" == which) BenchmarkStyle.activeCardBorder(
            requireContext(), R.dimen.radius_card_kpi) else null
        cardVix.foreground = if ("vix" == which) BenchmarkStyle.activeCardBorder(
            requireContext(), R.dimen.radius_card_kpi) else null
        // TICKET-58: the value size moves with the stroke — one hero per row.
        HeroValue.apply(tvSp500Value, "sp500" == which)
        HeroValue.apply(tvNasdaqValue, "nasdaq" == which)
        HeroValue.apply(tvVixValue, "vix" == which)
        when (which) {
            "nasdaq" -> tvChartTitle.text = "Nasdaq Composite Index"
            "vix" -> tvChartTitle.text = "VIX Volatility Index"
            else -> tvChartTitle.text = "S&P 500 Index"
        }
    }

    private fun buildSwappableChart() {
        if (!::swappableChart.isInitialized) return
        val data: List<EconomicDataPoint>?
        val seriesName: String
        // TICKET-57: every single-series panel draws in the palette primary; the
        // active card's gold stroke already says which series this is.
        val lineColor: Int = ChartPalette.primary(requireContext())
        val formatter: ValueFormatter
        when (activeCard) {
            "nasdaq" -> {
                data = currentNasdaqData; seriesName = "Nasdaq Composite Index"
                formatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float): String = String.format(Locale.US, "%.0f", value)
                }
            }
            "vix" -> {
                data = currentVixData; seriesName = "VIX Volatility Index"
                formatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float): String = String.format(Locale.US, "%.1f", value)
                }
            }
            else -> {
                data = currentSp500Data; seriesName = "S&P 500 Index"
                formatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float): String = String.format(Locale.US, "%.0f", value)
                }
            }
        }
        if (data == null) return
        var rows = rowsFor(data, seriesName)
        if (rows.isEmpty()) return
        rows = EconomicViewModel.filterByTimeframe(requireContext(), rows, "stocks")
        val entries = ArrayList<Entry>()
        val dateLabels = ArrayList<String>()
        for (i in rows.indices) {
            entries.add(Entry(i.toFloat(), rows[i].value.toFloat()))
            dateLabels.add(rows[i].date)
        }
        AxisDateFormat.attach(swappableChart, requireContext(), "stocks", dateLabels) // TICKET-57
        swappableChart.axisLeft.setValueFormatter(formatter)
        val ds = LineDataSet(entries, seriesName)
        ChartHelper.styleLineSeries(ds, lineColor) // IOS-07
        ChartHelper.setLineData(swappableChart, LineData(ds), 500)
        // P6 TICKET-37: TalkBack announces the visible series, not the static XML label.
        swappableChart.contentDescription =
            seriesName + " trend, " + NumberFormatUtil.number(rows[rows.size - 1].value, 0)
    }

    private fun addVixBenchmarks(chart: LineChart) {
        ChartHelper.addLimitLine(chart, 12f, "Low Vol", BenchmarkStyle.Tier.GOOD); ChartHelper.addNormalLimitLine(chart, 20f, "Normal")
        ChartHelper.addLimitLine(chart, 30f, "Elevated", BenchmarkStyle.Tier.ELEVATED); ChartHelper.addLimitLine(chart, 50f, "Extreme", BenchmarkStyle.Tier.ALERT)
        chart.axisLeft.setDrawLimitLinesBehindData(true)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }
}
