package com.economic.dashboard.ui.fragments

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

import androidx.cardview.widget.CardView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider

import com.economic.dashboard.R
import com.economic.dashboard.analyst.AskAnalyst
import com.economic.dashboard.databinding.FragmentInflationBinding
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
import com.economic.dashboard.utils.SettingsManager
import com.economic.dashboard.utils.TimeframeSelector
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet

import java.util.Locale

class InflationFragment : Fragment() {

    private var binding: FragmentInflationBinding? = null
    private lateinit var skeleton: SkeletonController

    private lateinit var viewModel: EconomicViewModel

    private lateinit var cardPceStatus: CardView
    private lateinit var tvPceValue: TextView
    private lateinit var tvPcePercentile: TextView

    private lateinit var cardCpiYoY: CardView
    private lateinit var tvCpiYoYValue: TextView
    private lateinit var tvCpiPercentile: TextView

    private lateinit var cardWageYoY: CardView
    private lateinit var tvWageYoYValue: TextView

    /** Roots of the three included view_status_badge rows (R2-TICKET-03). */
    private lateinit var statusPce: View
    private lateinit var statusCpi: View
    private lateinit var statusWage: View

    private lateinit var chartPceCpi: LineChart
    private lateinit var chartComparison: LineChart

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val b = FragmentInflationBinding.inflate(inflater, container, false)
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
        RetryChip.wire(this, view, viewModel,
            EconomicViewModel.CACHE_CPI, EconomicViewModel.KEY_PCE,
            EconomicViewModel.KEY_WAGES)

        cardPceStatus = b.cardPceStatus
        AskAnalyst.wireCardLongPress(
            cardPceStatus, requireActivity(), "PCE inflation", "PCE")
        tvPceValue = b.tvPceValue
        tvPcePercentile = b.tvPcePercentile
        statusPce = b.statusPce.root

        cardCpiYoY = b.cardCpiYoY
        AskAnalyst.wireCardLongPress(
            cardCpiYoY, requireActivity(), "CPI inflation (YoY)", "CPI")
        tvCpiYoYValue = b.tvCpiYoYValue
        tvCpiPercentile = b.tvCpiPercentile
        statusCpi = b.statusCpi.root

        cardWageYoY = b.cardWageYoY
        AskAnalyst.wireCardLongPress(
            // The card's subject is wage growth; inflation is the comparison it
            // draws, and HistoricalContextBuilder already carries CPI.
            cardWageYoY, requireActivity(), "wage growth vs inflation", "WAGES")
        tvWageYoYValue = b.tvWageYoYValue
        statusWage = b.statusWage.root

        chartPceCpi = b.chartPceCpi
        AskAnalyst.attachChartExplain(
            chartPceCpi, requireActivity(), "PCE and CPI inflation trends")
        chartComparison = b.comparisonChart
        AskAnalyst.attachChartExplain(
            chartComparison, requireActivity(), "a comparison of inflation measures")

        ChartHelper.styleNavyChart(chartPceCpi)
        ChartHelper.styleNavyChart(chartComparison)
        // TICKET-07/08: thin gridlines + label each line at its right end
        // instead of a detached legend.
        ChartHelper.declutterDark(chartPceCpi)
        ChartHelper.declutterDark(chartComparison)
        ChartHelper.useDirectLabels(chartPceCpi)
        ChartHelper.useDirectLabels(chartComparison)
        ChartHelper.attachCrosshair(chartPceCpi) // TICKET-23
        ChartHelper.attachCrosshair(chartComparison)

        // TICKET-11: one inline range switcher drives both Inflation charts.
        val tfSel: SegmentedView? = view.findViewById(R.id.timeframeSelector)
        TimeframeSelector.attach(tfSel, "inflation") {
            buildPceCpiChart(viewModel.getPceData().getValue(), viewModel.getCpiData().getValue())
            tryBuildComparisonChart()
        }

        chartPceCpi.axisLeft.setValueFormatter(object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String = String.format(Locale.US, "%.1f%%", value)
        })
        chartComparison.axisLeft.setValueFormatter(object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String = String.format(Locale.US, "%.0f", value)
        })

        addYoYLimitLines(chartPceCpi)

        cardCpiYoY.setOnClickListener {
            val ctx = context ?: return@setOnClickListener
            MetricPopupController.show(ctx, viewModel, "cpi")
        }
        cardWageYoY.setOnClickListener {
            val ctx = context ?: return@setOnClickListener
            MetricPopupController.show(ctx, viewModel, "wages")
        }

        viewModel.getPceData().observe(viewLifecycleOwner) { pce ->
            if (pce != null) {
                updatePceCard(pce)
                buildPceCpiChart(pce, viewModel.getCpiData().getValue())
                tryBuildComparisonChart()
                if (::skeleton.isInitialized) skeleton.reveal()
            }
        }
        viewModel.getCpiData().observe(viewLifecycleOwner) { cpi ->
            if (cpi != null) {
                updateCpiCard(cpi)
                buildPceCpiChart(viewModel.getPceData().getValue(), cpi)
                tryBuildComparisonChart()
                tryUpdateWageCard()
                if (::skeleton.isInitialized) skeleton.reveal()
            }
        }
        viewModel.getWageData().observe(viewLifecycleOwner) { wages ->
            if (wages != null) { tryUpdateWageCard(); tryBuildComparisonChart() }
        }
    }

    private fun updatePceCard(pceData: List<EconomicDataPoint>) {
        val rows = EconomicViewModel.filterBySeries(pceData, "PCE Price Index")
        if (rows.size < 13) return
        val yoy = SeriesMath.yoyPercentSeries(rows).last().value
        tvPceValue.text = NumberFormatUtil.percent(yoy) // TICKET-19

        val coreRows = EconomicViewModel.filterBySeries(pceData, "Core PCE Price Index")
        var coreLabel = ""
        if (coreRows.size >= 13) {
            val coreYoy = SeriesMath.yoyPercentSeries(coreRows).last().value
            coreLabel = "Core: " + NumberFormatUtil.percent(coreYoy) // TICKET-19
        }
        tvPcePercentile.text = coreLabel

        // PCE aligned to CPI wording + bands so the two inflation gauges match (TICKET-04)
        val result = Benchmarks.pceYoY(yoy)
        BenchmarkStyle.applyBadge(statusPce, requireContext(), result)
    }

    // "CPI-U All Items" gets read by four functions off the same observer tick;
    // filterBySeries scans+sorts each call, so cache one result per source list
    // (identity-keyed — a new LiveData emission is a new list reference).
    private var cachedCpiSource: List<EconomicDataPoint>? = null
    private var cachedCpiRows: List<EconomicDataPoint> = ArrayList()

    private fun cpiRowsFor(cpiData: List<EconomicDataPoint>): List<EconomicDataPoint> {
        if (cachedCpiSource !== cpiData) {
            cachedCpiSource = cpiData
            cachedCpiRows = EconomicViewModel.filterBySeries(cpiData, "CPI-U All Items")
        }
        return cachedCpiRows
    }

    private fun updateCpiCard(cpiData: List<EconomicDataPoint>) {
        val rows = cpiRowsFor(cpiData)
        if (rows.size < 13) return
        val latest = rows[rows.size - 1].value
        val yoy = SeriesMath.yoyPercentSeries(rows).last().value
        tvCpiYoYValue.text = NumberFormatUtil.percent(yoy) // TICKET-19

        val result = Benchmarks.cpiYoY(yoy)
        // R2-TICKET-02: this screen used to setTextColor on two of its three badges
        // and not the third, so the layout and the shipped pixel disagreed per card.
        // @style/Badge.Status now owns the colour for all three.
        BenchmarkStyle.applyBadge(statusCpi, requireContext(), result)

        val pct = EconomicViewModel.calculatePercentile(cpiData, "CPI-U All Items", latest)
        tvCpiPercentile.text = EconomicViewModel.formatPercentile(pct)
    }

    private fun tryUpdateWageCard() {
        val wageData = viewModel.getWageData().getValue()
        val cpiData = viewModel.getCpiData().getValue()
        if (wageData == null || cpiData == null) {
            // Diagnostics for the blank Real Wage Growth card: this card is the
            // only one on the screen needing two series at once, so it goes
            // blank for reasons its neighbours never show.
            Log.w(DIAG, "skipped — wageData=${wageData?.size ?: "null"}, cpiData=${cpiData?.size ?: "null"}")
            return
        }

        val wRows = EconomicViewModel.filterBySeries(wageData, "Average Hourly Earnings - Private")
        val cRows = cpiRowsFor(cpiData)
        if (wRows.size < 13 || cRows.size < 13) {
            Log.w(DIAG, "skipped — hourly=${wRows.size}, cpi=${cRows.size} (need 13 each); " +
                "series in wage payload=${wageData.map { it.series }.distinct()}")
            return
        }

        // Shared with MetricPopupController's "wages" popup — one formula (TICKET-03).
        // (WagesFragment, the formula's other historical caller, was removed as an
        // orphaned screen in P6 TICKET-40.)
        val spreadRows = SeriesMath.realWageGrowthSpread(wRows, cRows)
        if (spreadRows.isEmpty()) return
        val spread = spreadRows[spreadRows.size - 1].value

        tvWageYoYValue.text = NumberFormatUtil.percent(spread) // TICKET-19
        Log.i(DIAG, "populated — spread=$spread")

        val result = Benchmarks.realWageGrowth(spread)
        BenchmarkStyle.applyBadge(statusWage, requireContext(), result)
    }

    private fun buildPceCpiChart(pceData: List<EconomicDataPoint>?, cpiData: List<EconomicDataPoint>?) {
        if (pceData == null || cpiData == null || !::chartPceCpi.isInitialized) return
        val pceRows = EconomicViewModel.filterBySeries(pceData, "PCE Price Index")
        val cpiRows = cpiRowsFor(cpiData)
        if (pceRows.size < 13 || cpiRows.size < 13) return

        val pceEntries = ArrayList<Entry>()
        val cpiEntries = ArrayList<Entry>()
        val dates = ArrayList<String>()

        // Number of YoY months shown follows the standardized chart time range.
        val months = SettingsManager.getChartTimeframeMonths(requireContext(), "inflation")
        val pcStart = Math.max(12, pceRows.size - months)
        for (i in pcStart until pceRows.size) {
            val yoy = ((pceRows[i].value - pceRows[i - 12].value) / pceRows[i - 12].value) * 100.0
            pceEntries.add(Entry(pceEntries.size.toFloat(), yoy.toFloat()))
            dates.add(pceRows[i].date)
        }

        val ciStart = Math.max(12, cpiRows.size - months)
        var i = ciStart
        while (i < cpiRows.size && cpiEntries.size < pceEntries.size) {
            val cYoy = ((cpiRows[i].value - cpiRows[i - 12].value) / cpiRows[i - 12].value) * 100.0
            cpiEntries.add(Entry(cpiEntries.size.toFloat(), cYoy.toFloat()))
            i++
        }
        while (pceEntries.size > cpiEntries.size) pceEntries.removeAt(pceEntries.size - 1)
        while (dates.size > pceEntries.size) dates.removeAt(dates.size - 1)

        AxisDateFormat.attach(chartPceCpi, requireContext(), "inflation", dates) // TICKET-57

        ChartHelper.setLineData(chartPceCpi, LineData(
            makeLineDataSet(pceEntries, "PCE", ChartPalette.blue(requireContext())),
            makeLineDataSet(cpiEntries, "CPI-U", ChartPalette.gold(requireContext()))))
    }

    private fun tryBuildComparisonChart() {
        val cpiData = viewModel.getCpiData().getValue()
        val wageData = viewModel.getWageData().getValue()
        val pceData = viewModel.getPceData().getValue()
        if (cpiData == null || wageData == null || !::chartComparison.isInitialized) return

        var cpiRows = cpiRowsFor(cpiData)
        val wageRows = EconomicViewModel.filterBySeries(wageData, "Average Hourly Earnings - Private")
        val pceRows: List<EconomicDataPoint> = if (pceData != null)
            EconomicViewModel.filterBySeries(pceData, "PCE Price Index")
        else
            ArrayList()
        if (cpiRows.isEmpty() || wageRows.isEmpty()) return

        // Window the driving CPI series to the standardized chart time range;
        // wage/PCE are matched by date, so trimming CPI drives the x-axis.
        cpiRows = EconomicViewModel.filterByTimeframe(requireContext(), cpiRows, "inflation")
        if (cpiRows.isEmpty()) return
        val cpiBase = cpiRows[0].value
        var wageBase = -1.0 // set at the first wage point in the window
        var pceBase = -1.0 // set at the first PCE point that lines up with a plotted CPI date
        val cpiEntries = ArrayList<Entry>()
        val wageEntries = ArrayList<Entry>()
        val pceEntries = ArrayList<Entry>()
        val dates = ArrayList<String>()

        for (i in cpiRows.indices) {
            val date = cpiRows[i].date
            cpiEntries.add(Entry(i.toFloat(), ((cpiRows[i].value / cpiBase) * 100.0).toFloat()))
            dates.add(date)
            for (w in wageRows) {
                if (w.date == date) {
                    if (wageBase < 0) wageBase = w.value
                    wageEntries.add(Entry(i.toFloat(), ((w.value / wageBase) * 100.0).toFloat())); break
                }
            }
            for (p in pceRows) {
                if (p.date == date) {
                    if (pceBase < 0) pceBase = p.value
                    pceEntries.add(Entry(i.toFloat(), ((p.value / pceBase) * 100.0).toFloat())); break
                }
            }
        }

        AxisDateFormat.attach(chartComparison, requireContext(), "inflation", dates) // TICKET-57

        val sets = ArrayList<ILineDataSet>()
        sets.add(makeLineDataSet(cpiEntries, "CPI-U", ChartPalette.gold(requireContext())))
        if (!pceEntries.isEmpty()) sets.add(makeLineDataSet(pceEntries, "PCE", ChartPalette.blue(requireContext())))
        sets.add(makeLineDataSet(wageEntries, "Average Wages", ChartPalette.purple(requireContext())))
        ChartHelper.setLineData(chartComparison, LineData(sets))
    }

    private fun makeLineDataSet(entries: List<Entry>, label: String, color: Int): LineDataSet {
        val ds = LineDataSet(entries, label)
        ChartHelper.styleLineSeries(ds, color) // IOS-07 / TICKET-57
        // TICKET-08: draw the series name at the line's right end in its colour.
        ChartHelper.labelLineEnd(ds, label)
        return ds
    }

    private fun addYoYLimitLines(chart: LineChart) {
        // TICKET-57: tier-coloured through ChartPalette (colourblind-aware).
        ChartHelper.addLimitLine(chart, 2.0f, "Fed Target 2%", BenchmarkStyle.Tier.GOOD)
        ChartHelper.addLimitLine(chart, 3.5f, "Elevated 3.5%", BenchmarkStyle.Tier.ELEVATED)
        chart.axisLeft.setDrawLimitLinesBehindData(true)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }

    companion object {
        /** Logcat tag for the Real Wage Growth card — filter with `-s RealWageDiag`. */
        private const val DIAG = "RealWageDiag"
    }
}
