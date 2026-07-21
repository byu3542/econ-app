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

import com.economic.dashboard.databinding.FragmentGdpBinding;
import com.economic.dashboard.R;
import com.economic.dashboard.ui.MetricBottomSheet;
import com.economic.dashboard.models.EconomicDataPoint;
import com.economic.dashboard.ui.EconomicViewModel;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.ValueFormatter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class GdpFragment extends Fragment {

    private FragmentGdpBinding binding;

    private EconomicViewModel viewModel;
    private LineChart gdpChart;
    private TextView tvGdpLatest, tvGdpDesc, tvGdpStatusText;
    private View viewGdpIndicatorDot;
    private CardView cardGdpStatus;
    private TextView tvGdpLatestQuarterValue, tvGdpLatestQuarterLabel, tvGdpLatestQuarterStatus;
    private View viewGdpLatestQuarterDot;
    private CardView cardGdpLatestQuarter;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentGdpBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(requireActivity()).get(EconomicViewModel.class);

        gdpChart = binding.gdpChart;
        com.economic.dashboard.analyst.AskAnalyst.attachChartExplain(
                gdpChart, requireActivity(), "quarterly U.S. GDP growth");
        tvGdpLatest = binding.tvGdpLatest;
        tvGdpDesc = binding.tvGdpDesc;
        tvGdpStatusText = binding.tvGdpStatusText;
        viewGdpIndicatorDot = binding.viewGdpIndicatorDot;
        cardGdpStatus = binding.cardGdpStatus;
        com.economic.dashboard.analyst.AskAnalyst.wireCardLongPress(
                cardGdpStatus, requireActivity(), "GDP growth");
        cardGdpLatestQuarter = binding.cardGdpLatestQuarter;
        com.economic.dashboard.analyst.AskAnalyst.wireCardLongPress(
                cardGdpLatestQuarter, requireActivity(), "the latest quarter GDP reading");
        tvGdpLatestQuarterValue = binding.tvGdpLatestQuarterValue;
        tvGdpLatestQuarterLabel = binding.tvGdpLatestQuarterLabel;
        tvGdpLatestQuarterStatus = binding.tvGdpLatestQuarterStatus;
        viewGdpLatestQuarterDot = binding.viewGdpLatestQuarterDot;

        styleChart(gdpChart);
        gdpChart.getAxisLeft().setValueFormatter(new ValueFormatter() {
            @Override public String getFormattedValue(float value) {
                return String.format(Locale.US, "%.1f%%", value);
            }
        });

        viewModel.getGdpData().observe(getViewLifecycleOwner(), data -> {
            if (data != null) { buildGdpChart(data); updateGdpIndicator(data); updateLatestQuarterStatus(data); }
        });
        viewModel.getLatestQuarterGdp().observe(getViewLifecycleOwner(), value -> {
            if (value != null && tvGdpLatestQuarterValue != null) tvGdpLatestQuarterValue.setText(value);
        });
        viewModel.getLatestQuarterLabel().observe(getViewLifecycleOwner(), label -> {
            if (label != null && tvGdpLatestQuarterLabel != null) tvGdpLatestQuarterLabel.setText(label);
        });

        cardGdpStatus.setOnClickListener(v -> showGdpBenchmarks());
    }

    private void showGdpBenchmarks() {
        if (getContext() == null) return;
        MetricBottomSheet.show(getContext(), R.layout.dialog_gdp_status);
    }

    private void updateGdpIndicator(List<EconomicDataPoint> data) {
        List<EconomicDataPoint> gdpRows = EconomicViewModel.filterBySeries(data, "Gross domestic product");
        if (gdpRows.isEmpty()) return;

        double sum = 0; int count = 0;
        int startIndex = Math.max(0, gdpRows.size()-4);
        for (int i = startIndex; i < gdpRows.size(); i++) { sum += gdpRows.get(i).getValue(); count++; }
        double rollingAvg = (count > 0) ? sum/count : 0;

        tvGdpLatest.setText(String.format(Locale.US, "%.2f%%", rollingAvg));
        tvGdpDesc.setText("4-Quarter Rolling Average");

        String status; int dotColor;
        if (rollingAvg < 0)         { status = "RECESSION";        dotColor = Color.parseColor("#C75B4E"); }
        else if (rollingAvg <= 1.0) { status = "STAGNATION";       dotColor = Color.parseColor("#D98E4F"); }
        else if (rollingAvg <= 2.0) { status = "BELOW POTENTIAL";  dotColor = Color.parseColor("#DCC873"); }
        else if (rollingAvg <= 3.0) { status = "AT POTENTIAL";     dotColor = Color.parseColor("#6FA97A"); }
        else if (rollingAvg <= 4.0) { status = "ABOVE POTENTIAL";  dotColor = Color.parseColor("#5B8DB8"); }
        else                        { status = "OVERHEATING RISK"; dotColor = Color.parseColor("#8A6E9E"); }

        tvGdpStatusText.setText(status);
        tvGdpStatusText.setTextColor(Color.parseColor("#BBBBBB"));
        GradientDrawable dot = new GradientDrawable(); dot.setShape(GradientDrawable.OVAL); dot.setColor(dotColor);
        viewGdpIndicatorDot.setBackground(dot);
        cardGdpStatus.setCardBackgroundColor(Color.parseColor("#1A1F2B"));
    }

    private void updateLatestQuarterStatus(List<EconomicDataPoint> data) {
        List<EconomicDataPoint> gdpRows = EconomicViewModel.filterBySeries(data, "Gross domestic product");
        if (gdpRows.isEmpty()) return;
        double latestValue = gdpRows.get(gdpRows.size()-1).getValue();

        String status; int dotColor;
        if (latestValue < 0) { status = "CONTRACTION"; dotColor = Color.parseColor("#C75B4E"); }
        else if (latestValue < 2.0) { status = "SLOWING"; dotColor = Color.parseColor("#DCC873"); }
        else { status = "EXPANSION"; dotColor = Color.parseColor("#6FA97A"); }

        if (tvGdpLatestQuarterStatus != null) {
            tvGdpLatestQuarterStatus.setText(status);
            tvGdpLatestQuarterStatus.setTextColor(Color.parseColor("#BBBBBB"));
        }
        if (viewGdpLatestQuarterDot != null) {
            GradientDrawable dot = new GradientDrawable(); dot.setShape(GradientDrawable.OVAL); dot.setColor(dotColor);
            viewGdpLatestQuarterDot.setBackground(dot);
        }
        if (cardGdpLatestQuarter != null) cardGdpLatestQuarter.setCardBackgroundColor(Color.parseColor("#1A1F2B"));
    }

    private void buildGdpChart(List<EconomicDataPoint> data) {
        List<EconomicDataPoint> gdpRows = EconomicViewModel.filterBySeries(data, "Gross domestic product");
        if (gdpRows.isEmpty() && !data.isEmpty())
            gdpRows = EconomicViewModel.filterBySeries(data, data.get(0).getSeries());
        if (gdpRows.isEmpty()) return;

        List<EconomicDataPoint> chartRows = EconomicViewModel.filterByTimeframe(requireContext(), gdpRows);
        if (chartRows.isEmpty()) chartRows = gdpRows;

        List<Entry> entries = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        for (int i = 0; i < chartRows.size(); i++) {
            entries.add(new Entry(i, (float) chartRows.get(i).getValue()));
            labels.add(chartRows.get(i).getDate());
        }

        gdpChart.getXAxis().setValueFormatter(new ValueFormatter() {
            @Override public String getFormattedValue(float value) {
                int idx = (int) value;
                return (idx >= 0 && idx < labels.size()) ? labels.get(idx) : "";
            }
        });

        LineDataSet ds = new LineDataSet(entries, "GDP Growth (%)");
        ds.setColor(Color.parseColor("#1a73e8")); ds.setLineWidth(1.5f);
        ds.setDrawCircles(false); ds.setDrawValues(false); ds.setMode(LineDataSet.Mode.LINEAR);
        gdpChart.setData(new LineData(ds)); gdpChart.invalidate();
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
