package com.economic.dashboard.ui.fragments;

import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
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

import com.economic.dashboard.databinding.FragmentSpreadsBinding;
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

    private FragmentSpreadsBinding binding;

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
        binding = FragmentSpreadsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(requireActivity()).get(EconomicViewModel.class);

        swappableChart = binding.swappableChart;
        com.economic.dashboard.analyst.AskAnalyst.attachChartExplain(
                swappableChart, requireActivity(), "Treasury yield spreads (10Y-2Y and 10Y-3M) over time");
        tvChartTitle = binding.tvChartTitle;
        styleChart(swappableChart);
        addSpreadLimitLines(swappableChart);
        swappableChart.getAxisLeft().setValueFormatter(new ValueFormatter() {
            @Override public String getFormattedValue(float value) { return String.format(Locale.US, "%.1f%%", value); }
        });

        cardSpreadYoY = binding.cardSpreadYoY;
        com.economic.dashboard.analyst.AskAnalyst.wireCardLongPress(
                cardSpreadYoY, requireActivity(), "the 10Y-2Y Treasury spread");
        tvSpreadValue = binding.tvSpreadValue;
        tvIndicatorText = binding.tvIndicatorText;
        viewSpreadIndicatorDot = binding.viewSpreadIndicatorDot;
        cardSpread3M = binding.cardSpread3M;
        com.economic.dashboard.analyst.AskAnalyst.wireCardLongPress(
                cardSpread3M, requireActivity(), "the 10Y-3M Treasury spread");
        tvSpread3MValue = binding.tvSpread3MValue;
        tvIndicator3MText = binding.tvIndicator3MText;
        viewSpread3MIndicatorDot = binding.viewSpread3MIndicatorDot;

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
                if (spread >= 3.50) { dotColor = Color.parseColor("#8A6E9E"); status = "STEEP"; }
                else if (spread >= 2.00) { dotColor = Color.parseColor("#5B8DB8"); status = "STRONG"; }
                else if (spread >= 1.00) { dotColor = Color.parseColor("#6FA97A"); status = "HEALTHY"; }
                else if (spread >= 0.00) { dotColor = Color.parseColor("#DCC873"); status = "RECOVERING"; }
                else if (spread > -0.50) { dotColor = Color.parseColor("#DCC873"); status = "FLATTENING"; }
                else if (spread > -1.50) { dotColor = Color.parseColor("#D98E4F"); status = "INVERTED"; }
                else { dotColor = Color.parseColor("#C75B4E"); status = "DEEP INVERSION"; }
            } else {
                if (spread >= 0.50) { dotColor = Color.parseColor("#6FA97A"); status = "HEALTHY"; }
                else if (spread >= 0.00) { dotColor = Color.parseColor("#DCC873"); status = "CAUTION"; }
                else if (spread > -0.50) { dotColor = Color.parseColor("#D98E4F"); status = "WARNING"; }
                else { dotColor = Color.parseColor("#C75B4E"); status = "DANGER"; }
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

    private Drawable makeActiveBorder() {
        // Stroke is drawn centered on the shape edge; inset it so the full
        // width renders inside the CardView's rounded clip, and match the
        // card's 16dp corner radius (minus the inset) so corners align.
        float density = getResources().getDisplayMetrics().density;
        int strokePx = Math.max(2, Math.round(1.5f * density));
        int insetPx = (int) Math.ceil(strokePx / 2f);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.TRANSPARENT);
        gd.setCornerRadius(16f * density - insetPx);
        gd.setStroke(strokePx, Color.parseColor("#C9A84C"));
        return new InsetDrawable(gd, insetPx);
    }

    private void buildSwappableChart() {
        if (swappableChart == null) return;
        List<EconomicDataPoint> data; String label, lineColor;
        if ("3m".equals(activeCard)) { data = current3MData; label = "10Y-3M Spread (%)"; lineColor = "#E91E63"; }
        else { data = currentYoyData; label = "10Y-2Y Spread (%)"; lineColor = "#D98E4F"; }
        if (data == null || data.isEmpty()) return;
        data = EconomicViewModel.filterByTimeframe(requireContext(), data);
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
        zero.setLineColor(Color.parseColor("#C75B4E")); zero.setLineWidth(1.2f);
        zero.setTextColor(Color.parseColor("#C75B4E")); zero.setTextSize(9f); zero.enableDashedLine(8f, 4f, 0f);
        LimitLine warn = new LimitLine(-0.5f, "Warning");
        warn.setLineColor(Color.parseColor("#D98E4F")); warn.setLineWidth(1f);
        warn.setTextColor(Color.parseColor("#D98E4F")); warn.setTextSize(9f); warn.enableDashedLine(8f, 4f, 0f);
        LimitLine deep = new LimitLine(-1.5f, "Deep");
        deep.setLineColor(Color.parseColor("#8A6E9E")); deep.setLineWidth(1f);
        deep.setTextColor(Color.parseColor("#8A6E9E")); deep.setTextSize(9f); deep.enableDashedLine(8f, 4f, 0f);
        chart.getAxisLeft().addLimitLine(zero); chart.getAxisLeft().addLimitLine(warn); chart.getAxisLeft().addLimitLine(deep);
        chart.getAxisLeft().setDrawLimitLinesBehindData(true);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
