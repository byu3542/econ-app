package com.economic.dashboard.ui.fragments;

import android.graphics.Color;
import androidx.core.content.ContextCompat;
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

import com.economic.dashboard.databinding.FragmentCpiBinding;
import com.economic.dashboard.R;
import com.economic.dashboard.ui.MetricBottomSheet;
import com.economic.dashboard.models.EconomicDataPoint;
import com.economic.dashboard.ui.EconomicViewModel;
import com.economic.dashboard.utils.ChartHelper;
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
    private TextView tvCpiYoYValue, tvCpiYoYDate, tvCpiYoYStatus;
    private View viewCpiIndicatorDot;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentCpiBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(requireActivity()).get(EconomicViewModel.class);

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
        tvCpiYoYStatus = binding.tvCpiYoYStatus;
        viewCpiIndicatorDot = binding.viewCpiIndicatorDot;

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
        MetricBottomSheet.show(getContext(), R.layout.dialog_cpi_status);
    }

    private void calculateYoYInflation(List<EconomicDataPoint> data) {
        List<EconomicDataPoint> cpiRows = EconomicViewModel.filterBySeries(data, "CPI-U All Items");
        if (cpiRows.size() < 13) return; 

        EconomicDataPoint latest = cpiRows.get(cpiRows.size() - 1);
        EconomicDataPoint yearAgo = cpiRows.get(cpiRows.size() - 13);

        double yoyChange = ((latest.getValue() - yearAgo.getValue()) / yearAgo.getValue()) * 100.0;
        
        tvCpiYoYValue.setText(String.format(Locale.US, "%.2f%%", yoyChange));
        
        // Threshold Logic
        String status;
        int color;
        
        if (yoyChange < 1.5) {
            status = "DEFLATION RISK";
            color = Color.parseColor("#5B8DB8"); // Blue
        } else if (yoyChange <= 2.5) {
            status = "HEALTHY";
            color = Color.parseColor("#6FA97A"); // Green
        } else if (yoyChange <= 3.5) {
            status = "CAUTION";
            color = Color.parseColor("#DCC873"); // Yellow
        } else if (yoyChange <= 6.0) {
            status = "ELEVATED";
            color = Color.parseColor("#D98E4F"); // Orange
        } else {
            status = "CRITICAL";
            color = Color.parseColor("#C75B4E"); // Red
        }

        // Revert badge background to Header Navy
        cardCpiYoY.setCardBackgroundColor(Color.parseColor("#1A1F2B")); 
        
        tvCpiYoYStatus.setText(status);
        tvCpiYoYStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_on_navy_secondary));
        
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(color);
        viewCpiIndicatorDot.setBackground(dot);

        // Hide date range as requested
        tvCpiYoYDate.setVisibility(View.GONE);
    }

    private void buildChart(List<EconomicDataPoint> data, String series, LineChart chart, String hexColor) {
        List<EconomicDataPoint> rows = EconomicViewModel.filterBySeries(data, series);
        if (rows.isEmpty()) return;
        rows = EconomicViewModel.filterByTimeframe(requireContext(), rows);

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
