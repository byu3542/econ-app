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

public class TreasuryFragment extends Fragment {

    private EconomicViewModel viewModel;
    private LineChart yieldCurveChart;
    private LineChart tenYearTrendChart;
    private LineChart spreadChart;
    
    private CardView cardSpreadYoY;
    private TextView tvSpreadValue, tvIndicatorText;
    private View viewSpreadIndicatorDot;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_treasury, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(requireActivity()).get(EconomicViewModel.class);

        yieldCurveChart  = view.findViewById(R.id.yieldCurveChart);
        tenYearTrendChart = view.findViewById(R.id.tenYearTrendChart);
        spreadChart       = view.findViewById(R.id.spreadChart);
        
        cardSpreadYoY = view.findViewById(R.id.cardSpreadYoY);
        tvSpreadValue = view.findViewById(R.id.tvSpreadValue);
        tvIndicatorText = view.findViewById(R.id.tvIndicatorText);
        viewSpreadIndicatorDot = view.findViewById(R.id.viewSpreadIndicatorDot);

        ChartHelper.styleLineChart(yieldCurveChart,  "Treasury Yield Curve (Latest)", "Maturity (Years)", "Yield (%)");
        ChartHelper.styleLineChart(tenYearTrendChart, "10-Year Treasury",             "Date",              "Yield (%)");
        ChartHelper.styleLineChart(spreadChart,       "10Y-2Y Spread",                "Month",             "Spread (%)");

        // Add Y-Axis unit labeling
        ValueFormatter yieldFormatter = new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return String.format(Locale.US, "%.1f%%", value);
            }
        };
        yieldCurveChart.getAxisLeft().setValueFormatter(yieldFormatter);
        tenYearTrendChart.getAxisLeft().setValueFormatter(yieldFormatter);
        spreadChart.getAxisLeft().setValueFormatter(yieldFormatter);

        viewModel.getTreasuryData().observe(getViewLifecycleOwner(), data -> {
            if (data != null) {
                buildYieldCurveChart(data);
                build10YearTrendChart(data);
                updateSpreadIndicator(data);
            }
        });
        
        viewModel.getCalculatedSpreadData().observe(getViewLifecycleOwner(), data -> {
            if (data != null) {
                buildSpreadTrendChart(data);
            }
        });

        // Click listener to show benchmarks table
        cardSpreadYoY.setOnClickListener(v -> showTreasuryBenchmarks());
    }

    private void showTreasuryBenchmarks() {
        if (getContext() == null) return;
        View dialogView = LayoutInflater.from(getContext()).inflate(R.layout.dialog_treasury_status, null);
        
        AlertDialog dialog = new AlertDialog.Builder(getContext())
                .setView(dialogView)
                .create();

        View btnDismiss = dialogView.findViewById(R.id.btnClose);
        if (btnDismiss != null) {
            btnDismiss.setOnClickListener(v -> dialog.dismiss());
        }

        dialog.show();
    }

    private void updateSpreadIndicator(List<EconomicDataPoint> data) {
        EconomicDataPoint tenYear = EconomicViewModel.getLatest(data, "10 Year");
        EconomicDataPoint twoYear = EconomicViewModel.getLatest(data, "2 Year");

        if (tenYear != null && twoYear != null) {
            double spread = tenYear.getValue() - twoYear.getValue();
            tvSpreadValue.setText(String.format(Locale.US, "%.2f%%", spread));

            int dotColor;
            String status;

            if (spread >= 1.50) {
                dotColor = Color.parseColor("#4CAF50"); // Green
                status = "VERY HEALTHY";
            } else if (spread >= 0.50) {
                dotColor = Color.parseColor("#4CAF50"); // Green
                status = "HEALTHY";
            } else if (spread >= 0.00) {
                dotColor = Color.parseColor("#FFEB3B"); // Yellow
                status = "CAUTION";
            } else if (spread > -0.50) {
                dotColor = Color.parseColor("#FF9800"); // Orange
                status = "WARNING";
            } else {
                dotColor = Color.parseColor("#F44336"); // Red
                status = "DANGER";
            }

            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(dotColor);
            viewSpreadIndicatorDot.setBackground(dot);

            tvIndicatorText.setText(status);
            tvIndicatorText.setTextColor(Color.parseColor("#BBBBBB")); 

            cardSpreadYoY.setCardBackgroundColor(Color.parseColor("#1A1F2B"));
        }
    }

    private void buildSpreadTrendChart(List<EconomicDataPoint> data) {
        if (data.isEmpty()) return;

        List<Entry> entries = new ArrayList<>();
        final List<String> dates = new ArrayList<>();

        for (int i = 0; i < data.size(); i++) {
            EconomicDataPoint p = data.get(i);
            entries.add(new Entry(i, (float) p.getValue()));
            String d = p.getDate();
            if (d.length() >= 7) {
                dates.add(d.substring(5, 7) + "-" + d.substring(0, 4));
            } else {
                dates.add(d);
            }
        }

        spreadChart.getXAxis().setValueFormatter(new ValueFormatter() {
            @Override public String getFormattedValue(float value) {
                int idx = (int) value;
                return (idx >= 0 && idx < dates.size()) ? dates.get(idx) : "";
            }
        });

        LineDataSet dataSet = new LineDataSet(entries, "10Y-2Y Spread (%)");
        dataSet.setColor(Color.parseColor("#ff9800"));
        dataSet.setCircleColor(Color.parseColor("#ff9800"));
        dataSet.setLineWidth(3f);
        dataSet.setCircleRadius(4f);
        dataSet.setDrawValues(false);
        dataSet.setMode(LineDataSet.Mode.CUBIC_BEZIER);

        spreadChart.getAxisLeft().setDrawZeroLine(true);
        spreadChart.getAxisLeft().setZeroLineColor(Color.RED);
        spreadChart.getAxisLeft().setZeroLineWidth(1.5f);

        spreadChart.setData(new LineData(dataSet));
        spreadChart.invalidate();
    }

    private void buildYieldCurveChart(List<EconomicDataPoint> data) {
        String latestDate = null;
        for (EconomicDataPoint p : data) {
            if (latestDate == null || p.getDate().compareTo(latestDate) > 0) {
                latestDate = p.getDate();
            }
        }
        if (latestDate == null) return;

        final double[] maturityYears = {0.083, 0.25, 0.5, 1.0, 2.0, 5.0, 10.0, 30.0};
        final String[] maturityNames = {"1M","3M","6M","1Y","2Y","5Y","10Y","30Y"};
        final String[] seriesNames   = {"1 Month","3 Month","6 Month","1 Year","2 Year","5 Year","10 Year","30 Year"};

        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < seriesNames.length; i++) {
            for (EconomicDataPoint p : data) {
                if (p.getSeries().equals(seriesNames[i]) && p.getDate().equals(latestDate)) {
                    entries.add(new Entry((float) maturityYears[i], (float) p.getValue()));
                    break;
                }
            }
        }

        if (entries.isEmpty()) return;

        LineDataSet dataSet = new LineDataSet(entries, "Yield (%) — " + latestDate);
        dataSet.setColor(Color.parseColor("#1a73e8"));
        dataSet.setCircleColor(Color.parseColor("#1a73e8"));
        dataSet.setLineWidth(3f);
        dataSet.setCircleRadius(5f);
        dataSet.setValueTextSize(10f);
        dataSet.setValueFormatter(new ValueFormatter() {
            @Override public String getFormattedValue(float value) {
                return String.format(Locale.US, "%.2f", value);
            }
        });
        dataSet.setMode(LineDataSet.Mode.CUBIC_BEZIER);

        yieldCurveChart.getXAxis().setValueFormatter(new ValueFormatter() {
            @Override public String getFormattedValue(float value) {
                for (int i = 0; i < maturityYears.length; i++) {
                    if (Math.abs(maturityYears[i] - value) < 0.05) return maturityNames[i];
                }
                return "";
            }
        });

        yieldCurveChart.setData(new LineData(dataSet));
        yieldCurveChart.invalidate();
    }

    private void build10YearTrendChart(List<EconomicDataPoint> data) {
        List<EconomicDataPoint> tenYear = EconomicViewModel.filterBySeries(data, "10 Year");
        if (tenYear.isEmpty()) return;

        List<Entry> entries = new ArrayList<>();
        final List<String> dates = new ArrayList<>();

        for (int i = 0; i < tenYear.size(); i++) {
            EconomicDataPoint p = tenYear.get(i);
            entries.add(new Entry(i, (float) p.getValue()));
            dates.add(p.getDate().length() >= 7 ? p.getDate().substring(5, 7) + "/" + p.getDate().substring(0, 4) : p.getDate());
        }

        tenYearTrendChart.getXAxis().setValueFormatter(new ValueFormatter() {
            @Override public String getFormattedValue(float value) {
                int idx = (int) value;
                return (idx >= 0 && idx < dates.size()) ? dates.get(idx) : "";
            }
        });

        LineDataSet dataSet = new LineDataSet(entries, "10-Year Yield (%)");
        dataSet.setColor(Color.parseColor("#34a853"));
        dataSet.setCircleColor(Color.parseColor("#34a853"));
        dataSet.setLineWidth(3f);
        dataSet.setCircleRadius(4f);
        dataSet.setDrawValues(false);

        tenYearTrendChart.setData(new LineData(dataSet));
        tenYearTrendChart.invalidate();
    }
}
