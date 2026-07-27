package com.economic.dashboard.ui.fragments;

import com.economic.dashboard.ui.Benchmarks;
import com.economic.dashboard.ui.BenchmarkStyle;
import android.graphics.Color;
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

import com.economic.dashboard.databinding.FragmentCpiBinding;
import com.economic.dashboard.R;
import com.economic.dashboard.ui.MetricBottomSheet;
import com.economic.dashboard.models.EconomicDataPoint;
import com.economic.dashboard.ui.EconomicViewModel;
import com.economic.dashboard.utils.ChartHelper;
import com.economic.dashboard.utils.NumberFormatUtil;
import com.economic.dashboard.utils.TimeframeSelector;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.ValueFormatter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class CpiFragment extends Fragment {

    private FragmentCpiBinding binding;

    private EconomicViewModel viewModel;
    private LineChart cpiChart;
    private LineChart cpiWChart;
    
    private CardView cardCpiYoY;
    private TextView tvCpiYoYValue, tvCpiYoYDate;
    /** Root of the included view_status_badge row (R2-TICKET-03). */
    private View statusCpiYoY;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentCpiBinding.inflate(inflater, container, false);
        // IOS-04: bottom inset ONLY — this fragment lives inside EconomyFragment's
        // ViewPager2, below the host's tab strip, so the host owns the top inset.
        com.economic.dashboard.utils.EdgeToEdgeInsets.applyBottomInset(binding.getRoot());
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(requireActivity()).get(EconomicViewModel.class);

        // TICKET-18: per-screen retry chip for this screen's series
        android.widget.TextView retryChip = view.findViewById(R.id.tvRetry);
        final String[] retryKeys = { EconomicViewModel.CACHE_CPI };
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

        cpiChart  = binding.cpiUChart;
        com.economic.dashboard.analyst.AskAnalyst.attachChartExplain(
                cpiChart, requireActivity(), "the CPI-U consumer price index trend");
        cpiWChart = binding.cpiWChart;
        com.economic.dashboard.analyst.AskAnalyst.attachChartExplain(
                cpiWChart, requireActivity(), "the CPI-W consumer price index trend");
        
        cardCpiYoY     = binding.cardCpiYoY;
        com.economic.dashboard.analyst.AskAnalyst.wireCardLongPress(
                cardCpiYoY, requireActivity(), "CPI inflation (YoY)");
        tvCpiYoYValue  = binding.tvCpiYoYValue;
        tvCpiYoYDate   = binding.tvCpiYoYDate;
        statusCpiYoY   = binding.statusCpiYoY.getRoot();

        ChartHelper.styleLineChart(cpiChart,  "Consumer Price Index (CPI-U All Items)", "Month", "Index Value");
        ChartHelper.styleLineChart(cpiWChart, "CPI-W All Items",                         "Month", "Index Value");

        // Add Y-Axis unit labeling
        ValueFormatter indexFormatter = new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return String.format(Locale.US, "%.0f", value);
            }
        };
        cpiChart.getAxisLeft().setValueFormatter(indexFormatter);
        cpiWChart.getAxisLeft().setValueFormatter(indexFormatter);
        ChartHelper.attachCrosshair(cpiChart);  // TICKET-23
        ChartHelper.attachCrosshair(cpiWChart);

        // TICKET-20: inline range switcher — one control drives both CPI charts.
        android.widget.LinearLayout tfSel = view.findViewById(R.id.timeframeSelector);
        TimeframeSelector.attach(tfSel, "cpi", months -> {
            List<EconomicDataPoint> d = viewModel.getCpiData().getValue();
            if (d != null) {
                buildChart(d, "CPI-U All Items", cpiChart,  "#fbbc04");
                buildChart(d, "CPI-W All Items", cpiWChart, "#ff6d00");
            }
        });

        viewModel.getCpiData().observe(getViewLifecycleOwner(), data -> {
            if (data != null) {
                buildChart(data, "CPI-U All Items", cpiChart,  "#fbbc04");
                buildChart(data, "CPI-W All Items", cpiWChart, "#ff6d00");
                calculateYoYInflation(data);
            }
        });

        // Click listener to show benchmarks table
        cardCpiYoY.setOnClickListener(v -> showCpiBenchmarks());
    }

    private void showCpiBenchmarks() {
        if (getContext() == null) return;
        // TICKET-24: metric-aware sheet — offers "Add alert" for CPI YoY.
        MetricBottomSheet.show(getContext(), R.layout.dialog_cpi_status,
                EconomicViewModel.CACHE_CPI, "CPI YoY");
    }

    private void calculateYoYInflation(List<EconomicDataPoint> data) {
        List<EconomicDataPoint> cpiRows = EconomicViewModel.filterBySeries(data, "CPI-U All Items");
        if (cpiRows.size() < 13) return; 

        EconomicDataPoint latest = cpiRows.get(cpiRows.size() - 1);
        EconomicDataPoint yearAgo = cpiRows.get(cpiRows.size() - 13);

        double yoyChange = ((latest.getValue() - yearAgo.getValue()) / yearAgo.getValue()) * 100.0;
        
        tvCpiYoYValue.setText(NumberFormatUtil.percent(yoyChange)); // TICKET-19
        
        // Shared grading + semantic palette (TICKET-01/03/04)
        Benchmarks.Result result = Benchmarks.cpiYoY(yoyChange);

        // R2-TICKET-01/02: the badge word's colour is owned by @style/Badge.Status
        // in view_status_badge.xml — no setTextColor here. The card background is
        // already @color/card_navy_badge in the layout (R2-TICKET-04).
        BenchmarkStyle.applyBadge(statusCpiYoY, requireContext(), result);

        // Hide date range as requested
        tvCpiYoYDate.setVisibility(View.GONE);
    }

    private void buildChart(List<EconomicDataPoint> data, String series, LineChart chart, String hexColor) {
        List<EconomicDataPoint> rows = EconomicViewModel.filterBySeries(data, series);
        if (rows.isEmpty()) return;
        rows = EconomicViewModel.filterByTimeframe(requireContext(), rows, "cpi");

        List<Entry> entries = new ArrayList<>();
        final List<String> dates = new ArrayList<>();

        for (int i = 0; i < rows.size(); i++) {
            entries.add(new Entry(i, (float) rows.get(i).getValue()));
            String d = rows.get(i).getDate();
            if (d.length() >= 7) {
                String year = d.substring(0, 4);
                String month = d.substring(5, 7);
                dates.add(month + "-" + year);
            } else {
                dates.add(d);
            }
        }

        chart.getXAxis().setValueFormatter(new ValueFormatter() {
            @Override public String getFormattedValue(float value) {
                int idx = (int) value;
                return (idx >= 0 && idx < dates.size()) ? dates.get(idx) : "";
            }
        });

        LineDataSet dataSet = new LineDataSet(entries, series);
        int color = Color.parseColor(hexColor);
        dataSet.setColor(color);
        dataSet.setCircleColor(color);
        dataSet.setLineWidth(3f);
        dataSet.setCircleRadius(4f);
        dataSet.setDrawValues(false);
        dataSet.setMode(LineDataSet.Mode.CUBIC_BEZIER);

        chart.setData(new LineData(dataSet));
        chart.invalidate();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
