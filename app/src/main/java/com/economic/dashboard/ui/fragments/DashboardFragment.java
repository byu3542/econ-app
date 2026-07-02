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
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.economic.dashboard.R;
import com.economic.dashboard.databinding.FragmentDashboardBinding;
import com.economic.dashboard.models.EconomicDataPoint;
import com.economic.dashboard.ui.EconomicViewModel;
import com.economic.dashboard.ui.MainActivity;

import java.util.List;
import java.util.Locale;

public class DashboardFragment extends Fragment {

    private EconomicViewModel viewModel;
    private FragmentDashboardBinding binding;

    private View cardUnemployment, cardLabor, cardCPI, cardHourlyWage, cardMortgage, cardVix;
    // Fed funds hero views — bound directly so they never fail silently
    private TextView tvFedFundsHeroValue, changeFedFunds;
    private SwipeRefreshLayout swipeRefresh;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentDashboardBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(requireActivity()).get(EconomicViewModel.class);
        bindViews(view);
        observeData();
        swipeRefresh.setOnRefreshListener(() -> viewModel.fetchAllData());
        viewModel.getIsLoading().observe(getViewLifecycleOwner(),
                loading -> swipeRefresh.setRefreshing(loading));
    }

    private void bindViews(View v) {
        swipeRefresh = binding.swipeRefresh;

        // KPI badges
        cardHourlyWage   = binding.cardHourlyWage;
        cardLabor        = binding.cardLabor;
        cardCPI          = binding.cardCPI;
        cardUnemployment = binding.cardUnemployment;
        cardMortgage     = binding.cardMortgage;
        cardVix          = binding.cardVix;

        setupCardLabel(cardHourlyWage,   "GDP GROWTH");
        setupCardLabel(cardLabor,        "CPI-U YOY");
        setupCardLabel(cardCPI,          "10Y-3M SPREAD");
        setupCardLabel(cardUnemployment, "UNEMPLOYMENT");
        setupCardLabel(cardMortgage,     "30 YEAR MORTGAGE");
        setupCardLabel(cardVix,          "VIX INDEX");

        // Fed funds hero — bind views directly from the root to avoid null-chain issues
        tvFedFundsHeroValue = v.findViewById(R.id.tvFedFundsHeroValue);
        changeFedFunds      = v.findViewById(R.id.change_fed_funds);

        // Tap hero card → show Fed Funds history dialog
        View cardFedFundsHero = v.findViewById(R.id.cardFedFundsHero);
        if (cardFedFundsHero != null) {
            cardFedFundsHero.setOnClickListener(view -> {
                if (getActivity() instanceof MainActivity)
                    ((MainActivity) getActivity()).showFedFundsHistory();
            });
        }
    }

    private void setupCardLabel(View card, String label) {
        if (card == null) return;
        TextView tv = card.findViewById(R.id.tvMetricLabel);
        if (tv != null) tv.setText(label);
    }

    // ── Data observers ──────────────────────────────────────────────────────

    private void observeData() {
        viewModel.getTreasuryData().observe(getViewLifecycleOwner(), data -> {
            if (data != null) { updateSpread3M(data); applySpread3MTier(data); }
        });
        viewModel.getFedFundsData().observe(getViewLifecycleOwner(), data -> {
            if (data != null) updateFedFundsRate(data);
        });
        viewModel.getEmploymentData().observe(getViewLifecycleOwner(), data -> {
            if (data != null) { updateEmployment(data); applyUnemploymentTier(data); }
        });
        viewModel.getCpiData().observe(getViewLifecycleOwner(), data -> {
            if (data != null) { updateCpiYoY(data); applyCpiYoYTier(data); }
        });
        viewModel.getGdpData().observe(getViewLifecycleOwner(), data -> {
            if (data != null) { updateGdpGrowth(data); applyGdpTier(data); }
        });
        viewModel.getMbsMortgageData().observe(getViewLifecycleOwner(), data -> {
            if (data != null) { updateMortgage(data); applyMortgageTier(data); }
        });
        viewModel.getVixData().observe(getViewLifecycleOwner(), data -> {
            if (data != null) { updateVix(data); applyVixTier(data); }
        });
    }

    // ── Tier badge helpers ──────────────────────────────────────────────────

    private void applyUnemploymentTier(List<EconomicDataPoint> data) {
        List<EconomicDataPoint> rows = EconomicViewModel.filterBySeries(data, "Unemployment Rate");
        if (rows.isEmpty()) return;
        double current = rows.get(rows.size()-1).getValue();
        double low = Double.MAX_VALUE;
        for (int i = Math.max(0, rows.size()-12); i < rows.size(); i++) low = Math.min(low, rows.get(i).getValue());
        double rise = current - low;
        String status; int color;
        if (current > 7.0 || rise >= 0.5) { status = "RECESSION SIGNAL"; color = Color.parseColor("#F44336"); }
        else if (current > 5.5)           { status = "ELEVATED";          color = Color.parseColor("#FF9800"); }
        else if (rise >= 0.3)             { status = "WATCH CLOSELY";     color = Color.parseColor("#FFEB3B"); }
        else                              { status = "HEALTHY";            color = Color.parseColor("#4CAF50"); }
        int pct = EconomicViewModel.calculatePercentile(data, "Unemployment Rate", current);
        applyTierToCard(cardUnemployment, color, status, EconomicViewModel.formatPercentile(pct));
    }

    private void applyCpiYoYTier(List<EconomicDataPoint> data) {
        List<EconomicDataPoint> rows = EconomicViewModel.filterBySeries(data, "CPI-U All Items");
        if (rows.size() < 13) return;
        double latest = rows.get(rows.size()-1).getValue();
        double yoy = ((latest - rows.get(rows.size()-13).getValue()) / rows.get(rows.size()-13).getValue()) * 100.0;
        String status; int color;
        if (yoy < 1.5)       { status = "DEFLATION RISK"; color = Color.parseColor("#2196F3"); }
        else if (yoy <= 2.5) { status = "HEALTHY";        color = Color.parseColor("#4CAF50"); }
        else if (yoy <= 3.5) { status = "CAUTION";        color = Color.parseColor("#FFEB3B"); }
        else if (yoy <= 6.0) { status = "ELEVATED";       color = Color.parseColor("#FF9800"); }
        else                 { status = "CRITICAL";        color = Color.parseColor("#F44336"); }
        int pct = EconomicViewModel.calculatePercentile(data, "CPI-U All Items", latest);
        applyTierToCard(cardLabor, color, status, EconomicViewModel.formatPercentile(pct));
    }

    private void applySpread3MTier(List<EconomicDataPoint> data) {
        EconomicDataPoint longRate  = EconomicViewModel.getLatest(data, "10 Year");
        EconomicDataPoint shortRate = EconomicViewModel.getLatest(data, "3 Month");
        if (longRate == null || shortRate == null) return;
        double spread = longRate.getValue() - shortRate.getValue();
        String status; int color;
        if (spread >= 3.50)      { status = "STEEP";          color = Color.parseColor("#9C27B0"); }
        else if (spread >= 2.00) { status = "STRONG";         color = Color.parseColor("#2196F3"); }
        else if (spread >= 1.00) { status = "HEALTHY";        color = Color.parseColor("#4CAF50"); }
        else if (spread >= 0.00) { status = "RECOVERING";     color = Color.parseColor("#FFEB3B"); }
        else if (spread > -0.50) { status = "FLATTENING";     color = Color.parseColor("#FFEB3B"); }
        else if (spread > -1.50) { status = "INVERTED";       color = Color.parseColor("#FF9800"); }
        else                     { status = "DEEP INVERSION"; color = Color.parseColor("#F44336"); }
        applyTierToCard(cardCPI, color, status, "");
    }

    private void applyGdpTier(List<EconomicDataPoint> data) {
        List<EconomicDataPoint> rows = EconomicViewModel.filterBySeries(data, "Gross domestic product");
        if (rows.isEmpty()) return;
        double sum = 0; int count = 0;
        for (int i = Math.max(0, rows.size()-4); i < rows.size(); i++) { sum += rows.get(i).getValue(); count++; }
        double avg = count > 0 ? sum/count : 0;
        String status; int color;
        if (avg < 0)         { status = "RECESSION";        color = Color.parseColor("#F44336"); }
        else if (avg <= 1.0) { status = "STAGNATION";       color = Color.parseColor("#FF9800"); }
        else if (avg <= 2.0) { status = "BELOW POTENTIAL";  color = Color.parseColor("#FFEB3B"); }
        else if (avg <= 3.0) { status = "AT POTENTIAL";     color = Color.parseColor("#4CAF50"); }
        else if (avg <= 4.0) { status = "ABOVE POTENTIAL";  color = Color.parseColor("#2196F3"); }
        else                 { status = "OVERHEATING RISK"; color = Color.parseColor("#9C27B0"); }
        applyTierToCard(cardHourlyWage, color, status, "");
    }

    private void applyMortgageTier(List<EconomicDataPoint> data) {
        List<EconomicDataPoint> rows = EconomicViewModel.filterBySeries(data, "30-Yr Mortgage Rate");
        if (rows.isEmpty()) return;
        double current = rows.get(rows.size()-1).getValue();
        String status; int color;
        if (current < 3.0)       { status = "HISTORIC LOW"; color = Color.parseColor("#4CAF50"); }
        else if (current <= 4.0) { status = "FAVORABLE";    color = Color.parseColor("#2196F3"); }
        else if (current <= 5.0) { status = "MODERATE";     color = Color.parseColor("#FFEB3B"); }
        else if (current <= 6.5) { status = "ELEVATED";     color = Color.parseColor("#FF9800"); }
        else                     { status = "HIGH";          color = Color.parseColor("#F44336"); }
        applyTierToCard(cardMortgage, color, status, "");
    }

    private void applyVixTier(List<EconomicDataPoint> data) {
        List<EconomicDataPoint> rows = EconomicViewModel.filterBySeries(data, "VIX Volatility Index");
        if (rows.isEmpty()) return;
        double current = rows.get(rows.size()-1).getValue();
        String status; int color;
        if (current < 12)      { status = "LOW";      color = Color.parseColor("#4CAF50"); }
        else if (current < 20) { status = "NORMAL";   color = Color.parseColor("#2196F3"); }
        else if (current < 30) { status = "ELEVATED"; color = Color.parseColor("#FF9800"); }
        else                   { status = "EXTREME";  color = Color.parseColor("#F44336"); }
        applyTierToCard(cardVix, color, status, "");
    }

    private void applyTierToCard(View card, int dotColor, String status, String percentile) {
        if (card == null) return;
        View dotView      = card.findViewById(R.id.viewTierDot);
        TextView tvStatus = card.findViewById(R.id.tvTierStatus);
        TextView tvPct    = card.findViewById(R.id.tvPercentile);
        if (dotView != null) {
            GradientDrawable gd = new GradientDrawable();
            gd.setShape(GradientDrawable.OVAL); gd.setColor(dotColor);
            dotView.setBackground(gd);
        }
        if (tvStatus != null) tvStatus.setText(status);
        if (tvPct    != null) tvPct.setText(percentile);
    }

    // ── Value update helpers ────────────────────────────────────────────────

    private void updateFedFundsRate(List<EconomicDataPoint> data) {
        List<EconomicDataPoint> rows = EconomicViewModel.filterBySeries(data, "Federal Funds Effective Rate");
        if (rows.isEmpty()) return;
        EconomicDataPoint current = rows.get(rows.size()-1);
        if (tvFedFundsHeroValue != null)
            tvFedFundsHeroValue.setText(String.format(Locale.US, "%.2f%%", current.getValue()));
        if (changeFedFunds != null && rows.size() >= 2) {
            double lastChange = 0.0;
            for (int i = rows.size()-1; i >= 1; i--) {
                double delta = rows.get(i).getValue() - rows.get(i-1).getValue();
                if (Math.abs(delta) > 0.001) { lastChange = delta; break; }
            }
            if (Math.abs(lastChange) > 0.001) {
                String arrow = lastChange < 0 ? "↓" : "↑";
                changeFedFunds.setText(String.format(Locale.US, "%s %.2f from prev.", arrow, Math.abs(lastChange)));
                changeFedFunds.setVisibility(View.VISIBLE);
            } else {
                changeFedFunds.setVisibility(View.GONE);
            }
        }
    }

    private void updateSpread3M(List<EconomicDataPoint> data) {
        if (cardCPI == null) return;
        EconomicDataPoint longRate  = EconomicViewModel.getLatest(data, "10 Year");
        EconomicDataPoint shortRate = EconomicViewModel.getLatest(data, "3 Month");
        TextView valView  = cardCPI.findViewById(R.id.tvMetricValue);
        TextView dateView = cardCPI.findViewById(R.id.tvMetricDate);
        if (longRate != null && shortRate != null) {
            double spread = longRate.getValue() - shortRate.getValue();
            if (valView  != null) valView.setText(String.format(Locale.US, "%.2f%%", spread));
            if (dateView != null) dateView.setText(longRate.getDate());
        } else {
            if (valView  != null) valView.setText("—");
            if (dateView != null) dateView.setText("");
        }
    }

    private void updateCpiYoY(List<EconomicDataPoint> data) {
        if (cardLabor == null) return;
        List<EconomicDataPoint> rows = EconomicViewModel.filterBySeries(data, "CPI-U All Items");
        TextView valView  = cardLabor.findViewById(R.id.tvMetricValue);
        TextView dateView = cardLabor.findViewById(R.id.tvMetricDate);
        if (rows.size() >= 13) {
            double yoy = ((rows.get(rows.size()-1).getValue() - rows.get(rows.size()-13).getValue())
                    / rows.get(rows.size()-13).getValue()) * 100.0;
            if (valView  != null) valView.setText(String.format(Locale.US, "%.2f%%", yoy));
            if (dateView != null) dateView.setText(rows.get(rows.size()-1).getDate());
        } else {
            if (valView  != null) valView.setText("—");
            if (dateView != null) dateView.setText("");
        }
    }

    private void updateGdpGrowth(List<EconomicDataPoint> data) {
        if (cardHourlyWage == null) return;
        List<EconomicDataPoint> rows = EconomicViewModel.filterBySeries(data, "Gross domestic product");
        TextView valView  = cardHourlyWage.findViewById(R.id.tvMetricValue);
        TextView dateView = cardHourlyWage.findViewById(R.id.tvMetricDate);
        if (!rows.isEmpty()) {
            double sum = 0; int count = 0;
            for (int i = Math.max(0, rows.size()-4); i < rows.size(); i++) { sum += rows.get(i).getValue(); count++; }
            if (valView  != null) valView.setText(String.format(Locale.US, "%.2f%%", count > 0 ? sum/count : 0));
            if (dateView != null) dateView.setText(rows.get(rows.size()-1).getDate());
        } else {
            if (valView  != null) valView.setText("—");
            if (dateView != null) dateView.setText("");
        }
    }

    private void updateVix(List<EconomicDataPoint> data) {
        if (cardVix == null) return;
        List<EconomicDataPoint> rows = EconomicViewModel.filterBySeries(data, "VIX Volatility Index");
        TextView valView  = cardVix.findViewById(R.id.tvMetricValue);
        TextView dateView = cardVix.findViewById(R.id.tvMetricDate);
        if (!rows.isEmpty()) {
            EconomicDataPoint latest = rows.get(rows.size()-1);
            if (valView  != null) valView.setText(String.format(Locale.US, "%.1f", latest.getValue()));
            if (dateView != null) dateView.setText(latest.getDate());
        } else {
            if (valView  != null) valView.setText("—");
            if (dateView != null) dateView.setText("");
        }
    }

    private void updateEmployment(List<EconomicDataPoint> data) {
        setCardMetric(data, "Unemployment Rate", cardUnemployment, "%.1f%%");
    }

    private void updateMortgage(List<EconomicDataPoint> data) {
        setCardMetric(data, "30-Yr Mortgage Rate", cardMortgage, "%.2f%%");
    }

    private void setCardMetric(List<EconomicDataPoint> data, String series, View card, String fmt) {
        if (card == null) return;
        EconomicDataPoint p = EconomicViewModel.getLatest(data, series);
        TextView valView  = card.findViewById(R.id.tvMetricValue);
        TextView dateView = card.findViewById(R.id.tvMetricDate);
        if (p != null) {
            if (valView  != null) valView.setText(String.format(Locale.US, fmt, p.getValue()));
            if (dateView != null) dateView.setText(p.getDate());
        } else {
            if (valView  != null) valView.setText("—");
            if (dateView != null) dateView.setText("");
        }
    }

    @Override
    public void onResume() { super.onResume(); }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
