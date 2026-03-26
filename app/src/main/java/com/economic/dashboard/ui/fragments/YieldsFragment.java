package com.economic.dashboard.ui.fragments;

import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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

/**
 * Yields sub-tab inside Markets.
 * Displays the Current Yield Curve chart and the Treasury Yields table.
 */
public class YieldsFragment extends Fragment {

    private EconomicViewModel viewModel;
    private LineChart yieldCurveChart;

    // Treasury table rows
    private View row1M, row3M, row2Y, row10Y, row30Y;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_yields, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(requireActivity()).get(EconomicViewModel.class);

        // ── Yield curve chart ────────────────────────────────────────────────
        yieldCurveChart = view.findViewById(R.id.yieldCurveChart);
        ChartHelper.styleLineChart(yieldCurveChart, "Current Yield Curve", "Maturity", "Yield (%)");

        ValueFormatter yieldFormatter = new ValueFormatter() {
            @Override public String getFormattedValue(float value) {
                return String.format(Locale.US, "%.1f%%", value);
            }
        };
        yieldCurveChart.getAxisLeft().setValueFormatter(yieldFormatter);

        // ── Treasury table rows ──────────────────────────────────────────────
        row1M  = view.findViewById(R.id.row1M);
        row3M  = view.findViewById(R.id.row3M);
        row2Y  = view.findViewById(R.id.row2Y);
        row10Y = view.findViewById(R.id.row10Y);
        row30Y = view.findViewById(R.id.row30Y);
        setupTreasuryRow(row1M,  "1 Month");
        setupTreasuryRow(row3M,  "3 Month");
        setupTreasuryRow(row2Y,  "2 Year");
        setupTreasuryRow(row10Y, "10 Year");
        setupTreasuryRow(row30Y, "30 Year");

        // ── Observe treasury data ────────────────────────────────────────────
        viewModel.getTreasuryData().observe(getViewLifecycleOwner(), data -> {
            if (data != null) {
                buildYieldCurveChart(data);
                updateTreasury(data);
            }
        });
    }

    // ── Yield Curve chart ────────────────────────────────────────────────────

    private void buildYieldCurveChart(List<EconomicDataPoint> data) {
        String latestDate = null;
        for (EconomicDataPoint p : data) {
            if (latestDate == null || p.getDate().compareTo(latestDate) > 0) latestDate = p.getDate();
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

        LineDataSet dataSet = new LineDataSet(entries, "Yield (%) \u2014 " + latestDate);
        dataSet.setColor(Color.parseColor("#1a73e8"));
        dataSet.setLineWidth(1.5f);
        dataSet.setDrawCircles(false);
        dataSet.setDrawValues(false);
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

    // ── Treasury table helpers (moved from DashboardFragment) ────────────────

    private void setupTreasuryRow(View row, String label) {
        if (row == null) return;
        TextView tv = row.findViewById(R.id.tvMaturity);
        if (tv != null) tv.setText(label);
    }

    private void updateTreasury(List<EconomicDataPoint> data) {
        setTreasuryRate(data, "1 Month",  row1M);
        setTreasuryRate(data, "3 Month",  row3M);
        setTreasuryRate(data, "2 Year",   row2Y);
        setTreasuryRate(data, "10 Year",  row10Y);
        setTreasuryRate(data, "30 Year",  row30Y);
    }

    private void setTreasuryRate(List<EconomicDataPoint> data, String series, View row) {
        if (row == null) return;
        EconomicDataPoint p = EconomicViewModel.getLatest(data, series);
        TextView valView  = row.findViewById(R.id.tvYield);
        TextView dateView = row.findViewById(R.id.tvDate);
        if (p != null) {
            valView.setText(String.format(Locale.US, "%.2f%%", p.getValue()));
            dateView.setText(p.getDate());
        } else {
            valView.setText("\u2014");
            dateView.setText("");
        }
    }
}
