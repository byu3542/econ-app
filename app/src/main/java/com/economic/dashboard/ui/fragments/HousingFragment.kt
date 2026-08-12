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
import com.economic.dashboard.databinding.FragmentHousingBinding
import com.economic.dashboard.models.EconomicDataPoint
import com.economic.dashboard.ui.BenchmarkStyle
import com.economic.dashboard.ui.Benchmarks
import com.economic.dashboard.ui.EconomicViewModel
import com.economic.dashboard.ui.MetricPopupController
import com.economic.dashboard.ui.views.SkeletonController
import com.economic.dashboard.utils.ChartHelper
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

import java.text.ParseException
import java.text.SimpleDateFormat
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
    private lateinit var badgeMortgage: CardView
    private lateinit var badge10Y: CardView
    private lateinit var badgeSpread: CardView
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
        ChartHelper.declutterDark(chartSwappable)
        ChartHelper.attachCrosshair(chartSwappable) // TICKET-23

        // TICKET-11: one inline range switcher drives both Housing charts.
        val tfSel: LinearLayout? = view.findViewById(R.id.timeframeSelector)
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
        val mbsDarkBg = Color.parseColor("#1C2236")
        val mbsSubtleGrid = Color.argb(0x14, 0xFF, 0xFF, 0xFF) // ~8% white

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
        chartMbsMortgage.setNoDataTextColor(Color.parseColor("#5A6A8A"))
        chartMbsMortgage.setExtraBottomOffset(30f)
        chartMbsMortgage.setExtraLeftOffset(10f)
        chartMbsMortgage.setExtraTopOffset(10f)
        chartMbsMortgage.setExtraRightOffset(10f)

        // X-axis
        val mbsXAxis = chartMbsMortgage.xAxis
        mbsXAxis.setPosition(XAxis.XAxisPosition.BOTTOM)
        mbsXAxis.setTextColor(Color.parseColor("#5A6A8A"))
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
        mbsLeftAxis.setTextColor(Color.parseColor("#C9A84C"))
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
        mbsRightAxis.setTextColor(Color.parseColor("#E07060"))
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
        mbsLegend.setTextColor(Color.parseColor("#8899BB"))
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
        ChartHelper.declutterDark(chartMbsMortgage)
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
        // R3-TICKET-01: border geometry lives in BenchmarkStyle so it tracks the card radius token.
        badgeMortgage.foreground = if ("mortgage" == which) BenchmarkStyle.activeCardBorder(
            requireContext(), R.dimen.radius_card_kpi) else null
        badge10Y.foreground = if ("tenYear" == which) BenchmarkStyle.activeCardBorder(
            requireContext(), R.dimen.radius_card_kpi) else null
        badgeSpread.foreground = if ("spread" == which) BenchmarkStyle.activeCardBorder(
            requireContext(), R.dimen.radius_card_kpi) else null

        // Update swappable chart title
        when (which) {
            "mortgage" -> tvSwappableTitle.text = "30-Year Mortgage Rate (%)"
            "tenYear" -> tvSwappableTitle.text = "10-Year Treasury Rate (%)"
            "spread" -> tvSwappableTitle.text = "Mortgage - 10Y Treasury Spread (%)"
        }
    }

    // ── Swappable Chart ────────────────────────────────────────────────────────

    private fun styleSwappableChart() {
        val darkBg = Color.parseColor("#1C2236")
        val subtleGrid = Color.argb(0x14, 0xFF, 0xFF, 0xFF)

        chartSwappable.setBackgroundColor(darkBg)
        chartSwappable.setDrawGridBackground(false)
        chartSwappable.setDrawBorders(false)
        chartSwappable.setTouchEnabled(true)
        chartSwappable.setDragEnabled(true)
        chartSwappable.setScaleEnabled(true)
        chartSwappable.setPinchZoom(true)
        chartSwappable.description.setEnabled(false)
        chartSwappable.setNoDataText("Select a badge above…")
        chartSwappable.setNoDataTextColor(Color.parseColor("#5A6A8A"))
        chartSwappable.setExtraBottomOffset(8f)
        chartSwappable.setExtraLeftOffset(8f)
        chartSwappable.setExtraRightOffset(8f)

        val xAxis = chartSwappable.xAxis
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM)
        xAxis.setTextColor(Color.parseColor("#5A6A8A"))
        xAxis.setTextSize(9f)
        xAxis.setDrawGridLines(true)
        xAxis.setGridColor(subtleGrid)
        xAxis.setAxisLineColor(subtleGrid)
        xAxis.setLabelRotationAngle(-45f)
        xAxis.setGranularity(1f)
        xAxis.setLabelCount(6, false)
        xAxis.setAvoidFirstLastClipping(true)

        val leftAxis = chartSwappable.axisLeft
        leftAxis.setTextColor(Color.parseColor("#8899BB"))
        leftAxis.setTextSize(10f)
        leftAxis.setDrawGridLines(true)
        leftAxis.setGridColor(subtleGrid)
        leftAxis.setAxisLineColor(subtleGrid)

        chartSwappable.axisRight.setEnabled(false)

        val legend = chartSwappable.legend
        legend.setEnabled(false) // single-line chart, no legend needed
    }

    private fun buildSwappableChart(spreadRowsPrecomputed: List<EconomicDataPoint>? = null) {
        if (!::chartSwappable.isInitialized) return

        val seriesLabel: String
        val lineColor: String
        var rows: List<EconomicDataPoint>
        var twoDecimals = false

        when (activeBadge) {
            "tenYear" -> {
                val treasury = currentTreasuryData ?: return
                rows = EconomicViewModel.filterBySeries(treasury, "10 Year")
                seriesLabel = "10Y Treasury Rate"
                lineColor = "#C9A84C"
            }
            "spread" -> {
                rows = spreadRowsPrecomputed ?: computeSpreadRows()
                seriesLabel = "Mortgage-10Y Spread"
                lineColor = "#BBBBBB"
                twoDecimals = true
            }
            else -> { // mortgage
                val mbs = currentMbsData ?: return
                rows = EconomicViewModel.filterBySeries(mbs, "30-Yr Mortgage Rate")
                seriesLabel = "30-Yr Mortgage Rate"
                lineColor = "#E07060"
            }
        }

        if (rows.isEmpty()) return

        // Standardized chart window (Settings -> Charts -> time range)
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        rows = EconomicViewModel.filterByTimeframe(requireContext(), rows, "housing")
        if (rows.isEmpty()) return

        val entries = ArrayList<Entry>()
        val dateLabels = ArrayList<String>()
        val labelFmt = SimpleDateFormat("MMM yy", Locale.US)

        for (i in rows.indices) {
            val v = rows[i].value
            entries.add(Entry(i.toFloat(), v.toFloat()))
            try {
                val date = sdf.parse(rows[i].date)
                dateLabels.add(if (date != null) labelFmt.format(date) else "")
            } catch (e: ParseException) {
                dateLabels.add("")
            }
        }

        chartSwappable.xAxis.setValueFormatter(object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                val idx = value.toInt()
                return if (idx >= 0 && idx < dateLabels.size) dateLabels[idx] else ""
            }
        })

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

        chartSwappable.setData(LineData(ds))
        chartSwappable.animateX(500)
        chartSwappable.invalidate()
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
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)

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

        // Format X-axis labels as MMM yy
        val labelFmt = SimpleDateFormat("MMM yy", Locale.US)
        val dateLabels = ArrayList<String>()
        for (d in allDates) {
            try {
                val date = sdf.parse(d)
                dateLabels.add(if (date != null) labelFmt.format(date) else d)
            } catch (e: ParseException) {
                dateLabels.add(if (d.length >= 7) d.substring(5, 7) + "/" + d.substring(2, 4) else d)
            }
        }

        chartMbsMortgage.xAxis.setValueFormatter(object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                val idx = value.toInt()
                return if (idx >= 0 && idx < dateLabels.size) dateLabels[idx] else ""
            }
        })

        // Bank MBS — left axis, gold, thin line, no dots
        val bankSet = makeLineDataSet(bankEntries, "Bank MBS Holdings", "#C9A84C", false)
        bankSet.setAxisDependency(YAxis.AxisDependency.LEFT)
        bankSet.setDrawCircles(false)
        bankSet.setDrawCircleHole(false)
        bankSet.setLineWidth(1.5f)
        bankSet.setDrawFilled(false)

        // Fed MBS — left axis, light gray, thin line, no dots
        val fedSet = makeLineDataSet(fedEntries, "Fed MBS Holdings", "#BBBBBB", false)
        fedSet.setAxisDependency(YAxis.AxisDependency.LEFT)
        fedSet.setDrawCircles(false)
        fedSet.setDrawCircleHole(false)
        fedSet.setLineWidth(1.5f)
        fedSet.setDrawFilled(false)

        // Mortgage rate — right axis, muted coral, thin line, no dots
        val mortgageSet = makeLineDataSet(mortgageEntries, "30-Yr Mortgage Rate", "#E07060", false)
        mortgageSet.setAxisDependency(YAxis.AxisDependency.RIGHT)
        mortgageSet.setDrawCircles(false)
        mortgageSet.setDrawCircleHole(false)
        mortgageSet.setLineWidth(1.5f)
        mortgageSet.setDrawFilled(false)

        chartMbsMortgage.setData(LineData(bankSet, fedSet, mortgageSet))
        chartMbsMortgage.invalidate()
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

    private fun makeLineDataSet(entries: List<Entry>, label: String, hexColor: String, filled: Boolean): LineDataSet {
        val ds = LineDataSet(entries, label)
        val c = Color.parseColor(hexColor)
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
