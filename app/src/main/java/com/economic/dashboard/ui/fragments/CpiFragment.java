package com.economic.dashboard.ui.fragments;

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

import com.economic.dashboard.R;
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

    private EconomicViewModel viewModel;
    private LineChart cpiChart;
    private LineChart cpiWChart;
    
    private CardView cardCpiYoY;
    private TextView tvCpiYoYValue, tvCpiYoYDate, tvCpiYoYStatus;
    private View viewCpiIndicatorDot;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_cpi, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(requireActivity()).get(EconomicViewModel.class);

        cpiChart  = view.findViewById(R.id.cpiUChart);
        cpiWChart = view.findViewById(R.id.cpiWChart);
        
        cardCpiYoY     = view.findViewById(R.id.cardCpiYoY);
        tvCpiYoYValue  = view.findViewById(R.id.tvCpiYoYValue);
        tvCpiYoYDate   = view.findViewById(R.id.tvCpiYoYDate);
        tvCpiYoYStatus = view.findViewById(R.id.tvCpiYoYStatus);
        viewCpiIndicatorDot = view.findViewById(R.id.viewCpiIndicatorDot);

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
        View dialogView = LayoutInflater.from(getContext()).inflate(R.layout.dialog_cpi_status, null);
        
        AlertDialog dialog = new AlertDialog.Builder(getContext())
                .setView(dialogView)
                .create();

        View btnDismiss = dialogView.findViewById(R.id.btnClose);
        if (btnDismiss != null) {
            btnDismiss.setOnClickListener(v -> dialog.dismiss());
        }

        dialog.show();
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
            color = Color.parseColor("#2196F3"); // Blue
        } else if (yoyChange <= 2.5) {
            status = "HEALTHY";
            color = Color.parseColor("#4CAF50"); // Green
        } else if (yoyChange <= 3.5) {
            status = "CAUTION";
            color = Color.parseColor("#FFEB3B"); // Yellow
        } else if (yoyChange <= 6.0) {
            status = "ELEVATED";
            color = Color.parseColor("#FF9800"); // Orange
        } else {
            status = "CRITICAL";
            color = Color.parseColor("#F44336"); // Red
        }

        // Revert badge background to Header Navy
        cardCpiYoY.setCardBackgroundColor(Color.parseColor("#1A1F2B")); 
        
        tvCpiYoYStatus.setText(status);
        tvCpiYoYStatus.setTextColor(Color.parseColor("#BBBBBB"));
        
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
}
