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

import com.economic.dashboard.R;
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

public class EmploymentFragment extends Fragment {

    private EconomicViewModel viewModel;
    private LineChart swappableChart;
    private TextView tvChartTitle;

    private CardView cardUnemploymentStatus, cardLaborParticipation;
    private TextView tvUnempValue, tvUnempStatus, tvUnempLowInfo;
    private View viewUnempIndicatorDot;
    private TextView tvLaborValue, tvLaborStatus;
    private View viewLaborIndicatorDot;

    private String activeCard = "unemployment";
    private List<EconomicDataPoint> currentUnempData;
    private List<EconomicDataPoint> currentLaborData;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_employment, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(requireActivity()).get(EconomicViewModel.class);

        swappableChart = view.findViewById(R.id.swappableChart);
        tvChartTitle = view.findViewById(R.id.tvChartTitle);

        styleChart(swappableChart);
        addEmploymentLimitLines(swappableChart);

        swappableChart.getAxisLeft().setValueFormatter(new ValueFormatter() {
            @Override public String getFormattedValue(float value) {
                return String.format(Locale.US, "%.1f%%", value);
            }
        });

        cardUnemploymentStatus = view.findViewById(R.id.cardUnemploymentStatus);
        tvUnempValue = view.findViewById(R.id.tvUnempValue);
        tvUnempStatus = view.findViewById(R.id.tvUnempStatus);
        tvUnempLowInfo = view.findViewById(R.id.tvUnempLowInfo);
        viewUnempIndicatorDot = view.findViewById(R.id.viewUnempIndicatorDot);

        cardLaborParticipation = view.findViewById(R.id.cardLaborParticipation);
        tvLaborValue = view.findViewById(R.id.tvLaborValue);
        tvLaborStatus = view.findViewById(R.id.tvLaborStatus);
        viewLaborIndicatorDot = view.findViewById(R.id.viewLaborIndicatorDot);

        cardUnemploymentStatus.setOnClickListener(v -> {
            activeCard = "unemployment"; setActiveCard("unemployment"); buildSwappableChart();
        });
        cardLaborParticipation.setOnClickListener(v -> {
            activeCard = "labor"; setActiveCard("labor"); buildSwappableChart();
        });

        setActiveCard("unemployment");

        viewModel.getEmploymentData().observe(getViewLifecycleOwner(), data -> {
            if (data != null) {
                currentUnempData = data;
                currentLaborData = data;
                calculateUnemploymentStatus(data);
                calculateLaborParticipationStatus(data);
                buildSwappableChart();
            }
        });
    }

    private void setActiveCard(String which) {
        cardUnemploymentStatus.setForeground("unemployment".equals(which) ? makeActiveBorder() : null);
        cardLaborParticipation.setForeground("labor".equals(which) ? makeActiveBorder() : null);
        tvChartTitle.setText("labor".equals(which) ? "Labor Participation Rate Trend" : "Unemployment Rate Trend");
    }

    private GradientDrawable makeActiveBorder() {
        float density = getResources().getDisplayMetrics().density;
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(12 * density);
        gd.setStroke((int)(1.5f * density), Color.parseColor("#C8A84B"));
        gd.setColor(Color.TRANSPARENT);
        return gd;
    }

    private void buildSwappableChart() {
        if (swappableChart == null) return;
        List<EconomicDataPoint> data;
        String seriesName, lineColor;
        if ("labor".equals(activeCard)) { data = currentLaborData; seriesName = "Labor Force Participation Rate"; lineColor = "#4285F4"; }
        else { data = currentUnempData; seriesName = "Unemployment Rate"; lineColor = "#EA4335"; }
        if (data == null || data.isEmpty()) return;
        buildChart(data, seriesName, swappableChart, lineColor);
    }

    private void calculateUnemploymentStatus(List<EconomicDataPoint> data) {
        List<EconomicDataPoint> rows = EconomicViewModel.filterBySeries(data, "Unemployment Rate");
        if (rows.isEmpty()) return;
        double currentRate = rows.get(rows.size()-1).getValue();
        double low = Double.MAX_VALUE;
        int startIndex = Math.max(0, rows.size()-12);
        for (int i = startIndex; i < rows.size(); i++) low = Math.min(low, rows.get(i).getValue());
        double riseFromLow = currentRate - low;

        tvUnempValue.setText(String.format(Locale.US, "%.1f%%", currentRate));
        tvUnempLowInfo.setText(String.format(Locale.US, "12-month low: %.1f%% (Rise: +%.1f%%)", low, riseFromLow));

        String status; int dotColor;
        if (currentRate > 7.0) { status = "RECESSION TERRITORY"; dotColor = Color.parseColor("#F44336"); }
        else if (currentRate > 5.5) { status = "ELEVATED"; dotColor = Color.parseColor("#FF9800"); }
        else if (riseFromLow >= 0.5) { status = "RECESSION SIGNAL (SAHM RULE)"; dotColor = Color.parseColor("#F44336"); }
        else if (currentRate >= 3.5 && currentRate <= 4.5) {
            if (riseFromLow >= 0.3) { status = "WATCH CLOSELY"; dotColor = Color.parseColor("#FFEB3B"); }
            else { status = "HEALTHY"; dotColor = Color.parseColor("#4CAF50"); }
        } else { status = "STABLE"; dotColor = Color.parseColor("#4CAF50"); }

        tvUnempStatus.setText(status);
        tvUnempStatus.setTextColor(Color.parseColor("#BBBBBB"));
        GradientDrawable dot = new GradientDrawable(); dot.setShape(GradientDrawable.OVAL); dot.setColor(dotColor);
        viewUnempIndicatorDot.setBackground(dot);
    }

    private void calculateLaborParticipationStatus(List<EconomicDataPoint> data) {
        List<EconomicDataPoint> rows = EconomicViewModel.filterBySeries(data, "Labor Force Participation Rate");
        if (rows.isEmpty()) return;
        double currentRate = rows.get(rows.size()-1).getValue();
        tvLaborValue.setText(String.format(Locale.US, "%.1f%%", currentRate));
        String status; int dotColor;
        if (currentRate >= 63.3) { status = "HEALTHY"; dotColor = Color.parseColor("#4CAF50"); }
        else if (currentRate >= 62.0) { status = "DECLINING"; dotColor = Color.parseColor("#FF9800"); }
        else { status = "CRITICAL"; dotColor = Color.parseColor("#F44336"); }
        tvLaborStatus.setText(status);
        tvLaborStatus.setTextColor(Color.parseColor("#BBBBBB"));
        GradientDrawable dot = new GradientDrawable(); dot.setShape(GradientDrawable.OVAL); dot.setColor(dotColor);
        viewLaborIndicatorDot.setBackground(dot);
    }

    private void addEmploymentLimitLines(LineChart chart) {
        addLine(chart, 4.5f, "Healthy ≤4.5%", "#4CAF50");
        addLine(chart, 5.5f, "Elevated 5.5%", "#FF9800");
        addLine(chart, 7.0f, "Recession 7.0%", "#F44336");
        addLine(chart, 63.3f, "Pre-COVID 63.3%", "#4CAF50");
        addLine(chart, 62.0f, "Declining 62.0%", "#FF9800");
        addLine(chart, 61.0f, "Critical 61.0%", "#F44336");
        chart.getAxisLeft().setDrawLimitLinesBehindData(true);
    }

    private void addLine(LineChart chart, float value, String label, String color) {
        LimitLine l = new LimitLine(value, label);
        l.setLineColor(Color.parseColor(color)); l.setLineWidth(1f);
        l.setTextColor(Color.parseColor(color)); l.setTextSize(9f); l.enableDashedLine(8f, 4f, 0f);
        chart.getAxisLeft().addLimitLine(l);
    }

    private void buildChart(List<EconomicDataPoint> data, String series, LineChart chart, String hexColor) {
        List<EconomicDataPoint> rows = EconomicViewModel.filterBySeries(data, series);
        if (rows.isEmpty()) return;
        List<Entry> entries = new ArrayList<>();
        final List<String> dates = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            entries.add(new Entry(i, (float) rows.get(i).getValue()));
            String d = rows.get(i).getDate();
            dates.add(d.length() >= 7 ? d.substring(5,7)+"-"+d.substring(0,4) : d);
        }
        chart.getXAxis().setValueFormatter(new ValueFormatter() {
            @Override public String getFormattedValue(float value) {
                int idx = (int) value;
                return (idx % 5 == 0 || idx == dates.size()-1) && idx >= 0 && idx < dates.size() ? dates.get(idx) : "";
            }
        });
        LineDataSet ds = new LineDataSet(entries, series);
        ds.setColor(Color.parseColor(hexColor)); ds.setLineWidth(1.5f);
        ds.setDrawCircles(false); ds.setDrawValues(false); ds.setMode(LineDataSet.Mode.CUBIC_BEZIER);
        chart.setData(new LineData(ds)); chart.animateX(500); chart.invalidate();
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
}
