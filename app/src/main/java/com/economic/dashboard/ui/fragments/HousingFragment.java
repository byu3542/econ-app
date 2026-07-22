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

import com.economic.dashboard.databinding.FragmentHousingBinding;
import com.economic.dashboard.models.EconomicDataPoint;
import com.economic.dashboard.ui.EconomicViewModel;
import com.economic.dashboard.utils.NumberFormatUtil;
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
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Housing tab — Housing Starts (HOUST), Existing Home Sales (EXHOSLUSM495S),
 * 30-Year Mortgage Rate, 10Y Treasury rate, mortgage-treasury spread, and MBS holdings chart.
 */
public class HousingFragment extends Fragment {

    private FragmentHousingBinding binding;
    private com.economic.dashboard.ui.views.SkeletonController skeleton;

    private EconomicViewModel viewModel;

    // Housing Starts badge
    private TextView tvStartsValue, tvStartsStatus;
    private View viewStartsDot;

    // Existing Home Sales badge
    private TextView tvSalesValue, tvSalesStatus;
    private View viewSalesDot;

    // Rate badge cards
    private CardView badgeMortgage, badge10Y, badgeSpread;
    private TextView tvBadgeMortgageValue, tvBadge10YValue, tvBadgeSpreadValue;
    private TextView tvBadgeMortgageStatus, tvBadge10YStatus, tvBadgeSpreadStatus;
    private View viewBadgeMortgageDot, viewBadge10YDot, viewBadgeSpreadDot;

    // Swappable chart
    private LineChart chartSwappable;
    private TextView tvSwappableTitle;

    // MBS dual-axis chart
    private LineChart chartMbsMortgage;

    // State
    private String activeBadge = "mortgage";
    private List<EconomicDataPoint> currentMbsData;
    private List<EconomicDataPoint> currentTreasuryData;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentHousingBinding.inflate(inflater, container, false);
        skeleton = com.economic.dashboard.ui.views.SkeletonController.wrap(binding.getRoot());
        return skeleton.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(requireActivity()).get(EconomicViewModel.class);

        // TICKET-18: per-screen retry chip for this screen's series
        android.widget.TextView retryChip = view.findViewById(com.economic.dashboard.R.id.tvRetry);
        final String[] retryKeys = { EconomicViewModel.CACHE_MBS, EconomicViewModel.CACHE_TREASURY, EconomicViewModel.KEY_HOUSING };
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

        // Housing Starts badge
        tvStartsValue       = binding.tvStartsValue;
        tvStartsStatus      = binding.tvStartsStatus;
        viewStartsDot       = binding.viewStartsDot;

        // Existing Home Sales badge
        tvSalesValue        = binding.tvSalesValue;
        tvSalesStatus       = binding.tvSalesStatus;
        viewSalesDot        = binding.viewSalesDot;

        // Rate badge cards
        badgeMortgage         = binding.badgeMortgage;
        badge10Y              = binding.badge10Y;
        badgeSpread           = binding.badgeSpread;
        tvBadgeMortgageValue  = binding.tvBadgeMortgageValue;
        tvBadge10YValue       = binding.tvBadge10YValue;
        tvBadgeSpreadValue    = binding.tvBadgeSpreadValue;
        tvBadgeMortgageStatus = binding.tvBadgeMortgageStatus;
        tvBadge10YStatus      = binding.tvBadge10YStatus;
        tvBadgeSpreadStatus   = binding.tvBadgeSpreadStatus;
        viewBadgeMortgageDot  = binding.viewBadgeMortgageDot;
        viewBadge10YDot       = binding.viewBadge10YDot;
        viewBadgeSpreadDot    = binding.viewBadgeSpreadDot;

        // Swappable chart + title
        chartSwappable   = binding.chartSwappable;
        com.economic.dashboard.analyst.AskAnalyst.attachChartExplain(
                chartSwappable, requireActivity(), "housing market activity (housing starts and home sales)");
        tvSwappableTitle = binding.tvSwappableTitle;

        // MBS dual-axis chart
        chartMbsMortgage = binding.chartMbsMortgage;
        com.economic.dashboard.analyst.AskAnalyst.attachChartExplain(
                chartMbsMortgage, requireActivity(), "mortgage rates and MBS holdings");

        // Style swappable chart (dark theme)
        styleSwappableChart();
        com.economic.dashboard.utils.ChartHelper.declutterDark(chartSwappable);
        com.economic.dashboard.utils.ChartHelper.attachCrosshair(chartSwappable); // TICKET-23

        // TICKET-11: one inline range switcher drives both Housing charts.
        android.widget.LinearLayout tfSel = view.findViewById(com.economic.dashboard.R.id.timeframeSelector);
        com.economic.dashboard.utils.TimeframeSelector.attach(tfSel, "housing", months -> {
            buildSwappableChart();
            if (currentMbsData != null) buildMbsMortgageChart(currentMbsData);
        });

        // Badge tap listeners
        badgeMortgage.setOnClickListener(v -> {
            activeBadge = "mortgage";
            setActiveBadge("mortgage");
            buildSwappableChart();
        });
        badge10Y.setOnClickListener(v -> {
            activeBadge = "tenYear";
            setActiveBadge("tenYear");
            buildSwappableChart();
        });
        badgeSpread.setOnClickListener(v -> {
            activeBadge = "spread";
            setActiveBadge("spread");
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
        mbsLeftAxis.setTextColor(Color.parseColor("#C9A84C"));
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
        com.economic.dashboard.utils.ChartHelper.declutterDark(chartMbsMortgage);
        com.economic.dashboard.utils.ChartHelper.attachCrosshair(chartMbsMortgage); // TICKET-23

        viewModel.getHousingData().observe(getViewLifecycleOwner(), housing -> {
            if (housing != null) {
                updateStartsCard(housing);
                updateSalesCard(housing);
                if (skeleton != null) skeleton.reveal();
            }
        });

        // Observe MBS & mortgage data (badges + swappable chart + dual-axis chart)
        viewModel.getMbsMortgageData().observe(getViewLifecycleOwner(), mbsData -> {
            if (mbsData != null) {
                currentMbsData = mbsData;
                updateBadges();
                buildSwappableChart();
                buildMbsMortgageChart(mbsData);
                if (skeleton != null) skeleton.reveal();
            }
        });

        // Observe Treasury yield data (10Y T-Rate badge + Spread badge + swappable chart)
        viewModel.getTreasuryData().observe(getViewLifecycleOwner(), treasury -> {
            if (treasury != null) {
                currentTreasuryData = treasury;
                updateBadges();
                buildSwappableChart();
            }
        });
    }

    // ── Housing Starts Badge ──────────────────────────────────────────────────

    private void updateStartsCard(List<EconomicDataPoint> housingData) {
        List<EconomicDataPoint> rows = EconomicViewModel.filterBySeries(housingData, "Housing Starts");
        if (rows.isEmpty()) return;

        double latest = rows.get(rows.size() - 1).getValue();
        if (tvStartsValue != null)
            tvStartsValue.setText(NumberFormatUtil.number(latest, 0) + "K"); // TICKET-19

        // Thresholds in thousands of units (annualized monthly rate)
        String status; int color;
        if (latest < 900)        { status = "DEPRESSED";      color = Color.parseColor("#C75B4E"); }
        else if (latest < 1200)  { status = "BELOW AVERAGE";  color = Color.parseColor("#D98E4F"); }
        else if (latest < 1500)  { status = "NORMAL";         color = Color.parseColor("#6FA97A"); }
        else if (latest < 1800)  { status = "STRONG";         color = Color.parseColor("#5B8DB8"); }
        else                     { status = "VERY STRONG";    color = Color.parseColor("#8A6E9E"); }

        if (tvStartsStatus != null) tvStartsStatus.setText(status);
        setDot(viewStartsDot, color);
    }

    // ── Existing Home Sales Badge ─────────────────────────────────────────────

    private void updateSalesCard(List<EconomicDataPoint> housingData) {
        List<EconomicDataPoint> rows = EconomicViewModel.filterBySeries(housingData, "Existing Home Sales");
        if (rows.isEmpty()) return;

        double latest = rows.get(rows.size() - 1).getValue();
        // EXHOSLUSM495S returns values in THOUSANDS of units (SAAR), e.g. 3900 = 3.9M
        if (tvSalesValue != null)
            tvSalesValue.setText(NumberFormatUtil.number(latest / 1_000.0, 2) + "M"); // TICKET-19

        // Thresholds in thousands of units (matching FRED units)
        String status; int color;
        if (latest < 3_000)  { status = "VERY WEAK";   color = Color.parseColor("#C75B4E"); }
        else if (latest < 4_000) { status = "WEAK";    color = Color.parseColor("#D98E4F"); }
        else if (latest < 5_000) { status = "NORMAL";  color = Color.parseColor("#6FA97A"); }
        else if (latest < 6_000) { status = "STRONG";  color = Color.parseColor("#5B8DB8"); }
        else                 { status = "VERY STRONG"; color = Color.parseColor("#8A6E9E"); }

        if (tvSalesStatus != null) tvSalesStatus.setText(status);
        setDot(viewSalesDot, color);
    }

    // ── Rate Badges ── (10Y T-Rate + Spread pull from the Yields-tab treasury data)

    private void updateBadges() {
        // 30-Yr Mortgage Rate
        if (currentMbsData != null) {
            List<EconomicDataPoint> mortgageRows = EconomicViewModel.filterBySeries(currentMbsData, "30-Yr Mortgage Rate");
            if (!mortgageRows.isEmpty()) {
                double val = mortgageRows.get(mortgageRows.size() - 1).getValue();
                if (tvBadgeMortgageValue != null)
                    tvBadgeMortgageValue.setText(NumberFormatUtil.percent(val)); // TICKET-19
                String status; int color;
                if (val < 4.0)       { status = "LOW";       color = Color.parseColor("#6FA97A"); }
                else if (val < 5.5)  { status = "MODERATE";  color = Color.parseColor("#D98E4F"); }
                else if (val < 7.0)  { status = "HIGH";      color = Color.parseColor("#C75B4E"); }
                else                 { status = "VERY HIGH"; color = Color.parseColor("#8E3B32"); }
                if (tvBadgeMortgageStatus != null) tvBadgeMortgageStatus.setText(status);
                setDot(viewBadgeMortgageDot, color);
            }
        }

        // 10Y Treasury Rate (same data source as the Markets / Yields tab)
        if (currentTreasuryData != null) {
            List<EconomicDataPoint> tenYearRows = EconomicViewModel.filterBySeries(currentTreasuryData, "10 Year");
            if (!tenYearRows.isEmpty()) {
                double val = tenYearRows.get(tenYearRows.size() - 1).getValue();
                if (tvBadge10YValue != null)
                    tvBadge10YValue.setText(NumberFormatUtil.percent(val)); // TICKET-19
                String status; int color;
                if (val < 3.0)       { status = "LOW";       color = Color.parseColor("#6FA97A"); }
                else if (val < 4.0)  { status = "MODERATE";  color = Color.parseColor("#D98E4F"); }
                else if (val < 5.0)  { status = "HIGH";      color = Color.parseColor("#C75B4E"); }
                else                 { status = "VERY HIGH"; color = Color.parseColor("#8E3B32"); }
                if (tvBadge10YStatus != null) tvBadge10YStatus.setText(status);
                setDot(viewBadge10YDot, color);
            }
        }

        // Spread: 30-Yr Mortgage Rate minus 10Y Treasury (long-run norm ~1.7%)
        List<EconomicDataPoint> spreadRows = computeSpreadRows();
        if (!spreadRows.isEmpty()) {
            double val = spreadRows.get(spreadRows.size() - 1).getValue();
            if (tvBadgeSpreadValue != null)
                tvBadgeSpreadValue.setText(NumberFormatUtil.percent(val)); // TICKET-19
            String status; int color;
            if (val < 1.5)       { status = "NARROW";    color = Color.parseColor("#5B8DB8"); }
            else if (val < 2.2)  { status = "NORMAL";    color = Color.parseColor("#6FA97A"); }
            else if (val < 2.8)  { status = "WIDE";      color = Color.parseColor("#D98E4F"); }
            else                 { status = "VERY WIDE"; color = Color.parseColor("#C75B4E"); }
            if (tvBadgeSpreadStatus != null) tvBadgeSpreadStatus.setText(status);
            setDot(viewBadgeSpreadDot, color);
        }
    }

    /**
     * Pairs each weekly 30-Yr mortgage print with the most recent daily 10Y
     * Treasury close on or before that date and returns the spread series.
     * Treasury data covers the current year, so the spread series is YTD only.
     */
    private List<EconomicDataPoint> computeSpreadRows() {
        List<EconomicDataPoint> result = new ArrayList<>();
        if (currentMbsData == null || currentTreasuryData == null) return result;
        List<EconomicDataPoint> mortgageRows = EconomicViewModel.filterBySeries(currentMbsData, "30-Yr Mortgage Rate");
        List<EconomicDataPoint> treasuryRows = EconomicViewModel.filterBySeries(currentTreasuryData, "10 Year");
        if (mortgageRows.isEmpty() || treasuryRows.isEmpty()) return result;

        int tIdx = 0;
        EconomicDataPoint lastTreasury = null;
        for (EconomicDataPoint m : mortgageRows) {
            while (tIdx < treasuryRows.size()
                    && treasuryRows.get(tIdx).getDate().compareTo(m.getDate()) <= 0) {
                lastTreasury = treasuryRows.get(tIdx);
                tIdx++;
            }
            if (lastTreasury != null) {
                result.add(new EconomicDataPoint("Calculated", "Housing", "Mortgage-10Y Spread",
                        m.getDate(), m.getValue() - lastTreasury.getValue(), "%"));
            }
        }
        return result;
    }

    private void setActiveBadge(String which) {
        if (badgeMortgage != null)
            badgeMortgage.setForeground("mortgage".equals(which) ? makeActiveBorder() : null);
        if (badge10Y != null)
            badge10Y.setForeground("tenYear".equals(which) ? makeActiveBorder() : null);
        if (badgeSpread != null)
            badgeSpread.setForeground("spread".equals(which) ? makeActiveBorder() : null);

        // Update swappable chart title
        if (tvSwappableTitle != null) {
            switch (which) {
                case "mortgage": tvSwappableTitle.setText("30-Year Mortgage Rate (%)"); break;
                case "tenYear":  tvSwappableTitle.setText("10-Year Treasury Rate (%)"); break;
                case "spread":   tvSwappableTitle.setText("Mortgage - 10Y Treasury Spread (%)"); break;
            }
        }
    }

    private Drawable makeActiveBorder() {
        // Stroke is drawn centered on the shape edge; inset it so the full
        // width renders inside the CardView's rounded clip, and match the
        // card's 12dp corner radius (minus the inset) so corners align.
        float density = getResources().getDisplayMetrics().density;
        int strokePx = Math.max(2, Math.round(1.5f * density));
        int insetPx = (int) Math.ceil(strokePx / 2f);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.TRANSPARENT);
        gd.setCornerRadius(12f * density - insetPx);
        gd.setStroke(strokePx, Color.parseColor("#C9A84C"));
        return new InsetDrawable(gd, insetPx);
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
        if (chartSwappable == null) return;

        String seriesLabel;
        String lineColor;
        List<EconomicDataPoint> rows;
        boolean twoDecimals = false;

        switch (activeBadge) {
            case "tenYear":
                if (currentTreasuryData == null) return;
                rows = EconomicViewModel.filterBySeries(currentTreasuryData, "10 Year");
                seriesLabel = "10Y Treasury Rate";
                lineColor = "#C9A84C";
                break;
            case "spread":
                rows = computeSpreadRows();
                seriesLabel = "Mortgage-10Y Spread";
                lineColor = "#BBBBBB";
                twoDecimals = true;
                break;
            default: // mortgage
                if (currentMbsData == null) return;
                rows = EconomicViewModel.filterBySeries(currentMbsData, "30-Yr Mortgage Rate");
                seriesLabel = "30-Yr Mortgage Rate";
                lineColor = "#E07060";
                break;
        }

        if (rows.isEmpty()) return;

        // Standardized chart window (Settings -> Charts -> time range)
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        rows = EconomicViewModel.filterByTimeframe(requireContext(), rows, "housing");
        if (rows.isEmpty()) return;

        List<Entry> entries = new ArrayList<>();
        final List<String> dateLabels = new ArrayList<>();
        SimpleDateFormat labelFmt = new SimpleDateFormat("MMM yy", Locale.US);

        for (int i = 0; i < rows.size(); i++) {
            double val = rows.get(i).getValue();
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

        // All swappable series are percentages; the spread gets extra precision
        final boolean useTwoDecimals = twoDecimals;
        chartSwappable.getAxisLeft().setValueFormatter(new ValueFormatter() {
            @Override public String getFormattedValue(float v) {
                return String.format(Locale.US, useTwoDecimals ? "%.2f%%" : "%.1f%%", v);
            }
        });

        LineDataSet ds = makeLineDataSet(entries, seriesLabel, lineColor, false);
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

        // Standardized chart window (Settings -> Charts -> time range)
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);

        bankRows     = EconomicViewModel.filterByTimeframe(requireContext(), bankRows, "housing");
        fedRows      = EconomicViewModel.filterByTimeframe(requireContext(), fedRows, "housing");
        mortgageRows = EconomicViewModel.filterByTimeframe(requireContext(), mortgageRows, "housing");

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
        LineDataSet bankSet = makeLineDataSet(bankEntries, "Bank MBS Holdings", "#C9A84C", false);
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

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
