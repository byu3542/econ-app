package com.economic.dashboard.utils;

import android.graphics.Color;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.Legend;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.components.Description;

public class ChartHelper {

    /**
     * Styles a LineChart with standard themes and adds axis labels.
     * Note: MPAndroidChart does not have a native "Axis Title" property.
     * We simulate the Y-axis title using the Chart Description and the X-axis 
     * title is usually implied by the context or legend.
     */
    public static void styleLineChart(LineChart chart, String description, String xTitle, String yTitle) {
        // Use Description as a Y-axis label/Header
        Description desc = new Description();
        desc.setText(yTitle);
        desc.setTextColor(Color.parseColor("#888888"));
        desc.setTextSize(10f);
        // Position description at the top left
        desc.setPosition(120f, 40f); 
        chart.setDescription(desc);

        // Background & Borders
        chart.setBackgroundColor(Color.WHITE);
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
        xAxis.setDrawGridLines(true);
        xAxis.setGridColor(Color.parseColor("#F0F0F0"));
        xAxis.setTextColor(Color.parseColor("#666666"));
        xAxis.setTextSize(9f);
        xAxis.setGranularity(1f);
        xAxis.setLabelRotationAngle(-45f);
        xAxis.setAvoidFirstLastClipping(true);
        xAxis.setLabelCount(6, false);
        // We don't have a native xTitle property, but it's often dates.

        // Y Axis (Left)
        YAxis leftAxis = chart.getAxisLeft();
        leftAxis.setDrawGridLines(true);
        leftAxis.setGridColor(Color.parseColor("#F0F0F0"));
        leftAxis.setTextColor(Color.parseColor("#666666"));
        leftAxis.setTextSize(10f);
        leftAxis.setDrawZeroLine(true);
        leftAxis.setZeroLineColor(Color.parseColor("#CCCCCC"));
        leftAxis.setXOffset(10f); // Space for labels

        // Disable Right Axis
        chart.getAxisRight().setEnabled(false);

        // Legend
        Legend legend = chart.getLegend();
        legend.setVerticalAlignment(Legend.LegendVerticalAlignment.BOTTOM);
        legend.setHorizontalAlignment(Legend.LegendHorizontalAlignment.CENTER);
        legend.setOrientation(Legend.LegendOrientation.HORIZONTAL);
        legend.setDrawInside(false);
        legend.setTextSize(10f);
        legend.setTextColor(Color.parseColor("#444444"));
        legend.setYOffset(5f);

        // Loading state
        chart.setNoDataText("Awaiting Economic Data...");
        chart.setNoDataTextColor(Color.GRAY);

        // Padding to prevent labels from being cut off
        chart.setExtraBottomOffset(30f);
        chart.setExtraLeftOffset(10f);
        chart.setExtraTopOffset(20f);
        chart.setExtraRightOffset(20f);
    }
}
