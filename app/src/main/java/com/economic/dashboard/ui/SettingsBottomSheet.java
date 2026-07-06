package com.economic.dashboard.ui;

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDelegate;

import com.economic.dashboard.R;
import com.economic.dashboard.cache.CacheManager;
import com.economic.dashboard.news.NewsRepository;
import com.economic.dashboard.utils.SettingsManager;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.switchmaterial.SwitchMaterial;

/**
 * Settings sheet opened from the gear icon in the header.
 * Every control reads its state from SettingsManager and writes back
 * immediately — there is no Save button.
 */
public class SettingsBottomSheet extends BottomSheetDialogFragment {

    public static final String TAG = "SettingsBottomSheet";

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
        RadioGroup rg = v.findViewById(R.id.rgTheme);
        int mode = SettingsManager.getNightMode(requireContext());
        if (mode == AppCompatDelegate.MODE_NIGHT_NO)       rg.check(R.id.radioThemeLight);
        else if (mode == AppCompatDelegate.MODE_NIGHT_YES) rg.check(R.id.radioThemeDark);
        else                                               rg.check(R.id.radioThemeSystem);

        rg.setOnCheckedChangeListener((group, checkedId) -> {
            int newMode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
            if (checkedId == R.id.radioThemeLight)     newMode = AppCompatDelegate.MODE_NIGHT_NO;
            else if (checkedId == R.id.radioThemeDark) newMode = AppCompatDelegate.MODE_NIGHT_YES;
            SettingsManager.setNightMode(requireContext(), newMode);
            dismiss();
            // Recreates the activity with the new theme
            AppCompatDelegate.setDefaultNightMode(newMode);
        });
    }

    // ── Data ─────────────────────────────────────────────────────────────────

    private void setupData(View v) {
        v.findViewById(R.id.btnRefreshNow).setOnClickListener(b -> {
            Toast.makeText(requireContext(), R.string.refreshing_data, Toast.LENGTH_SHORT).show();
            host().getViewModel().fetchAllData();
            CacheManager.forceRefreshAll(requireContext(), success -> {});
            dismiss();
        });

        v.findViewById(R.id.btnClearCache).setOnClickListener(b -> new AlertDialog.Builder(requireContext())
                .setTitle(R.string.clear_cache)
                .setMessage(R.string.clear_cache_confirm)
                .setPositiveButton(android.R.string.ok, (d, w) ->
                        CacheManager.clearAllCaches(requireContext(), success ->
                                host().runOnUiThread(() -> Toast.makeText(requireContext(),
                                        R.string.cache_cleared, Toast.LENGTH_SHORT).show())))
                .setNegativeButton(android.R.string.cancel, null)
                .show());

        bindSwitch(v, R.id.switchRefreshOnOpen, SettingsManager.KEY_REFRESH_ON_OPEN, false, null);
    }

    // ── Default tab ──────────────────────────────────────────────────────────

    private void setupDefaultTab(View v) {
        RadioGroup rg = v.findViewById(R.id.rgDefaultTab);
        int[] radioIds = { R.id.radioTabOverview, R.id.radioTabMarkets,
                           R.id.radioTabEconomy, R.id.radioTabNews };
        int tab = SettingsManager.getDefaultTab(requireContext());
        rg.check(radioIds[Math.max(0, Math.min(3, tab))]);

        rg.setOnCheckedChangeListener((group, checkedId) -> {
            int selected = 0;
            for (int i = 0; i < radioIds.length; i++) if (radioIds[i] == checkedId) selected = i;
            SettingsManager.setInt(requireContext(), SettingsManager.KEY_DEFAULT_TAB, selected);
        });
    }

    // ── News sources ─────────────────────────────────────────────────────────

    private void setupNews(View v) {
        Runnable onChange = () -> NewsRepository.getInstance().invalidateCache();
        bindSwitch(v, R.id.switchNewsGov,      SettingsManager.KEY_NEWS_GOV,      true, onChange);
        bindSwitch(v, R.id.switchNewsMedia,    SettingsManager.KEY_NEWS_MEDIA,    true, onChange);
        bindSwitch(v, R.id.switchNewsIntl,     SettingsManager.KEY_NEWS_INTL,     true, onChange);
        bindSwitch(v, R.id.switchNewsResearch, SettingsManager.KEY_NEWS_RESEARCH, true, onChange);
    }

    // ── AI Analyst ───────────────────────────────────────────────────────────

    private void setupAi(View v) {
        bindSwitch(v, R.id.switchSmartChips, SettingsManager.KEY_SMART_CHIPS, true, null);
        bindSwitch(v, R.id.switchDetailedAi, SettingsManager.KEY_DETAILED_AI, false, null);

        v.findViewById(R.id.btnClearChat).setOnClickListener(b -> new AlertDialog.Builder(requireContext())
                .setTitle("Clear conversation")
                .setMessage("Delete the full chat history? This can't be undone.")
                .setPositiveButton("Clear", (d, w) -> {
                    host().clearChatHistory();
                    Toast.makeText(requireContext(), "Chat history cleared", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show());
    }

    // ── Charts ───────────────────────────────────────────────────────────────

    private void setupCharts(View v) {
        // Chart settings apply the next time each chart screen is opened
        bindSwitch(v, R.id.switchGridlines, SettingsManager.KEY_CHART_GRIDLINES, true, null);

        RadioGroup rg = v.findViewById(R.id.rgDecimals);
        int[] radioIds = { R.id.radioDec1, R.id.radioDec2, R.id.radioDec3 };
        int decimals = SettingsManager.getChartDecimals(requireContext());
        rg.check(radioIds[Math.max(1, Math.min(3, decimals)) - 1]);

        rg.setOnCheckedChangeListener((group, checkedId) -> {
            int selected = 2;
            for (int i = 0; i < radioIds.length; i++) if (radioIds[i] == checkedId) selected = i + 1;
            SettingsManager.setInt(requireContext(), SettingsManager.KEY_CHART_DECIMALS, selected);
        });
    }

    // ── Notifications ────────────────────────────────────────────────────────

    private void setupNotifications(View v) {
        Runnable requestPermission = () -> host().ensureNotificationPermission();
        bindSwitchWithEnableAction(v, R.id.switchNotifyMoves,
                SettingsManager.KEY_NOTIFY_BIG_MOVES, requestPermission);
        bindSwitchWithEnableAction(v, R.id.switchNotifyReleases,
                SettingsManager.KEY_NOTIFY_RELEASES, requestPermission);
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
        CacheManager.getStatus(requireContext(), status ->
                host().runOnUiThread(() -> {
                    if (isAdded()) tvCache.setText("Cache: " + status.toDisplayString());
                }));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Wires a switch to a boolean pref; runs onChange (if any) after every flip. */
    private void bindSwitch(View root, int id, String prefKey, boolean def, @Nullable Runnable onChange) {
        SwitchMaterial sw = root.findViewById(id);
        sw.setChecked(SettingsManager.getBool(requireContext(), prefKey, def));
        sw.setOnCheckedChangeListener((btn, checked) -> {
            SettingsManager.setBool(requireContext(), prefKey, checked);
            if (onChange != null) onChange.run();
        });
    }

    /** Like bindSwitch (default off), but runs the action only when turned ON. */
    private void bindSwitchWithEnableAction(View root, int id, String prefKey, Runnable onEnable) {
        SwitchMaterial sw = root.findViewById(id);
        sw.setChecked(SettingsManager.getBool(requireContext(), prefKey, false));
        sw.setOnCheckedChangeListener((btn, checked) -> {
            SettingsManager.setBool(requireContext(), prefKey, checked);
            if (checked) onEnable.run();
        });
    }
}
