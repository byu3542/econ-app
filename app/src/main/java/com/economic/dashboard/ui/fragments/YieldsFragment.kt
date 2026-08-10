package com.economic.dashboard.ui.fragments

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider

import com.economic.dashboard.R
import com.economic.dashboard.analyst.AskAnalyst
import com.economic.dashboard.databinding.FragmentYieldsBinding
import com.economic.dashboard.models.EconomicDataPoint
import com.economic.dashboard.ui.EconomicViewModel
import com.economic.dashboard.ui.views.SkeletonController
import com.economic.dashboard.utils.ChartHelper
import com.economic.dashboard.utils.DeltaFormatter
import com.economic.dashboard.utils.EdgeToEdgeInsets
import com.economic.dashboard.utils.NumberFormatUtil
import com.economic.dashboard.utils.RetryChip
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter

import java.util.Locale

class YieldsFragment : Fragment() {

    private var binding: FragmentYieldsBinding? = null
    private lateinit var skeleton: SkeletonController

    private lateinit var viewModel: EconomicViewModel
    private lateinit var yieldCurveChart: LineChart
    private lateinit var row1M: View
    private lateinit var row3M: View
    private lateinit var row2Y: View
    private lateinit var row10Y: View
    private lateinit var row30Y: View

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val b = FragmentYieldsBinding.inflate(inflater, container, false)
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

        yieldCurveChart = b.yieldCurveChart
        AskAnalyst.attachChartExplain(
            yieldCurveChart, requireActivity(), "the current Treasury yield curve across maturities")
        styleChart(yieldCurveChart)
        ChartHelper.declutterDark(yieldCurveChart)
        yieldCurveChart.axisLeft.setValueFormatter(object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                return String.format(Locale.US, "%.1f%%", value)
            }
        })

        // <include> entries — binding exposes the included layout's binding,
        // so getRoot() gives the View the rest of this class expects.
        row1M = b.row1M.root
        row3M = b.row3M.root
        row2Y = b.row2Y.root
        row10Y = b.row10Y.root
        row30Y = b.row30Y.root
        setupTreasuryRow(row1M, "1 Month")
        setupTreasuryRow(row3M, "3 Month")
        setupTreasuryRow(row2Y, "2 Year")
        setupTreasuryRow(row10Y, "10 Year")
        setupTreasuryRow(row30Y, "30 Year")
        applyZebra()

        viewModel.getTreasuryData().observe(viewLifecycleOwner) { data ->
            if (data != null) {
                buildYieldCurveChart(data); updateTreasury(data)
                if (::skeleton.isInitialized) skeleton.reveal()
            }
        }
    }

    private fun buildYieldCurveChart(data: List<EconomicDataPoint>) {
        var latestDate: String? = null
        for (p in data)
            if (latestDate == null || p.date.compareTo(latestDate) > 0) latestDate = p.date
        val finalLatestDate = latestDate ?: return

        val maturityYears = doubleArrayOf(0.083, 0.25, 0.5, 1.0, 2.0, 5.0, 10.0, 30.0)
        val maturityNames = arrayOf("1M", "3M", "6M", "1Y", "2Y", "5Y", "10Y", "30Y")
        val seriesNames = arrayOf("1 Month", "3 Month", "6 Month", "1 Year", "2 Year", "5 Year", "10 Year", "30 Year")

        val entries = ArrayList<Entry>()
        for (i in seriesNames.indices) {
            for (p in data) {
                if (p.series == seriesNames[i] && p.date == finalLatestDate) {
                    entries.add(Entry(maturityYears[i].toFloat(), p.value.toFloat()))
                    break
                }
            }
        }

        val ds = LineDataSet(entries, "Yield (%) — $finalLatestDate")
        ChartHelper.styleLineSeries(ds, Color.parseColor("#1a73e8")) // IOS-07

        yieldCurveChart.xAxis.setValueFormatter(object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                for (i in maturityYears.indices)
                    if (Math.abs(maturityYears[i] - value) < 0.05) return maturityNames[i]
                return ""
            }
        })

        yieldCurveChart.setData(LineData(ds)); yieldCurveChart.invalidate()
    }

    private fun setupTreasuryRow(row: View, label: String) {
        val tv: TextView? = row.findViewById(R.id.tvMaturity)
        if (tv != null) tv.text = label
    }

    /** Zebra striping — alternate rows get the warm alt background. */
    private fun applyZebra() {
        val rowsInOrder = arrayOf(row1M, row3M, row2Y, row10Y, row30Y)
        for (i in rowsInOrder.indices) {
            if (i % 2 == 1)
                rowsInOrder[i].setBackgroundColor(
                    ContextCompat.getColor(requireContext(), R.color.row_alt_bg))
        }
    }

    private fun updateTreasury(data: List<EconomicDataPoint>) {
        setTreasuryRate(data, "1 Month", row1M)
        setTreasuryRate(data, "3 Month", row3M)
        setTreasuryRate(data, "2 Year", row2Y)
        setTreasuryRate(data, "10 Year", row10Y)
        setTreasuryRate(data, "30 Year", row30Y)
    }

    private fun setTreasuryRate(data: List<EconomicDataPoint>, series: String, row: View) {
        val rows = EconomicViewModel.filterBySeries(data, series)
        val valView: TextView? = row.findViewById(R.id.tvYield)
        val dateView: TextView? = row.findViewById(R.id.tvDate)
        val changeView: TextView? = row.findViewById(R.id.tvChange)
        if (!rows.isEmpty()) {
            val p = rows[rows.size - 1]
            if (valView != null) valView.text = NumberFormatUtil.percent(p.value) // TICKET-19
            if (dateView != null) dateView.text = p.date
            if (changeView != null && rows.size >= 2) {
                val bps = (p.value - rows[rows.size - 2].value) * 100.0
                if (Math.abs(bps) < 0.5) {
                    changeView.text = "—"
                    changeView.setTextColor(ContextCompat.getColor(
                        requireContext(), R.color.text_muted))
                } else {
                    changeView.text = DeltaFormatter.format(bps, "%.0fbp")
                    changeView.setTextColor(DeltaFormatter.color(requireContext(), bps, goodWhenDown = true))
                }
            }
        } else {
            if (valView != null) valView.text = "—"
            if (dateView != null) dateView.text = ""
            if (changeView != null) changeView.text = ""
        }
    }

    private fun styleChart(chart: LineChart) {
        val grid = Color.argb(0x14, 0xFF, 0xFF, 0xFF)
        chart.setBackgroundColor(Color.parseColor("#1C2236")); chart.setDrawGridBackground(false)
        chart.setDrawBorders(false); chart.setTouchEnabled(true); chart.setDragEnabled(true)
        chart.setScaleEnabled(true); chart.setPinchZoom(true); chart.description.setEnabled(false)
        chart.setNoDataText("Loading yield data…")
        chart.setNoDataTextColor(Color.parseColor("#8899BB"))
        chart.setExtraBottomOffset(8f)
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
