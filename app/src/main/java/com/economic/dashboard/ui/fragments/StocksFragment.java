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

import com.economic.dashboard.databinding.FragmentStocksBinding;
import com.economic.dashboard.models.EconomicDataPoint;
import com.economic.dashboard.ui.EconomicViewModel;
import com.economic.dashboard.utils.ChartHelper;
import com.economic.dashboard.utils.NumberFormatUtil;
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

public class StocksFragment extends Fragment {

    private EconomicViewModel viewModel;
    private FragmentStocksBinding binding;
    private com.economic.dashboard.ui.views.SkeletonController skeleton;
    private CardView cardSp500, cardNasdaq, cardVix;
    private TextView tvSp500Value, tvSp500Status, tvNasdaqValue, tvNasdaqStatus, tvVixValue, tvVixStatus;
    private View viewSp500Dot, viewNasdaqDot, viewVixDot;
    private LineChart swappableChart;
    private TextView tvChartTitle;
    private String activeCard = "sp500";
    private List<EconomicDataPoint> currentSp500Data;
    private List<EconomicDataPoint> currentNasdaqData;
    private List<EconomicDataPoint> currentVixData;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentStocksBinding.inflate(inflater, container, false);
        skeleton = com.economic.dashboard.ui.views.SkeletonController.wrap(binding.getRoot());
        return skeleton.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(requireActivity()).get(EconomicViewModel.class);

        // TICKET-18: per-screen retry chip for this screen's series
        android.widget.TextView retryChip = view.findViewById(com.economic.dashboard.R.id.tvRetry);
        final String[] retryKeys = { EconomicViewModel.KEY_SP500, EconomicViewModel.KEY_NASDAQ, EconomicViewModel.CACHE_VIX };
        viewModel.getFailedSeries().observe(getViewLifecycleOwner(), failed -> {
            if (retryChip == null) return;
            String hit = null;
            if (failed != null) for (String k : retryKeys) if (failed.contains(k)) { hit = k; break; }
            if (hit != null) {
                final String key = hit;
                retryChip.setOnClickListener(x -> viewModel.retrySeries(key));
                retryChip.setVisibility(View.VISIBLE);
            } else {
                retryChip.setVisibility(View.GONE);
            }
        });

        cardSp500 = binding.cardSp500;
        com.economic.dashboard.analyst.AskAnalyst.wireCardLongPress(
                cardSp500, requireActivity(), "the S&P 500 index");
        cardNasdaq = binding.cardNasdaq;
        com.economic.dashboard.analyst.AskAnalyst.wireCardLongPress(
                cardNasdaq, requireActivity(), "the Nasdaq index");
        cardVix = binding.cardVix;
        com.economic.dashboard.analyst.AskAnalyst.wireCardLongPress(
                cardVix, requireActivity(), "the VIX volatility index");
        tvSp500Value = binding.tvSp500Value;
        tvSp500Status = binding.tvSp500Status;
        tvNasdaqValue = binding.tvNasdaqValue;
        tvNasdaqStatus = binding.tvNasdaqStatus;
        tvVixValue = binding.tvVixValue;
        tvVixStatus = binding.tvVixStatus;
        viewSp500Dot = binding.viewSp500Dot;
        viewNasdaqDot = binding.viewNasdaqDot;
        viewVixDot = binding.viewVixDot;
        swappableChart = binding.swappableChart;
        com.economic.dashboard.analyst.AskAnalyst.attachChartExplain(
                swappableChart, requireActivity(), "stock market indices (S&P 500, Nasdaq, VIX)");
        tvChartTitle = binding.tvChartTitle;

        styleChart(swappableChart);
        com.economic.dashboard.utils.ChartHelper.declutterDark(swappableChart);
        ChartHelper.attachCrosshair(swappableChart); // TICKET-23
        addVixBenchmarks(swappableChart);

        // TICKET-11: inline range switcher redraws the active index/series.
        android.widget.LinearLayout tfSel = view.findViewById(com.economic.dashboard.R.id.timeframeSelector);
        com.economic.dashboard.utils.TimeframeSelector.attach(tfSel, "stocks", months -> buildSwappableChart());

        cardSp500.setOnClickListener(v -> { activeCard = "sp500"; setActiveCard("sp500"); buildSwappableChart(); });
        cardNasdaq.setOnClickListener(v -> { activeCard = "nasdaq"; setActiveCard("nasdaq"); buildSwappableChart(); });
        cardVix.setOnClickListener(v -> { activeCard = "vix"; setActiveCard("vix"); buildSwappableChart(); });

        setActiveCard("sp500");

        viewModel.getSp500Data().observe(getViewLifecycleOwner(), data -> {
            if (data != null) { currentSp500Data = data; updateSp500Card(data); buildSwappableChart(); if (skeleton != null) skeleton.reveal(); }
        });
        viewModel.getNasdaqData().observe(getViewLifecycleOwner(), data -> {
            if (data != null) { currentNasdaqData = data; updateNasdaqCard(data); buildSwappableChart(); if (skeleton != null) skeleton.reveal(); }
        });
        viewModel.getVixData().observe(getViewLifecycleOwner(), data -> {
            if (data != null) { currentVixData = data; updateVixCard(data); buildSwappableChart(); if (skeleton != null) skeleton.reveal(); }
        });
    }

    private void updateSp500Card(List<EconomicDataPoint> data) {
        List<EconomicDataPoint> rows = EconomicViewModel.filterBySeries(data, "S&P 500 Index");
        if (rows.isEmpty()) return;
        double latest = rows.get(rows.size() - 1).getValue();
        tvSp500Value.setText(formatValueWithK(latest));
        String status; int color;
        if (latest < 3500) { status = "WEAK"; color = Color.parseColor("#C75B4E"); }
        else if (latest < 4000) { status = "FAIR"; color = Color.parseColor("#D98E4F"); }
        else if (latest < 4500) { status = "STRONG"; color = Color.parseColor("#6FA97A"); }
        else { status = "VERY STRONG"; color = Color.parseColor("#5B8DB8"); }
        tvSp500Status.setText(status); setDot(viewSp500Dot, color);
    }

    private void updateNasdaqCard(List<EconomicDataPoint> data) {
        List<EconomicDataPoint> rows = EconomicViewModel.filterBySeries(data, "Nasdaq Composite Index");
        if (rows.isEmpty()) return;
        double latest = rows.get(rows.size() - 1).getValue();
        tvNasdaqValue.setText(formatValueWithK(latest));
        String status; int color;
        if (latest < 10000) { status = "WEAK"; color = Color.parseColor("#C75B4E"); }
        else if (latest < 12000) { status = "FAIR"; color = Color.parseColor("#D98E4F"); }
        else if (latest < 14000) { status = "STRONG"; color = Color.parseColor("#6FA97A"); }
        else { status = "VERY STRONG"; color = Color.parseColor("#5B8DB8"); }
        tvNasdaqStatus.setText(status); setDot(viewNasdaqDot, color);
    }

    private void updateVixCard(List<EconomicDataPoint> data) {
        List<EconomicDataPoint> rows = EconomicViewModel.filterBySeries(data, "VIX Volatility Index");
        if (rows.isEmpty()) return;
        double latest = rows.get(rows.size() - 1).getValue();
        tvVixValue.setText(NumberFormatUtil.indexPoints(latest, 1)); // TICKET-19
        String status; int color;
        if (latest < 12) { status = "LOW"; color = Color.parseColor("#6FA97A"); }
        else if (latest < 20) { status = "NORMAL"; color = Color.parseColor("#5B8DB8"); }
        else if (latest < 30) { status = "ELEVATED"; color = Color.parseColor("#D98E4F"); }
        else { status = "EXTREME"; color = Color.parseColor("#C75B4E"); }
        tvVixStatus.setText(status); setDot(viewVixDot, color);
    }

    private void setActiveCard(String which) {
        cardSp500.setForeground("sp500".equals(which) ? makeActiveBorder() : null);
        cardNasdaq.setForeground("nasdaq".equals(which) ? makeActiveBorder() : null);
        cardVix.setForeground("vix".equals(which) ? makeActiveBorder() : null);
        switch (which) {
            case "nasdaq": tvChartTitle.setText("Nasdaq Composite Index"); break;
            case "vix": tvChartTitle.setText("VIX Volatility Index"); break;
            default: tvChartTitle.setText("S&P 500 Index"); break;
        }
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
        List<EconomicDataPoint> data; String seriesName, lineColor;
        ValueFormatter formatter;
        switch (activeCard) {
            case "nasdaq": data = currentNasdaqData; seriesName = "Nasdaq Composite Index"; lineColor = "#F57C00";
                formatter = new ValueFormatter() { @Override public String getFormattedValue(float v) { return String.format(Locale.US, "%.0f", v); } }; break;
            case "vix": data = currentVixData; seriesName = "VIX Volatility Index"; lineColor = "#D32F2F";
                formatter = new ValueFormatter() { @Override public String getFormattedValue(float v) { return String.format(Locale.US, "%.1f", v); } }; break;
            default: data = currentSp500Data; seriesName = "S&P 500 Index"; lineColor = "#1976D2";
                formatter = new ValueFormatter() { @Override public String getFormattedValue(float v) { return String.format(Locale.US, "%.0f", v); } }; break;
        }
        if (data == null) return;
        List<EconomicDataPoint> rows = EconomicViewModel.filterBySeries(data, seriesName);
        if (rows.isEmpty()) return;
        rows = EconomicViewModel.filterByTimeframe(requireContext(), rows, "stocks");
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
        swappableChart.getAxisLeft().setValueFormatter(formatter);
        LineDataSet ds = new LineDataSet(entries, seriesName);
        ds.setColor(Color.parseColor(lineColor)); ds.setLineWidth(1.5f);
        ds.setDrawCircles(false); ds.setDrawValues(false); ds.setMode(LineDataSet.Mode.CUBIC_BEZIER);
        swappableChart.setData(new LineData(ds)); swappableChart.animateX(500); swappableChart.invalidate();
    }

    private void styleChart(LineChart chart) {
        int darkBg = Color.parseColor("#1C2236");
        int grid = Color.argb(0x14, 0xFF, 0xFF, 0xFF);
        chart.setBackgroundColor(darkBg); chart.setDrawGridBackground(false); chart.setDrawBorders(false);
        chart.setTouchEnabled(true); chart.setDragEnabled(true); chart.setScaleEnabled(true); chart.setPinchZoom(true);
        chart.getDescription().setEnabled(false); chart.setNoDataText("Loading…"); chart.setExtraBottomOffset(8f);
        XAxis x = chart.getXAxis();
        x.setPosition(XAxis.XAxisPosition.BOTTOM); x.setTextColor(Color.parseColor("#5A6A8A")); x.setTextSize(9f);
        x.setDrawGridLines(true); x.setGridColor(grid); x.setAxisLineColor(grid);
        x.setLabelRotationAngle(-45f); x.setGranularity(1f); x.setLabelCount(6, false); x.setAvoidFirstLastClipping(true);
        YAxis y = chart.getAxisLeft();
        y.setTextColor(Color.parseColor("#8899BB")); y.setTextSize(10f); y.setDrawGridLines(true); y.setGridColor(grid);
        chart.getAxisRight().setEnabled(false);
    }

    private void addVixBenchmarks(LineChart chart) {
        addLine(chart, 12f, "Low Vol", "#6FA97A"); addLine(chart, 20f, "Normal", "#5B8DB8");
        addLine(chart, 30f, "Elevated", "#D98E4F"); addLine(chart, 50f, "Extreme", "#C75B4E");
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

    private String formatValueWithK(double value) {
        // TICKET-19: route index levels through NumberFormatUtil for thousands
        // separators consistent with the rest of the app (e.g. "5,900").
        return NumberFormatUtil.number(value, 0);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
