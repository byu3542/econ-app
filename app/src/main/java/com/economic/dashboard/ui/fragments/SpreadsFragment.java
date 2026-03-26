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
 * Spreads sub-tab inside Markets.
 * Displays 10Y-2Y and 10Y-3M spread badges and their trend charts.
 */
public class SpreadsFragment extends Fragment {

    private EconomicViewModel viewModel;
    private LineChart spreadChart;
    private LineChart spread3MChart;

    private CardView cardSpreadYoY, cardSpread3M;
    private TextView tvSpreadValue, tvIndicatorText;
    private TextView tvSpread3MValue, tvIndicator3MText;
    private View viewSpreadIndicatorDot, viewSpread3MIndicatorDot;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_spreads, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(requireActivity()).get(EconomicViewModel.class);

        // ── Charts ───────────────────────────────────────────────────────────
        spreadChart   = view.findViewById(R.id.spreadChart);
        spread3MChart = view.findViewById(R.id.spread3MChart);

        ChartHelper.styleLineChart(spreadChart,   "10Y-2Y Spread", "Month", "Spread (%)");
        ChartHelper.styleLineChart(spread3MChart,  "10Y-3M Spread", "Month", "Spread (%)");

        // Persistent benchmark LimitLines
        addSpreadLimitLines(spreadChart,   false);
        addSpreadLimitLines(spread3MChart, true);

        ValueFormatter yieldFormatter = new ValueFormatter() {
            @Override public String getFormattedValue(float value) {
                return String.format(Locale.US, "%.1f%%", value);
            }
        };
        spreadChart.getAxisLeft().setValueFormatter(yieldFormatter);
        spread3MChart.getAxisLeft().setValueFormatter(yieldFormatter);

        // ── Spread badge cards ───────────────────────────────────────────────
        cardSpreadYoY      = view.findViewById(R.id.cardSpreadYoY);
        tvSpreadValue      = view.findViewById(R.id.tvSpreadValue);
        tvIndicatorText    = view.findViewById(R.id.tvIndicatorText);
        viewSpreadIndicatorDot = view.findViewById(R.id.viewSpreadIndicatorDot);

        cardSpread3M       = view.findViewById(R.id.cardSpread3M);
        tvSpread3MValue    = view.findViewById(R.id.tvSpread3MValue);
        tvIndicator3MText  = view.findViewById(R.id.tvIndicator3MText);
        viewSpread3MIndicatorDot = view.findViewById(R.id.viewSpread3MIndicatorDot);

        // ── Benchmark dialog on card tap ─────────────────────────────────────
        cardSpreadYoY.setOnClickListener(v -> showTreasuryBenchmarks(R.layout.dialog_treasury_status));
        cardSpread3M.setOnClickListener(v -> showTreasuryBenchmarks(R.layout.dialog_treasury_3m_status));

        // ── Observers ────────────────────────────────────────────────────────
        viewModel.getTreasuryData().observe(getViewLifecycleOwner(), data -> {
            if (data != null) updateSpreadIndicators(data);
        });

        viewModel.getCalculatedSpreadData().observe(getViewLifecycleOwner(), data -> {
            if (data != null) buildSpreadTrendChart(spreadChart, data, "10Y-2Y Spread (%)", "#ff9800");
        });

        viewModel.getCalculatedSpread3MData().observe(getViewLifecycleOwner(), data -> {
            if (data != null) buildSpreadTrendChart(spread3MChart, data, "10Y-3M Spread (%)", "#e91e63");
        });
    }

    // ── Benchmark dialogs ────────────────────────────────────────────────────

    private void showTreasuryBenchmarks(int layoutResId) {
        if (getContext() == null) return;
        View dialogView = LayoutInflater.from(getContext()).inflate(layoutResId, null);
        AlertDialog dialog = new AlertDialog.Builder(getContext()).setView(dialogView).create();
        View btnDismiss = dialogView.findViewById(R.id.btnClose);
        if (btnDismiss != null) btnDismiss.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    // ── Spread indicator updates ─────────────────────────────────────────────

    private void updateSpreadIndicators(List<EconomicDataPoint> data) {
        updateSingleIndicator(data, "10 Year", "2 Year",
                tvSpreadValue, tvIndicatorText, viewSpreadIndicatorDot, false);
        updateSingleIndicator(data, "10 Year", "3 Month",
                tvSpread3MValue, tvIndicator3MText, viewSpread3MIndicatorDot, true);
    }

    private void updateSingleIndicator(List<EconomicDataPoint> data, String longKey, String shortKey,
                                       TextView valTv, TextView statusTv, View dotView, boolean is3M) {
        EconomicDataPoint longRate = EconomicViewModel.getLatest(data, longKey);
        EconomicDataPoint shortRate = EconomicViewModel.getLatest(data, shortKey);

        if (longRate != null && shortRate != null) {
            double spread = longRate.getValue() - shortRate.getValue();
            valTv.setText(String.format(Locale.US, "%.2f%%", spread));

            int dotColor;
            String status;

            if (is3M) {
                if (spread >= 3.50) {
                    dotColor = Color.parseColor("#9C27B0");
                    status = "STEEP";
                } else if (spread >= 2.00) {
                    dotColor = Color.parseColor("#2196F3");
                    status = "STRONG";
                } else if (spread >= 1.00) {
                    dotColor = Color.parseColor("#4CAF50");
                    status = "HEALTHY";
                } else if (spread >= 0.00) {
                    dotColor = Color.parseColor("#FFEB3B");
                    status = "RECOVERING";
                } else if (spread > -0.50) {
                    dotColor = Color.parseColor("#FFEB3B");
                    status = "FLATTENING";
                } else if (spread > -1.50) {
                    dotColor = Color.parseColor("#FF9800");
                    status = "INVERTED";
                } else {
                    dotColor = Color.parseColor("#F44336");
                    status = "DEEP INVERSION";
                }
            } else {
                if (spread >= 0.50) {
                    dotColor = Color.parseColor("#4CAF50");
                    status = "HEALTHY";
                } else if (spread >= 0.00) {
                    dotColor = Color.parseColor("#FFEB3B");
                    status = "CAUTION";
                } else if (spread > -0.50) {
                    dotColor = Color.parseColor("#FF9800");
                    status = "WARNING";
                } else {
                    dotColor = Color.parseColor("#F44336");
                    status = "DANGER";
                }
            }

            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(dotColor);
            dotView.setBackground(dot);
            statusTv.setText(status);
        }
    }

    // ── Spread trend charts ──────────────────────────────────────────────────

    private void buildSpreadTrendChart(LineChart chart, List<EconomicDataPoint> data,
                                       String label, String colorHex) {
        if (data.isEmpty()) return;

        List<Entry> entries = new ArrayList<>();
        final List<String> dates = new ArrayList<>();

        for (int i = 0; i < data.size(); i++) {
            EconomicDataPoint p = data.get(i);
            entries.add(new Entry(i, (float) p.getValue()));
            String d = p.getDate();
            dates.add(d.length() >= 7 ? d.substring(5, 7) + "-" + d.substring(0, 4) : d);
        }

        chart.getXAxis().setValueFormatter(new ValueFormatter() {
            @Override public String getFormattedValue(float value) {
                int idx = (int) value;
                return (idx >= 0 && idx < dates.size()) ? dates.get(idx) : "";
            }
        });

        LineDataSet dataSet = new LineDataSet(entries, label);
        dataSet.setColor(Color.parseColor(colorHex));
        dataSet.setLineWidth(1.5f);
        dataSet.setDrawCircles(false);
        dataSet.setDrawValues(false);
        dataSet.setMode(LineDataSet.Mode.CUBIC_BEZIER);

        chart.setData(new LineData(dataSet));
        chart.invalidate();
    }

    // ── Spread chart limit lines ─────────────────────────────────────────────

    private void addSpreadLimitLines(LineChart chart, boolean is3M) {
        // Zero line — inversion boundary
        LimitLine zero = new LimitLine(0f, "Inversion");
        zero.setLineColor(Color.parseColor("#F44336"));
        zero.setLineWidth(1.2f);
        zero.setTextColor(Color.parseColor("#F44336"));
        zero.setTextSize(9f);
        zero.enableDashedLine(8f, 4f, 0f);
        chart.getAxisLeft().addLimitLine(zero);

        // Warning threshold
        LimitLine warn = new LimitLine(-0.5f, "Warning \u22120.5%");
        warn.setLineColor(Color.parseColor("#FF9800"));
        warn.setLineWidth(1f);
        warn.setTextColor(Color.parseColor("#FF9800"));
        warn.setTextSize(9f);
        warn.enableDashedLine(8f, 4f, 0f);
        chart.getAxisLeft().addLimitLine(warn);

        if (is3M) {
            // Deep inversion threshold only shown on 10Y-3M chart
            LimitLine deep = new LimitLine(-1.5f, "Deep \u22121.5%");
            deep.setLineColor(Color.parseColor("#9C27B0"));
            deep.setLineWidth(1f);
            deep.setTextColor(Color.parseColor("#9C27B0"));
            deep.setTextSize(9f);
            deep.enableDashedLine(8f, 4f, 0f);
            chart.getAxisLeft().addLimitLine(deep);
        }

        chart.getAxisLeft().setDrawLimitLinesBehindData(true);
    }
}
