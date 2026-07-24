package com.economic.dashboard.ui.fragments;

import androidx.core.content.ContextCompat;
import com.economic.dashboard.ui.Benchmarks;
import com.economic.dashboard.ui.BenchmarkStyle;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.cardview.widget.CardView;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.economic.dashboard.databinding.FragmentGdpBinding;
import com.economic.dashboard.R;
import com.economic.dashboard.ui.MetricBottomSheet;
import com.economic.dashboard.models.EconomicDataPoint;
import com.economic.dashboard.ui.EconomicViewModel;
import com.economic.dashboard.utils.NumberFormatUtil;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.ValueFormatter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class GdpFragment extends Fragment {

    private FragmentGdpBinding binding;
    private com.economic.dashboard.ui.views.SkeletonController skeleton;

    private EconomicViewModel viewModel;
    private LineChart gdpChart;
    private TextView tvGdpLatest, tvGdpDesc, tvGdpStatusText;
    private View viewGdpIndicatorDot;
    private CardView cardGdpStatus;
    private TextView tvGdpLatestQuarterValue, tvGdpLatestQuarterLabel, tvGdpLatestQuarterStatus;
    private View viewGdpLatestQuarterDot;
    private CardView cardGdpLatestQuarter;
    private List<EconomicDataPoint> lastGdpData; // last observed data, for range re-draws

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentGdpBinding.inflate(inflater, container, false);
        skeleton = com.economic.dashboard.ui.views.SkeletonController.wrap(binding.getRoot());
        return skeleton.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(requireActivity()).get(EconomicViewModel.class);

        // TICKET-18: per-screen retry chip for this screen's series
        android.widget.TextView retryChip = view.findViewById(R.id.tvRetry);
        final String[] retryKeys = { EconomicViewModel.CACHE_GDP };
        viewModel.getFailedSeries().observe(getViewLifecycleOwner(), failed -> {
            if (retryChip == null) return;
            String hit = null;
            if (failed != null) for (String k : retryKeys) if (failed.contains(k)) { hit = k; break; }
            if (hit != null) {
                final String key = hit;
                retryChip.setOnClickListener(x -> viewModel.retrySeries(key));
                retryChip.setVisibility(View.VISIBLE);
            } else {
                retryChip.setVisibility(View.GONE);
            }
        });

        gdpChart = binding.gdpChart;
        com.economic.dashboard.analyst.AskAnalyst.attachChartExplain(
                gdpChart, requireActivity(), "quarterly U.S. GDP growth");
        tvGdpLatest = binding.tvGdpLatest;
        tvGdpDesc = binding.tvGdpDesc;
        tvGdpStatusText = binding.tvGdpStatusText;
        viewGdpIndicatorDot = binding.viewGdpIndicatorDot;
        cardGdpStatus = binding.cardGdpStatus;
        com.economic.dashboard.analyst.AskAnalyst.wireCardLongPress(
                cardGdpStatus, requireActivity(), "GDP growth");
        cardGdpLatestQuarter = binding.cardGdpLatestQuarter;
        com.economic.dashboard.analyst.AskAnalyst.wireCardLongPress(
                cardGdpLatestQuarter, requireActivity(), "the latest quarter GDP reading");
        tvGdpLatestQuarterValue = binding.tvGdpLatestQuarterValue;
        tvGdpLatestQuarterLabel = binding.tvGdpLatestQuarterLabel;
        tvGdpLatestQuarterStatus = binding.tvGdpLatestQuarterStatus;
        viewGdpLatestQuarterDot = binding.viewGdpLatestQuarterDot;

        styleChart(gdpChart);
        com.economic.dashboard.utils.ChartHelper.declutterDark(gdpChart);
        com.economic.dashboard.utils.ChartHelper.attachCrosshair(gdpChart); // TICKET-23
        gdpChart.getAxisLeft().setValueFormatter(new ValueFormatter() {
            @Override public String getFormattedValue(float value) {
                return String.format(Locale.US, "%.1f%%", value);
            }
        });

        // TICKET-11: inline range switcher — redraw the chart for the new window.
        android.widget.LinearLayout tfSelector = view.findViewById(R.id.timeframeSelector);
        com.economic.dashboard.utils.TimeframeSelector.attach(tfSelector, "gdp", months -> {
            if (lastGdpData != null) buildGdpChart(lastGdpData);
        });

        viewModel.getGdpData().observe(getViewLifecycleOwner(), data -> {
            if (data != null) { lastGdpData = data; buildGdpChart(data); updateGdpIndicator(data); updateLatestQuarterStatus(data); if (skeleton != null) skeleton.reveal(); }
        });
        viewModel.getLatestQuarterGdp().observe(getViewLifecycleOwner(), value -> {
            if (value != null && tvGdpLatestQuarterValue != null) tvGdpLatestQuarterValue.setText(value);
        });
        viewModel.getLatestQuarterLabel().observe(getViewLifecycleOwner(), label -> {
            if (label != null && tvGdpLatestQuarterLabel != null) tvGdpLatestQuarterLabel.setText(label);
        });

        cardGdpStatus.setOnClickListener(v -> showGdpBenchmarks());
    }

    private void showGdpBenchmarks() {
        if (getContext() == null) return;
        // TICKET-24: metric-aware sheet — offers "Add alert" for GDP growth.
        MetricBottomSheet.show(getContext(), R.layout.dialog_gdp_status,
                EconomicViewModel.CACHE_GDP, "GDP growth");
    }

    private void updateGdpIndicator(List<EconomicDataPoint> data) {
        List<EconomicDataPoint> gdpRows = EconomicViewModel.filterBySeries(data, "Gross domestic product");
        if (gdpRows.isEmpty()) return;

        double sum = 0; int count = 0;
        int startIndex = Math.max(0, gdpRows.size()-4);
        for (int i = startIndex; i < gdpRows.size(); i++) { sum += gdpRows.get(i).getValue(); count++; }
        double rollingAvg = (count > 0) ? sum/count : 0;

        tvGdpLatest.setText(NumberFormatUtil.percent(rollingAvg)); // TICKET-19
        tvGdpDesc.setText("4-Quarter Rolling Average");

        // Shared grading + semantic palette (TICKET-01/02/04)
        Benchmarks.Result result = Benchmarks.gdpGrowth(rollingAvg);
        tvGdpStatusText.setText(result.word);
        tvGdpStatusText.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_slate));
        BenchmarkStyle.tintDot(viewGdpIndicatorDot, requireContext(), result.tier);
        cardGdpStatus.setCardBackgroundColor(ContextCompat.getColor(requireContext(), R.color.card_navy_badge));
    }

    private void updateLatestQuarterStatus(List<EconomicDataPoint> data) {
        List<EconomicDataPoint> gdpRows = EconomicViewModel.filterBySeries(data, "Gross domestic product");
        if (gdpRows.isEmpty()) return;
        double latestValue = gdpRows.get(gdpRows.size()-1).getValue();

        Benchmarks.Result result = Benchmarks.gdpQuarter(latestValue);
        if (tvGdpLatestQuarterStatus != null) {
            tvGdpLatestQuarterStatus.setText(result.word);
            tvGdpLatestQuarterStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_slate));
        }
        if (viewGdpLatestQuarterDot != null) {
            BenchmarkStyle.tintDot(viewGdpLatestQuarterDot, requireContext(), result.tier);
        }
        if (cardGdpLatestQuarter != null) cardGdpLatestQuarter.setCardBackgroundColor(ContextCompat.getColor(requireContext(), R.color.card_navy_badge));
    }

    private void buildGdpChart(List<EconomicDataPoint> data) {
        List<EconomicDataPoint> gdpRows = EconomicViewModel.filterBySeries(data, "Gross domestic product");
        if (gdpRows.isEmpty() && !data.isEmpty())
            gdpRows = EconomicViewModel.filterBySeries(data, data.get(0).getSeries());
        if (gdpRows.isEmpty()) return;

        List<EconomicDataPoint> chartRows = EconomicViewModel.filterByTimeframe(requireContext(), gdpRows, "gdp");
        if (chartRows.isEmpty()) chartRows = gdpRows;

        List<Entry> entries = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        for (int i = 0; i < chartRows.size(); i++) {
            entries.add(new Entry(i, (float) chartRows.get(i).getValue()));
            labels.add(chartRows.get(i).getDate());
        }

        gdpChart.getXAxis().setValueFormatter(new ValueFormatter() {
            @Override public String getFormattedValue(float value) {
                int idx = (int) value;
                return (idx >= 0 && idx < labels.size()) ? labels.get(idx) : "";
            }
        });

        LineDataSet ds = new LineDataSet(entries, "GDP Growth (%)");
        ds.setColor(Color.parseColor("#1a73e8")); ds.setLineWidth(1.5f);
        ds.setDrawCircles(false); ds.setDrawValues(false); ds.setMode(LineDataSet.Mode.LINEAR);
        gdpChart.setData(new LineData(ds)); gdpChart.invalidate();
    }

    private void styleChart(LineChart chart) {
        int grid = Color.argb(0x14, 0xFF, 0xFF, 0xFF);
        chart.setBackgroundColor(Color.parseColor("#1C2236")); chart.setDrawGridBackground(false);
        chart.setDrawBorders(false); chart.setTouchEnabled(true); chart.setDragEnabled(true);
        chart.setScaleEnabled(true); chart.setPinchZoom(true); chart.getDescription().setEnabled(false);
        chart.setNoDataText("Loading data..."); chart.setExtraBottomOffset(8f);
        XAxis x = chart.getXAxis();
        x.setPosition(XAxis.XAxisPosition.BOTTOM); x.setTextColor(Color.parseColor("#5A6A8A")); x.setTextSize(9f);
        x.setDrawGridLines(true); x.setGridColor(grid); x.setLabelRotationAngle(-45f);
        x.setGranularity(1f); x.setLabelCount(6, false); x.setAvoidFirstLastClipping(true);
        YAxis y = chart.getAxisLeft();
        y.setTextColor(Color.parseColor("#8899BB")); y.setTextSize(10f); y.setDrawGridLines(true); y.setGridColor(grid);
        chart.getAxisRight().setEnabled(false);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
