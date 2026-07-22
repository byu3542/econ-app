package com.economic.dashboard.ui.onboarding;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.economic.dashboard.R;
import com.economic.dashboard.utils.SettingsManager;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * TICKET-25: first-run "pick the metrics you care about" sheet (Goal-Gradient).
 *
 * The order the user taps checkboxes in becomes the watchlist order, which the
 * Overview uses to rank its metric clusters (most-cared-for first). A progress
 * bar fills as metrics are picked. Skippable — skipping keeps the default
 * watchlist so the app works with zero configuration. Shown once, guarded by
 * {@link SettingsManager#isOnboardingComplete}.
 */
public class WatchlistSetupFragment extends BottomSheetDialogFragment {

    public static final String TAG = "WatchlistSetupFragment";

    /** Optional callback so the host can re-apply the Overview order on finish. */
    public interface Listener { void onWatchlistSetupFinished(); }

    @Nullable private Listener listener;
    /** Metric keys in the order the user selected them. */
    private final List<String> selectionOrder = new ArrayList<>();

    public void setListener(@Nullable Listener l) { this.listener = l; }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_watchlist_setup, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        ProgressBar pb = view.findViewById(R.id.pbWatchlist);
        TextView tvProgress = view.findViewById(R.id.tvWatchlistProgress);

        wireCheckbox(view, R.id.cbGdp,          "gdp",          pb, tvProgress);
        wireCheckbox(view, R.id.cbSpread,       "spread_10y3m", pb, tvProgress);
        wireCheckbox(view, R.id.cbCpi,          "cpi",          pb, tvProgress);
        wireCheckbox(view, R.id.cbUnemployment, "employment",   pb, tvProgress);
        wireCheckbox(view, R.id.cbMortgage,     "mbs_mortgage", pb, tvProgress);
        wireCheckbox(view, R.id.cbVix,          "vix",          pb, tvProgress);

        View btnSkip = view.findViewById(R.id.btnWatchlistSkip);
        View btnDone = view.findViewById(R.id.btnWatchlistDone);

        if (btnSkip != null) btnSkip.setOnClickListener(v -> finish(false));
        if (btnDone != null) btnDone.setOnClickListener(v -> finish(true));
    }

    private void wireCheckbox(View root, int id, String key,
                              ProgressBar pb, TextView tvProgress) {
        CheckBox cb = root.findViewById(id);
        if (cb == null) return;
        cb.setOnCheckedChangeListener((btn, checked) -> {
            if (checked) {
                if (!selectionOrder.contains(key)) selectionOrder.add(key);
            } else {
                selectionOrder.remove(key);
            }
            if (pb != null) pb.setProgress(selectionOrder.size());
            if (tvProgress != null)
                tvProgress.setText(String.format(Locale.US, "%d of 6", selectionOrder.size()));
        });
    }

    /** @param save true = persist the picked order; false = skip (keep defaults). */
    private void finish(boolean save) {
        if (getContext() != null) {
            if (save && !selectionOrder.isEmpty()) {
                // Picked metrics first (in tap order), then the remaining
                // defaults so every metric still has a stable rank.
                List<String> full = new ArrayList<>(selectionOrder);
                for (String k : SettingsManager.DEFAULT_WATCHLIST)
                    if (!full.contains(k)) full.add(k);
                SettingsManager.setWatchlist(getContext(), full);
            }
            SettingsManager.setOnboardingComplete(getContext());
        }
        if (listener != null) listener.onWatchlistSetupFinished();
        dismissAllowingStateLoss();
    }
}
