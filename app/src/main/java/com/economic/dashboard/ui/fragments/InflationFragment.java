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
import com.github.mikephil.charting.components.LimitLine;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.ValueFormatter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Combined Inflation tab — merges CPI, PCE (new), and Wages data into one scrollable screen.
 * Replaces the previous separate CPI and Wages tabs.
 */
public class InflationFragment extends Fragment {

    private EconomicViewModel viewModel;

    // PCE hero card (Fed's preferred measure)
    private CardView cardPceStatus;
    private TextView tvPceValue, tvPceStatus, tvPcePercentile;
    private View viewPceDot;

    // CPI status card
    private CardView cardCpiYoY;
    private TextView tvCpiYoYValue, tvCpiYoYStatus, tvCpiPercentile;
    private View viewCpiDot;

    // Wage status card
    private CardView cardWageYoY;
    private TextView tvWageYoYValue, tvWageYoYStatus;
    private View viewWageDot;

    // Charts
    private LineChart chartPceCpi;     // PCE vs CPI comparison
    private LineChart chartComparison; // Inflation vs wage indexed

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_inflation, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(requireActivity()).get(EconomicViewModel.class);

        // PCE card
        cardPceStatus  = view.findViewById(R.id.cardPceStatus);
        tvPceValue     = view.findViewById(R.id.tvPceValue);
        tvPceStatus    = view.findViewById(R.id.tvPceStatus);
        tvPcePercentile = view.findViewById(R.id.tvPcePercentile);
        viewPceDot     = view.findViewById(R.id.viewPceDot);

        // CPI card
        cardCpiYoY     = view.findViewById(R.id.cardCpiYoY);
        tvCpiYoYValue  = view.findViewById(R.id.tvCpiYoYValue);
        tvCpiYoYStatus = view.findViewById(R.id.tvCpiYoYStatus);
        tvCpiPercentile = view.findViewById(R.id.tvCpiPercentile);
        viewCpiDot     = view.findViewById(R.id.viewCpiIndicatorDot);

        // Wage card
        cardWageYoY    = view.findViewById(R.id.cardWageYoY);
        tvWageYoYValue = view.findViewById(R.id.tvWageYoYValue);
        tvWageYoYStatus = view.findViewById(R.id.tvWageYoYStatus);
        viewWageDot    = view.findViewById(R.id.viewWageIndicatorDot);

        // Charts
        chartPceCpi     = view.findViewById(R.id.chartPceCpi);
        chartComparison = view.findViewById(R.id.comparisonChart);

        // Style charts
        ChartHelper.styleLineChart(chartPceCpi,     "PCE vs CPI-U (Year-over-Year %)",    "Month", "YoY %");
        ChartHelper.styleLineChart(chartComparison,  "Inflation vs Wage Growth (Base=100)", "Month", "Index");

        ValueFormatter pctFmt = new ValueFormatter() {
            @Override public String getFormattedValue(float v) { return String.format(Locale.US, "%.1f%%", v); }
        };
        ValueFormatter idxFmt = new ValueFormatter() {
            @Override public String getFormattedValue(float v) { return String.format(Locale.US, "%.0f", v); }
        };
        chartPceCpi.getAxisLeft().setValueFormatter(pctFmt);
        chartComparison.getAxisLeft().setValueFormatter(idxFmt);

        // Add benchmark limit lines to YoY chart
        addYoYLimitLines(chartPceCpi);

        // Click listeners for benchmark modals
        if (cardCpiYoY != null)
            cardCpiYoY.setOnClickListener(v2 -> showBenchmarkDialog(R.layout.dialog_cpi_status));
        if (cardWageYoY != null)
            cardWageYoY.setOnClickListener(v2 -> showBenchmarkDialog(R.layout.dialog_wages_status));

        // Observe data
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
                tryUpdateWageCard(); // CPI may arrive after wages; resolve wage spread card here too
            }
        });
        viewModel.getWageData().observe(getViewLifecycleOwner(), wages -> {
            if (wages != null) {
                tryUpdateWageCard();
                tryBuildComparisonChart();
            }
        });
    }

    // ── PCE Card ─────────────────────────────────────────────────────────────

    private void updatePceCard(List<EconomicDataPoint> pceData) {
        List<EconomicDataPoint> rows = EconomicViewModel.filterBySeries(pceData, "PCE Price Index");
        if (rows.size() < 13) return;

        double latest  = rows.get(rows.size() - 1).getValue();
        double yearAgo = rows.get(rows.size() - 13).getValue();
        double yoy     = ((latest - yearAgo) / yearAgo) * 100.0;

        if (tvPceValue != null) tvPceValue.setText(String.format(Locale.US, "%.2f%%", yoy));

        // Core PCE YoY
        List<EconomicDataPoint> coreRows = EconomicViewModel.filterBySeries(pceData, "Core PCE Price Index");
        String coreLabel = "";
        if (coreRows.size() >= 13) {
            double coreYoy = ((coreRows.get(coreRows.size()-1).getValue() - coreRows.get(coreRows.size()-13).getValue())
                    / coreRows.get(coreRows.size()-13).getValue()) * 100.0;
            coreLabel = String.format(Locale.US, "Core: %.2f%%", coreYoy);
        }
        if (tvPcePercentile != null) tvPcePercentile.setText(coreLabel);

        String status; int color;
        if (yoy < 1.5)       { status = "DEFLATION RISK"; color = Color.parseColor("#2196F3"); }
        else if (yoy <= 2.0) { status = "AT FED TARGET";  color = Color.parseColor("#4CAF50"); }
        else if (yoy <= 3.0) { status = "ABOVE TARGET";   color = Color.parseColor("#FFEB3B"); }
        else if (yoy <= 5.0) { status = "ELEVATED";       color = Color.parseColor("#FF9800"); }
        else                 { status = "CRITICAL";        color = Color.parseColor("#F44336"); }

        if (tvPceStatus != null) tvPceStatus.setText(status);
        setDot(viewPceDot, color);
    }

    // ── CPI Card ──────────────────────────────────────────────────────────────

    private void updateCpiCard(List<EconomicDataPoint> cpiData) {
        List<EconomicDataPoint> rows = EconomicViewModel.filterBySeries(cpiData, "CPI-U All Items");
        if (rows.size() < 13) return;

        double latest  = rows.get(rows.size() - 1).getValue();
        double yearAgo = rows.get(rows.size() - 13).getValue();
        double yoy     = ((latest - yearAgo) / yearAgo) * 100.0;

        if (tvCpiYoYValue != null) tvCpiYoYValue.setText(String.format(Locale.US, "%.2f%%", yoy));

        String status; int color;
        if (yoy < 1.5)       { status = "DEFLATION RISK"; color = Color.parseColor("#2196F3"); }
        else if (yoy <= 2.5) { status = "HEALTHY";        color = Color.parseColor("#4CAF50"); }
        else if (yoy <= 3.5) { status = "CAUTION";        color = Color.parseColor("#FFEB3B"); }
        else if (yoy <= 6.0) { status = "ELEVATED";       color = Color.parseColor("#FF9800"); }
        else                 { status = "CRITICAL";        color = Color.parseColor("#F44336"); }

        if (tvCpiYoYStatus != null) {
            tvCpiYoYStatus.setText(status);
            tvCpiYoYStatus.setTextColor(Color.parseColor("#BBBBBB"));
        }
        setDot(viewCpiDot, color);

        // Historical percentile
        int pct = EconomicViewModel.calculatePercentile(cpiData, "CPI-U All Items", latest);
        if (tvCpiPercentile != null) tvCpiPercentile.setText(EconomicViewModel.formatPercentile(pct));
    }

    // ── Wage Card ─────────────────────────────────────────────────────────────

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
        if (spread < -2.0)      { status = "FALLING BEHIND FAST"; color = Color.parseColor("#F44336"); }
        else if (spread < 0.0)  { status = "LOSING GROUND";       color = Color.parseColor("#FF9800"); }
        else if (spread <= 1.0) { status = "BARELY AHEAD";        color = Color.parseColor("#FFEB3B"); }
        else if (spread <= 2.5) { status = "HEALTHY";             color = Color.parseColor("#4CAF50"); }
        else                    { status = "STRONG";               color = Color.parseColor("#2196F3"); }

        if (tvWageYoYStatus != null) {
            tvWageYoYStatus.setText(status);
            tvWageYoYStatus.setTextColor(Color.parseColor("#BBBBBB"));
        }
        setDot(viewWageDot, color);
    }

    // ── Charts ────────────────────────────────────────────────────────────────

    /** PCE YoY vs CPI YoY rolling line chart */
    private void buildPceCpiChart(List<EconomicDataPoint> pceData, List<EconomicDataPoint> cpiData) {
        if (pceData == null || cpiData == null || chartPceCpi == null) return;

        List<EconomicDataPoint> pceRows = EconomicViewModel.filterBySeries(pceData, "PCE Price Index");
        List<EconomicDataPoint> cpiRows = EconomicViewModel.filterBySeries(cpiData, "CPI-U All Items");
        if (pceRows.size() < 13 || cpiRows.size() < 13) return;

        List<Entry> pceEntries = new ArrayList<>();
        List<Entry> cpiEntries = new ArrayList<>();
        final List<String> dates = new ArrayList<>();

        // Compute rolling YoY for last 24 months of PCE
        int pcStart = Math.max(12, pceRows.size() - 24);
        for (int i = pcStart; i < pceRows.size(); i++) {
            double yoy = ((pceRows.get(i).getValue() - pceRows.get(i - 12).getValue())
                    / pceRows.get(i - 12).getValue()) * 100.0;
            pceEntries.add(new Entry(pceEntries.size(), (float) yoy));
            String d = pceRows.get(i).getDate();
            dates.add(d.length() >= 7 ? d.substring(5, 7) + "/" + d.substring(2, 4) : d);
        }

        // Match CPI YoY to same date range.
        // Use original cpiRows indices (not a subList) so lookback is always valid.
        // ciStart >= 12 guarantees cpiRows.get(i - 12) never goes out of bounds.
        int ciStart = Math.max(12, cpiRows.size() - 24);
        for (int i = ciStart; i < cpiRows.size() && cpiEntries.size() < pceEntries.size(); i++) {
            double cYoy = ((cpiRows.get(i).getValue() - cpiRows.get(i - 12).getValue())
                    / cpiRows.get(i - 12).getValue()) * 100.0;
            cpiEntries.add(new Entry(cpiEntries.size(), (float) cYoy));
        }
        // Trim PCE and dates to match CPI length (never pad CPI with zeros)
        while (pceEntries.size() > cpiEntries.size()) pceEntries.remove(pceEntries.size() - 1);
        while (dates.size() > pceEntries.size()) dates.remove(dates.size() - 1);

        chartPceCpi.getXAxis().setValueFormatter(new ValueFormatter() {
            @Override public String getFormattedValue(float v) {
                int idx = (int) v;
                return (idx >= 0 && idx < dates.size()) ? dates.get(idx) : "";
            }
        });

        LineDataSet pceSet = makeLineDataSet(pceEntries, "PCE", "#4285F4", true);
        LineDataSet cpiSet = makeLineDataSet(cpiEntries, "CPI-U", "#fbbc04", false);

        chartPceCpi.setData(new LineData(pceSet, cpiSet));
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
            cpiEntries.add(new Entry(i, (float) ((cpiRows.get(i).getValue() / cpiBase) * 100.0)));
            dates.add(date.length() >= 7 ? date.substring(5, 7) + "-" + date.substring(0, 4) : date);
            for (EconomicDataPoint w : wageRows) {
                if (w.getDate().equals(date)) {
                    wageEntries.add(new Entry(i, (float) ((w.getValue() / wageBase) * 100.0)));
                    break;
                }
            }
        }

        chartComparison.getXAxis().setValueFormatter(new ValueFormatter() {
            @Override public String getFormattedValue(float v) {
                int idx = (int) v; return (idx >= 0 && idx < dates.size()) ? dates.get(idx) : "";
            }
        });

        LineDataSet cpiSet  = makeLineDataSet(cpiEntries,  "Consumer Prices (CPI)", "#fbbc04", false);
        LineDataSet wageSet = makeLineDataSet(wageEntries, "Average Wages",          "#9c27b0", false);
        chartComparison.setData(new LineData(cpiSet, wageSet));
        chartComparison.invalidate();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private LineDataSet makeLineDataSet(List<Entry> entries, String label, String hexColor, boolean filled) {
        LineDataSet ds = new LineDataSet(entries, label);
        int c = Color.parseColor(hexColor);
        ds.setColor(c);
        ds.setLineWidth(1.5f);
        ds.setDrawCircles(false);
        ds.setDrawValues(false);
        ds.setMode(LineDataSet.Mode.CUBIC_BEZIER);
        return ds;
    }

    private void addYoYLimitLines(LineChart chart) {
        // Fed target (2%), elevated (3.5%) reference lines
        LimitLine target = new LimitLine(2.0f, "Fed Target 2%");
        target.setLineColor(Color.parseColor("#4CAF50"));
        target.setLineWidth(1f);
        target.setTextColor(Color.parseColor("#4CAF50"));
        target.setTextSize(9f);
        target.enableDashedLine(8f, 4f, 0f);

        LimitLine elevated = new LimitLine(3.5f, "Elevated 3.5%");
        elevated.setLineColor(Color.parseColor("#FF9800"));
        elevated.setLineWidth(1f);
        elevated.setTextColor(Color.parseColor("#FF9800"));
        elevated.setTextSize(9f);
        elevated.enableDashedLine(8f, 4f, 0f);

        chart.getAxisLeft().addLimitLine(target);
        chart.getAxisLeft().addLimitLine(elevated);
        chart.getAxisLeft().setDrawLimitLinesBehindData(true);
    }

    private void setDot(View dot, int color) {
        if (dot == null) return;
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.OVAL);
        gd.setColor(color);
        dot.setBackground(gd);
    }

    private void showBenchmarkDialog(int layoutRes) {
        if (getContext() == null) return;
        View dialogView = LayoutInflater.from(getContext()).inflate(layoutRes, null);
        AlertDialog dialog = new AlertDialog.Builder(getContext()).setView(dialogView).create();
        View btn = dialogView.findViewById(R.id.btnClose);
        if (btn != null) btn.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }
}
