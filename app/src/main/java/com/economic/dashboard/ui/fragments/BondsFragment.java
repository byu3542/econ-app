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
import androidx.cardview.widget.CardView;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.economic.dashboard.databinding.FragmentBondsBinding;
import com.economic.dashboard.models.EconomicDataPoint;
import com.economic.dashboard.ui.EconomicViewModel;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.LimitLine;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.ValueFormatter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class BondsFragment extends Fragment {

    private EconomicViewModel viewModel;
    private FragmentBondsBinding binding;
    private CardView cardBaa, cardHy;
    private TextView tvBaaValue, tvBaaStatus, tvHyValue, tvHyStatus;
    private View viewBaaDot, viewHyDot;
    private LineChart swappableChart;
    private TextView tvChartTitle;
    private String activeCard = "baa";
    private List<EconomicDataPoint> currentBaaData;
    private List<EconomicDataPoint> currentHyData;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentBondsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(requireActivity()).get(EconomicViewModel.class);

        cardBaa = binding.cardBaa; cardHy = binding.cardHy;
        tvBaaValue = binding.tvBaaValue; tvBaaStatus = binding.tvBaaStatus;
        tvHyValue = binding.tvHyValue; tvHyStatus = binding.tvHyStatus;
        viewBaaDot = binding.viewBaaDot; viewHyDot = binding.viewHyDot;
        swappableChart = binding.swappableChart; tvChartTitle = binding.tvChartTitle;

        styleChart(swappableChart); addAllBenchmarks(swappableChart);

        cardBaa.setOnClickListener(v -> { activeCard = "baa"; setActiveCard("baa"); buildSwappableChart(); });
        cardHy.setOnClickListener(v -> { activeCard = "hy"; setActiveCard("hy"); buildSwappableChart(); });
        setActiveCard("baa");

        viewModel.getBaaSpreadData().observe(getViewLifecycleOwner(), data -> {
            if (data != null) { currentBaaData = data; updateBaaCard(data); buildSwappableChart(); }
        });
        viewModel.getHySpreadData().observe(getViewLifecycleOwner(), data -> {
            if (data != null) { currentHyData = data; updateHyCard(data); buildSwappableChart(); }
        });
    }

    private void updateBaaCard(List<EconomicDataPoint> data) {
        List<EconomicDataPoint> rows = EconomicViewModel.filterBySeries(data, "BAA Corporate Spread");
        if (rows.isEmpty()) return;
        double latest = rows.get(rows.size()-1).getValue();
        tvBaaValue.setText(String.format(Locale.US, "%.2f%%", latest));
        String status; int color;
        if (latest < 1.5) { status = "TIGHT"; color = Color.parseColor("#4CAF50"); }
        else if (latest < 2.5) { status = "NORMAL"; color = Color.parseColor("#2196F3"); }
        else if (latest < 3.5) { status = "ELEVATED"; color = Color.parseColor("#FF9800"); }
        else { status = "DISTRESS"; color = Color.parseColor("#F44336"); }
        tvBaaStatus.setText(status); setDot(viewBaaDot, color);
    }

    private void updateHyCard(List<EconomicDataPoint> data) {
        List<EconomicDataPoint> rows = EconomicViewModel.filterBySeries(data, "High Yield Spread");
        if (rows.isEmpty()) return;
        double latest = rows.get(rows.size()-1).getValue();
        tvHyValue.setText(String.format(Locale.US, "%.2f%%", latest));
        String status; int color;
        if (latest < 3) { status = "TIGHT"; color = Color.parseColor("#4CAF50"); }
        else if (latest < 5) { status = "NORMAL"; color = Color.parseColor("#2196F3"); }
        else if (latest < 8) { status = "ELEVATED"; color = Color.parseColor("#FF9800"); }
        else { status = "DISTRESS"; color = Color.parseColor("#F44336"); }
        tvHyStatus.setText(status); setDot(viewHyDot, color);
    }

    private void setActiveCard(String which) {
        cardBaa.setForeground("baa".equals(which) ? makeActiveBorder() : null);
        cardHy.setForeground("hy".equals(which) ? makeActiveBorder() : null);
        tvChartTitle.setText("hy".equals(which) ? "High Yield Spread" : "BAA Corporate Spread");
    }

    private GradientDrawable makeActiveBorder() {
        float d = getResources().getDisplayMetrics().density;
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(12*d); gd.setStroke((int)(1.5f*d), Color.parseColor("#C8A84B")); gd.setColor(Color.TRANSPARENT);
        return gd;
    }

    private void buildSwappableChart() {
        if (swappableChart == null) return;
        List<EconomicDataPoint> data; String seriesName, lineColor;
        if ("hy".equals(activeCard)) { data = currentHyData; seriesName = "High Yield Spread"; lineColor = "#F57C00"; }
        else { data = currentBaaData; seriesName = "BAA Corporate Spread"; lineColor = "#1976D2"; }
        if (data == null) return;
        List<EconomicDataPoint> rows = EconomicViewModel.filterBySeries(data, seriesName);
        if (rows.isEmpty()) return;
        List<Entry> entries = new ArrayList<>();
        final List<String> dateLabels = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            entries.add(new Entry(i, (float) rows.get(i).getValue()));
            String d = rows.get(i).getDate();
            dateLabels.add(d.length() >= 10 ? d.substring(5,7)+"/"+d.substring(8,10)+"/"+d.substring(2,4) : d);
        }
        swappableChart.getXAxis().setValueFormatter(new ValueFormatter() {
            @Override public String getFormattedValue(float value) {
                int idx = (int) value;
                return (idx % 20 == 0 || idx == dateLabels.size()-1) && idx >= 0 && idx < dateLabels.size() ? dateLabels.get(idx) : "";
            }
        });
        swappableChart.getAxisLeft().setValueFormatter(new ValueFormatter() {
            @Override public String getFormattedValue(float value) { return String.format(Locale.US, "%.2f%%", value); }
        });
        LineDataSet ds = new LineDataSet(entries, seriesName);
        ds.setColor(Color.parseColor(lineColor)); ds.setLineWidth(1.5f);
        ds.setDrawCircles(false); ds.setDrawValues(false); ds.setMode(LineDataSet.Mode.CUBIC_BEZIER);
        swappableChart.setData(new LineData(ds)); swappableChart.animateX(500); swappableChart.invalidate();
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

    private void addAllBenchmarks(LineChart chart) {
        addLine(chart, 1.5f, "BAA Tight", "#4CAF50"); addLine(chart, 2.5f, "BAA Normal", "#2196F3");
        addLine(chart, 3.5f, "BAA Elevated", "#FF9800"); addLine(chart, 3f, "HY Tight", "#4CAF50");
        addLine(chart, 5f, "HY Normal", "#2196F3"); addLine(chart, 8f, "HY Elevated", "#FF9800");
        chart.getAxisLeft().setDrawLimitLinesBehindData(true);
    }

    private void addLine(LineChart chart, float value, String label, String color) {
        LimitLine l = new LimitLine(value, label);
        l.setLineColor(Color.parseColor(color)); l.setLineWidth(1f);
        l.setTextColor(Color.parseColor(color)); l.setTextSize(9f); l.enableDashedLine(8f, 4f, 0f);
        chart.getAxisLeft().addLimitLine(l);
    }

    private void setDot(View dot, int color) {
        if (dot == null) return;
        GradientDrawable gd = new GradientDrawable(); gd.setShape(GradientDrawable.OVAL); gd.setColor(color);
        dot.setBackground(gd);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
