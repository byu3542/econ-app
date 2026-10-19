package com.economic.dashboard.ui.fragments

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

import com.google.android.material.card.MaterialCardView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider

import com.economic.dashboard.R
import com.economic.dashboard.analyst.AskAnalyst
import com.economic.dashboard.databinding.FragmentHousingBinding
import com.economic.dashboard.models.EconomicDataPoint
import com.economic.dashboard.ui.BenchmarkStyle
import com.economic.dashboard.ui.Benchmarks
import com.economic.dashboard.ui.EconomicViewModel
import com.economic.dashboard.ui.MetricPopupController
import com.economic.dashboard.ui.views.HeroValue
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
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter

import java.util.Collections
import java.util.Locale

/**
 * Housing tab — Housing Starts (HOUST), Existing Home Sales (EXHOSLUSM495S),
 * 30-Year Mortgage Rate, 10Y Treasury rate, mortgage-treasury spread, and MBS holdings chart.
 */
class HousingFragment : Fragment() {

    private var binding: FragmentHousingBinding? = null
    private lateinit var skeleton: SkeletonController

    private lateinit var viewModel: EconomicViewModel

    // Housing Starts badge
    private lateinit var tvStartsValue: TextView

    // Existing Home Sales badge
    private lateinit var tvSalesValue: TextView

    // Rate badge cards
    private lateinit var badgeMortgage: MaterialCardView
    private lateinit var badge10Y: MaterialCardView
    private lateinit var badgeSpread: MaterialCardView
    private lateinit var tvBadgeMortgageValue: TextView
    private lateinit var tvBadge10YValue: TextView
    private lateinit var tvBadgeSpreadValue: TextView

    /** Roots of the five included view_status_badge rows (R2-TICKET-03). */
    private lateinit var statusStarts: View
    private lateinit var statusSales: View
    private lateinit var statusMortgage: View
    private lateinit var status10Y: View
    private lateinit var statusHousingSpread: View

    // Swappable chart
    private lateinit var chartSwappable: LineChart
    private lateinit var tvSwappableTitle: TextView

    // MBS dual-axis chart
    private lateinit var chartMbsMortgage: LineChart

    // State
    private var activeBadge = "mortgage"
    private var currentMbsData: List<EconomicDataPoint>? = null
    private var currentTreasuryData: List<EconomicDataPoint>? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val b = FragmentHousingBinding.inflate(inflater, container, false)
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
            EconomicViewModel.CACHE_MBS, EconomicViewModel.CACHE_TREASURY,
            EconomicViewModel.KEY_HOUSING)

        // Housing Starts badge
        tvStartsValue = b.tvStartsValue
        statusStarts = b.statusStarts.root

        // Existing Home Sales badge
        tvSalesValue = b.tvSalesValue
        statusSales = b.statusSales.root

        // Rate badge cards
        badgeMortgage = b.badgeMortgage
        badge10Y = b.badge10Y
        badgeSpread = b.badgeSpread
        tvBadgeMortgageValue = b.tvBadgeMortgageValue
        tvBadge10YValue = b.tvBadge10YValue
        tvBadgeSpreadValue = b.tvBadgeSpreadValue
        statusMortgage = b.statusMortgage.root
        status10Y = b.status10Y.root
        statusHousingSpread = b.statusHousingSpread.root

        // Swappable chart + title
        chartSwappable = b.chartSwappable
        AskAnalyst.attachChartExplain(
            chartSwappable, requireActivity(), "housing market activity (housing starts and home sales)")
        tvSwappableTitle = b.tvSwappableTitle

        // MBS dual-axis chart
        chartMbsMortgage = b.chartMbsMortgage
        AskAnalyst.attachChartExplain(
            chartMbsMortgage, requireActivity(), "mortgage rates and MBS holdings")

        // Style swappable chart (dark theme)
        styleSwappableChart()
        ChartHelper.declutterPanel(chartSwappable)
        ChartHelper.attachCrosshair(chartSwappable) // TICKET-23

        // TICKET-11: one inline range switcher drives both Housing charts.
        val tfSel: SegmentedView? = view.findViewById(R.id.timeframeSelector)
        TimeframeSelector.attach(tfSel, "housing") {
            buildSwappableChart()
            currentMbsData?.let { buildMbsMortgageChart(it) }
        }

        // Badge tap listeners — P6 TICKET-35: tap inactive = swap chart (existing),
        // tap active = metric popup ("tenYear" shares the 10Y "yield10y" popup;
        // "spread" gets its own "mortgagespread" popup — it is the mortgage-10Y
        // spread, a different metric from the Overview's 10Y-3M curve spread).
        badgeMortgage.setOnClickListener { onBadgeTap("mortgage") }
        badge10Y.setOnClickListener { onBadgeTap("tenYear") }
        badgeSpread.setOnClickListener { onBadgeTap("spread") }

        // Set default active badge
        setActiveBadge("mortgage")

        // ── MBS Chart: full dark theme styling ──────────────────────────────
        // Pantrii port U10: on the surface card, chrome from ChartPalette.
        val mbsDarkBg = Color.TRANSPARENT
        val mbsSubtleGrid = ChartPalette.grid(requireContext())

        chartMbsMortgage.setBackgroundColor(mbsDarkBg)
        chartMbsMortgage.setDrawGridBackground(false)
        chartMbsMortgage.setDrawBorders(false)
        chartMbsMortgage.setTouchEnabled(true)
        chartMbsMortgage.setDragEnabled(true)
        chartMbsMortgage.setScaleEnabled(true)
        chartMbsMortgage.setPinchZoom(true)
        chartMbsMortgage.setDoubleTapToZoomEnabled(true)
        chartMbsMortgage.description.setEnabled(false)
        chartMbsMortgage.setNoDataText("Awaiting MBS Data...")
        chartMbsMortgage.setNoDataTextColor(ChartPalette.axisText(requireContext()))
        chartMbsMortgage.setExtraBottomOffset(30f)
        chartMbsMortgage.setExtraLeftOffset(10f)
        chartMbsMortgage.setExtraTopOffset(10f)
        chartMbsMortgage.setExtraRightOffset(10f)

        // X-axis
        val mbsXAxis = chartMbsMortgage.xAxis
        mbsXAxis.setPosition(XAxis.XAxisPosition.BOTTOM)
        mbsXAxis.setTextColor(ChartPalette.axisText(requireContext()))
        mbsXAxis.setTextSize(10f)
        mbsXAxis.setDrawGridLines(true)
        mbsXAxis.setGridColor(mbsSubtleGrid)
        mbsXAxis.setAxisLineColor(mbsSubtleGrid)
        mbsXAxis.setLabelRotationAngle(-45f)
        mbsXAxis.setGranularity(1f)
        mbsXAxis.setLabelCount(8, false)
        mbsXAxis.setAvoidFirstLastClipping(true)

        // Left Y-axis (MBS holdings in billions → display as trillions)
        val mbsLeftAxis = chartMbsMortgage.axisLeft
        mbsLeftAxis.setTextColor(ChartPalette.gold(requireContext())) // matches the Bank MBS line
        mbsLeftAxis.setTextSize(10f)
        mbsLeftAxis.setDrawGridLines(true)
        mbsLeftAxis.setGridColor(mbsSubtleGrid)
        mbsLeftAxis.setAxisLineColor(mbsSubtleGrid)
        mbsLeftAxis.setDrawZeroLine(false)
        mbsLeftAxis.setValueFormatter(object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                return String.format(Locale.US, "$%.1fT", value / 1000f)
            }
        })

        // Right Y-axis (mortgage rate %)
        val mbsRightAxis = chartMbsMortgage.axisRight
        mbsRightAxis.setEnabled(true)
        mbsRightAxis.setTextColor(ChartPalette.coral(requireContext())) // matches the mortgage line
        mbsRightAxis.setTextSize(10f)
        mbsRightAxis.setDrawGridLines(false)
        mbsRightAxis.setAxisLineColor(mbsSubtleGrid)
        mbsRightAxis.setValueFormatter(object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                return String.format(Locale.US, "%.1f%%", value)
            }
        })

        // Legend
        val mbsLegend = chartMbsMortgage.legend
        mbsLegend.setTextColor(ChartPalette.legendText(requireContext()))
        mbsLegend.setTextSize(11f)
        mbsLegend.setForm(Legend.LegendForm.LINE)
        mbsLegend.setFormSize(16f)
        mbsLegend.setFormLineWidth(2f)
        mbsLegend.setVerticalAlignment(Legend.LegendVerticalAlignment.BOTTOM)
        mbsLegend.setHorizontalAlignment(Legend.LegendHorizontalAlignment.CENTER)
        mbsLegend.setOrientation(Legend.LegendOrientation.HORIZONTAL)
        mbsLegend.setDrawInside(false)
        mbsLegend.setYOffset(5f)

        // Observe housing data (metric cards only)
        ChartHelper.declutterPanel(chartMbsMortgage)
        ChartHelper.attachCrosshair(chartMbsMortgage) // TICKET-23

        viewModel.getHousingData().observe(viewLifecycleOwner) { housing ->
            if (housing != null) {
                updateStartsCard(housing)
                updateSalesCard(housing)
                if (::skeleton.isInitialized) skeleton.reveal()
            }
        }

        // Observe MBS & mortgage data (badges + swappable chart + dual-axis chart)
        viewModel.getMbsMortgageData().observe(viewLifecycleOwner) { mbsData ->
            if (mbsData != null) {
                currentMbsData = mbsData
                // Spread series is pure function of the two cached fields —
                // compute once per tick, share between badge and chart.
                val spread = computeSpreadRows()
                updateBadges(spread)
                buildSwappableChart(spread)
                buildMbsMortgageChart(mbsData)
                if (::skeleton.isInitialized) skeleton.reveal()
            }
        }

        // Observe Treasury yield data (10Y T-Rate badge + Spread badge + swappable chart)
        viewModel.getTreasuryData().observe(viewLifecycleOwner) { treasury ->
            if (treasury != null) {
                currentTreasuryData = treasury
                val spread = computeSpreadRows()
                updateBadges(spread)
                buildSwappableChart(spread)
            }
        }
    }

    // ── Housing Starts Badge ──────────────────────────────────────────────────

    private fun updateStartsCard(housingData: List<EconomicDataPoint>) {
        val rows = EconomicViewModel.filterBySeries(housingData, "Housing Starts")
        if (rows.isEmpty()) return

        val latest = rows[rows.size - 1].value
        tvStartsValue.text = NumberFormatUtil.number(latest, 0) + "K" // TICKET-19

        val result = Benchmarks.housingStarts(latest)
        BenchmarkStyle.applyBadge(statusStarts, requireContext(), result)
    }

    // ── Existing Home Sales Badge ─────────────────────────────────────────────

    private fun updateSalesCard(housingData: List<EconomicDataPoint>) {
        val rows = EconomicViewModel.filterBySeries(housingData, "Existing Home Sales")
        if (rows.isEmpty()) return

        val latest = rows[rows.size - 1].value

        // FIX-2026-07-24: EXHOSLUSM495S is published in "Number of Units" (SAAR) --
        // RAW units, e.g. 4090000, not thousands as the old comment claimed. The
        // single /1_000 therefore rendered "4,090.00M" instead of "4.09M", and fed
        // 4,090,000 to a benchmark whose thresholds are 3000/4000, pinning the badge
        // to HEALTHY for any reading at all. Normalise once, then use it twice.
        val thousands = latest / 1_000.0 // 4090 -- Benchmarks' unit
        tvSalesValue.text = NumberFormatUtil.number(thousands / 1_000.0, 2) + "M" // TICKET-19

        val result = Benchmarks.existingHomeSales(thousands)
        BenchmarkStyle.applyBadge(statusSales, requireContext(), result)
    }

    // ── Rate Badges ── (10Y T-Rate + Spread pull from the Yields-tab treasury data)

    private fun updateBadges(spreadRowsPrecomputed: List<EconomicDataPoint>) {
        // 30-Yr Mortgage Rate
        val mbs = currentMbsData
        if (mbs != null) {
            val mortgageRows = EconomicViewModel.filterBySeries(mbs, "30-Yr Mortgage Rate")
            if (!mortgageRows.isEmpty()) {
                val v = mortgageRows[mortgageRows.size - 1].value
                tvBadgeMortgageValue.text = NumberFormatUtil.percent(v) // TICKET-19
                val result = Benchmarks.rateBadge(v, 4.0, 5.5, 7.0)
                BenchmarkStyle.applyBadge(statusMortgage, requireContext(), result)
            }
        }

        // 10Y Treasury Rate (same data source as the Markets / Yields tab)
        val treasury = currentTreasuryData
        if (treasury != null) {
            val tenYearRows = EconomicViewModel.filterBySeries(treasury, "10 Year")
            if (!tenYearRows.isEmpty()) {
                val v = tenYearRows[tenYearRows.size - 1].value
                tvBadge10YValue.text = NumberFormatUtil.percent(v) // TICKET-19
                val result = Benchmarks.rateBadge(v, 3.0, 4.0, 5.0)
                BenchmarkStyle.applyBadge(status10Y, requireContext(), result)
            }
        }

        // Spread: 30-Yr Mortgage Rate minus 10Y Treasury (long-run norm ~1.7%)
        val spreadRows = spreadRowsPrecomputed
        if (!spreadRows.isEmpty()) {
            val v = spreadRows[spreadRows.size - 1].value
            tvBadgeSpreadValue.text = NumberFormatUtil.percent(v) // TICKET-19
            val result = Benchmarks.mortgageSpread(v)
            BenchmarkStyle.applyBadge(statusHousingSpread, requireContext(), result)
        }
    }

    /**
     * Pairs each weekly 30-Yr mortgage print with the most recent daily 10Y
     * Treasury close on or before that date and returns the spread series.
     * Treasury data covers the current year, so the spread series is YTD only.
     */
    private fun computeSpreadRows(): List<EconomicDataPoint> {
        val mbs = currentMbsData
        val treasury = currentTreasuryData
        if (mbs == null || treasury == null) return ArrayList()
        // P6 TICKET-35: formula lives in SeriesMath so the "mortgagespread" popup
        // and this badge can never drift apart.
        return SeriesMath.mortgageMinusTenYearSpread(
            EconomicViewModel.filterBySeries(mbs, "30-Yr Mortgage Rate"),
            EconomicViewModel.filterBySeries(treasury, "10 Year"))
    }

    // P6 TICKET-35: badge id → MetricPopupRegistry id.
    private fun onBadgeTap(which: String) {
        if (activeBadge == which) {
            val popupId = when (which) {
                "tenYear" -> "yield10y"
                "spread" -> "mortgagespread"
                else -> "mortgage"
            }
            MetricPopupController.show(requireContext(), viewModel, popupId)
        } else {
            activeBadge = which; setActiveBadge(which); buildSwappableChart()
        }
    }

    private fun setActiveBadge(which: String) {
        // R3-TICKET-01 / U10: the active card swaps its stroke through BenchmarkStyle.
        BenchmarkStyle.setCardActive(badgeMortgage, "mortgage" == which)
        BenchmarkStyle.setCardActive(badge10Y, "tenYear" == which)
        BenchmarkStyle.setCardActive(badgeSpread, "spread" == which)
        // TICKET-58: the value size moves with the stroke — one hero per row.
        HeroValue.apply(tvBadgeMortgageValue, "mortgage" == which)
        HeroValue.apply(tvBadge10YValue, "tenYear" == which)
        HeroValue.apply(tvBadgeSpreadValue, "spread" == which)

        // Update swappable chart title
        when (which) {
            "mortgage" -> tvSwappableTitle.text = "30-Year Mortgage Rate (%)"
            "tenYear" -> tvSwappableTitle.text = "10-Year Treasury Rate (%)"
            "spread" -> tvSwappableTitle.text = "Mortgage - 10Y Treasury Spread (%)"
        }
    }

    // ── Swappable Chart ────────────────────────────────────────────────────────

    private fun styleSwappableChart() {
        val darkBg = Color.TRANSPARENT // Pantrii port U10: on the surface card
        val subtleGrid = ChartPalette.grid(requireContext())

        chartSwappable.setBackgroundColor(darkBg)
        chartSwappable.setDrawGridBackground(false)
        chartSwappable.setDrawBorders(false)
        chartSwappable.setTouchEnabled(true)
        chartSwappable.setDragEnabled(true)
        chartSwappable.setScaleEnabled(true)
        chartSwappable.setPinchZoom(true)
        chartSwappable.description.setEnabled(false)
        chartSwappable.setNoDataText("Select a badge above…")
        chartSwappable.setNoDataTextColor(ChartPalette.axisText(requireContext()))
        chartSwappable.setExtraBottomOffset(8f)
        chartSwappable.setExtraLeftOffset(8f)
        chartSwappable.setExtraRightOffset(8f)

        val xAxis = chartSwappable.xAxis
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM)
        xAxis.setTextColor(ChartPalette.axisText(requireContext()))
        xAxis.setTextSize(9f)
        xAxis.setDrawGridLines(true)
        xAxis.setGridColor(subtleGrid)
        xAxis.setAxisLineColor(subtleGrid)
        xAxis.setLabelRotationAngle(-45f)
        xAxis.setGranularity(1f)
        xAxis.setLabelCount(6, false)
        xAxis.setAvoidFirstLastClipping(true)

        val leftAxis = chartSwappable.axisLeft
        leftAxis.setTextColor(ChartPalette.axisText(requireContext()))
        leftAxis.setTextSize(10f)
        leftAxis.setDrawGridLines(true)
        leftAxis.setGridColor(subtleGrid)
        leftAxis.setAxisLineColor(subtleGrid)

        chartSwappable.axisRight.setEnabled(false)
        // Legend: ChartHelper.setLineData decides (TICKET-57) — single series, so off.
    }

    private fun buildSwappableChart(spreadRowsPrecomputed: List<EconomicDataPoint>? = null) {
        if (!::chartSwappable.isInitialized) return

        val seriesLabel: String
        val lineColor: Int = ChartPalette.primary(requireContext()) // TICKET-57
        var rows: List<EconomicDataPoint>
        var twoDecimals = false

        when (activeBadge) {
            "tenYear" -> {
                val treasury = currentTreasuryData ?: return
                rows = EconomicViewModel.filterBySeries(treasury, "10 Year")
                seriesLabel = "10Y Treasury Rate"
            }
            "spread" -> {
                rows = spreadRowsPrecomputed ?: computeSpreadRows()
                seriesLabel = "Mortgage-10Y Spread"
                twoDecimals = true
            }
            else -> { // mortgage
                val mbs = currentMbsData ?: return
                rows = EconomicViewModel.filterBySeries(mbs, "30-Yr Mortgage Rate")
                seriesLabel = "30-Yr Mortgage Rate"
            }
        }

        if (rows.isEmpty()) return

        // Standardized chart window (Settings -> Charts -> time range)
        rows = EconomicViewModel.filterByTimeframe(requireContext(), rows, "housing")
        if (rows.isEmpty()) return

        val entries = ArrayList<Entry>()
        val dateLabels = ArrayList<String>()
        for (i in rows.indices) {
            entries.add(Entry(i.toFloat(), rows[i].value.toFloat()))
            dateLabels.add(rows[i].date)
        }
        AxisDateFormat.attach(chartSwappable, requireContext(), "housing", dateLabels) // TICKET-57

        // All swappable series are percentages; the spread gets extra precision
        val useTwoDecimals = twoDecimals
        chartSwappable.axisLeft.setValueFormatter(object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                return String.format(Locale.US, if (useTwoDecimals) "%.2f%%" else "%.1f%%", value)
            }
        })

        val ds = makeLineDataSet(entries, seriesLabel, lineColor, false)
        ds.setDrawCircles(false)
        ds.setDrawCircleHole(false)
        ds.setLineWidth(1.5f)
        ds.setDrawFilled(false)

        ChartHelper.setLineData(chartSwappable, LineData(ds), 500)
        val latest = rows[rows.size - 1].value
        ChartHelper.showLatest(chartSwappable, when (activeBadge) { // unified charts: same grade as the badge
            "tenYear" -> Benchmarks.rateBadge(latest, 3.0, 4.0, 5.0)
            "spread" -> Benchmarks.mortgageSpread(latest)
            else -> Benchmarks.rateBadge(latest, 4.0, 5.5, 7.0)
        }, NumberFormatUtil.percent(latest))
        // P6 TICKET-37: TalkBack announces the visible series, not the static XML label.
        chartSwappable.contentDescription = seriesLabel + " trend, " +
            NumberFormatUtil.percent(rows[rows.size - 1].value, if (twoDecimals) 2 else 1)
    }

    // ── MBS & Mortgage Dual-Axis Chart ─────────────────────────────────────────

    private fun buildMbsMortgageChart(mbsData: List<EconomicDataPoint>) {
        if (!::chartMbsMortgage.isInitialized) return

        var bankRows = EconomicViewModel.filterBySeries(mbsData, "Bank MBS Holdings")
        var fedRows = EconomicViewModel.filterBySeries(mbsData, "Fed MBS Holdings")
        var mortgageRows = EconomicViewModel.filterBySeries(mbsData, "30-Yr Mortgage Rate")

        if (bankRows.isEmpty() && fedRows.isEmpty() && mortgageRows.isEmpty()) return

        // Standardized chart window (Settings -> Charts -> time range)
        bankRows = EconomicViewModel.filterByTimeframe(requireContext(), bankRows, "housing")
        fedRows = EconomicViewModel.filterByTimeframe(requireContext(), fedRows, "housing")
        mortgageRows = EconomicViewModel.filterByTimeframe(requireContext(), mortgageRows, "housing")

        // Build a unified date index from all three series
        val allDates = ArrayList<String>()
        for (p in bankRows) {
            if (!allDates.contains(p.date)) allDates.add(p.date)
        }
        for (p in fedRows) {
            if (!allDates.contains(p.date)) allDates.add(p.date)
        }
        for (p in mortgageRows) {
            if (!allDates.contains(p.date)) allDates.add(p.date)
        }
        Collections.sort(allDates)

        // Build entries — Bank MBS is already in billions; normalize Fed MBS
        // from millions to billions so both share the same left-axis scale
        val bankEntries = buildEntriesForDates(bankRows, allDates)
        val fedEntries = ArrayList<Entry>()
        var fedIdx = 0
        var i = 0
        while (i < allDates.size && fedIdx < fedRows.size) {
            if (allDates[i] == fedRows[fedIdx].date) {
                fedEntries.add(Entry(i.toFloat(), (fedRows[fedIdx].value / 1000.0).toFloat()))
                fedIdx++
            }
            i++
        }
        val mortgageEntries = buildEntriesForDates(mortgageRows, allDates)

        AxisDateFormat.attach(chartMbsMortgage, requireContext(), "housing", allDates) // TICKET-57

        // Bank MBS — left axis, gold, thin line, no dots
        val bankSet = makeLineDataSet(bankEntries, "Bank MBS Holdings", ChartPalette.gold(requireContext()), false)
        bankSet.setAxisDependency(YAxis.AxisDependency.LEFT)
        bankSet.setDrawCircles(false)
        bankSet.setDrawCircleHole(false)
        bankSet.setLineWidth(1.5f)
        bankSet.setDrawFilled(false)

        // Fed MBS — left axis, light gray, thin line, no dots
        val fedSet = makeLineDataSet(fedEntries, "Fed MBS Holdings", ChartPalette.slate(requireContext()), false)
        fedSet.setAxisDependency(YAxis.AxisDependency.LEFT)
        fedSet.setDrawCircles(false)
        fedSet.setDrawCircleHole(false)
        fedSet.setLineWidth(1.5f)
        fedSet.setDrawFilled(false)

        // Mortgage rate — right axis, muted coral, thin line, no dots
        val mortgageSet = makeLineDataSet(mortgageEntries, "30-Yr Mortgage Rate", ChartPalette.coral(requireContext()), false)
        mortgageSet.setAxisDependency(YAxis.AxisDependency.RIGHT)
        mortgageSet.setDrawCircles(false)
        mortgageSet.setDrawCircleHole(false)
        mortgageSet.setLineWidth(1.5f)
        mortgageSet.setDrawFilled(false)

        ChartHelper.setLineData(chartMbsMortgage, LineData(bankSet, fedSet, mortgageSet))
        // Unified charts: the mortgage rate (dataset 2) is the primary series.
        if (mortgageRows.isNotEmpty()) {
            val rate = mortgageRows[mortgageRows.size - 1].value
            ChartHelper.showLatest(chartMbsMortgage, Benchmarks.rateBadge(rate, 4.0, 5.5, 7.0),
                "Mortgage " + NumberFormatUtil.percent(rate), dataSetIndex = 2)
        }
    }

    /** Builds chart Entry list by mapping data points onto a shared date index. */
    private fun buildEntriesForDates(rows: List<EconomicDataPoint>, allDates: List<String>): List<Entry> {
        val entries = ArrayList<Entry>()
        var rowIdx = 0
        var i = 0
        while (i < allDates.size && rowIdx < rows.size) {
            if (allDates[i] == rows[rowIdx].date) {
                entries.add(Entry(i.toFloat(), rows[rowIdx].value.toFloat()))
                rowIdx++
            }
            i++
        }
        return entries
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun makeLineDataSet(entries: List<Entry>, label: String, c: Int, filled: Boolean): LineDataSet {
        val ds = LineDataSet(entries, label)
        ds.setColor(c)
        ds.setCircleColor(c)
        ds.setLineWidth(2.5f)
        ds.setCircleRadius(3f)
        ds.setDrawValues(false)
        ds.setMode(LineDataSet.Mode.CUBIC_BEZIER)
        if (filled) ds.setDrawCircles(false)
        return ds
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }
}
