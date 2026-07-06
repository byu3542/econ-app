package com.economic.dashboard.utils;

import android.content.Context;
import android.graphics.Color;

import androidx.core.content.ContextCompat;

import com.economic.dashboard.R;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.Legend;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.components.Description;
import com.github.mikephil.charting.formatter.DefaultAxisValueFormatter;

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

        // X Axis
        XAxis xAxis = chart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setDrawGridLines(showGrid);
        xAxis.setGridColor(gridColor);
        xAxis.setTextColor(axisText);
        xAxis.setTextSize(10f);
        xAxis.setGranularity(1f);
        xAxis.setLabelRotationAngle(-45f);
        xAxis.setAvoidFirstLastClipping(true);
        xAxis.setLabelCount(6, false);
        xAxis.setAxisLineColor(axisLine);

        // Y Axis (Left)
        YAxis leftAxis = chart.getAxisLeft();
        leftAxis.setDrawGridLines(showGrid);
        leftAxis.setGridColor(gridColor);
        leftAxis.setTextColor(axisText);
        leftAxis.setTextSize(10f);
        leftAxis.setDrawZeroLine(true);
        leftAxis.setZeroLineColor(axisLine);
        leftAxis.setAxisLineColor(axisLine);
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
}
