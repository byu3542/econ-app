package com.economic.dashboard.ui.fragments;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.facebook.shimmer.ShimmerFrameLayout;

import com.economic.dashboard.R;
import com.economic.dashboard.databinding.FragmentDashboardBinding;
import com.economic.dashboard.models.EconomicDataPoint;
import com.economic.dashboard.ui.AiAnalystBottomSheet;
import com.economic.dashboard.ui.EconomicViewModel;
import com.economic.dashboard.ui.MainActivity;
import com.economic.dashboard.ui.views.SparklineView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class DashboardFragment extends Fragment {

    private EconomicViewModel viewModel;
    private FragmentDashboardBinding binding;

    private View cardUnemployment, cardCpiYoy, cardGdp, cardSpread, cardMortgage, cardVix;
    // Fed funds hero views — bound directly so they never fail silently
    private TextView tvFedFundsHeroValue, changeFedFunds, tvFedFundsHeroCycle, tvFedFundsHeroDate;
    private SwipeRefreshLayout swipeRefresh;
    private ShimmerFrameLayout skeletonShimmer;
    private View contentContainer;
    private boolean skeletonHidden = false;
    private boolean wasLoading = false;

    /** 2026 FOMC meetings: {month(1-12), startDay, endDay} — federalreserve.gov. */
    private static final int[][] FOMC_2026 = {
            {1, 27, 28}, {3, 17, 18}, {4, 28, 29}, {6, 16, 17},
            {7, 28, 29}, {9, 15, 16}, {10, 27, 28}, {12, 8, 9}
    };
    private static final String[] MONTH_ABBR = {
            "Jan", "Feb", "Mar", "Apr", "May", "Jun",
            "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
    };

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
        viewModel.getIsLoading().observe(getViewLifecycleOwner(), loading -> {
            // Haptic tick when a refresh completes
            if (wasLoading && Boolean.FALSE.equals(loading)) {
                hideSkeleton();
                if (swipeRefresh != null)
                    swipeRefresh.performHapticFeedback(
                            android.view.HapticFeedbackConstants.CONTEXT_CLICK);
            }
            wasLoading = Boolean.TRUE.equals(loading);
            swipeRefresh.setRefreshing(Boolean.TRUE.equals(loading));
        });
    }

    private void bindViews(View v) {
        swipeRefresh = binding.swipeRefresh;
        skeletonShimmer  = v.findViewById(R.id.skeletonShimmer);
        contentContainer = v.findViewById(R.id.contentContainer);

        // KPI badges
        // These are <include layout="@layout/card_kpi_badge"> entries, so view
        // binding exposes them as CardKpiBadgeBinding — getRoot() gives the View.
        cardGdp          = binding.cardGdp.getRoot();
        cardCpiYoy       = binding.cardCpiYoy.getRoot();
        cardSpread       = binding.cardSpread.getRoot();
        cardUnemployment = binding.cardUnemployment.getRoot();
        cardMortgage     = binding.cardMortgage.getRoot();
        cardVix          = binding.cardVix.getRoot();

        setupCardLabel(cardGdp,          "GDP GROWTH");
        setupCardLabel(cardCpiYoy,       "CPI-U YOY");
        setupCardLabel(cardSpread,       "10Y-3M SPREAD");
        setupCardLabel(cardUnemployment, "UNEMPLOYMENT");
        setupCardLabel(cardMortgage,     "30 YEAR MORTGAGE");
        setupCardLabel(cardVix,          "VIX INDEX");

        // Skeleton pulse until real data arrives
        startSkeleton(cardGdp); startSkeleton(cardCpiYoy); startSkeleton(cardSpread);
        startSkeleton(cardUnemployment); startSkeleton(cardMortgage); startSkeleton(cardVix);

        // Tap KPI card → matching benchmark/status dialog
        wireCardDialog(cardGdp,          R.layout.dialog_gdp_status);
        wireCardDialog(cardCpiYoy,       R.layout.dialog_cpi_status);
        wireCardDialog(cardSpread,       R.layout.dialog_treasury_3m_status);
        wireCardDialog(cardUnemployment, R.layout.dialog_unemployment_status);

        // Long-press KPI card → ask the AI Analyst about that metric
        wireAskAnalyst(cardGdp,          "GDP growth");
        wireAskAnalyst(cardCpiYoy,       "CPI inflation (YoY)");
        wireAskAnalyst(cardSpread,       "the 10Y-3M Treasury spread");
        wireAskAnalyst(cardUnemployment, "the unemployment rate");
        wireAskAnalyst(cardMortgage,     "the 30-year mortgage rate");
        wireAskAnalyst(cardVix,          "the VIX index");

        // Fed funds hero — bind views directly from the root to avoid null-chain issues
        tvFedFundsHeroValue = v.findViewById(R.id.tvFedFundsHeroValue);
        changeFedFunds      = v.findViewById(R.id.change_fed_funds);
        tvFedFundsHeroCycle = v.findViewById(R.id.tvFedFundsHeroCycle);
        tvFedFundsHeroDate  = v.findViewById(R.id.tvFedFundsHeroDate);

        updateNextFomcLabel();

        // Tap hero card → show Fed Funds history dialog
        View cardFedFundsHero = v.findViewById(R.id.cardFedFundsHero);
        if (cardFedFundsHero != null) {
            cardFedFundsHero.setOnClickListener(view -> {
                if (getActivity() instanceof MainActivity)
                    ((MainActivity) getActivity()).showFedFundsHistory();
            });
            cardFedFundsHero.setOnLongClickListener(view -> {
                String value = tvFedFundsHeroValue != null
                        ? tvFedFundsHeroValue.getText().toString() : "";
                showAskMenu(view, "the Fed Funds rate", value);
                return true;
            });
        }
    }

    /** Long-press → menu offering to ask the AI Analyst about this card. */
    private void wireAskAnalyst(View card, String label) {
        if (card == null) return;
        card.setOnLongClickListener(v -> {
            TextView tv = card.findViewById(R.id.tvMetricValue);
            String value = tv != null ? tv.getText().toString() : "";
            showAskMenu(v, label, value);
            return true;
        });
    }

    /** Confirmation menu so a long-press never fires a query by itself. */
    private void showAskMenu(View anchor, String label, String value) {
        if (getContext() == null) return;
        android.widget.PopupMenu menu = new android.widget.PopupMenu(getContext(), anchor);
        menu.getMenu().add(0, 1, 0, "Ask AI Analyst");
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                openAnalystWith(buildAskQuery(label, value));
                return true;
            }
            return false;
        });
        menu.show();
    }

    private static String buildAskQuery(String label, String value) {
        if (value == null || value.isEmpty() || value.equals("—"))
            return "Tell me about the current reading for " + label + " and what it means.";
        return "The dashboard shows " + label + " at " + value
                + ". What's driving this, and what does it mean for the economy?";
    }

    private void openAnalystWith(String query) {
        if (getActivity() == null) return;
        androidx.fragment.app.FragmentManager fm = requireActivity().getSupportFragmentManager();
        if (fm.findFragmentByTag(AiAnalystBottomSheet.TAG) == null)
            AiAnalystBottomSheet.newInstance(query).show(fm, AiAnalystBottomSheet.TAG);
    }

    private void setupCardLabel(View card, String label) {
        if (card == null) return;
        TextView tv = card.findViewById(R.id.tvMetricLabel);
        if (tv != null) tv.setText(label);
    }

    private void wireCardDialog(View card, int layoutRes) {
        if (card == null) return;
        card.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);
            showBenchmarkDialog(layoutRes);
        });
    }

    private void showBenchmarkDialog(int layoutRes) {
        if (getContext() == null) return;
        View dialogView = LayoutInflater.from(getContext()).inflate(layoutRes, null);
        AlertDialog dialog = new AlertDialog.Builder(getContext()).setView(dialogView).create();
        View btn = dialogView.findViewById(R.id.btnClose);
        if (btn != null) btn.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    // ── Skeleton loading state ──────────────────────────────────────────────

    private void startSkeleton(View card) {
        if (card == null) return;
        TextView tv = card.findViewById(R.id.tvMetricValue);
        if (tv == null) return;
        AlphaAnimation pulse = new AlphaAnimation(1f, 0.35f);
        pulse.setDuration(700);
        pulse.setRepeatCount(Animation.INFINITE);
        pulse.setRepeatMode(Animation.REVERSE);
        tv.startAnimation(pulse);
    }

    /** Fades out the full-screen shimmer skeleton and reveals real content. */
    private void hideSkeleton() {
        if (skeletonHidden) return;
        skeletonHidden = true;
        if (contentContainer != null) {
            contentContainer.setAlpha(0f);
            contentContainer.setVisibility(View.VISIBLE);
            contentContainer.animate().alpha(1f).setDuration(300).start();
        }
        if (skeletonShimmer != null) {
            skeletonShimmer.stopShimmer();
            skeletonShimmer.animate().alpha(0f).setDuration(300)
                    .withEndAction(() -> skeletonShimmer.setVisibility(View.GONE))
                    .start();
        }
    }

    /** Sets the metric value, stops the skeleton pulse, fades in changed values. */
    private void setCardValue(View card, String text) {
        if (card == null) return;
        TextView tv = card.findViewById(R.id.tvMetricValue);
        if (tv == null) return;
        String old = tv.getText() != null ? tv.getText().toString() : "";
        tv.clearAnimation();
        tv.setText(text);
        if (!old.equals(text) && !old.isEmpty() && !old.equals("—")) {
            // Fresh data replacing a real value — brief fade so the change registers
            tv.setAlpha(0.25f);
            tv.animate().alpha(1f).setDuration(400).start();
        } else {
            tv.setAlpha(1f);
        }
    }

    private void setCardDate(View card, String text) {
        if (card == null) return;
        TextView tv = card.findViewById(R.id.tvMetricDate);
        if (tv != null) tv.setText(text);
    }

    // ── Delta chip ──────────────────────────────────────────────────────────

    /**
     * Shows the change vs. the previous release next to the value.
     * goodWhenDown: true for metrics where a decrease is favorable (CPI,
     * unemployment, mortgage rate, VIX).
     */
    private void applyDelta(View card, double delta, String fmt, boolean goodWhenDown) {
        if (card == null || getContext() == null) return;
        TextView tv = card.findViewById(R.id.tvMetricDelta);
        if (tv == null) return;
        if (Math.abs(delta) < 1e-9) { tv.setVisibility(View.GONE); return; }
        boolean good = goodWhenDown ? delta < 0 : delta > 0;
        String arrow = delta > 0 ? "▲" : "▼";
        tv.setTextColor(ContextCompat.getColor(getContext(),
                good ? R.color.delta_good : R.color.delta_bad));
        tv.setText(String.format(Locale.US, "%s " + fmt, arrow, Math.abs(delta)));
        tv.setVisibility(View.VISIBLE);
    }

    // ── Sparkline ───────────────────────────────────────────────────────────

    private void setSparkline(View card, List<Float> vals) {
        if (card == null) return;
        SparklineView sp = card.findViewById(R.id.sparkline);
        if (sp != null) sp.setValues(vals);
    }

    /** Last n values of a series, oldest first. */
    private static List<Float> tailValues(List<EconomicDataPoint> rows, int n) {
        List<Float> out = new ArrayList<>();
        for (int i = Math.max(0, rows.size() - n); i < rows.size(); i++)
            out.add((float) rows.get(i).getValue());
        return out;
    }

    // ── Data observers ──────────────────────────────────────────────────────

    private void observeData() {
        viewModel.getTreasuryData().observe(getViewLifecycleOwner(), data -> {
            if (data != null) { hideSkeleton(); updateSpread3M(data); applySpread3MTier(data); }
        });
        viewModel.getFedFundsData().observe(getViewLifecycleOwner(), data -> {
            if (data != null) { hideSkeleton(); updateFedFundsRate(data); }
        });
        viewModel.getEmploymentData().observe(getViewLifecycleOwner(), data -> {
            if (data != null) { hideSkeleton(); updateEmployment(data); applyUnemploymentTier(data); }
        });
        viewModel.getCpiData().observe(getViewLifecycleOwner(), data -> {
            if (data != null) { hideSkeleton(); updateCpiYoY(data); applyCpiYoYTier(data); }
        });
        viewModel.getGdpData().observe(getViewLifecycleOwner(), data -> {
            if (data != null) { hideSkeleton(); updateGdpGrowth(data); applyGdpTier(data); }
        });
        viewModel.getMbsMortgageData().observe(getViewLifecycleOwner(), data -> {
            if (data != null) { hideSkeleton(); updateMortgage(data); applyMortgageTier(data); }
        });
        viewModel.getVixData().observe(getViewLifecycleOwner(), data -> {
            if (data != null) { hideSkeleton(); updateVix(data); applyVixTier(data); }
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
        applyTierToCard(cardCpiYoy, color, status, EconomicViewModel.formatPercentile(pct));
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
        applyTierToCard(cardSpread, color, status, "");
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
        applyTierToCard(cardGdp, color, status, "");
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

    // ── Fed funds hero ──────────────────────────────────────────────────────

    private void updateFedFundsRate(List<EconomicDataPoint> data) {
        List<EconomicDataPoint> rows = EconomicViewModel.filterBySeries(data, "Federal Funds Effective Rate");
        if (rows.isEmpty()) return;
        EconomicDataPoint current = rows.get(rows.size()-1);
        if (tvFedFundsHeroValue != null)
            tvFedFundsHeroValue.setText(String.format(Locale.US, "%.2f%%", current.getValue()));

        double lastChange = 0.0;
        for (int i = rows.size()-1; i >= 1; i--) {
            double delta = rows.get(i).getValue() - rows.get(i-1).getValue();
            if (Math.abs(delta) > 0.001) { lastChange = delta; break; }
        }
        if (changeFedFunds != null) {
            if (Math.abs(lastChange) > 0.001) {
                String arrow = lastChange < 0 ? "↓" : "↑";
                changeFedFunds.setText(String.format(Locale.US, "%s %.2f from prev.", arrow, Math.abs(lastChange)));
                changeFedFunds.setVisibility(View.VISIBLE);
            } else {
                changeFedFunds.setVisibility(View.GONE);
            }
        }
        // Cycle note driven by the direction of the last actual move
        if (tvFedFundsHeroCycle != null) {
            if (lastChange < -0.001)     tvFedFundsHeroCycle.setText("Cut cycle ongoing");
            else if (lastChange > 0.001) tvFedFundsHeroCycle.setText("Hike cycle ongoing");
            else                         tvFedFundsHeroCycle.setText("Rates on hold");
            tvFedFundsHeroCycle.setVisibility(View.VISIBLE);
        }
    }

    /** Next scheduled FOMC meeting from the published calendar. */
    private void updateNextFomcLabel() {
        if (tvFedFundsHeroDate == null) return;
        Calendar now = Calendar.getInstance();
        int year = now.get(Calendar.YEAR);
        if (year < 2026) { tvFedFundsHeroDate.setText("Next FOMC: Jan 27–28"); return; }
        if (year == 2026) {
            int month = now.get(Calendar.MONTH) + 1, day = now.get(Calendar.DAY_OF_MONTH);
            for (int[] m : FOMC_2026) {
                if (m[0] > month || (m[0] == month && m[2] >= day)) {
                    tvFedFundsHeroDate.setText(String.format(Locale.US,
                            "Next FOMC: %s %d–%d", MONTH_ABBR[m[0]-1], m[1], m[2]));
                    return;
                }
            }
        }
        // Past Dec 2026 — schedule not embedded; hide rather than show stale info
        tvFedFundsHeroDate.setVisibility(View.GONE);
    }

    // ── Value update helpers ────────────────────────────────────────────────

    private void updateSpread3M(List<EconomicDataPoint> data) {
        if (cardSpread == null) return;
        // Build the spread series by matching 10Y and 3M observations by date
        List<EconomicDataPoint> tens   = EconomicViewModel.filterBySeries(data, "10 Year");
        List<EconomicDataPoint> threes = EconomicViewModel.filterBySeries(data, "3 Month");
        Map<String, Double> threeByDate = new HashMap<>();
        for (EconomicDataPoint p : threes) threeByDate.put(p.getDate(), p.getValue());
        List<EconomicDataPoint> spreads = new ArrayList<>();
        for (EconomicDataPoint p : tens) {
            Double s = threeByDate.get(p.getDate());
            if (s != null) spreads.add(new EconomicDataPoint("FRED", "Treasury", "10Y-3M Spread", p.getDate(), p.getValue() - s, "%"));
        }
        if (!spreads.isEmpty()) {
            EconomicDataPoint latest = spreads.get(spreads.size()-1);
            setCardValue(cardSpread, String.format(Locale.US, "%.2f%%", latest.getValue()));
            setCardDate(cardSpread, latest.getDate());
            if (spreads.size() >= 2)
                applyDelta(cardSpread, latest.getValue() - spreads.get(spreads.size()-2).getValue(),
                        "%.2f", false);
            setSparkline(cardSpread, tailValues(spreads, 30));
        }
    }

    private void updateCpiYoY(List<EconomicDataPoint> data) {
        if (cardCpiYoy == null) return;
        List<EconomicDataPoint> rows = EconomicViewModel.filterBySeries(data, "CPI-U All Items");
        if (rows.size() < 13) return;
        // YoY series for value, delta, and sparkline
        List<Float> yoySeries = new ArrayList<>();
        for (int i = 12; i < rows.size(); i++) {
            double base = rows.get(i-12).getValue();
            if (Math.abs(base) > 1e-9)
                yoySeries.add((float) (((rows.get(i).getValue() - base) / base) * 100.0));
        }
        if (yoySeries.isEmpty()) return;
        float latest = yoySeries.get(yoySeries.size()-1);
        setCardValue(cardCpiYoy, String.format(Locale.US, "%.2f%%", latest));
        setCardDate(cardCpiYoy, rows.get(rows.size()-1).getDate());
        if (yoySeries.size() >= 2)
            applyDelta(cardCpiYoy, latest - yoySeries.get(yoySeries.size()-2), "%.2fpp", true);
        setSparkline(cardCpiYoy, yoySeries.subList(Math.max(0, yoySeries.size()-12), yoySeries.size()));
    }

    private void updateGdpGrowth(List<EconomicDataPoint> data) {
        if (cardGdp == null) return;
        List<EconomicDataPoint> rows = EconomicViewModel.filterBySeries(data, "Gross domestic product");
        if (rows.isEmpty()) return;
        double sum = 0; int count = 0;
        for (int i = Math.max(0, rows.size()-4); i < rows.size(); i++) { sum += rows.get(i).getValue(); count++; }
        setCardValue(cardGdp, String.format(Locale.US, "%.2f%%", count > 0 ? sum/count : 0));
        setCardDate(cardGdp, rows.get(rows.size()-1).getDate());
        if (rows.size() >= 2)
            applyDelta(cardGdp, rows.get(rows.size()-1).getValue() - rows.get(rows.size()-2).getValue(),
                    "%.1fpp", false);
        setSparkline(cardGdp, tailValues(rows, 8));
    }

    private void updateVix(List<EconomicDataPoint> data) {
        updateSimpleCard(data, "VIX Volatility Index", cardVix, "%.1f", "%.1f", true, 30);
    }

    private void updateEmployment(List<EconomicDataPoint> data) {
        updateSimpleCard(data, "Unemployment Rate", cardUnemployment, "%.1f%%", "%.1fpp", true, 12);
    }

    private void updateMortgage(List<EconomicDataPoint> data) {
        updateSimpleCard(data, "30-Yr Mortgage Rate", cardMortgage, "%.2f%%", "%.2fpp", true, 12);
    }

    /** Shared: value + delta vs. previous observation + sparkline of the last n points. */
    private void updateSimpleCard(List<EconomicDataPoint> data, String series, View card,
                                  String fmt, String deltaFmt, boolean goodWhenDown, int sparkN) {
        if (card == null) return;
        List<EconomicDataPoint> rows = EconomicViewModel.filterBySeries(data, series);
        if (rows.isEmpty()) return;
        EconomicDataPoint latest = rows.get(rows.size()-1);
        setCardValue(card, String.format(Locale.US, fmt, latest.getValue()));
        setCardDate(card, latest.getDate());
        if (rows.size() >= 2)
            applyDelta(card, latest.getValue() - rows.get(rows.size()-2).getValue(), deltaFmt, goodWhenDown);
        setSparkline(card, tailValues(rows, sparkN));
    }

    @Override
    public void onResume() { super.onResume(); }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
