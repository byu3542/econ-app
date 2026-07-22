package com.economic.dashboard.utils;

import android.content.Context;
import android.graphics.Color;

import androidx.core.content.ContextCompat;

import com.economic.dashboard.R;

import android.widget.TextView;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.Legend;
import com.github.mikephil.charting.components.MarkerView;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.components.Description;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.DefaultAxisValueFormatter;
import com.github.mikephil.charting.formatter.ValueFormatter;
import com.github.mikephil.charting.highlight.Highlight;
import com.github.mikephil.charting.utils.MPPointF;

public class ChartHelper {

    /**
     * Styles a LineChart with the app theme:
     * transparent background, theme grid lines and axis text, LINE-form
     * legend, and disabled description overlay. Grid visibility and Y-axis
     * decimal precision come from user settings (SettingsManager).
     */
    public static void styleLineChart(LineChart chart, String description, String xTitle, String yTitle) {
        Context ctx = chart.getContext();
        int gridColor   = ContextCompat.getColor(ctx, R.color.chart_grid);
        int axisLine    = ContextCompat.getColor(ctx, R.color.chart_axis_line);
        int axisText    = ContextCompat.getColor(ctx, R.color.chart_axis_text);
        int legendText  = ContextCompat.getColor(ctx, R.color.chart_legend_text);

        // User preferences
        boolean showGrid = SettingsManager.gridlinesEnabled(ctx);
        int decimals     = SettingsManager.getChartDecimals(ctx);

        // Disable description label (title is in the card header)
        Description desc = new Description();
        desc.setEnabled(false);
        chart.setDescription(desc);

        // Background & Borders — transparent so card background shows through
        chart.setBackgroundColor(Color.TRANSPARENT);
        chart.setDrawGridBackground(false);
        chart.setDrawBorders(false);

        // Interaction
        chart.setTouchEnabled(true);
        chart.setDragEnabled(true);
        chart.setScaleEnabled(true);
        chart.setPinchZoom(true);
        chart.setDoubleTapToZoomEnabled(true);

        // X Axis — de-cluttered: thin gridlines, fewer labels (TICKET-07)
        XAxis xAxis = chart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setDrawGridLines(showGrid);
        xAxis.setGridColor(gridColor);
        xAxis.setGridLineWidth(0.4f);
        xAxis.setTextColor(axisText);
        xAxis.setTextSize(10f);
        xAxis.setGranularity(1f);
        xAxis.setLabelRotationAngle(-45f);
        xAxis.setAvoidFirstLastClipping(true);
        xAxis.setLabelCount(5, false);
        xAxis.setAxisLineColor(axisLine);
        xAxis.setDrawAxisLine(false);

        // Y Axis (Left) — thin gridlines, no redundant axis line (TICKET-07)
        YAxis leftAxis = chart.getAxisLeft();
        leftAxis.setDrawGridLines(showGrid);
        leftAxis.setGridColor(gridColor);
        leftAxis.setGridLineWidth(0.4f);
        leftAxis.setTextColor(axisText);
        leftAxis.setTextSize(10f);
        leftAxis.setDrawZeroLine(true);
        leftAxis.setZeroLineColor(axisLine);
        leftAxis.setDrawAxisLine(false);
        leftAxis.setLabelCount(5, false);
        leftAxis.setXOffset(10f); // Space for labels
        leftAxis.setValueFormatter(new DefaultAxisValueFormatter(decimals));

        // Disable Right Axis
        chart.getAxisRight().setEnabled(false);

        // Legend
        Legend legend = chart.getLegend();
        legend.setVerticalAlignment(Legend.LegendVerticalAlignment.BOTTOM);
        legend.setHorizontalAlignment(Legend.LegendHorizontalAlignment.CENTER);
        legend.setOrientation(Legend.LegendOrientation.HORIZONTAL);
        legend.setDrawInside(false);
        legend.setTextSize(11f);
        legend.setTextColor(legendText);
        legend.setForm(Legend.LegendForm.LINE);
        legend.setYOffset(5f);

        // Loading state
        chart.setNoDataText("Awaiting Economic Data...");
        chart.setNoDataTextColor(axisText);

        // Padding to prevent labels from being cut off
        chart.setExtraBottomOffset(30f);
        chart.setExtraLeftOffset(10f);
        chart.setExtraTopOffset(20f);
        chart.setExtraRightOffset(20f);
    }

    /**
     * TICKET-07 — de-clutter pass for the navy-surface charts that carry their
     * own local {@code styleChart}. Call once right after that method: it thins
     * gridlines, drops the redundant axis lines, and trims the label count so
     * the plot reads cleanly at a glance. Colour choices are self-contained
     * (low-alpha white on navy) so no resource lookups are needed.
     */
    public static void declutterDark(LineChart chart) {
        if (chart == null) return;
        int faintGrid = Color.argb(0x0D, 0xFF, 0xFF, 0xFF); // ~5% white
        chart.getDescription().setEnabled(false);

        XAxis x = chart.getXAxis();
        x.setDrawGridLines(true);
        x.setGridColor(faintGrid);
        x.setGridLineWidth(0.4f);
        x.setDrawAxisLine(false);
        x.setLabelCount(5, false);
        x.setAvoidFirstLastClipping(true);

        YAxis y = chart.getAxisLeft();
        y.setDrawGridLines(true);
        y.setGridColor(faintGrid);
        y.setGridLineWidth(0.4f);
        y.setDrawAxisLine(false);
        y.setLabelCount(5, false);

        chart.getAxisRight().setEnabled(false);
        chart.setExtraRightOffset(28f); // room for right-end line labels (TICKET-08)
    }

    // ── TICKET-08: direct line labeling on multi-series charts ──────────────

    /**
     * Turns off the detached legend so multi-series charts can rely on labels
     * drawn at each line's right end instead (recognition over recall).
     */
    public static void useDirectLabels(LineChart chart) {
        if (chart == null) return;
        chart.getLegend().setEnabled(false);
        chart.setExtraRightOffset(40f); // reserve space for the end labels
    }

    /**
     * Draws {@code label} once, at the right-most point of {@code ds}, in the
     * line's own colour — so each series is identifiable without cross-checking
     * a legend. Values on all other points stay hidden.
     */
    public static void labelLineEnd(LineDataSet ds, String label) {
        if (ds == null || ds.getEntryCount() == 0) return;
        final float lastX = ds.getEntryForIndex(ds.getEntryCount() - 1).getX();
        ds.setDrawValues(true);
        ds.setValueTextColor(ds.getColor());
        ds.setValueTextSize(9f);
        ds.setValueFormatter(new ValueFormatter() {
            @Override public String getPointLabel(Entry entry) {
                return (entry != null && entry.getX() == lastX) ? label : "";
            }
        });
    }

    // ── TICKET-23: chart crosshair / value scrubbing ────────────────────────

    /**
     * Enables drag-to-scrub read-out on a line chart. Dragging highlights the
     * nearest point and shows a {@link ChartMarker} bubble with the date and the
     * unit-formatted value at that point. One-line opt-in per chart.
     *
     * The marker is cleared when the gesture ends. Charts that also use
     * {@code AskAnalyst.attachChartExplain} get that clear for free (its gesture
     * listener calls {@code highlightValue(null)} on gesture end); this method
     * deliberately does NOT install its own gesture listener so it never
     * clobbers the "Explain this chart" long-press.
     */
    public static void attachCrosshair(LineChart chart) {
        if (chart == null) return;
        ChartMarker marker = new ChartMarker(chart.getContext(), chart);
        marker.setChartView(chart);
        chart.setMarker(marker);
        chart.setDrawMarkers(true);
        chart.setHighlightPerDragEnabled(true);
        chart.setHighlightPerTapEnabled(true);
    }

    /**
     * Read-out bubble drawn at the touched point. Reuses the chart's own axis
     * value formatters so the date and unit match what's already on the axes;
     * falls back to {@link NumberFormatUtil} when a chart has no left-axis
     * formatter set.
     */
    public static class ChartMarker extends MarkerView {
        private final TextView tvValue;
        private final TextView tvDate;
        private final LineChart chart;

        public ChartMarker(Context ctx, LineChart chart) {
            super(ctx, R.layout.view_chart_marker);
            this.chart = chart;
            tvValue = findViewById(R.id.tvMarkerValue);
            tvDate  = findViewById(R.id.tvMarkerDate);
        }

        @Override public void refreshContent(Entry e, Highlight highlight) {
            if (e != null) {
                ValueFormatter yf = chart != null ? chart.getAxisLeft().getValueFormatter() : null;
                tvValue.setText(yf != null ? yf.getFormattedValue(e.getY())
                                           : NumberFormatUtil.number(e.getY(), 2));
                ValueFormatter xf = chart != null ? chart.getXAxis().getValueFormatter() : null;
                tvDate.setText(xf != null ? xf.getFormattedValue(e.getX()) : "");
            }
            super.refreshContent(e, highlight);
        }

        @Override public MPPointF getOffset() {
            return new MPPointF(-(getWidth() / 2f), -getHeight() - 8);
        }
    }
}
