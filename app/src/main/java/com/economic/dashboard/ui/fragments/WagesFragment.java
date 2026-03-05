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

public class WagesFragment extends Fragment {

    private EconomicViewModel viewModel;
    private LineChart hourlyWageChart;
    private LineChart comparisonChart;
    
    private CardView cardWageYoY;
    private TextView tvWageYoYValue, tvWageYoYStatus;
    private View viewWageIndicatorDot;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_wages, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(requireActivity()).get(EconomicViewModel.class);

        hourlyWageChart = view.findViewById(R.id.hourlyWageChart);
        comparisonChart = view.findViewById(R.id.comparisonChart);
        
        cardWageYoY     = view.findViewById(R.id.cardWageYoY);
        tvWageYoYValue  = view.findViewById(R.id.tvWageYoYValue);
        tvWageYoYStatus = view.findViewById(R.id.tvWageYoYStatus);
        viewWageIndicatorDot = view.findViewById(R.id.viewWageIndicatorDot);

        ChartHelper.styleLineChart(hourlyWageChart, "Average Hourly Earnings (Private Sector)", "Month", "Wage ($)");
        ChartHelper.styleLineChart(comparisonChart,  "Inflation vs Wage Growth (Indexed to 100)", "Month", "Index");

        // Add Y-Axis unit labeling
        hourlyWageChart.getAxisLeft().setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return String.format(Locale.US, "$%.2f", value);
            }
        });
        
        comparisonChart.getAxisLeft().setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return String.format(Locale.US, "%.0f", value);
            }
        });

        viewModel.getWageData().observe(getViewLifecycleOwner(), wageData -> {
            if (wageData != null) {
                buildHourlyChart(wageData);
                calculateRealWageGrowth();
            }
            buildComparisonIfReady();
        });

        viewModel.getCpiData().observe(getViewLifecycleOwner(), cpiData -> {
            if (cpiData != null) {
                calculateRealWageGrowth();
            }
            buildComparisonIfReady();
        });

        // Click listener to show benchmarks table
        cardWageYoY.setOnClickListener(v -> showWagesBenchmarks());
    }

    private void showWagesBenchmarks() {
        if (getContext() == null) return;
        View dialogView = LayoutInflater.from(getContext()).inflate(R.layout.dialog_wages_status, null);
        
        AlertDialog dialog = new AlertDialog.Builder(getContext())
                .setView(dialogView)
                .create();

        View btnDismiss = dialogView.findViewById(R.id.btnClose);
        if (btnDismiss != null) {
            btnDismiss.setOnClickListener(v -> dialog.dismiss());
        }

        dialog.show();
    }

    private void calculateRealWageGrowth() {
        List<EconomicDataPoint> wageData = viewModel.getWageData().getValue();
        List<EconomicDataPoint> cpiData = viewModel.getCpiData().getValue();

        if (wageData == null || cpiData == null) return;

        List<EconomicDataPoint> wageRows = EconomicViewModel.filterBySeries(wageData, "Average Hourly Earnings - Private");
        List<EconomicDataPoint> cpiRows = EconomicViewModel.filterBySeries(cpiData, "CPI-U All Items");

        if (wageRows.size() < 13 || cpiRows.size() < 13) return;

        // Calculate Wage YoY
        EconomicDataPoint latestWage = wageRows.get(wageRows.size() - 1);
        EconomicDataPoint yearAgoWage = wageRows.get(wageRows.size() - 13);
        double wageYoY = ((latestWage.getValue() - yearAgoWage.getValue()) / yearAgoWage.getValue()) * 100.0;

        // Calculate CPI YoY
        EconomicDataPoint latestCpi = cpiRows.get(cpiRows.size() - 1);
        EconomicDataPoint yearAgoCpi = cpiRows.get(cpiRows.size() - 13);
        double cpiYoY = ((latestCpi.getValue() - yearAgoCpi.getValue()) / yearAgoCpi.getValue()) * 100.0;

        // "Real Wage Growth" calculation (Spread)
        double spread = wageYoY - cpiYoY;

        // Display the calculated difference as the main value
        tvWageYoYValue.setText(String.format(Locale.US, "%.2f%%", spread));

        String reading;
        int dotColor;

        if (spread < -2.0) {
            dotColor = Color.parseColor("#F44336"); // Red
            reading = "FALLING BEHIND FAST";
        } else if (spread < 0.0) {
            dotColor = Color.parseColor("#FF9800"); // Orange
            reading = "LOSING GROUND";
        } else if (spread <= 1.0) {
            dotColor = Color.parseColor("#FFEB3B"); // Yellow
            reading = "BARELY AHEAD";
        } else if (spread <= 2.5) {
            dotColor = Color.parseColor("#4CAF50"); // Green
            reading = "HEALTHY";
        } else {
            dotColor = Color.parseColor("#2196F3"); // Blue
            reading = "STRONG";
        }

        tvWageYoYStatus.setText(reading);
        tvWageYoYStatus.setTextColor(Color.parseColor("#BBBBBB"));
        
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(dotColor);
        viewWageIndicatorDot.setBackground(dot);

        // Keep Badge Navy
        cardWageYoY.setCardBackgroundColor(Color.parseColor("#1A1F2B"));
    }

    private void buildHourlyChart(List<EconomicDataPoint> data) {
        List<EconomicDataPoint> rows = EconomicViewModel.filterBySeries(data, "Average Hourly Earnings - Private");
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

        hourlyWageChart.getXAxis().setValueFormatter(new ValueFormatter() {
            @Override public String getFormattedValue(float value) {
                int idx = (int) value;
                return (idx >= 0 && idx < dates.size()) ? dates.get(idx) : "";
            }
        });

        LineDataSet dataSet = new LineDataSet(entries, "Avg Hourly Wage ($)");
        dataSet.setColor(Color.parseColor("#9c27b0"));
        dataSet.setCircleColor(Color.parseColor("#9c27b0"));
        dataSet.setLineWidth(3f);
        dataSet.setCircleRadius(4f);
        dataSet.setDrawValues(false);
        dataSet.setMode(LineDataSet.Mode.CUBIC_BEZIER);

        hourlyWageChart.setData(new LineData(dataSet));
        hourlyWageChart.invalidate();
    }

    private void buildComparisonIfReady() {
        List<EconomicDataPoint> cpiData  = viewModel.getCpiData().getValue();
        List<EconomicDataPoint> wageData = viewModel.getWageData().getValue();

        if (cpiData == null || wageData == null) return;

        List<EconomicDataPoint> cpiRows  = EconomicViewModel.filterBySeries(cpiData,  "CPI-U All Items");
        List<EconomicDataPoint> wageRows = EconomicViewModel.filterBySeries(wageData, "Average Hourly Earnings - Private");

        if (cpiRows.isEmpty() || wageRows.isEmpty()) return;

        double cpiBase  = cpiRows.get(0).getValue();
        double wageBase = wageRows.get(0).getValue();

        List<Entry> cpiEntries  = new ArrayList<>();
        List<Entry> wageEntries = new ArrayList<>();
        final List<String> dates = new ArrayList<>();

        for (int i = 0; i < cpiRows.size(); i++) {
            String date = cpiRows.get(i).getDate();
            double indexedCpi = (cpiRows.get(i).getValue() / cpiBase) * 100.0;
            cpiEntries.add(new Entry(i, (float) indexedCpi));
            
            if (date.length() >= 7) {
                String year = date.substring(0, 4);
                String month = date.substring(5, 7);
                dates.add(month + "-" + year);
            } else {
                dates.add(date);
            }

            for (EconomicDataPoint w : wageRows) {
                if (w.getDate().equals(date)) {
                    double indexedWage = (w.getValue() / wageBase) * 100.0;
                    wageEntries.add(new Entry(i, (float) indexedWage));
                    break;
                }
            }
        }

        comparisonChart.getXAxis().setValueFormatter(new ValueFormatter() {
            @Override public String getFormattedValue(float value) {
                int idx = (int) value;
                return (idx >= 0 && idx < dates.size()) ? dates.get(idx) : "";
            }
        });

        LineDataSet cpiSet = new LineDataSet(cpiEntries, "Consumer Prices (CPI)");
        cpiSet.setColor(Color.parseColor("#fbbc04"));
        cpiSet.setCircleColor(Color.parseColor("#fbbc04"));
        cpiSet.setLineWidth(3f);
        cpiSet.setCircleRadius(3f);
        cpiSet.setDrawValues(false);

        LineDataSet wageSet = new LineDataSet(wageEntries, "Average Wages");
        wageSet.setColor(Color.parseColor("#9c27b0"));
        wageSet.setCircleColor(Color.parseColor("#9c27b0"));
        wageSet.setLineWidth(3f);
        wageSet.setCircleRadius(3f);
        wageSet.setDrawValues(false);

        LineData lineData = new LineData(cpiSet, wageSet);
        comparisonChart.setData(lineData);
        comparisonChart.invalidate();
    }
}
