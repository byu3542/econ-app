package com.economic.dashboard.ui.fragments;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.economic.dashboard.R;
import com.economic.dashboard.models.EconomicDataPoint;
import com.economic.dashboard.ui.EconomicViewModel;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class DashboardFragment extends Fragment {

    private EconomicViewModel viewModel;

    // Treasury rows
    private View row1M, row3M, row2Y, row10Y, row30Y;

    // Employment cards
    private View cardUnemployment, cardLabor;

    // CPI & Wages cards/rows
    private View cardCPI, cardHourlyWage;
    private View rowCPI, rowWages, rowHourlyWageRow;

    private TextView tvHeaderBadgeValue, tvHeaderDate, tvEyebrow;
    private SwipeRefreshLayout swipeRefresh;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_dashboard, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(requireActivity()).get(EconomicViewModel.class);

        bindViews(view);
        updateEyebrow();
        observeData();

        swipeRefresh.setOnRefreshListener(() -> {
            viewModel.fetchAllData();
        });

        viewModel.getIsLoading().observe(getViewLifecycleOwner(), loading -> {
            swipeRefresh.setRefreshing(loading);
        });
    }

    private void bindViews(View v) {
        swipeRefresh = v.findViewById(R.id.swipeRefresh);
        
        if (getActivity() != null) {
            tvHeaderBadgeValue = getActivity().findViewById(R.id.tvHeaderBadgeValue);
            tvEyebrow = getActivity().findViewById(R.id.tvEyebrow);
            tvHeaderDate = getActivity().findViewById(R.id.tvHeaderDate);
        }

        // Treasury Rows
        row1M  = v.findViewById(R.id.row1M);
        row3M  = v.findViewById(R.id.row3M);
        row2Y  = v.findViewById(R.id.row2Y);
        row10Y = v.findViewById(R.id.row10Y);
        row30Y = v.findViewById(R.id.row30Y);

        setupTreasuryRow(row1M, "1 Month");
        setupTreasuryRow(row3M, "3 Month");
        setupTreasuryRow(row2Y, "2 Year");
        setupTreasuryRow(row10Y, "10 Year");
        setupTreasuryRow(row30Y, "30 Year");

        // Employment Cards
        cardUnemployment = v.findViewById(R.id.cardUnemployment);
        cardLabor        = v.findViewById(R.id.cardLabor);

        setupCardLabel(cardUnemployment, "UNEMPLOYMENT");
        setupCardLabel(cardLabor, "LABOR PART.");

        // CPI & Wages
        cardCPI        = v.findViewById(R.id.cardCPI);
        cardHourlyWage = v.findViewById(R.id.cardHourlyWage);
        rowCPI         = v.findViewById(R.id.rowCPI);
        rowWages       = v.findViewById(R.id.rowWages);
        rowHourlyWageRow = v.findViewById(R.id.rowHourlyWageRow);

        setupCardLabel(cardCPI, "CPI-U");
        setupCardLabel(cardHourlyWage, "HRLY WAGE");
        
        setupTreasuryRow(rowCPI, "CPI-U (All Items)");
        setupTreasuryRow(rowHourlyWageRow, "Avg Hourly Wage");
        setupTreasuryRow(rowWages, "Avg Weekly Wage");
    }

    private void updateEyebrow() {
        if (tvEyebrow != null) {
            Calendar cal = Calendar.getInstance();
            int month = cal.get(Calendar.MONTH); // 0-11
            int quarter = (month / 3) + 1;
            int year = cal.get(Calendar.YEAR);
            String text = String.format(Locale.US, "U.S. ECONOMIC MONITOR  ·  Q%d %d", quarter, year);
            tvEyebrow.setText(text);
        }
    }

    private void setupTreasuryRow(View row, String label) {
        if (row != null) {
            TextView tv = row.findViewById(R.id.tvMaturity);
            if (tv != null) tv.setText(label);
        }
    }

    private void setupCardLabel(View card, String label) {
        if (card != null) {
            TextView tv = card.findViewById(R.id.tvMetricLabel);
            if (tv != null) tv.setText(label);
        }
    }

    private void observeData() {
        viewModel.getTreasuryData().observe(getViewLifecycleOwner(), data -> {
            if (data != null) {
                updateTreasury(data);
            }
        });
        viewModel.getFedFundsData().observe(getViewLifecycleOwner(), data -> {
            if (data != null) {
                updateFedFundsRate(data);
            }
        });
        viewModel.getEmploymentData().observe(getViewLifecycleOwner(), data -> {
            if (data != null) updateEmployment(data);
        });
        viewModel.getCpiData().observe(getViewLifecycleOwner(), data -> {
            if (data != null) updateCPI(data);
        });
        viewModel.getWageData().observe(getViewLifecycleOwner(), data -> {
            if (data != null) updateWages(data);
        });
    }

    private void updateFedFundsRate(List<EconomicDataPoint> data) {
        EconomicDataPoint p = EconomicViewModel.getLatest(data, "Federal Funds Effective Rate");
        if (p != null && tvHeaderBadgeValue != null) {
            tvHeaderBadgeValue.setText(String.format(Locale.US, "%.2f%%", p.getValue()));
        }
    }

    private void updateTreasury(List<EconomicDataPoint> data) {
        setTreasuryRate(data, "1 Month",  row1M);
        setTreasuryRate(data, "3 Month",  row3M);
        setTreasuryRate(data, "2 Year",   row2Y);
        setTreasuryRate(data, "10 Year",  row10Y);
        setTreasuryRate(data, "30 Year",  row30Y);
        updateTimestamp();
    }

    private void setTreasuryRate(List<EconomicDataPoint> data, String series, View row) {
        setRowMetric(data, series, row, "%.2f%%");
    }

    private void setRowMetric(List<EconomicDataPoint> data, String series, View row, String fmt) {
        if (row == null) return;
        EconomicDataPoint p = EconomicViewModel.getLatest(data, series);
        TextView valView = row.findViewById(R.id.tvYield);
        TextView dateView = row.findViewById(R.id.tvDate);
        
        if (p != null) {
            valView.setText(String.format(Locale.US, fmt, p.getValue()));
            dateView.setText(p.getDate());
        } else {
            valView.setText("—");
            dateView.setText("");
        }
    }

    private void updateEmployment(List<EconomicDataPoint> data) {
        setCardMetric(data, "Unemployment Rate",            cardUnemployment, "%.1f%%");
        setCardMetric(data, "Labor Force Participation Rate", cardLabor,        "%.1f%%");
        updateTimestamp();
    }

    private void updateCPI(List<EconomicDataPoint> data) {
        setCardMetric(data, "CPI-U All Items", cardCPI, "%.1f");
        setRowMetric(data, "CPI-U All Items", rowCPI, "%.1f");
        updateTimestamp();
    }

    private void updateWages(List<EconomicDataPoint> data) {
        setCardMetric(data, "Average Hourly Earnings - Private",  cardHourlyWage, "$%,.2f");
        setRowMetric(data, "Average Hourly Earnings - Private", rowHourlyWageRow, "$%,.2f");
        setRowMetric(data, "Average Weekly Earnings - Private", rowWages, "$%,.2f");
        updateTimestamp();
    }

    private void setCardMetric(List<EconomicDataPoint> data, String series, View card, String fmt) {
        if (card == null) return;
        EconomicDataPoint p = EconomicViewModel.getLatest(data, series);
        TextView valView = card.findViewById(R.id.tvMetricValue);
        TextView dateView = card.findViewById(R.id.tvMetricDate);
        
        if (p != null) {
            valView.setText(String.format(Locale.US, fmt, p.getValue()));
            dateView.setText(p.getDate());
        } else {
            valView.setText("—");
            dateView.setText("");
        }
    }

    private void updateTimestamp() {
        if (tvHeaderDate != null) {
            String now = new SimpleDateFormat("MM/dd/yyyy hh:mm a", Locale.US).format(new Date());
            tvHeaderDate.setText("Last updated: " + now);
        }
    }
}
