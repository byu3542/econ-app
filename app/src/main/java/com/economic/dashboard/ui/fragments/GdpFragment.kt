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
import com.economic.dashboard.databinding.FragmentGdpBinding
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
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter

import java.util.Locale

class GdpFragment : Fragment() {

    private var binding: FragmentGdpBinding? = null
    private lateinit var skeleton: SkeletonController

    private lateinit var viewModel: EconomicViewModel
    private lateinit var gdpChart: LineChart
    private lateinit var tvGdpLatest: TextView
    private lateinit var tvGdpDesc: TextView
    private lateinit var cardGdpStatus: CardView
    private lateinit var tvGdpLatestQuarterValue: TextView
    private lateinit var tvGdpLatestQuarterLabel: TextView
    /** Roots of the included view_status_badge rows (R2-TICKET-03). */
    private lateinit var statusGdp: View
    private lateinit var statusGdpLatestQuarter: View
    private lateinit var cardGdpLatestQuarter: CardView
    private var lastGdpData: List<EconomicDataPoint>? = null // last observed data, for range re-draws

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val b = FragmentGdpBinding.inflate(inflater, container, false)
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
        RetryChip.wire(this, view, viewModel, EconomicViewModel.CACHE_GDP)

        gdpChart = b.gdpChart
        AskAnalyst.attachChartExplain(
            gdpChart, requireActivity(), "quarterly U.S. GDP growth")
        tvGdpLatest = b.tvGdpLatest
        tvGdpDesc = b.tvGdpDesc
        statusGdp = b.statusGdp.root
        cardGdpStatus = b.cardGdpStatus
        AskAnalyst.wireCardLongPress(
            cardGdpStatus, requireActivity(), "GDP growth")
        cardGdpLatestQuarter = b.cardGdpLatestQuarter
        AskAnalyst.wireCardLongPress(
            cardGdpLatestQuarter, requireActivity(), "the latest quarter GDP reading")
        tvGdpLatestQuarterValue = b.tvGdpLatestQuarterValue
        tvGdpLatestQuarterLabel = b.tvGdpLatestQuarterLabel
        statusGdpLatestQuarter = b.statusGdpLatestQuarter.root

        ChartHelper.styleNavyChart(gdpChart)
        ChartHelper.declutterDark(gdpChart)
        ChartHelper.attachCrosshair(gdpChart) // TICKET-23
        gdpChart.axisLeft.setValueFormatter(object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                return String.format(Locale.US, "%.1f%%", value)
            }
        })

        // TICKET-11: inline range switcher — redraw the chart for the new window.
        val tfSelector: LinearLayout? = view.findViewById(R.id.timeframeSelector)
        TimeframeSelector.attach(tfSelector, "gdp") {
            lastGdpData?.let { buildGdpChart(it) }
        }

        viewModel.getGdpData().observe(viewLifecycleOwner) { data ->
            if (data != null) {
                lastGdpData = data; buildGdpChart(data); updateGdpIndicator(data); updateLatestQuarterStatus(data)
                if (::skeleton.isInitialized) skeleton.reveal()
            }
        }
        viewModel.getLatestQuarterGdp().observe(viewLifecycleOwner) { value ->
            if (value != null) tvGdpLatestQuarterValue.text = value
        }
        viewModel.getLatestQuarterLabel().observe(viewLifecycleOwner) { label ->
            if (label != null) tvGdpLatestQuarterLabel.text = ""
        }

        cardGdpStatus.setOnClickListener { showGdpBenchmarks() }
    }

    private fun showGdpBenchmarks() {
        val ctx = context ?: return
        MetricPopupController.show(ctx, viewModel, "gdp")
    }

    private fun updateGdpIndicator(data: List<EconomicDataPoint>) {
        val gdpRows = EconomicViewModel.filterBySeries(data, "Gross domestic product")
        if (gdpRows.isEmpty()) return

        val rollingAvg = SeriesMath.trailingAverage(gdpRows, 4)

        tvGdpLatest.text = NumberFormatUtil.percent(rollingAvg) // TICKET-19
        tvGdpDesc.text = ""

        // Shared grading + semantic palette (TICKET-01/02/04)
        val result = Benchmarks.gdpGrowth(rollingAvg)
        // R2-TICKET-01/02: the badge word's colour is owned by @style/Badge.Status.
        // The card background is @color/card_navy_badge in the layout (R2-TICKET-04).
        BenchmarkStyle.applyBadge(statusGdp, requireContext(), result)
    }

    private fun updateLatestQuarterStatus(data: List<EconomicDataPoint>) {
        val gdpRows = EconomicViewModel.filterBySeries(data, "Gross domestic product")
        if (gdpRows.isEmpty()) return
        val latestValue = gdpRows[gdpRows.size - 1].value

        val result = Benchmarks.gdpQuarter(latestValue)
        BenchmarkStyle.applyBadge(statusGdpLatestQuarter, requireContext(), result)
    }

    private fun buildGdpChart(data: List<EconomicDataPoint>) {
        var gdpRows = EconomicViewModel.filterBySeries(data, "Gross domestic product")
        if (gdpRows.isEmpty() && !data.isEmpty())
            gdpRows = EconomicViewModel.filterBySeries(data, data[0].series)
        if (gdpRows.isEmpty()) return

        var chartRows = EconomicViewModel.filterByTimeframe(requireContext(), gdpRows, "gdp")
        if (chartRows.isEmpty()) chartRows = gdpRows

        val entries = ArrayList<Entry>()
        val labels = ArrayList<String>()
        for (i in chartRows.indices) {
            entries.add(Entry(i.toFloat(), chartRows[i].value.toFloat()))
            labels.add(chartRows[i].date)
        }

        gdpChart.xAxis.setValueFormatter(object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                val idx = value.toInt()
                return if (idx >= 0 && idx < labels.size) labels[idx] else ""
            }
        })

        val ds = LineDataSet(entries, "GDP Growth (%)")
        ChartHelper.styleLineSeries(ds, Color.parseColor("#1a73e8")) // IOS-07
        gdpChart.setData(LineData(ds)); gdpChart.invalidate()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }
}
