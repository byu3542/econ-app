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
import com.economic.dashboard.models.EconomicDataPoint;
import com.economic.dashboard.ui.EconomicViewModel;
import com.economic.dashboard.ui.MainActivity;

import java.util.List;
import java.util.Locale;

public class DashboardFragment extends Fragment {

    private EconomicViewModel viewModel;

    private View cardUnemployment, cardLabor, cardCPI, cardHourlyWage;
    private TextView tvFedFundsHeroValue, changeFedFunds, tvFedFundsHeroLastUpdated;
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
        observeData();
        swipeRefresh.setOnRefreshListener(() -> viewModel.fetchAllData());
        viewModel.getIsLoading().observe(getViewLifecycleOwner(),
                loading -> swipeRefresh.setRefreshing(loading));
    }

    private void bindViews(View v) {
        swipeRefresh = v.findViewById(R.id.swipeRefresh);

        // Top-left card: Unemployment (unchanged)
        cardUnemployment = v.findViewById(R.id.cardUnemployment);
        setupCardLabel(cardUnemployment, "UNEMPLOYMENT");

        // Top-right card: CPI-U YOY % Change
        cardLabor = v.findViewById(R.id.cardLabor);
        setupCardLabel(cardLabor, "CPI-U YOY");

        // Bottom-left card: 10Y-3M Spread
        cardCPI = v.findViewById(R.id.cardCPI);
        setupCardLabel(cardCPI, "10Y-3M SPREAD");

        // Bottom-right card: GDP Growth Rate
        cardHourlyWage = v.findViewById(R.id.cardHourlyWage);
        setupCardLabel(cardHourlyWage, "GDP GROWTH");

        // Fed Funds hero card
        View cardFedFundsHero = v.findViewById(R.id.cardFedFundsHero);
        if (cardFedFundsHero != null) {
            tvFedFundsHeroValue       = cardFedFundsHero.findViewById(R.id.tvFedFundsHeroValue);
            changeFedFunds            = cardFedFundsHero.findViewById(R.id.change_fed_funds);
            tvFedFundsHeroLastUpdated = cardFedFundsHero.findViewById(R.id.tvFedFundsHeroLastUpdated);
            cardFedFundsHero.setOnClickListener(view ->  {
                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).showFedFundsHistory();
                }
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
        // Treasury → cardCPI (10Y-3M spread)
        viewModel.getTreasuryData().observe(getViewLifecycleOwner(), data -> {
            if (data != null) {
                updateSpread3M(data);
                applySpread3MTier(data);
            }
        });

        // Fed funds header badge
        viewModel.getFedFundsData().observe(getViewLifecycleOwner(), data -> {
            if (data != null) updateFedFundsRate(data);
        });

        // Employment → cardUnemployment only
        viewModel.getEmploymentData().observe(getViewLifecycleOwner(), data -> {
            if (data != null) {
                updateEmployment(data);
                applyUnemploymentTier(data);
            }
        });

        // CPI → cardLabor (YOY %)
        viewModel.getCpiData().observe(getViewLifecycleOwner(), data -> {
            if (data != null) {
                updateCpiYoY(data);
                applyCpiYoYTier(data);
            }
        });

        // GDP → cardHourlyWage (4-quarter rolling avg)
        viewModel.getGdpData().observe(getViewLifecycleOwner(), data -> {
            if (data != null) {
                updateGdpGrowth(data);
                applyGdpTier(data);
            }
        });
    }

    // ── Tier badge helpers ──────────────────────────────────────────────────

    private void applyUnemploymentTier(List<EconomicDataPoint> data) {
        List<EconomicDataPoint> rows =
                EconomicViewModel.filterBySeries(data, "Unemployment Rate");
        if (rows.isEmpty()) return;
        double current = rows.get(rows.size() - 1).getValue();
        double low = Double.MAX_VALUE;
        int start = Math.max(0, rows.size() - 12);
        for (int i = start; i < rows.size(); i++) low = Math.min(low, rows.get(i).getValue());
        double rise = current - low;

        String status; int color;
        if (current > 7.0 || rise >= 0.5) { status = "RECESSION SIGNAL"; color = Color.parseColor("#F44336"); }
        else if (current > 5.5)           { status = "ELEVATED";          color = Color.parseColor("#FF9800"); }
        else if (rise >= 0.3)             { status = "WATCH CLOSELY";     color = Color.parseColor("#FFEB3B"); }
        else                              { status = "HEALTHY";            color = Color.parseColor("#4CAF50"); }

        int pct = EconomicViewModel.calculatePercentile(data, "Unemployment Rate", current);
        applyTierToCard(cardUnemployment, color, status, EconomicViewModel.formatPercentile(pct));
    }

    /** cardLabor now shows CPI-U YOY % \u2014 same tier logic as InflationFragment. */
    private void applyCpiYoYTier(List<EconomicDataPoint> data) {
        List<EconomicDataPoint> rows =
                EconomicViewModel.filterBySeries(data, "CPI-U All Items");
        if (rows.size() < 13) return;
        double latest  = rows.get(rows.size() - 1).getValue();
        double yearAgo = rows.get(rows.size() - 13).getValue();
        double yoy     = ((latest - yearAgo) / yearAgo) * 100.0;

        String status; int color;
        if (yoy < 1.5)       { status = "DEFLATION RISK"; color = Color.parseColor("#2196F3"); }
        else if (yoy <= 2.5) { status = "HEALTHY";        color = Color.parseColor("#4CAF50"); }
        else if (yoy <= 3.5) { status = "CAUTION";        color = Color.parseColor("#FFEB3B"); }
        else if (yoy <= 6.0) { status = "ELEVATED";       color = Color.parseColor("#FF9800"); }
        else                 { status = "CRITICAL";        color = Color.parseColor("#F44336"); }

        int pct = EconomicViewModel.calculatePercentile(data, "CPI-U All Items", latest);
        applyTierToCard(cardLabor, color, status, EconomicViewModel.formatPercentile(pct));
    }

    /** cardCPI now shows 10Y-3M spread \u2014 same tier logic as SpreadsFragment. */
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

    /** cardHourlyWage now shows GDP growth \u2014 same tier logic as GdpFragment. */
    private void applyGdpTier(List<EconomicDataPoint> data) {
        List<EconomicDataPoint> gdpRows =
                EconomicViewModel.filterBySeries(data, "Gross domestic product");
        if (gdpRows.isEmpty()) return;
        double sum = 0; int count = 0;
        int startIndex = Math.max(0, gdpRows.size() - 4);
        for (int i = startIndex; i < gdpRows.size(); i++) { sum += gdpRows.get(i).getValue(); count++; }
        double rollingAvg = (count > 0) ? sum / count : 0;

        String status; int color;
        if (rollingAvg < 0)         { status = "RECESSION";        color = Color.parseColor("#F44336"); }
        else if (rollingAvg <= 1.0) { status = "STAGNATION";       color = Color.parseColor("#FF9800"); }
        else if (rollingAvg <= 2.0) { status = "BELOW POTENTIAL";  color = Color.parseColor("#FFEB3B"); }
        else if (rollingAvg <= 3.0) { status = "AT POTENTIAL";     color = Color.parseColor("#4CAF50"); }
        else if (rollingAvg <= 4.0) { status = "ABOVE POTENTIAL";  color = Color.parseColor("#2196F3"); }
        else                        { status = "OVERHEATING RISK"; color = Color.parseColor("#9C27B0"); }

        applyTierToCard(cardHourlyWage, color, status, "");
    }

    private void applyTierToCard(View card, int dotColor, String status, String percentile) {
        if (card == null) return;
        View dotView      = card.findViewById(R.id.viewTierDot);
        TextView tvStatus = card.findViewById(R.id.tvTierStatus);
        TextView tvPct    = card.findViewById(R.id.tvPercentile);
        if (dotView != null) {
            GradientDrawable gd = new GradientDrawable();
            gd.setShape(GradientDrawable.OVAL);
            gd.setColor(dotColor);
            dotView.setBackground(gd);
        }
        if (tvStatus != null) tvStatus.setText(status);
        if (tvPct    != null) tvPct.setText(percentile);
    }

    // ── Value update helpers ────────────────────────────────────────────────

    private void updateFedFundsRate(List<EconomicDataPoint> data) {
        List<EconomicDataPoint> rows =
                EconomicViewModel.filterBySeries(data, "Federal Funds Effective Rate");
        if (rows.isEmpty()) return;
        EconomicDataPoint current = rows.get(rows.size() - 1);

        // Hero card rate value
        if (tvFedFundsHeroValue != null)
            tvFedFundsHeroValue.setText(String.format(Locale.US, "%.2f%%", current.getValue()));

        // Hero card change chip \u2014 scan back for the last non-zero rate move
        if (changeFedFunds != null && rows.size() >= 2) {
            double lastChange = 0.0;
            for (int i = rows.size() - 1; i >= 1; i--) {
                double rateNow  = rows.get(i).getValue();
                double ratePrev = rows.get(i - 1).getValue();
                double delta    = rateNow - ratePrev;
                if (Math.abs(delta) > 0.001) {
                    lastChange = delta;
                    break;
                }
            }
            if (Math.abs(lastChange) > 0.001) {
                String arrow = lastChange < 0 ? "\u2193" : "\u2191";
                String chipText = String.format(Locale.US,
                        "%s %.2f from prev.", arrow, Math.abs(lastChange));
                changeFedFunds.setText(chipText);
                changeFedFunds.setVisibility(View.VISIBLE);
            } else {
                changeFedFunds.setVisibility(View.GONE);
            }
        } else if (changeFedFunds != null) {
            changeFedFunds.setVisibility(View.GONE);
        }
    }

    /** cardCPI: 10Y minus 3M spread value and date. */
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
            if (valView  != null) valView.setText("\u2014");
            if (dateView != null) dateView.setText("");
        }
    }

    /** cardLabor: CPI-U YOY % change value and date. */
    private void updateCpiYoY(List<EconomicDataPoint> data) {
        if (cardLabor == null) return;
        List<EconomicDataPoint> rows =
                EconomicViewModel.filterBySeries(data, "CPI-U All Items");
        TextView valView  = cardLabor.findViewById(R.id.tvMetricValue);
        TextView dateView = cardLabor.findViewById(R.id.tvMetricDate);
        if (rows.size() >= 13) {
            double latest  = rows.get(rows.size() - 1).getValue();
            double yearAgo = rows.get(rows.size() - 13).getValue();
            double yoy     = ((latest - yearAgo) / yearAgo) * 100.0;
            if (valView  != null) valView.setText(String.format(Locale.US, "%.2f%%", yoy));
            if (dateView != null) dateView.setText(rows.get(rows.size() - 1).getDate());
        } else {
            if (valView  != null) valView.setText("\u2014");
            if (dateView != null) dateView.setText("");
        }
    }

    /** cardHourlyWage: 4-quarter rolling average GDP growth rate. */
    private void updateGdpGrowth(List<EconomicDataPoint> data) {
        if (cardHourlyWage == null) return;
        List<EconomicDataPoint> gdpRows =
                EconomicViewModel.filterBySeries(data, "Gross domestic product");
        TextView valView  = cardHourlyWage.findViewById(R.id.tvMetricValue);
        TextView dateView = cardHourlyWage.findViewById(R.id.tvMetricDate);
        if (!gdpRows.isEmpty()) {
            double sum = 0; int count = 0;
            int startIndex = Math.max(0, gdpRows.size() - 4);
            for (int i = startIndex; i < gdpRows.size(); i++) {
                sum += gdpRows.get(i).getValue(); count++;
            }
            double rollingAvg = (count > 0) ? sum / count : 0;
            if (valView  != null) valView.setText(String.format(Locale.US, "%.2f%%", rollingAvg));
            if (dateView != null) dateView.setText(gdpRows.get(gdpRows.size() - 1).getDate());
        } else {
            if (valView  != null) valView.setText("\u2014");
            if (dateView != null) dateView.setText("");
        }
    }

    private void updateEmployment(List<EconomicDataPoint> data) {
        setCardMetric(data, "Unemployment Rate", cardUnemployment, "%.1f%%");
    }

    private void setCardMetric(List<EconomicDataPoint> data, String series, View card, String fmt) {
        if (card == null) return;
        EconomicDataPoint p = EconomicViewModel.getLatest(data, series);
        TextView valView  = card.findViewById(R.id.tvMetricValue);
        TextView dateView = card.findViewById(R.id.tvMetricDate);
        if (p != null) {
            valView.setText(String.format(Locale.US, fmt, p.getValue()));
            dateView.setText(p.getDate());
        } else {
            valView.setText("\u2014");
            dateView.setText("");
        }
    }

    @Override
    public void onResume() {
        super.onResume();
    }
}
