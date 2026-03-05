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

public class EmploymentFragment extends Fragment {

    private EconomicViewModel viewModel;
    private LineChart unemploymentChart;
    private LineChart laborParticipationChart;
    
    private CardView cardUnemploymentStatus;
    private TextView tvUnempValue, tvUnempStatus, tvUnempLowInfo;
    private View viewUnempIndicatorDot;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_employment, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(requireActivity()).get(EconomicViewModel.class);

        unemploymentChart       = view.findViewById(R.id.unemploymentChart);
        laborParticipationChart = view.findViewById(R.id.laborParticipationChart);
        
        cardUnemploymentStatus = view.findViewById(R.id.cardUnemploymentStatus);
        tvUnempValue           = view.findViewById(R.id.tvUnempValue);
        tvUnempStatus          = view.findViewById(R.id.tvUnempStatus);
        tvUnempLowInfo         = view.findViewById(R.id.tvUnempLowInfo);
        viewUnempIndicatorDot  = view.findViewById(R.id.viewUnempIndicatorDot);

        ChartHelper.styleLineChart(unemploymentChart,       "Unemployment Rate Trend",           "Month", "Rate (%)");
        ChartHelper.styleLineChart(laborParticipationChart, "Labor Force Participation Rate",     "Month", "Rate (%)");

        // Add Y-Axis unit labeling
        ValueFormatter percentFormatter = new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return String.format(Locale.US, "%.1f%%", value);
            }
        };
        unemploymentChart.getAxisLeft().setValueFormatter(percentFormatter);
        laborParticipationChart.getAxisLeft().setValueFormatter(percentFormatter);

        viewModel.getEmploymentData().observe(getViewLifecycleOwner(), data -> {
            if (data != null) {
                buildChart(data, "Unemployment Rate", unemploymentChart, "#ea4335");
                buildChart(data, "Labor Force Participation Rate", laborParticipationChart, "#4285f4");
                calculateUnemploymentStatus(data);
            }
        });

        // Click listener to show benchmarks table
        cardUnemploymentStatus.setOnClickListener(v -> showUnemploymentBenchmarks());
    }

    private void showUnemploymentBenchmarks() {
        if (getContext() == null) return;
        View dialogView = LayoutInflater.from(getContext()).inflate(R.layout.dialog_unemployment_status, null);
        
        AlertDialog dialog = new AlertDialog.Builder(getContext())
                .setView(dialogView)
                .create();

        // Use the custom layout's "DISMISS" text as the close button
        View btnDismiss = dialogView.findViewById(R.id.btnClose);
        if (btnDismiss != null) {
            btnDismiss.setOnClickListener(v -> dialog.dismiss());
        }

        dialog.show();
    }

    private void calculateUnemploymentStatus(List<EconomicDataPoint> data) {
        List<EconomicDataPoint> rows = EconomicViewModel.filterBySeries(data, "Unemployment Rate");
        if (rows.isEmpty()) return;

        // Current Rate
        EconomicDataPoint current = rows.get(rows.size() - 1);
        double currentRate = current.getValue();

        // 12-Month Low (taking last 12 points)
        double low = Double.MAX_VALUE;
        int startIndex = Math.max(0, rows.size() - 12);
        for (int i = startIndex; i < rows.size(); i++) {
            low = Math.min(low, rows.get(i).getValue());
        }

        double riseFromLow = currentRate - low;

        tvUnempValue.setText(String.format(Locale.US, "%.1f%%", currentRate));
        tvUnempLowInfo.setText(String.format(Locale.US, "12-month low: %.1f%% (Rise: +%.1f%%)", low, riseFromLow));

        String status;
        int dotColor;

        if (currentRate > 7.0) {
            status = "RECESSION TERRITORY";
            dotColor = Color.parseColor("#F44336"); // Red
        } else if (currentRate > 5.5) {
            status = "ELEVATED";
            dotColor = Color.parseColor("#FF9800"); // Orange
        } else if (riseFromLow >= 0.5) {
            status = "RECESSION SIGNAL (SAHM RULE)";
            dotColor = Color.parseColor("#F44336"); // Red
        } else if (currentRate >= 3.5 && currentRate <= 4.5) {
            if (riseFromLow >= 0.3) {
                status = "WATCH CLOSELY";
                dotColor = Color.parseColor("#FFEB3B"); // Yellow
            } else {
                status = "HEALTHY";
                dotColor = Color.parseColor("#4CAF50"); // Green
            }
        } else {
            // General catch-all for rates outside 3.5-4.5 but under thresholds
            status = "STABLE";
            dotColor = Color.parseColor("#4CAF50");
        }

        tvUnempStatus.setText(status);
        tvUnempStatus.setTextColor(Color.parseColor("#BBBBBB"));
        
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(dotColor);
        viewUnempIndicatorDot.setBackground(dot);

        // Keep Badge Navy
        cardUnemploymentStatus.setCardBackgroundColor(Color.parseColor("#1A1F2B"));
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
