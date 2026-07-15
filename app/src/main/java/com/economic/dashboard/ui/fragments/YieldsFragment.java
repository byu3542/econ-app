package com.economic.dashboard.ui.fragments;

import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.economic.dashboard.databinding.FragmentYieldsBinding;
import com.economic.dashboard.R;
import com.economic.dashboard.models.EconomicDataPoint;
import com.economic.dashboard.ui.EconomicViewModel;
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

public class YieldsFragment extends Fragment {

    private FragmentYieldsBinding binding;

    private EconomicViewModel viewModel;
    private LineChart yieldCurveChart;
    private View row1M, row3M, row2Y, row10Y, row30Y;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentYieldsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(requireActivity()).get(EconomicViewModel.class);

        yieldCurveChart = binding.yieldCurveChart;
        com.economic.dashboard.analyst.AskAnalyst.attachChartExplain(
                yieldCurveChart, requireActivity(), "the current Treasury yield curve across maturities");
        styleChart(yieldCurveChart);
        yieldCurveChart.getAxisLeft().setValueFormatter(new ValueFormatter() {
            @Override public String getFormattedValue(float value) {
                return String.format(Locale.US, "%.1f%%", value);
            }
        });

        // <include> entries — binding exposes the included layout's binding,
        // so getRoot() gives the View the rest of this class expects.
        row1M  = binding.row1M.getRoot();
        row3M  = binding.row3M.getRoot();
        row2Y  = binding.row2Y.getRoot();
        row10Y = binding.row10Y.getRoot();
        row30Y = binding.row30Y.getRoot();
        setupTreasuryRow(row1M,  "1 Month");
        setupTreasuryRow(row3M,  "3 Month");
        setupTreasuryRow(row2Y,  "2 Year");
        setupTreasuryRow(row10Y, "10 Year");
        setupTreasuryRow(row30Y, "30 Year");
        applyZebra();

        viewModel.getTreasuryData().observe(getViewLifecycleOwner(), data -> {
            if (data != null) { buildYieldCurveChart(data); updateTreasury(data); }
        });
    }

    private void buildYieldCurveChart(List<EconomicDataPoint> data) {
        String latestDate = null;
        for (EconomicDataPoint p : data)
            if (latestDate == null || p.getDate().compareTo(latestDate) > 0) latestDate = p.getDate();
        if (latestDate == null) return;

        final double[] maturityYears = {0.083, 0.25, 0.5, 1.0, 2.0, 5.0, 10.0, 30.0};
        final String[] maturityNames = {"1M","3M","6M","1Y","2Y","5Y","10Y","30Y"};
        final String[] seriesNames   = {"1 Month","3 Month","6 Month","1 Year","2 Year","5 Year","10 Year","30 Year"};
        final String finalLatestDate = latestDate;

        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < seriesNames.length; i++) {
            for (EconomicDataPoint p : data) {
                if (p.getSeries().equals(seriesNames[i]) && p.getDate().equals(finalLatestDate)) {
                    entries.add(new Entry((float) maturityYears[i], (float) p.getValue()));
                    break;
                }
            }
        }

        LineDataSet ds = new LineDataSet(entries, "Yield (%) — " + latestDate);
        ds.setColor(Color.parseColor("#1a73e8")); ds.setLineWidth(1.5f);
        ds.setDrawCircles(false); ds.setDrawValues(false); ds.setMode(LineDataSet.Mode.CUBIC_BEZIER);

        yieldCurveChart.getXAxis().setValueFormatter(new ValueFormatter() {
            @Override public String getFormattedValue(float value) {
                for (int i = 0; i < maturityYears.length; i++)
                    if (Math.abs(maturityYears[i] - value) < 0.05) return maturityNames[i];
                return "";
            }
        });

        yieldCurveChart.setData(new LineData(ds)); yieldCurveChart.invalidate();
    }

    private void setupTreasuryRow(View row, String label) {
        if (row == null) return;
        TextView tv = row.findViewById(R.id.tvMaturity);
        if (tv != null) tv.setText(label);
    }

    /** Zebra striping — alternate rows get the warm alt background. */
    private void applyZebra() {
        View[] rowsInOrder = {row1M, row3M, row2Y, row10Y, row30Y};
        for (int i = 0; i < rowsInOrder.length; i++) {
            if (rowsInOrder[i] != null && i % 2 == 1)
                rowsInOrder[i].setBackgroundColor(
                        ContextCompat.getColor(requireContext(), R.color.row_alt_bg));
        }
    }

    private void updateTreasury(List<EconomicDataPoint> data) {
        setTreasuryRate(data, "1 Month", row1M);
        setTreasuryRate(data, "3 Month", row3M);
        setTreasuryRate(data, "2 Year",  row2Y);
        setTreasuryRate(data, "10 Year", row10Y);
        setTreasuryRate(data, "30 Year", row30Y);
    }

    private void setTreasuryRate(List<EconomicDataPoint> data, String series, View row) {
        if (row == null) return;
        List<EconomicDataPoint> rows = EconomicViewModel.filterBySeries(data, series);
        TextView valView    = row.findViewById(R.id.tvYield);
        TextView dateView   = row.findViewById(R.id.tvDate);
        TextView changeView = row.findViewById(R.id.tvChange);
        if (!rows.isEmpty()) {
            EconomicDataPoint p = rows.get(rows.size() - 1);
            if (valView  != null) valView.setText(String.format(Locale.US, "%.2f%%", p.getValue()));
            if (dateView != null) dateView.setText(p.getDate());
            if (changeView != null && rows.size() >= 2) {
                double bps = (p.getValue() - rows.get(rows.size() - 2).getValue()) * 100.0;
                if (Math.abs(bps) < 0.5) {
                    changeView.setText("—");
                    changeView.setTextColor(ContextCompat.getColor(
                            requireContext(), R.color.text_muted));
                } else {
                    changeView.setText(String.format(Locale.US, "%s%.0fbp",
                            bps > 0 ? "▲" : "▼", Math.abs(bps)));
                    changeView.setTextColor(ContextCompat.getColor(requireContext(),
                            bps > 0 ? R.color.delta_bad : R.color.delta_good));
                }
            }
        } else {
            if (valView  != null) valView.setText("—");
            if (dateView != null) dateView.setText("");
            if (changeView != null) changeView.setText("");
        }
    }

    private void styleChart(LineChart chart) {
        int grid = Color.argb(0x14, 0xFF, 0xFF, 0xFF);
        chart.setBackgroundColor(Color.parseColor("#1C2236")); chart.setDrawGridBackground(false);
        chart.setDrawBorders(false); chart.setTouchEnabled(true); chart.setDragEnabled(true);
        chart.setScaleEnabled(true); chart.setPinchZoom(true); chart.getDescription().setEnabled(false);
        chart.setNoDataText("Loading yield data…");
        chart.setNoDataTextColor(Color.parseColor("#8899BB"));
        chart.setExtraBottomOffset(8f);
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
