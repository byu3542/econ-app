package com.economic.dashboard.ui;

import android.Manifest;
import android.app.Dialog;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;

import com.economic.dashboard.R;
import com.economic.dashboard.cache.CacheManager;
import com.economic.dashboard.news.NewsRepository;
import com.economic.dashboard.utils.SettingsManager;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.switchmaterial.SwitchMaterial;

/**
 * Settings sheet opened from the gear icon in the header.
 * Every control reads its state from SettingsManager and writes back
 * immediately — there is no Save button.
 */
public class SettingsBottomSheet extends BottomSheetDialogFragment {

    public static final String TAG = "SettingsBottomSheet";

    private static final String[] NEWS_KEYS = {
            SettingsManager.KEY_NEWS_GOV, SettingsManager.KEY_NEWS_MEDIA,
            SettingsManager.KEY_NEWS_INTL, SettingsManager.KEY_NEWS_RESEARCH };

    private MainActivity host() { return (MainActivity) requireActivity(); }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        setStyle(STYLE_NORMAL, R.style.Theme_EconomicDashboard_BottomSheet24);
        return super.onCreateDialog(savedInstanceState);
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

    // ── Appearance ───────────────────────────────────────────────────────────

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
    }

    // ── Data ─────────────────────────────────────────────────────────────────

    private void setupData(View v) {
        v.findViewById(R.id.btnRefreshNow).setOnClickListener(b -> {
            MainActivity act = host();
            Toast.makeText(act, R.string.refreshing_data, Toast.LENGTH_SHORT).show();
            act.getViewModel().fetchAllData();          // live data behind the visible UI
            CacheManager.forceRefreshAll(act, success -> // cached 24-month history
                    act.runOnUiThread(() -> Toast.makeText(act,
                            success ? R.string.refresh_done : R.string.refresh_failed,
                            Toast.LENGTH_SHORT).show()));
            dismiss();
        });

        v.findViewById(R.id.btnClearCache).setOnClickListener(b -> {
            MainActivity act = host();
            new AlertDialog.Builder(act)
                    .setTitle(R.string.clear_cache)
                    .setMessage(R.string.clear_cache_confirm)
                    .setPositiveButton(android.R.string.ok, (d, w) ->
                            CacheManager.clearAllCaches(act, success ->
                                    act.runOnUiThread(() -> Toast.makeText(act,
                                            R.string.cache_cleared, Toast.LENGTH_SHORT).show())))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        });

        bindSwitch(v, R.id.switchRefreshOnOpen, SettingsManager.KEY_REFRESH_ON_OPEN, false, null);
    }

    // ── Startup tab ──────────────────────────────────────────────────────────

    private void setupDefaultTab(View v) {
        MaterialButtonToggleGroup tg = v.findViewById(R.id.tgDefaultTab);
        int[] ids = { R.id.btnTabOverview, R.id.btnTabMarkets,
                      R.id.btnTabEconomy, R.id.btnTabNews };
        int tab = SettingsManager.getDefaultTab(requireContext());
        tg.check(ids[Math.max(0, Math.min(3, tab))]);

        tg.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            for (int i = 0; i < ids.length; i++)
                if (ids[i] == checkedId)
                    SettingsManager.setInt(requireContext(), SettingsManager.KEY_DEFAULT_TAB, i);
        });
    }

    // ── News sources ─────────────────────────────────────────────────────────

    private void setupNews(View v) {
        bindNewsSwitch(v, R.id.switchNewsGov,      SettingsManager.KEY_NEWS_GOV);
        bindNewsSwitch(v, R.id.switchNewsMedia,    SettingsManager.KEY_NEWS_MEDIA);
        bindNewsSwitch(v, R.id.switchNewsIntl,     SettingsManager.KEY_NEWS_INTL);
        bindNewsSwitch(v, R.id.switchNewsResearch, SettingsManager.KEY_NEWS_RESEARCH);
    }

    /** News switch with a guard: the last enabled source can't be turned off. */
    private void bindNewsSwitch(View root, int id, String prefKey) {
        SwitchMaterial sw = root.findViewById(id);
        sw.setChecked(SettingsManager.getBool(requireContext(), prefKey, true));
        makeRowTappable(sw);
        sw.setOnCheckedChangeListener((btn, checked) -> {
            if (!checked && isLastEnabledSource(prefKey)) {
                sw.setChecked(true); // revert — re-fires the listener with true
                Toast.makeText(requireContext(), R.string.news_source_required,
                        Toast.LENGTH_SHORT).show();
                return;
            }
            SettingsManager.setBool(requireContext(), prefKey, checked);
            NewsRepository.getInstance().invalidateCache();
        });
    }

    private boolean isLastEnabledSource(String prefKey) {
        for (String k : NEWS_KEYS)
            if (!k.equals(prefKey) && SettingsManager.getBool(requireContext(), k, true))
                return false;
        return true;
    }

    // ── AI Analyst ───────────────────────────────────────────────────────────

    private void setupAi(View v) {
        bindSwitch(v, R.id.switchSmartChips, SettingsManager.KEY_SMART_CHIPS, true, null);
        bindSwitch(v, R.id.switchDetailedAi, SettingsManager.KEY_DETAILED_AI, false, null);

        v.findViewById(R.id.btnClearChat).setOnClickListener(b -> new AlertDialog.Builder(requireContext())
                .setTitle(R.string.clear_chat_title)
                .setMessage(R.string.clear_chat_confirm)
                .setPositiveButton(R.string.clear_chat_action, (d, w) -> {
                    host().clearChatHistory();
                    Toast.makeText(requireContext(), R.string.chat_cleared, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show());
    }

    // ── Charts ───────────────────────────────────────────────────────────────

    private void setupCharts(View v) {
        // Chart settings apply the next time each chart screen is opened
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
    }

    // ── Notifications ────────────────────────────────────────────────────────

    private void setupNotifications(View v) {
        bindNotifySwitch(v, R.id.switchNotifyMoves,    SettingsManager.KEY_NOTIFY_BIG_MOVES);
        bindNotifySwitch(v, R.id.switchNotifyReleases, SettingsManager.KEY_NOTIFY_RELEASES);
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
    }

    // ── About ────────────────────────────────────────────────────────────────

    private void setupAbout(View v) {
        TextView tvVersion = v.findViewById(R.id.tvVersion);
        try {
            String name = requireContext().getPackageManager()
                    .getPackageInfo(requireContext().getPackageName(), 0).versionName;
            tvVersion.setText("Econ Monitor v" + name);
        } catch (Exception e) {
            tvVersion.setText("Econ Monitor");
        }

        TextView tvCache = v.findViewById(R.id.tvCacheStatus);
        MainActivity act = host(); // capture now: safe if the sheet closes before the callback
        CacheManager.getStatus(act, status ->
                act.runOnUiThread(() -> {
                    if (isAdded()) tvCache.setText("Cache: " + status.toDisplayString());
                }));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Tapping anywhere on the row flips its switch (not just the small thumb). */
    private void makeRowTappable(SwitchMaterial sw) {
        View row = (View) sw.getParent();
        row.setOnClickListener(x -> sw.toggle());
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
