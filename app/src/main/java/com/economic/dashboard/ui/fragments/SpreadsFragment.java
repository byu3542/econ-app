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

public class SpreadsFragment extends Fragment {

    private EconomicViewModel viewModel;
    private LineChart swappableChart;
    private TextView tvChartTitle;
    private CardView cardSpreadYoY, cardSpread3M;
    private TextView tvSpreadValue, tvIndicatorText, tvSpread3MValue, tvIndicator3MText;
    private View viewSpreadIndicatorDot, viewSpread3MIndicatorDot;
    private String activeCard = "yoy";
    private List<EconomicDataPoint> currentYoyData;
    private List<EconomicDataPoint> current3MData;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_spreads, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(requireActivity()).get(EconomicViewModel.class);

        swappableChart = view.findViewById(R.id.swappableChart);
        tvChartTitle = view.findViewById(R.id.tvChartTitle);
        styleChart(swappableChart);
        addSpreadLimitLines(swappableChart);
        swappableChart.getAxisLeft().setValueFormatter(new ValueFormatter() {
            @Override public String getFormattedValue(float value) { return String.format(Locale.US, "%.1f%%", value); }
        });

        cardSpreadYoY = view.findViewById(R.id.cardSpreadYoY);
        tvSpreadValue = view.findViewById(R.id.tvSpreadValue);
        tvIndicatorText = view.findViewById(R.id.tvIndicatorText);
        viewSpreadIndicatorDot = view.findViewById(R.id.viewSpreadIndicatorDot);
        cardSpread3M = view.findViewById(R.id.cardSpread3M);
        tvSpread3MValue = view.findViewById(R.id.tvSpread3MValue);
        tvIndicator3MText = view.findViewById(R.id.tvIndicator3MText);
        viewSpread3MIndicatorDot = view.findViewById(R.id.viewSpread3MIndicatorDot);

        cardSpreadYoY.setOnClickListener(v -> { activeCard = "yoy"; setActiveCard("yoy"); buildSwappableChart(); });
        cardSpread3M.setOnClickListener(v -> { activeCard = "3m"; setActiveCard("3m"); buildSwappableChart(); });
        setActiveCard("yoy");

        viewModel.getTreasuryData().observe(getViewLifecycleOwner(), data -> { if (data != null) updateSpreadIndicators(data); });
        viewModel.getCalculatedSpreadData().observe(getViewLifecycleOwner(), data -> { if (data != null) { currentYoyData = data; buildSwappableChart(); } });
        viewModel.getCalculatedSpread3MData().observe(getViewLifecycleOwner(), data -> { if (data != null) { current3MData = data; buildSwappableChart(); } });
    }

    private void updateSpreadIndicators(List<EconomicDataPoint> data) {
        updateSingleIndicator(data, "10 Year", "2 Year", tvSpreadValue, tvIndicatorText, viewSpreadIndicatorDot, false);
        updateSingleIndicator(data, "10 Year", "3 Month", tvSpread3MValue, tvIndicator3MText, viewSpread3MIndicatorDot, true);
    }

    private void updateSingleIndicator(List<EconomicDataPoint> data, String longKey, String shortKey,
                                        TextView valTv, TextView statusTv, View dotView, boolean is3M) {
        EconomicDataPoint longRate = EconomicViewModel.getLatest(data, longKey);
        EconomicDataPoint shortRate = EconomicViewModel.getLatest(data, shortKey);
        if (longRate != null && shortRate != null) {
            double spread = longRate.getValue() - shortRate.getValue();
            valTv.setText(String.format(Locale.US, "%.2f%%", spread));
            int dotColor; String status;
            if (is3M) {
                if (spread >= 3.50) { dotColor = Color.parseColor("#9C27B0"); status = "STEEP"; }
                else if (spread >= 2.00) { dotColor = Color.parseColor("#2196F3"); status = "STRONG"; }
                else if (spread >= 1.00) { dotColor = Color.parseColor("#4CAF50"); status = "HEALTHY"; }
                else if (spread >= 0.00) { dotColor = Color.parseColor("#FFEB3B"); status = "RECOVERING"; }
                else if (spread > -0.50) { dotColor = Color.parseColor("#FFEB3B"); status = "FLATTENING"; }
                else if (spread > -1.50) { dotColor = Color.parseColor("#FF9800"); status = "INVERTED"; }
                else { dotColor = Color.parseColor("#F44336"); status = "DEEP INVERSION"; }
            } else {
                if (spread >= 0.50) { dotColor = Color.parseColor("#4CAF50"); status = "HEALTHY"; }
                else if (spread >= 0.00) { dotColor = Color.parseColor("#FFEB3B"); status = "CAUTION"; }
                else if (spread > -0.50) { dotColor = Color.parseColor("#FF9800"); status = "WARNING"; }
                else { dotColor = Color.parseColor("#F44336"); status = "DANGER"; }
            }
            GradientDrawable dot = new GradientDrawable(); dot.setShape(GradientDrawable.OVAL); dot.setColor(dotColor);
            dotView.setBackground(dot); statusTv.setText(status);
        }
    }

    private void setActiveCard(String which) {
        cardSpreadYoY.setForeground("yoy".equals(which) ? makeActiveBorder() : null);
        cardSpread3M.setForeground("3m".equals(which) ? makeActiveBorder() : null);
        tvChartTitle.setText("3m".equals(which) ? "10Y-3M Spread" : "10Y-2Y Spread");
    }

    private GradientDrawable makeActiveBorder() {
        float d = getResources().getDisplayMetrics().density;
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(12*d); gd.setStroke((int)(1.5f*d), Color.parseColor("#C8A84B")); gd.setColor(Color.TRANSPARENT);
        return gd;
    }

    private void buildSwappableChart() {
        if (swappableChart == null) return;
        List<EconomicDataPoint> data; String label, lineColor;
        if ("3m".equals(activeCard)) { data = current3MData; label = "10Y-3M Spread (%)"; lineColor = "#E91E63"; }
        else { data = currentYoyData; label = "10Y-2Y Spread (%)"; lineColor = "#FF9800"; }
        if (data == null || data.isEmpty()) return;
        List<Entry> entries = new ArrayList<>();
        final List<String> dateLabels = new ArrayList<>();
        for (int i = 0; i < data.size(); i++) {
            entries.add(new Entry(i, (float) data.get(i).getValue()));
            String d = data.get(i).getDate();
            dateLabels.add(d.length() >= 7 ? d.substring(5,7)+"-"+d.substring(0,4) : d);
        }
        swappableChart.getXAxis().setValueFormatter(new ValueFormatter() {
            @Override public String getFormattedValue(float value) {
                int idx = (int) value;
                return (idx % 5 == 0 || idx == dateLabels.size()-1) && idx >= 0 && idx < dateLabels.size() ? dateLabels.get(idx) : "";
            }
        });
        LineDataSet ds = new LineDataSet(entries, label);
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

    private void addSpreadLimitLines(LineChart chart) {
        LimitLine zero = new LimitLine(0f, "Inversion");
        zero.setLineColor(Color.parseColor("#F44336")); zero.setLineWidth(1.2f);
        zero.setTextColor(Color.parseColor("#F44336")); zero.setTextSize(9f); zero.enableDashedLine(8f, 4f, 0f);
        LimitLine warn = new LimitLine(-0.5f, "Warning");
        warn.setLineColor(Color.parseColor("#FF9800")); warn.setLineWidth(1f);
        warn.setTextColor(Color.parseColor("#FF9800")); warn.setTextSize(9f); warn.enableDashedLine(8f, 4f, 0f);
        LimitLine deep = new LimitLine(-1.5f, "Deep");
        deep.setLineColor(Color.parseColor("#9C27B0")); deep.setLineWidth(1f);
        deep.setTextColor(Color.parseColor("#9C27B0")); deep.setTextSize(9f); deep.enableDashedLine(8f, 4f, 0f);
        chart.getAxisLeft().addLimitLine(zero); chart.getAxisLeft().addLimitLine(warn); chart.getAxisLeft().addLimitLine(deep);
        chart.getAxisLeft().setDrawLimitLinesBehindData(true);
    }
}
