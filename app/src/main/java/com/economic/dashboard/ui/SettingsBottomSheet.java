package com.economic.dashboard.ui;

import android.Manifest;
import android.app.Dialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;

import com.economic.dashboard.R;
import com.economic.dashboard.cache.CacheManager;
import com.economic.dashboard.news.NewsRepository;
import com.economic.dashboard.utils.SettingsManager;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.switchmaterial.SwitchMaterial;

/**
 * Settings sheet opened from the gear icon in the header.
 * Every control reads its state from SettingsManager and writes back
 * immediately - there is no Save button. Consequential changes are
 * confirmed with a Snackbar anchored inside the sheet.
 */
public class SettingsBottomSheet extends BottomSheetDialogFragment {

    public static final String TAG = "SettingsBottomSheet";

    private static final String[] NEWS_KEYS = {
            SettingsManager.KEY_NEWS_GOV, SettingsManager.KEY_NEWS_MEDIA,
            SettingsManager.KEY_NEWS_INTL, SettingsManager.KEY_NEWS_RESEARCH };

    private static final int[] NEWS_SWITCH_IDS = {
            R.id.switchNewsGov, R.id.switchNewsMedia,
            R.id.switchNewsIntl, R.id.switchNewsResearch };

    private MainActivity host() { return (MainActivity) requireActivity(); }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        setStyle(STYLE_NORMAL, R.style.Theme_EconomicDashboard_BottomSheet24);
        BottomSheetDialog dialog = (BottomSheetDialog) super.onCreateDialog(savedInstanceState);
        // Open fully expanded and skip the collapsed stop so a single
        // swipe down dismisses the sheet instead of snapping halfway.
        BottomSheetBehavior<?> behavior = dialog.getBehavior();
        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        behavior.setSkipCollapsed(true);
        return dialog;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.sheet_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        setupTheme(v);
        setupData(v);
        setupDefaultTab(v);
        setupNews(v);
        setupAi(v);
        setupCharts(v);
        setupNotifications(v);
        setupAbout(v);
    }

    @Override
    public void onResume() {
        super.onResume();
        // The user may have flipped the OS notification permission and come back
        updateNotifBlockedBanner();
    }

    // -- Appearance -----------------------------------------------------------

    private void setupTheme(View v) {
        MaterialButtonToggleGroup tg = v.findViewById(R.id.tgTheme);
        int mode = SettingsManager.getNightMode(requireContext());
        if (mode == AppCompatDelegate.MODE_NIGHT_NO)       tg.check(R.id.btnThemeLight);
        else if (mode == AppCompatDelegate.MODE_NIGHT_YES) tg.check(R.id.btnThemeDark);
        else                                               tg.check(R.id.btnThemeSystem);

        tg.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            int newMode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
            if (checkedId == R.id.btnThemeLight)     newMode = AppCompatDelegate.MODE_NIGHT_NO;
            else if (checkedId == R.id.btnThemeDark) newMode = AppCompatDelegate.MODE_NIGHT_YES;
            if (newMode == SettingsManager.getNightMode(requireContext())) return;
            SettingsManager.setNightMode(requireContext(), newMode);
            // Recreates the activity with the new theme. The sheet is restored
            // automatically afterwards, so the user can keep comparing themes.
            AppCompatDelegate.setDefaultNightMode(newMode);
        });

        // Colorblind-safe delta palette. Re-fetch so every delta chip repaints
        // with the new palette immediately.
        bindSwitch(v, R.id.switchColorblind, SettingsManager.KEY_COLORBLIND, false,
                () -> host().getViewModel().fetchAllData());
    }

    // -- Data -----------------------------------------------------------------

    private void setupData(View v) {
        MaterialButton btnRefresh = v.findViewById(R.id.btnRefreshNow);
        MaterialButton btnClear   = v.findViewById(R.id.btnClearCache);

        // Refresh runs inline: the sheet stays open and the button shows progress.
        btnRefresh.setOnClickListener(b -> {
            MainActivity act = host();
            btnRefresh.setEnabled(false);
            btnClear.setEnabled(false);
            btnRefresh.setText(R.string.refreshing_data);
            act.getViewModel().fetchAllData();           // live data behind the visible UI
            CacheManager.forceRefreshAll(act, success -> // cached 24-month history
                    act.runOnUiThread(() -> {
                        if (!isAdded()) return;
                        btnRefresh.setEnabled(true);
                        btnClear.setEnabled(true);
                        btnRefresh.setText(R.string.refresh_data);
                        snack(success ? R.string.refresh_done : R.string.refresh_failed);
                        updateLastUpdated();
                    }));
        });

        btnClear.setOnClickListener(b -> {
            MainActivity act = host();
            new AlertDialog.Builder(act)
                    .setTitle(R.string.clear_cache)
                    .setMessage(R.string.clear_cache_confirm)
                    .setPositiveButton(android.R.string.ok, (d, w) ->
                            CacheManager.clearAllCaches(act, success ->
                                    act.runOnUiThread(() -> {
                                        if (!isAdded()) return;
                                        snack(R.string.cache_cleared);
                                        updateLastUpdated(); // freshness line reflects the empty cache
                                    })))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        });

        bindSwitch(v, R.id.switchRefreshOnOpen, SettingsManager.KEY_REFRESH_ON_OPEN, false, null);
        updateLastUpdated();
    }

    /** Refreshes the "Data updated X ago" line in the Data card. */
    private void updateLastUpdated() {
        View v = getView();
        if (v == null) return;
        TextView tv = v.findViewById(R.id.tvLastUpdated);
        MainActivity act = host(); // capture now: safe if the sheet closes before the callback
        CacheManager.getStatus(act, status ->
                act.runOnUiThread(() -> {
                    if (isAdded()) tv.setText(status.toDisplayString());
                }));
    }

    // -- Startup tab ------------------------------------------------------------

    private void setupDefaultTab(View v) {
        ChipGroup cg = v.findViewById(R.id.cgDefaultTab);
        int[] ids = { R.id.chipTabOverview, R.id.chipTabMarkets,
                      R.id.chipTabEconomy, R.id.chipTabNews };
        int tab = SettingsManager.getDefaultTab(requireContext());
        cg.check(ids[Math.max(0, Math.min(3, tab))]);

        cg.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            int checkedId = checkedIds.get(0);
            for (int i = 0; i < ids.length; i++)
                if (ids[i] == checkedId)
                    SettingsManager.setInt(requireContext(), SettingsManager.KEY_DEFAULT_TAB, i);
        });
    }

    // -- News sources -----------------------------------------------------------

    private void setupNews(View v) {
        for (int i = 0; i < NEWS_SWITCH_IDS.length; i++)
            bindNewsSwitch(v, NEWS_SWITCH_IDS[i], NEWS_KEYS[i]);
        updateNewsEnabledStates();
    }

    private void bindNewsSwitch(View root, int id, String prefKey) {
        SwitchMaterial sw = root.findViewById(id);
        sw.setChecked(SettingsManager.getBool(requireContext(), prefKey, true));
        makeRowTappable(sw);
        sw.setOnCheckedChangeListener((btn, checked) -> {
            SettingsManager.setBool(requireContext(), prefKey, checked);
            NewsRepository.getInstance().invalidateCache();
            updateNewsEnabledStates();
            snack(R.string.news_updated);
        });
    }

    /**
     * The last enabled source is locked (disabled while on) so the feed can
     * never go empty - prevention instead of revert-and-toast.
     */
    private void updateNewsEnabledStates() {
        View v = getView();
        if (v == null) return;
        int enabledCount = 0;
        for (String k : NEWS_KEYS)
            if (SettingsManager.getBool(requireContext(), k, true)) enabledCount++;
        for (int i = 0; i < NEWS_SWITCH_IDS.length; i++) {
            SwitchMaterial sw = v.findViewById(NEWS_SWITCH_IDS[i]);
            boolean on = SettingsManager.getBool(requireContext(), NEWS_KEYS[i], true);
            boolean locked = on && enabledCount == 1;
            sw.setEnabled(!locked);
            ((View) sw.getParent()).setAlpha(locked ? 0.55f : 1f);
        }
    }

    // -- AI Analyst ---------------------------------------------------------------

    private void setupAi(View v) {
        bindSwitch(v, R.id.switchSmartChips, SettingsManager.KEY_SMART_CHIPS, true, null);
        bindSwitch(v, R.id.switchDetailedAi, SettingsManager.KEY_DETAILED_AI, false, null);

        v.findViewById(R.id.btnClearChat).setOnClickListener(b -> new AlertDialog.Builder(requireContext())
                .setTitle(R.string.clear_chat_title)
                .setMessage(R.string.clear_chat_confirm)
                .setPositiveButton(R.string.clear_chat_action, (d, w) -> {
                    host().clearChatHistory();
                    snack(R.string.chat_cleared);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show());
    }

    // -- Charts -------------------------------------------------------------------

    private void setupCharts(View v) {
        // A note under the controls tells the user changes apply on next chart open
        bindSwitch(v, R.id.switchGridlines, SettingsManager.KEY_CHART_GRIDLINES, true, null);

        MaterialButtonToggleGroup tg = v.findViewById(R.id.tgDecimals);
        int[] ids = { R.id.btnDec1, R.id.btnDec2, R.id.btnDec3 };
        int decimals = SettingsManager.getChartDecimals(requireContext());
        tg.check(ids[Math.max(1, Math.min(3, decimals)) - 1]);

        tg.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            for (int i = 0; i < ids.length; i++)
                if (ids[i] == checkedId)
                    SettingsManager.setInt(requireContext(), SettingsManager.KEY_CHART_DECIMALS, i + 1);
        });

        // Default chart time range — standardizes the x-axis window on every chart
        MaterialButtonToggleGroup tgTf = v.findViewById(R.id.tgTimeframe);
        final int[] tfIds    = { R.id.btnTf3m, R.id.btnTf6m, R.id.btnTf1y, R.id.btnTf2y, R.id.btnTf5y };
        final int[] tfMonths = { 3, 6, 12, 24, 60 };
        int currentMonths = SettingsManager.getChartTimeframeMonths(requireContext());
        int sel = tfMonths.length - 1; // default -> 5Y
        for (int i = 0; i < tfMonths.length; i++) if (tfMonths[i] == currentMonths) { sel = i; break; }
        tgTf.check(tfIds[sel]);
        tgTf.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            for (int i = 0; i < tfIds.length; i++)
                if (tfIds[i] == checkedId)
                    SettingsManager.setInt(requireContext(), SettingsManager.KEY_CHART_TIMEFRAME, tfMonths[i]);
        });
    }

    // -- Notifications --------------------------------------------------------------

    private void setupNotifications(View v) {
        bindNotifySwitch(v, R.id.switchNotifyMoves,    SettingsManager.KEY_NOTIFY_BIG_MOVES);
        bindNotifySwitch(v, R.id.switchNotifyReleases, SettingsManager.KEY_NOTIFY_RELEASES);
        bindNotifySwitch(v, R.id.switchDailyBrief,     SettingsManager.KEY_DAILY_BRIEF);

        v.findViewById(R.id.tvNotifBlocked).setOnClickListener(x ->
                startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE,
                                requireContext().getPackageName())));
        updateNotifBlockedBanner();
    }

    /** Shows OFF when the OS permission is missing, regardless of the stored pref. */
    private void bindNotifySwitch(View root, int id, String prefKey) {
        SwitchMaterial sw = root.findViewById(id);
        sw.setChecked(notificationsAllowed()
                && SettingsManager.getBool(requireContext(), prefKey, false));
        makeRowTappable(sw);
        sw.setOnCheckedChangeListener((btn, checked) -> {
            SettingsManager.setBool(requireContext(), prefKey, checked);
            if (checked) host().ensureNotificationPermission();
        });
    }

    /** "Notifications are blocked" link row - visible only when permission is missing. */
    private void updateNotifBlockedBanner() {
        View v = getView();
        if (v == null) return;
        v.findViewById(R.id.tvNotifBlocked)
                .setVisibility(notificationsAllowed() ? View.GONE : View.VISIBLE);
    }

    private boolean notificationsAllowed() {
        return Build.VERSION.SDK_INT < 33
                || ContextCompat.checkSelfPermission(requireContext(),
                        Manifest.permission.POST_NOTIFICATIONS)
                   == PackageManager.PERMISSION_GRANTED;
    }

    /** Called by MainActivity when the POST_NOTIFICATIONS request is denied. */
    public void onNotificationPermissionDenied() {
        View v = getView();
        if (v == null) return;
        // setChecked fires the listeners, which also revert the stored prefs
        ((SwitchMaterial) v.findViewById(R.id.switchNotifyMoves)).setChecked(false);
        ((SwitchMaterial) v.findViewById(R.id.switchNotifyReleases)).setChecked(false);
        ((SwitchMaterial) v.findViewById(R.id.switchDailyBrief)).setChecked(false);
        updateNotifBlockedBanner();
        snack(R.string.notifications_denied);
    }

    // -- About ------------------------------------------------------------------------

    private void setupAbout(View v) {
        TextView tvVersion = v.findViewById(R.id.tvVersion);
        try {
            String name = requireContext().getPackageManager()
                    .getPackageInfo(requireContext().getPackageName(), 0).versionName;
            tvVersion.setText("Econ Monitor v" + name);
        } catch (Exception e) {
            tvVersion.setText("Econ Monitor");
        }
    }

    // -- Helpers ------------------------------------------------------------------------

    /** Short confirmation shown inside the sheet after a consequential change. */
    private void snack(int messageRes) {
        View v = getView();
        if (v == null) return;
        Snackbar.make(v, messageRes, Snackbar.LENGTH_SHORT).show();
    }

    /** Tapping anywhere on the row flips its switch (not just the small thumb). */
    private void makeRowTappable(SwitchMaterial sw) {
        View row = (View) sw.getParent();
        row.setOnClickListener(x -> {
            if (sw.isEnabled()) sw.toggle();
        });
    }

    /** Wires a switch to a boolean pref; runs onChange (if any) after every flip. */
    private void bindSwitch(View root, int id, String prefKey, boolean def, @Nullable Runnable onChange) {
        SwitchMaterial sw = root.findViewById(id);
        sw.setChecked(SettingsManager.getBool(requireContext(), prefKey, def));
        makeRowTappable(sw);
        sw.setOnCheckedChangeListener((btn, checked) -> {
            SettingsManager.setBool(requireContext(), prefKey, checked);
            if (onChange != null) onChange.run();
        });
    }
}
