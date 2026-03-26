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
import com.github.mikephil.charting.components.Legend;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.ValueFormatter;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Housing tab — Housing Starts (HOUST), Existing Home Sales (EXHOSLUSM495S),
 * MBS holdings, and 30-Year Mortgage Rate.
 */
public class HousingFragment extends Fragment {

    private EconomicViewModel viewModel;

    // Housing Starts badge
    private TextView tvStartsValue, tvStartsStatus;
    private View viewStartsDot;

    // Existing Home Sales badge
    private TextView tvSalesValue, tvSalesStatus;
    private View viewSalesDot;

    // MBS Badge cards
    private CardView badgeMortgage, badgeBankMbs, badgeFedMbs;
    private TextView tvBadgeMortgageValue, tvBadgeBankMbsValue, tvBadgeFedMbsValue;
    private TextView tvBadgeMortgageStatus, tvBadgeBankMbsStatus, tvBadgeFedMbsStatus;
    private View viewBadgeMortgageDot, viewBadgeBankMbsDot, viewBadgeFedMbsDot;

    // Swappable chart
    private LineChart chartSwappable;
    private TextView tvSwappableTitle;

    // MBS dual-axis chart
    private LineChart chartMbsMortgage;

    // State
    private String activeBadge = "mortgage";
    private List<EconomicDataPoint> currentMbsData;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_housing, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(requireActivity()).get(EconomicViewModel.class);

        // Housing Starts badge
        tvStartsValue       = view.findViewById(R.id.tvStartsValue);
        tvStartsStatus      = view.findViewById(R.id.tvStartsStatus);
        viewStartsDot       = view.findViewById(R.id.viewStartsDot);

        // Existing Home Sales badge
        tvSalesValue        = view.findViewById(R.id.tvSalesValue);
        tvSalesStatus       = view.findViewById(R.id.tvSalesStatus);
        viewSalesDot        = view.findViewById(R.id.viewSalesDot);

        // MBS Badge cards
        badgeMortgage         = view.findViewById(R.id.badgeMortgage);
        badgeBankMbs          = view.findViewById(R.id.badgeBankMbs);
        badgeFedMbs           = view.findViewById(R.id.badgeFedMbs);
        tvBadgeMortgageValue  = view.findViewById(R.id.tvBadgeMortgageValue);
        tvBadgeBankMbsValue   = view.findViewById(R.id.tvBadgeBankMbsValue);
        tvBadgeFedMbsValue    = view.findViewById(R.id.tvBadgeFedMbsValue);
        tvBadgeMortgageStatus = view.findViewById(R.id.tvBadgeMortgageStatus);
        tvBadgeBankMbsStatus  = view.findViewById(R.id.tvBadgeBankMbsStatus);
        tvBadgeFedMbsStatus   = view.findViewById(R.id.tvBadgeFedMbsStatus);
        viewBadgeMortgageDot  = view.findViewById(R.id.viewBadgeMortgageDot);
        viewBadgeBankMbsDot   = view.findViewById(R.id.viewBadgeBankMbsDot);
        viewBadgeFedMbsDot    = view.findViewById(R.id.viewBadgeFedMbsDot);

        // Swappable chart + title
        chartSwappable   = view.findViewById(R.id.chartSwappable);
        tvSwappableTitle = view.findViewById(R.id.tvSwappableTitle);

        // MBS dual-axis chart
        chartMbsMortgage = view.findViewById(R.id.chartMbsMortgage);

        // Style swappable chart (dark theme)
        styleSwappableChart();

        // Badge tap listeners
        badgeMortgage.setOnClickListener(v -> {
            activeBadge = "mortgage";
            setActiveBadge("mortgage");
            buildSwappableChart();
        });
        badgeBankMbs.setOnClickListener(v -> {
            activeBadge = "bankMbs";
            setActiveBadge("bankMbs");
            buildSwappableChart();
        });
        badgeFedMbs.setOnClickListener(v -> {
            activeBadge = "fedMbs";
            setActiveBadge("fedMbs");
            buildSwappableChart();
        });

        // Set default active badge
        setActiveBadge("mortgage");

        // ── MBS Chart: full dark theme styling ──────────────────────────────
        int mbsDarkBg = Color.parseColor("#1C2236");
        int mbsSubtleGrid = Color.argb(0x14, 0xFF, 0xFF, 0xFF); // ~8% white

        chartMbsMortgage.setBackgroundColor(mbsDarkBg);
        chartMbsMortgage.setDrawGridBackground(false);
        chartMbsMortgage.setDrawBorders(false);
        chartMbsMortgage.setTouchEnabled(true);
        chartMbsMortgage.setDragEnabled(true);
        chartMbsMortgage.setScaleEnabled(true);
        chartMbsMortgage.setPinchZoom(true);
        chartMbsMortgage.setDoubleTapToZoomEnabled(true);
        chartMbsMortgage.getDescription().setEnabled(false);
        chartMbsMortgage.setNoDataText("Awaiting MBS Data...");
        chartMbsMortgage.setNoDataTextColor(Color.parseColor("#5A6A8A"));
        chartMbsMortgage.setExtraBottomOffset(30f);
        chartMbsMortgage.setExtraLeftOffset(10f);
        chartMbsMortgage.setExtraTopOffset(10f);
        chartMbsMortgage.setExtraRightOffset(10f);

        // X-axis
        XAxis mbsXAxis = chartMbsMortgage.getXAxis();
        mbsXAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        mbsXAxis.setTextColor(Color.parseColor("#5A6A8A"));
        mbsXAxis.setTextSize(10f);
        mbsXAxis.setDrawGridLines(true);
        mbsXAxis.setGridColor(mbsSubtleGrid);
        mbsXAxis.setAxisLineColor(mbsSubtleGrid);
        mbsXAxis.setLabelRotationAngle(-45f);
        mbsXAxis.setGranularity(1f);
        mbsXAxis.setLabelCount(8, false);
        mbsXAxis.setAvoidFirstLastClipping(true);

        // Left Y-axis (MBS holdings in billions → display as trillions)
        YAxis mbsLeftAxis = chartMbsMortgage.getAxisLeft();
        mbsLeftAxis.setTextColor(Color.parseColor("#C8A84B"));
        mbsLeftAxis.setTextSize(10f);
        mbsLeftAxis.setDrawGridLines(true);
        mbsLeftAxis.setGridColor(mbsSubtleGrid);
        mbsLeftAxis.setAxisLineColor(mbsSubtleGrid);
        mbsLeftAxis.setDrawZeroLine(false);
        mbsLeftAxis.setValueFormatter(new ValueFormatter() {
            @Override public String getFormattedValue(float v) {
                return String.format(Locale.US, "$%.1fT", v / 1000f);
            }
        });

        // Right Y-axis (mortgage rate %)
        YAxis mbsRightAxis = chartMbsMortgage.getAxisRight();
        mbsRightAxis.setEnabled(true);
        mbsRightAxis.setTextColor(Color.parseColor("#E07060"));
        mbsRightAxis.setTextSize(10f);
        mbsRightAxis.setDrawGridLines(false);
        mbsRightAxis.setAxisLineColor(mbsSubtleGrid);
        mbsRightAxis.setValueFormatter(new ValueFormatter() {
            @Override public String getFormattedValue(float v) {
                return String.format(Locale.US, "%.1f%%", v);
            }
        });

        // Legend
        Legend mbsLegend = chartMbsMortgage.getLegend();
        mbsLegend.setTextColor(Color.parseColor("#8899BB"));
        mbsLegend.setTextSize(11f);
        mbsLegend.setForm(Legend.LegendForm.LINE);
        mbsLegend.setFormSize(16f);
        mbsLegend.setFormLineWidth(2f);
        mbsLegend.setVerticalAlignment(Legend.LegendVerticalAlignment.BOTTOM);
        mbsLegend.setHorizontalAlignment(Legend.LegendHorizontalAlignment.CENTER);
        mbsLegend.setOrientation(Legend.LegendOrientation.HORIZONTAL);
        mbsLegend.setDrawInside(false);
        mbsLegend.setYOffset(5f);

        // Observe housing data (metric cards only)
        viewModel.getHousingData().observe(getViewLifecycleOwner(), housing -> {
            if (housing != null) {
                updateStartsCard(housing);
                updateSalesCard(housing);
            }
        });

        // Observe MBS & mortgage data (badges + swappable chart + dual-axis chart)
        viewModel.getMbsMortgageData().observe(getViewLifecycleOwner(), mbsData -> {
            if (mbsData != null) {
                currentMbsData = mbsData;
                updateBadges(mbsData);
                buildSwappableChart();
                buildMbsMortgageChart(mbsData);
            }
        });
    }

    // ── Housing Starts Badge ──────────────────────────────────────────────────

    private void updateStartsCard(List<EconomicDataPoint> housingData) {
        List<EconomicDataPoint> rows = EconomicViewModel.filterBySeries(housingData, "Housing Starts");
        if (rows.isEmpty()) return;

        double latest = rows.get(rows.size() - 1).getValue();
        if (tvStartsValue != null)
            tvStartsValue.setText(String.format(Locale.US, "%.0fK", latest));

        // Thresholds in thousands of units (annualized monthly rate)
        String status; int color;
        if (latest < 900)        { status = "DEPRESSED";      color = Color.parseColor("#F44336"); }
        else if (latest < 1200)  { status = "BELOW AVERAGE";  color = Color.parseColor("#FF9800"); }
        else if (latest < 1500)  { status = "NORMAL";         color = Color.parseColor("#4CAF50"); }
        else if (latest < 1800)  { status = "STRONG";         color = Color.parseColor("#2196F3"); }
        else                     { status = "VERY STRONG";    color = Color.parseColor("#9C27B0"); }

        if (tvStartsStatus != null) tvStartsStatus.setText(status);
        setDot(viewStartsDot, color);
    }

    // ── Existing Home Sales Badge ─────────────────────────────────────────────

    private void updateSalesCard(List<EconomicDataPoint> housingData) {
        List<EconomicDataPoint> rows = EconomicViewModel.filterBySeries(housingData, "Existing Home Sales");
        if (rows.isEmpty()) return;

        double latest = rows.get(rows.size() - 1).getValue();
        // EXHOSLUSM495S returns raw unit counts (e.g. 3,910,000); display in millions
        if (tvSalesValue != null)
            tvSalesValue.setText(String.format(Locale.US, "%.2fM", latest / 1_000_000.0));

        // Thresholds in actual units (SAAR)
        String status; int color;
        if (latest < 3_000_000)  { status = "VERY WEAK";      color = Color.parseColor("#F44336"); }
        else if (latest < 4_000_000) { status = "WEAK";        color = Color.parseColor("#FF9800"); }
        else if (latest < 5_000_000) { status = "NORMAL";      color = Color.parseColor("#4CAF50"); }
        else if (latest < 6_000_000) { status = "STRONG";      color = Color.parseColor("#2196F3"); }
        else                     { status = "VERY STRONG";    color = Color.parseColor("#9C27B0"); }

        if (tvSalesStatus != null) tvSalesStatus.setText(status);
        setDot(viewSalesDot, color);
    }

    // ── MBS Badges ─────────────────────────────────────────────────────────────

    private void updateBadges(List<EconomicDataPoint> mbsData) {
        // 30-Yr Mortgage Rate
        List<EconomicDataPoint> mortgageRows = EconomicViewModel.filterBySeries(mbsData, "30-Yr Mortgage Rate");
        if (!mortgageRows.isEmpty()) {
            double val = mortgageRows.get(mortgageRows.size() - 1).getValue();
            if (tvBadgeMortgageValue != null)
                tvBadgeMortgageValue.setText(String.format(Locale.US, "%.2f%%", val));
            String status; int color;
            if (val < 4.0)       { status = "LOW";       color = Color.parseColor("#4CAF50"); }
            else if (val < 5.5)  { status = "MODERATE";  color = Color.parseColor("#FF9800"); }
            else if (val < 7.0)  { status = "HIGH";      color = Color.parseColor("#F44336"); }
            else                 { status = "VERY HIGH"; color = Color.parseColor("#B71C1C"); }
            if (tvBadgeMortgageStatus != null) tvBadgeMortgageStatus.setText(status);
            setDot(viewBadgeMortgageDot, color);
        }

        // Bank MBS Holdings (billions)
        List<EconomicDataPoint> bankRows = EconomicViewModel.filterBySeries(mbsData, "Bank MBS Holdings");
        if (!bankRows.isEmpty()) {
            double val = bankRows.get(bankRows.size() - 1).getValue();
            if (tvBadgeBankMbsValue != null)
                tvBadgeBankMbsValue.setText(String.format(Locale.US, "$%.0fB", val));
            String status; int color;
            if (val < 2500)      { status = "DECLINING"; color = Color.parseColor("#FF9800"); }
            else if (val < 2800) { status = "STABLE";    color = Color.parseColor("#4CAF50"); }
            else                 { status = "GROWING";   color = Color.parseColor("#2196F3"); }
            if (tvBadgeBankMbsStatus != null) tvBadgeBankMbsStatus.setText(status);
            setDot(viewBadgeBankMbsDot, color);
        }

        // Fed MBS Holdings (millions → convert to billions for display)
        List<EconomicDataPoint> fedRows = EconomicViewModel.filterBySeries(mbsData, "Fed MBS Holdings");
        if (!fedRows.isEmpty()) {
            double valMillions = fedRows.get(fedRows.size() - 1).getValue();
            double valBillions = valMillions / 1000.0;
            if (tvBadgeFedMbsValue != null)
                tvBadgeFedMbsValue.setText(String.format(Locale.US, "$%.0fB", valBillions));
            String status; int color;
            if (valBillions < 2300)      { status = "QT ACTIVE";  color = Color.parseColor("#FF9800"); }
            else if (valBillions < 2600) { status = "REDUCING";   color = Color.parseColor("#4CAF50"); }
            else                         { status = "ELEVATED";   color = Color.parseColor("#2196F3"); }
            if (tvBadgeFedMbsStatus != null) tvBadgeFedMbsStatus.setText(status);
            setDot(viewBadgeFedMbsDot, color);
        }
    }

    private void setActiveBadge(String which) {
        if (badgeMortgage != null)
            badgeMortgage.setForeground("mortgage".equals(which) ? makeActiveBorder() : null);
        if (badgeBankMbs != null)
            badgeBankMbs.setForeground("bankMbs".equals(which) ? makeActiveBorder() : null);
        if (badgeFedMbs != null)
            badgeFedMbs.setForeground("fedMbs".equals(which) ? makeActiveBorder() : null);

        // Update swappable chart title
        if (tvSwappableTitle != null) {
            switch (which) {
                case "mortgage": tvSwappableTitle.setText("30-Year Mortgage Rate (%)"); break;
                case "bankMbs":  tvSwappableTitle.setText("Bank MBS Holdings ($B)"); break;
                case "fedMbs":   tvSwappableTitle.setText("Fed MBS Holdings ($B)"); break;
            }
        }
    }

    private GradientDrawable makeActiveBorder() {
        float density = getResources().getDisplayMetrics().density;
        float cornerPx = 12 * density;
        int strokePx = (int) (1.5f * density);
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(cornerPx);
        gd.setStroke(strokePx, Color.parseColor("#C8A84B"));
        gd.setColor(Color.TRANSPARENT);
        return gd;
    }

    // ── Swappable Chart ────────────────────────────────────────────────────────

    private void styleSwappableChart() {
        int darkBg = Color.parseColor("#1C2236");
        int subtleGrid = Color.argb(0x14, 0xFF, 0xFF, 0xFF);

        chartSwappable.setBackgroundColor(darkBg);
        chartSwappable.setDrawGridBackground(false);
        chartSwappable.setDrawBorders(false);
        chartSwappable.setTouchEnabled(true);
        chartSwappable.setDragEnabled(true);
        chartSwappable.setScaleEnabled(true);
        chartSwappable.setPinchZoom(true);
        chartSwappable.getDescription().setEnabled(false);
        chartSwappable.setNoDataText("Select a badge above\u2026");
        chartSwappable.setNoDataTextColor(Color.parseColor("#5A6A8A"));
        chartSwappable.setExtraBottomOffset(8f);
        chartSwappable.setExtraLeftOffset(8f);
        chartSwappable.setExtraRightOffset(8f);

        XAxis xAxis = chartSwappable.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setTextColor(Color.parseColor("#5A6A8A"));
        xAxis.setTextSize(9f);
        xAxis.setDrawGridLines(true);
        xAxis.setGridColor(subtleGrid);
        xAxis.setAxisLineColor(subtleGrid);
        xAxis.setLabelRotationAngle(-45f);
        xAxis.setGranularity(1f);
        xAxis.setLabelCount(6, false);
        xAxis.setAvoidFirstLastClipping(true);

        YAxis leftAxis = chartSwappable.getAxisLeft();
        leftAxis.setTextColor(Color.parseColor("#8899BB"));
        leftAxis.setTextSize(10f);
        leftAxis.setDrawGridLines(true);
        leftAxis.setGridColor(subtleGrid);
        leftAxis.setAxisLineColor(subtleGrid);

        chartSwappable.getAxisRight().setEnabled(false);

        Legend legend = chartSwappable.getLegend();
        legend.setEnabled(false); // single-line chart, no legend needed
    }

    private void buildSwappableChart() {
        if (chartSwappable == null || currentMbsData == null) return;

        String seriesName;
        String lineColor;
        boolean convertToB = false;

        switch (activeBadge) {
            case "bankMbs":
                seriesName = "Bank MBS Holdings";
                lineColor = "#C8A84B";
                break;
            case "fedMbs":
                seriesName = "Fed MBS Holdings";
                lineColor = "#BBBBBB";
                convertToB = true; // Fed MBS in millions, display in billions
                break;
            default: // mortgage
                seriesName = "30-Yr Mortgage Rate";
                lineColor = "#E07060";
                break;
        }

        List<EconomicDataPoint> rows = EconomicViewModel.filterBySeries(currentMbsData, seriesName);
        if (rows.isEmpty()) return;

        // Filter to last 5 years
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.YEAR, -5);
        String cutoff = sdf.format(cal.getTime());
        rows = filterAfterDate(rows, cutoff);
        if (rows.isEmpty()) return;

        List<Entry> entries = new ArrayList<>();
        final List<String> dateLabels = new ArrayList<>();
        SimpleDateFormat labelFmt = new SimpleDateFormat("MMM yy", Locale.US);

        for (int i = 0; i < rows.size(); i++) {
            double val = rows.get(i).getValue();
            if (convertToB) val /= 1000.0;
            entries.add(new Entry(i, (float) val));
            try {
                Date date = sdf.parse(rows.get(i).getDate());
                dateLabels.add(date != null ? labelFmt.format(date) : "");
            } catch (ParseException e) {
                dateLabels.add("");
            }
        }

        chartSwappable.getXAxis().setValueFormatter(new ValueFormatter() {
            @Override public String getFormattedValue(float v) {
                int idx = (int) v;
                return (idx >= 0 && idx < dateLabels.size()) ? dateLabels.get(idx) : "";
            }
        });

        // Configure left axis formatter based on active series
        final boolean isMortgage = "mortgage".equals(activeBadge);
        chartSwappable.getAxisLeft().setValueFormatter(new ValueFormatter() {
            @Override public String getFormattedValue(float v) {
                return isMortgage
                        ? String.format(Locale.US, "%.1f%%", v)
                        : String.format(Locale.US, "$%.0fB", v);
            }
        });

        LineDataSet ds = makeLineDataSet(entries, seriesName, lineColor, false);
        ds.setDrawCircles(false);
        ds.setDrawCircleHole(false);
        ds.setLineWidth(1.5f);
        ds.setDrawFilled(false);

        chartSwappable.setData(new LineData(ds));
        chartSwappable.animateX(500);
        chartSwappable.invalidate();
    }

    // ── MBS & Mortgage Dual-Axis Chart ─────────────────────────────────────────

    private void buildMbsMortgageChart(List<EconomicDataPoint> mbsData) {
        if (chartMbsMortgage == null) return;

        List<EconomicDataPoint> bankRows     = EconomicViewModel.filterBySeries(mbsData, "Bank MBS Holdings");
        List<EconomicDataPoint> fedRows      = EconomicViewModel.filterBySeries(mbsData, "Fed MBS Holdings");
        List<EconomicDataPoint> mortgageRows = EconomicViewModel.filterBySeries(mbsData, "30-Yr Mortgage Rate");

        if (bankRows.isEmpty() && fedRows.isEmpty() && mortgageRows.isEmpty()) return;

        // Filter to last 5 years
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.YEAR, -5);
        String cutoff = sdf.format(cal.getTime());

        bankRows     = filterAfterDate(bankRows, cutoff);
        fedRows      = filterAfterDate(fedRows, cutoff);
        mortgageRows = filterAfterDate(mortgageRows, cutoff);

        // Build a unified date index from all three series
        List<String> allDates = new ArrayList<>();
        for (EconomicDataPoint p : bankRows) {
            if (!allDates.contains(p.getDate())) allDates.add(p.getDate());
        }
        for (EconomicDataPoint p : fedRows) {
            if (!allDates.contains(p.getDate())) allDates.add(p.getDate());
        }
        for (EconomicDataPoint p : mortgageRows) {
            if (!allDates.contains(p.getDate())) allDates.add(p.getDate());
        }
        java.util.Collections.sort(allDates);

        // Build entries — Bank MBS is already in billions; normalize Fed MBS
        // from millions to billions so both share the same left-axis scale
        List<Entry> bankEntries     = buildEntriesForDates(bankRows, allDates);
        List<Entry> fedEntries      = new ArrayList<>();
        int fedIdx = 0;
        for (int i = 0; i < allDates.size() && fedIdx < fedRows.size(); i++) {
            if (allDates.get(i).equals(fedRows.get(fedIdx).getDate())) {
                fedEntries.add(new Entry(i, (float) (fedRows.get(fedIdx).getValue() / 1000.0)));
                fedIdx++;
            }
        }
        List<Entry> mortgageEntries = buildEntriesForDates(mortgageRows, allDates);

        // Format X-axis labels as MMM yy
        SimpleDateFormat labelFmt = new SimpleDateFormat("MMM yy", Locale.US);
        final List<String> dateLabels = new ArrayList<>();
        for (String d : allDates) {
            try {
                Date date = sdf.parse(d);
                dateLabels.add(date != null ? labelFmt.format(date) : d);
            } catch (ParseException e) {
                dateLabels.add(d.length() >= 7 ? d.substring(5, 7) + "/" + d.substring(2, 4) : d);
            }
        }

        chartMbsMortgage.getXAxis().setValueFormatter(new ValueFormatter() {
            @Override public String getFormattedValue(float v) {
                int idx = (int) v;
                return (idx >= 0 && idx < dateLabels.size()) ? dateLabels.get(idx) : "";
            }
        });

        // Bank MBS — left axis, gold, thin line, no dots
        LineDataSet bankSet = makeLineDataSet(bankEntries, "Bank MBS Holdings", "#C8A84B", false);
        bankSet.setAxisDependency(YAxis.AxisDependency.LEFT);
        bankSet.setDrawCircles(false);
        bankSet.setDrawCircleHole(false);
        bankSet.setLineWidth(1.5f);
        bankSet.setDrawFilled(false);

        // Fed MBS — left axis, light gray, thin line, no dots
        LineDataSet fedSet = makeLineDataSet(fedEntries, "Fed MBS Holdings", "#BBBBBB", false);
        fedSet.setAxisDependency(YAxis.AxisDependency.LEFT);
        fedSet.setDrawCircles(false);
        fedSet.setDrawCircleHole(false);
        fedSet.setLineWidth(1.5f);
        fedSet.setDrawFilled(false);

        // Mortgage rate — right axis, muted coral, thin line, no dots
        LineDataSet mortgageSet = makeLineDataSet(mortgageEntries, "30-Yr Mortgage Rate", "#E07060", false);
        mortgageSet.setAxisDependency(YAxis.AxisDependency.RIGHT);
        mortgageSet.setDrawCircles(false);
        mortgageSet.setDrawCircleHole(false);
        mortgageSet.setLineWidth(1.5f);
        mortgageSet.setDrawFilled(false);

        chartMbsMortgage.setData(new LineData(bankSet, fedSet, mortgageSet));
        chartMbsMortgage.invalidate();
    }

    /** Filters data points to those on or after the given cutoff date string. */
    private List<EconomicDataPoint> filterAfterDate(List<EconomicDataPoint> rows, String cutoff) {
        List<EconomicDataPoint> result = new ArrayList<>();
        for (EconomicDataPoint p : rows) {
            if (p.getDate().compareTo(cutoff) >= 0) result.add(p);
        }
        return result;
    }

    /** Builds chart Entry list by mapping data points onto a shared date index. */
    private List<Entry> buildEntriesForDates(List<EconomicDataPoint> rows, List<String> allDates) {
        List<Entry> entries = new ArrayList<>();
        int rowIdx = 0;
        for (int i = 0; i < allDates.size() && rowIdx < rows.size(); i++) {
            if (allDates.get(i).equals(rows.get(rowIdx).getDate())) {
                entries.add(new Entry(i, (float) rows.get(rowIdx).getValue()));
                rowIdx++;
            }
        }
        return entries;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private LineDataSet makeLineDataSet(List<Entry> entries, String label, String hexColor, boolean filled) {
        LineDataSet ds = new LineDataSet(entries, label);
        int c = Color.parseColor(hexColor);
        ds.setColor(c);
        ds.setCircleColor(c);
        ds.setLineWidth(2.5f);
        ds.setCircleRadius(3f);
        ds.setDrawValues(false);
        ds.setMode(LineDataSet.Mode.CUBIC_BEZIER);
        if (filled) ds.setDrawCircles(false);
        return ds;
    }

    private void setDot(View dot, int color) {
        if (dot == null) return;
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.OVAL);
        gd.setColor(color);
        dot.setBackground(gd);
    }
}
