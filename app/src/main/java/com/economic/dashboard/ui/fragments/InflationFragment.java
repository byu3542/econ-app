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

import com.economic.dashboard.databinding.FragmentInflationBinding;
import com.economic.dashboard.R;
import com.economic.dashboard.ui.MetricBottomSheet;
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

public class InflationFragment extends Fragment {

    private FragmentInflationBinding binding;

    private EconomicViewModel viewModel;

    private CardView cardPceStatus;
    private TextView tvPceValue, tvPceStatus, tvPcePercentile;
    private View viewPceDot;

    private CardView cardCpiYoY;
    private TextView tvCpiYoYValue, tvCpiYoYStatus, tvCpiPercentile;
    private View viewCpiDot;

    private CardView cardWageYoY;
    private TextView tvWageYoYValue, tvWageYoYStatus;
    private View viewWageDot;

    private LineChart chartPceCpi;
    private LineChart chartComparison;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentInflationBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(requireActivity()).get(EconomicViewModel.class);

        cardPceStatus   = binding.cardPceStatus;
        tvPceValue      = binding.tvPceValue;
        tvPceStatus     = binding.tvPceStatus;
        tvPcePercentile = binding.tvPcePercentile;
        viewPceDot      = binding.viewPceDot;

        cardCpiYoY      = binding.cardCpiYoY;
        tvCpiYoYValue   = binding.tvCpiYoYValue;
        tvCpiYoYStatus  = binding.tvCpiYoYStatus;
        tvCpiPercentile = binding.tvCpiPercentile;
        viewCpiDot      = binding.viewCpiIndicatorDot;

        cardWageYoY     = binding.cardWageYoY;
        tvWageYoYValue  = binding.tvWageYoYValue;
        tvWageYoYStatus = binding.tvWageYoYStatus;
        viewWageDot     = binding.viewWageIndicatorDot;

        chartPceCpi     = binding.chartPceCpi;
        chartComparison = binding.comparisonChart;

        styleChart(chartPceCpi);
        styleChart(chartComparison);

        chartPceCpi.getAxisLeft().setValueFormatter(new ValueFormatter() {
            @Override public String getFormattedValue(float v) { return String.format(Locale.US, "%.1f%%", v); }
        });
        chartComparison.getAxisLeft().setValueFormatter(new ValueFormatter() {
            @Override public String getFormattedValue(float v) { return String.format(Locale.US, "%.0f", v); }
        });

        addYoYLimitLines(chartPceCpi);

        if (cardCpiYoY != null)
            cardCpiYoY.setOnClickListener(v2 -> showBenchmarkDialog(R.layout.dialog_cpi_status));
        if (cardWageYoY != null)
            cardWageYoY.setOnClickListener(v2 -> showBenchmarkDialog(R.layout.dialog_wages_status));

        viewModel.getPceData().observe(getViewLifecycleOwner(), pce -> {
            if (pce != null) {
                updatePceCard(pce);
                buildPceCpiChart(pce, viewModel.getCpiData().getValue());
            }
        });
        viewModel.getCpiData().observe(getViewLifecycleOwner(), cpi -> {
            if (cpi != null) {
                updateCpiCard(cpi);
                buildPceCpiChart(viewModel.getPceData().getValue(), cpi);
                tryBuildComparisonChart();
                tryUpdateWageCard();
            }
        });
        viewModel.getWageData().observe(getViewLifecycleOwner(), wages -> {
            if (wages != null) { tryUpdateWageCard(); tryBuildComparisonChart(); }
        });
    }

    private void updatePceCard(List<EconomicDataPoint> pceData) {
        List<EconomicDataPoint> rows = EconomicViewModel.filterBySeries(pceData, "PCE Price Index");
        if (rows.size() < 13) return;
        double latest  = rows.get(rows.size()-1).getValue();
        double yearAgo = rows.get(rows.size()-13).getValue();
        double yoy = ((latest - yearAgo) / yearAgo) * 100.0;
        if (tvPceValue != null) tvPceValue.setText(String.format(Locale.US, "%.2f%%", yoy));

        List<EconomicDataPoint> coreRows = EconomicViewModel.filterBySeries(pceData, "Core PCE Price Index");
        String coreLabel = "";
        if (coreRows.size() >= 13) {
            double coreYoy = ((coreRows.get(coreRows.size()-1).getValue() - coreRows.get(coreRows.size()-13).getValue())
                    / coreRows.get(coreRows.size()-13).getValue()) * 100.0;
            coreLabel = String.format(Locale.US, "Core: %.2f%%", coreYoy);
        }
        if (tvPcePercentile != null) tvPcePercentile.setText(coreLabel);

        String status; int color;
        if (yoy < 1.5)       { status = "DEFLATION RISK"; color = Color.parseColor("#5B8DB8"); }
        else if (yoy <= 2.0) { status = "AT FED TARGET";  color = Color.parseColor("#6FA97A"); }
        else if (yoy <= 3.0) { status = "ABOVE TARGET";   color = Color.parseColor("#DCC873"); }
        else if (yoy <= 5.0) { status = "ELEVATED";       color = Color.parseColor("#D98E4F"); }
        else                 { status = "CRITICAL";        color = Color.parseColor("#C75B4E"); }

        if (tvPceStatus != null) tvPceStatus.setText(status);
        setDot(viewPceDot, color);
    }

    private void updateCpiCard(List<EconomicDataPoint> cpiData) {
        List<EconomicDataPoint> rows = EconomicViewModel.filterBySeries(cpiData, "CPI-U All Items");
        if (rows.size() < 13) return;
        double latest  = rows.get(rows.size()-1).getValue();
        double yearAgo = rows.get(rows.size()-13).getValue();
        double yoy = ((latest - yearAgo) / yearAgo) * 100.0;
        if (tvCpiYoYValue != null) tvCpiYoYValue.setText(String.format(Locale.US, "%.2f%%", yoy));

        String status; int color;
        if (yoy < 1.5)       { status = "DEFLATION RISK"; color = Color.parseColor("#5B8DB8"); }
        else if (yoy <= 2.5) { status = "HEALTHY";        color = Color.parseColor("#6FA97A"); }
        else if (yoy <= 3.5) { status = "CAUTION";        color = Color.parseColor("#DCC873"); }
        else if (yoy <= 6.0) { status = "ELEVATED";       color = Color.parseColor("#D98E4F"); }
        else                 { status = "CRITICAL";        color = Color.parseColor("#C75B4E"); }

        if (tvCpiYoYStatus != null) {
            tvCpiYoYStatus.setText(status);
            tvCpiYoYStatus.setTextColor(Color.parseColor("#BBBBBB"));
        }
        setDot(viewCpiDot, color);

        int pct = EconomicViewModel.calculatePercentile(cpiData, "CPI-U All Items", latest);
        if (tvCpiPercentile != null) tvCpiPercentile.setText(EconomicViewModel.formatPercentile(pct));
    }

    private void tryUpdateWageCard() {
        List<EconomicDataPoint> wageData = viewModel.getWageData().getValue();
        List<EconomicDataPoint> cpiData  = viewModel.getCpiData().getValue();
        if (wageData == null || cpiData == null) return;

        List<EconomicDataPoint> wRows = EconomicViewModel.filterBySeries(wageData, "Average Hourly Earnings - Private");
        List<EconomicDataPoint> cRows = EconomicViewModel.filterBySeries(cpiData,  "CPI-U All Items");
        if (wRows.size() < 13 || cRows.size() < 13) return;

        double wYoy   = ((wRows.get(wRows.size()-1).getValue() - wRows.get(wRows.size()-13).getValue())
                / wRows.get(wRows.size()-13).getValue()) * 100.0;
        double cYoy   = ((cRows.get(cRows.size()-1).getValue() - cRows.get(cRows.size()-13).getValue())
                / cRows.get(cRows.size()-13).getValue()) * 100.0;
        double spread = wYoy - cYoy;

        if (tvWageYoYValue != null) tvWageYoYValue.setText(String.format(Locale.US, "%.2f%%", spread));

        String status; int color;
        if (spread < -2.0)      { status = "FALLING BEHIND FAST"; color = Color.parseColor("#C75B4E"); }
        else if (spread < 0.0)  { status = "LOSING GROUND";       color = Color.parseColor("#D98E4F"); }
        else if (spread <= 1.0) { status = "BARELY AHEAD";        color = Color.parseColor("#DCC873"); }
        else if (spread <= 2.5) { status = "HEALTHY";             color = Color.parseColor("#6FA97A"); }
        else                    { status = "STRONG";               color = Color.parseColor("#5B8DB8"); }

        if (tvWageYoYStatus != null) {
            tvWageYoYStatus.setText(status);
            tvWageYoYStatus.setTextColor(Color.parseColor("#BBBBBB"));
        }
        setDot(viewWageDot, color);
    }

    private void buildPceCpiChart(List<EconomicDataPoint> pceData, List<EconomicDataPoint> cpiData) {
        if (pceData == null || cpiData == null || chartPceCpi == null) return;
        List<EconomicDataPoint> pceRows = EconomicViewModel.filterBySeries(pceData, "PCE Price Index");
        List<EconomicDataPoint> cpiRows = EconomicViewModel.filterBySeries(cpiData, "CPI-U All Items");
        if (pceRows.size() < 13 || cpiRows.size() < 13) return;

        List<Entry> pceEntries = new ArrayList<>();
        List<Entry> cpiEntries = new ArrayList<>();
        final List<String> dates = new ArrayList<>();

        int pcStart = Math.max(12, pceRows.size()-24);
        for (int i = pcStart; i < pceRows.size(); i++) {
            double yoy = ((pceRows.get(i).getValue() - pceRows.get(i-12).getValue()) / pceRows.get(i-12).getValue()) * 100.0;
            pceEntries.add(new Entry(pceEntries.size(), (float) yoy));
            String d = pceRows.get(i).getDate();
            dates.add(d.length() >= 7 ? d.substring(5,7)+"/"+d.substring(2,4) : d);
        }

        int ciStart = Math.max(12, cpiRows.size()-24);
        for (int i = ciStart; i < cpiRows.size() && cpiEntries.size() < pceEntries.size(); i++) {
            double cYoy = ((cpiRows.get(i).getValue() - cpiRows.get(i-12).getValue()) / cpiRows.get(i-12).getValue()) * 100.0;
            cpiEntries.add(new Entry(cpiEntries.size(), (float) cYoy));
        }
        while (pceEntries.size() > cpiEntries.size()) pceEntries.remove(pceEntries.size()-1);
        while (dates.size() > pceEntries.size()) dates.remove(dates.size()-1);

        chartPceCpi.getXAxis().setValueFormatter(new ValueFormatter() {
            @Override public String getFormattedValue(float v) {
                int idx = (int) v;
                return (idx >= 0 && idx < dates.size()) ? dates.get(idx) : "";
            }
        });

        chartPceCpi.setData(new LineData(makeLineDataSet(pceEntries, "PCE", "#4285F4"), makeLineDataSet(cpiEntries, "CPI-U", "#fbbc04")));
        chartPceCpi.invalidate();
    }

    private void tryBuildComparisonChart() {
        List<EconomicDataPoint> cpiData  = viewModel.getCpiData().getValue();
        List<EconomicDataPoint> wageData = viewModel.getWageData().getValue();
        if (cpiData == null || wageData == null || chartComparison == null) return;

        List<EconomicDataPoint> cpiRows  = EconomicViewModel.filterBySeries(cpiData,  "CPI-U All Items");
        List<EconomicDataPoint> wageRows = EconomicViewModel.filterBySeries(wageData, "Average Hourly Earnings - Private");
        if (cpiRows.isEmpty() || wageRows.isEmpty()) return;

        double cpiBase  = cpiRows.get(0).getValue();
        double wageBase = wageRows.get(0).getValue();
        List<Entry> cpiEntries  = new ArrayList<>();
        List<Entry> wageEntries = new ArrayList<>();
        final List<String> dates = new ArrayList<>();

        for (int i = 0; i < cpiRows.size(); i++) {
            String date = cpiRows.get(i).getDate();
            cpiEntries.add(new Entry(i, (float)((cpiRows.get(i).getValue()/cpiBase)*100.0)));
            dates.add(date.length() >= 7 ? date.substring(5,7)+"-"+date.substring(0,4) : date);
            for (EconomicDataPoint w : wageRows) {
                if (w.getDate().equals(date)) {
                    wageEntries.add(new Entry(i, (float)((w.getValue()/wageBase)*100.0))); break;
                }
            }
        }

        chartComparison.getXAxis().setValueFormatter(new ValueFormatter() {
            @Override public String getFormattedValue(float v) {
                int idx = (int) v; return (idx >= 0 && idx < dates.size()) ? dates.get(idx) : "";
            }
        });

        chartComparison.setData(new LineData(makeLineDataSet(cpiEntries, "Consumer Prices (CPI)", "#fbbc04"), makeLineDataSet(wageEntries, "Average Wages", "#8A6E9E")));
        chartComparison.invalidate();
    }

    private LineDataSet makeLineDataSet(List<Entry> entries, String label, String hexColor) {
        LineDataSet ds = new LineDataSet(entries, label);
        ds.setColor(Color.parseColor(hexColor)); ds.setLineWidth(1.5f);
        ds.setDrawCircles(false); ds.setDrawValues(false); ds.setMode(LineDataSet.Mode.CUBIC_BEZIER);
        return ds;
    }

    private void addYoYLimitLines(LineChart chart) {
        LimitLine target = new LimitLine(2.0f, "Fed Target 2%");
        target.setLineColor(Color.parseColor("#6FA97A")); target.setLineWidth(1f);
        target.setTextColor(Color.parseColor("#6FA97A")); target.setTextSize(9f); target.enableDashedLine(8f, 4f, 0f);
        LimitLine elevated = new LimitLine(3.5f, "Elevated 3.5%");
        elevated.setLineColor(Color.parseColor("#D98E4F")); elevated.setLineWidth(1f);
        elevated.setTextColor(Color.parseColor("#D98E4F")); elevated.setTextSize(9f); elevated.enableDashedLine(8f, 4f, 0f);
        chart.getAxisLeft().addLimitLine(target); chart.getAxisLeft().addLimitLine(elevated);
        chart.getAxisLeft().setDrawLimitLinesBehindData(true);
    }

    private void setDot(View dot, int color) {
        if (dot == null) return;
        GradientDrawable gd = new GradientDrawable(); gd.setShape(GradientDrawable.OVAL); gd.setColor(color);
        dot.setBackground(gd);
    }

    private void showBenchmarkDialog(int layoutRes) {
        if (getContext() == null) return;
        MetricBottomSheet.show(getContext(), layoutRes);
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

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
