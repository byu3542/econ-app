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

public class GdpFragment extends Fragment {

    private EconomicViewModel viewModel;
    private LineChart gdpChart;
    private TextView tvGdpLatest, tvGdpDesc, tvGdpStatusText;
    private View viewGdpIndicatorDot;
    private CardView cardGdpStatus;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_gdp, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(requireActivity()).get(EconomicViewModel.class);

        gdpChart   = view.findViewById(R.id.gdpChart);
        tvGdpLatest = view.findViewById(R.id.tvGdpLatest);
        tvGdpDesc   = view.findViewById(R.id.tvGdpDesc);
        tvGdpStatusText = view.findViewById(R.id.tvGdpStatusText);
        viewGdpIndicatorDot = view.findViewById(R.id.viewGdpIndicatorDot);
        cardGdpStatus = view.findViewById(R.id.cardGdpStatus);

        ChartHelper.styleLineChart(gdpChart, "GDP Growth Rate", "Quarter", "Growth (%)");
        
        // Add Y-Axis unit labeling
        gdpChart.getAxisLeft().setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return String.format(Locale.US, "%.1f%%", value);
            }
        });

        viewModel.getGdpData().observe(getViewLifecycleOwner(), data -> {
            if (data != null) {
                buildGdpChart(data);
                updateGdpIndicator(data);
            }
        });

        // Click listener to show GDP specific benchmarks table
        cardGdpStatus.setOnClickListener(v -> showGdpBenchmarks());
    }

    private void showGdpBenchmarks() {
        if (getContext() == null) return;
        View dialogView = LayoutInflater.from(getContext()).inflate(R.layout.dialog_gdp_status, null);
        
        AlertDialog dialog = new AlertDialog.Builder(getContext())
                .setView(dialogView)
                .create();

        View btnDismiss = dialogView.findViewById(R.id.btnClose);
        if (btnDismiss != null) {
            btnDismiss.setOnClickListener(v -> dialog.dismiss());
        }

        dialog.show();
    }

    private void updateGdpIndicator(List<EconomicDataPoint> data) {
        final String GDP_SERIES = "Gross domestic product";
        List<EconomicDataPoint> gdpRows = EconomicViewModel.filterBySeries(data, GDP_SERIES);
        if (gdpRows.isEmpty()) return;

        // Calculate 4-Quarter Rolling Average
        double sum = 0;
        int count = 0;
        int startIndex = Math.max(0, gdpRows.size() - 4);
        for (int i = startIndex; i < gdpRows.size(); i++) {
            sum += gdpRows.get(i).getValue();
            count++;
        }
        double rollingAvg = (count > 0) ? sum / count : 0;

        // Set main value to rolling average
        tvGdpLatest.setText(String.format(Locale.US, "%.2f%%", rollingAvg));
        tvGdpDesc.setText("4-Quarter Rolling Average");

        String status;
        int dotColor;

        // Apply new benchmark logic strictly:
        if (rollingAvg < 0) {
            status = "RECESSION";
            dotColor = Color.parseColor("#F44336"); // Red
        } else if (rollingAvg <= 1.0) {
            status = "STAGNATION";
            dotColor = Color.parseColor("#FF9800"); // Orange
        } else if (rollingAvg <= 2.0) {
            status = "BELOW POTENTIAL";
            dotColor = Color.parseColor("#FFEB3B"); // Yellow
        } else if (rollingAvg <= 3.0) {
            status = "AT POTENTIAL";
            dotColor = Color.parseColor("#4CAF50"); // Green
        } else if (rollingAvg <= 4.0) {
            status = "ABOVE POTENTIAL";
            dotColor = Color.parseColor("#2196F3"); // Blue
        } else {
            status = "OVERHEATING RISK";
            dotColor = Color.parseColor("#9C27B0"); // Purple
        }

        tvGdpStatusText.setText(status);
        tvGdpStatusText.setTextColor(Color.parseColor("#BBBBBB"));
        
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(dotColor);
        viewGdpIndicatorDot.setBackground(dot);
        
        cardGdpStatus.setCardBackgroundColor(Color.parseColor("#1A1F2B"));
    }

    private void buildGdpChart(List<EconomicDataPoint> data) {
        final String GDP_SERIES = "Gross domestic product";
        List<EconomicDataPoint> gdpRows = EconomicViewModel.filterBySeries(data, GDP_SERIES);

        if (gdpRows.isEmpty() && !data.isEmpty()) {
            String firstSeries = data.get(0).getSeries();
            gdpRows = EconomicViewModel.filterBySeries(data, firstSeries);
        }

        if (gdpRows.isEmpty()) return;

        List<Entry> entries = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        for (int i = 0; i < gdpRows.size(); i++) {
            entries.add(new Entry(i, (float) gdpRows.get(i).getValue()));
            labels.add(gdpRows.get(i).getDate());
        }

        gdpChart.getXAxis().setValueFormatter(new ValueFormatter() {
            @Override public String getFormattedValue(float value) {
                int idx = (int) value;
                return (idx >= 0 && idx < labels.size()) ? labels.get(idx) : "";
            }
        });

        LineDataSet dataSet = new LineDataSet(entries, "GDP Growth (%)");
        dataSet.setColor(Color.parseColor("#1a73e8"));
        dataSet.setCircleColor(Color.parseColor("#1a73e8"));
        dataSet.setLineWidth(3f);
        dataSet.setCircleRadius(5f);
        dataSet.setDrawValues(true);
        dataSet.setValueTextSize(9f);
        dataSet.setValueFormatter(new ValueFormatter() {
            @Override public String getFormattedValue(float value) {
                return String.format(Locale.US, "%.1f", value);
            }
        });

        dataSet.setMode(LineDataSet.Mode.LINEAR);

        gdpChart.setData(new LineData(dataSet));
        gdpChart.invalidate();
    }
}
