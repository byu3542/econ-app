package com.economic.dashboard.utils;

import android.graphics.Color;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.Legend;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.components.Description;

public class ChartHelper {

    /**
     * Styles a LineChart with the app's light theme:
     * transparent background, subtle #EEEEEE grid lines, #888888 axis text,
     * #555555 legend with LINE form, and disabled description overlay.
     */
    public static void styleLineChart(LineChart chart, String description, String xTitle, String yTitle) {
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
        xAxis.setDrawGridLines(true);
        xAxis.setGridColor(Color.parseColor("#EEEEEE"));
        xAxis.setTextColor(Color.parseColor("#888888"));
        xAxis.setTextSize(10f);
        xAxis.setGranularity(1f);
        xAxis.setLabelRotationAngle(-45f);
        xAxis.setAvoidFirstLastClipping(true);
        xAxis.setLabelCount(6, false);
        xAxis.setAxisLineColor(Color.parseColor("#CCCCCC"));

        // Y Axis (Left)
        YAxis leftAxis = chart.getAxisLeft();
        leftAxis.setDrawGridLines(true);
        leftAxis.setGridColor(Color.parseColor("#EEEEEE"));
        leftAxis.setTextColor(Color.parseColor("#888888"));
        leftAxis.setTextSize(10f);
        leftAxis.setDrawZeroLine(true);
        leftAxis.setZeroLineColor(Color.parseColor("#CCCCCC"));
        leftAxis.setAxisLineColor(Color.parseColor("#CCCCCC"));
        leftAxis.setXOffset(10f); // Space for labels

        // Disable Right Axis
        chart.getAxisRight().setEnabled(false);

        // Legend
        Legend legend = chart.getLegend();
        legend.setVerticalAlignment(Legend.LegendVerticalAlignment.BOTTOM);
        legend.setHorizontalAlignment(Legend.LegendHorizontalAlignment.CENTER);
        legend.setOrientation(Legend.LegendOrientation.HORIZONTAL);
        legend.setDrawInside(false);
        legend.setTextSize(11f);
        legend.setTextColor(Color.parseColor("#555555"));
        legend.setForm(Legend.LegendForm.LINE);
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
