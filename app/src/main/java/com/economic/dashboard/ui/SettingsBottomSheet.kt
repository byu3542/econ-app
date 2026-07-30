package com.economic.dashboard.ui

import android.Manifest
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat

import com.economic.dashboard.R
import com.economic.dashboard.cache.CacheManager
import com.economic.dashboard.news.NewsRepository
import com.economic.dashboard.utils.SettingsManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.ChipGroup
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.switchmaterial.SwitchMaterial

/**
 * Settings sheet opened from the gear icon in the header.
 * Every control reads its state from SettingsManager and writes back
 * immediately - there is no Save button. Consequential changes are
 * confirmed with a Snackbar anchored inside the sheet.
 */
class SettingsBottomSheet : BottomSheetDialogFragment() {

    private fun host(): MainActivity = requireActivity() as MainActivity

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        setStyle(STYLE_NORMAL, R.style.Theme_EconomicDashboard_BottomSheet24)
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        // Open fully expanded and skip the collapsed stop so a single
        // swipe down dismisses the sheet instead of snapping halfway.
        val behavior = dialog.behavior
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        behavior.skipCollapsed = true
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.sheet_settings, container, false)
    }

    override fun onViewCreated(v: View, savedInstanceState: Bundle?) {
        super.onViewCreated(v, savedInstanceState)
        setupTheme(v)
        setupData(v)
        setupDefaultTab(v)
        setupNews(v)
        setupAi(v)
        setupCharts(v)
        setupNotifications(v)
        setupAbout(v)
    }

    override fun onResume() {
        super.onResume()
        // The user may have flipped the OS notification permission and come back
        updateNotifBlockedBanner()
    }

    // -- Appearance -----------------------------------------------------------

    private fun setupTheme(v: View) {
        val tg: MaterialButtonToggleGroup = v.findViewById(R.id.tgTheme)
        val mode = SettingsManager.getNightMode(requireContext())
        if (mode == AppCompatDelegate.MODE_NIGHT_NO) tg.check(R.id.btnThemeLight)
        else if (mode == AppCompatDelegate.MODE_NIGHT_YES) tg.check(R.id.btnThemeDark)
        else tg.check(R.id.btnThemeSystem)

        tg.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                var newMode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                if (checkedId == R.id.btnThemeLight) newMode = AppCompatDelegate.MODE_NIGHT_NO
                else if (checkedId == R.id.btnThemeDark) newMode = AppCompatDelegate.MODE_NIGHT_YES
                if (newMode != SettingsManager.getNightMode(requireContext())) {
                    SettingsManager.setNightMode(requireContext(), newMode)
                    // Recreates the activity with the new theme. The sheet is restored
                    // automatically afterwards, so the user can keep comparing themes.
                    AppCompatDelegate.setDefaultNightMode(newMode)
                }
            }
        }

        // Colorblind-safe delta palette. Re-fetch so every delta chip repaints
        // with the new palette immediately.
        bindSwitch(v, R.id.switchColorblind, SettingsManager.KEY_COLORBLIND, false) {
            host().getViewModel().fetchAllData()
        }
        bindSwitch(v, R.id.switchHaptics, SettingsManager.KEY_HAPTICS, true, null) // IOS-09
    }

    // -- Data -----------------------------------------------------------------

    private fun setupData(v: View) {
        val btnRefresh: MaterialButton = v.findViewById(R.id.btnRefreshNow)
        val btnClear: MaterialButton = v.findViewById(R.id.btnClearCache)

        // Refresh runs inline: the sheet stays open and the button shows progress.
        btnRefresh.setOnClickListener {
            val act = host()
            btnRefresh.isEnabled = false
            btnClear.isEnabled = false
            btnRefresh.setText(R.string.refreshing_data)
            act.getViewModel().fetchAllData()             // live data behind the visible UI
            CacheManager.forceRefreshAll(act) { success -> // cached 24-month history
                act.runOnUiThread {
                    if (isAdded) {
                        btnRefresh.isEnabled = true
                        btnClear.isEnabled = true
                        btnRefresh.setText(R.string.refresh_data)
                        snack(if (success) R.string.refresh_done else R.string.refresh_failed)
                        updateLastUpdated()
                    }
                }
            }
        }

        btnClear.setOnClickListener {
            val act = host()
            AlertDialog.Builder(act)
                .setTitle(R.string.clear_cache)
                .setMessage(R.string.clear_cache_confirm)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    CacheManager.clearAllCaches(act) {
                        act.runOnUiThread {
                            if (isAdded) {
                                snack(R.string.cache_cleared)
                                updateLastUpdated() // freshness line reflects the empty cache
                            }
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        bindSwitch(v, R.id.switchRefreshOnOpen, SettingsManager.KEY_REFRESH_ON_OPEN, false, null)
        updateLastUpdated()
    }

    /** Refreshes the "Data updated X ago" line in the Data card. */
    private fun updateLastUpdated() {
        val v = view ?: return
        val tv: TextView = v.findViewById(R.id.tvLastUpdated)
        val act = host() // capture now: safe if the sheet closes before the callback
        CacheManager.getStatus(act) { status ->
            act.runOnUiThread {
                if (isAdded) tv.text = status.toDisplayString()
            }
        }
    }

    // -- Startup tab ------------------------------------------------------------

    private fun setupDefaultTab(v: View) {
        val cg: ChipGroup = v.findViewById(R.id.cgDefaultTab)
        val ids = intArrayOf(R.id.chipTabOverview, R.id.chipTabMarkets,
            R.id.chipTabEconomy, R.id.chipTabNews)
        val tab = SettingsManager.getDefaultTab(requireContext())
        cg.check(ids[Math.max(0, Math.min(3, tab))])

        cg.setOnCheckedStateChangeListener { _, checkedIds ->
            if (!checkedIds.isEmpty()) {
                val checkedId = checkedIds[0]
                for (i in ids.indices)
                    if (ids[i] == checkedId)
                        SettingsManager.setInt(requireContext(), SettingsManager.KEY_DEFAULT_TAB, i)
            }
        }
    }

    // -- News sources -----------------------------------------------------------

    private fun setupNews(v: View) {
        for (i in NEWS_SWITCH_IDS.indices)
            bindNewsSwitch(v, NEWS_SWITCH_IDS[i], NEWS_KEYS[i])
        updateNewsEnabledStates()
    }

    private fun bindNewsSwitch(root: View, id: Int, prefKey: String) {
        val sw: SwitchMaterial = root.findViewById(id)
        sw.isChecked = SettingsManager.getBool(requireContext(), prefKey, true)
        makeRowTappable(sw)
        sw.setOnCheckedChangeListener { _, checked ->
            SettingsManager.setBool(requireContext(), prefKey, checked)
            NewsRepository.getInstance().invalidateCache()
            updateNewsEnabledStates()
            snack(R.string.news_updated)
        }
    }

    /**
     * The last enabled source is locked (disabled while on) so the feed can
     * never go empty - prevention instead of revert-and-toast.
     */
    private fun updateNewsEnabledStates() {
        val v = view ?: return
        var enabledCount = 0
        for (k in NEWS_KEYS)
            if (SettingsManager.getBool(requireContext(), k, true)) enabledCount++
        for (i in NEWS_SWITCH_IDS.indices) {
            val sw: SwitchMaterial = v.findViewById(NEWS_SWITCH_IDS[i])
            val on = SettingsManager.getBool(requireContext(), NEWS_KEYS[i], true)
            val locked = on && enabledCount == 1
            sw.isEnabled = !locked
            (sw.parent as View).alpha = if (locked) 0.55f else 1f
        }
        val summary: TextView = v.findViewById(R.id.tvNewsSourcesSummary)
        summary.text = getString(R.string.news_sources_summary, enabledCount)
    }

    // -- AI Analyst ---------------------------------------------------------------

    private fun setupAi(v: View) {
        bindSwitch(v, R.id.switchSmartChips, SettingsManager.KEY_SMART_CHIPS, true, null)
        bindSwitch(v, R.id.switchDetailedAi, SettingsManager.KEY_DETAILED_AI, false, null)

        v.findViewById<View>(R.id.btnClearChat).setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.clear_chat_title)
                .setMessage(R.string.clear_chat_confirm)
                .setPositiveButton(R.string.clear_chat_action) { _, _ ->
                    host().clearChatHistory()
                    snack(R.string.chat_cleared)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    // -- Charts -------------------------------------------------------------------

    private fun setupCharts(v: View) {
        // A note under the controls tells the user changes apply on next chart open
        bindSwitch(v, R.id.switchGridlines, SettingsManager.KEY_CHART_GRIDLINES, true, null)

        val tg: MaterialButtonToggleGroup = v.findViewById(R.id.tgDecimals)
        val ids = intArrayOf(R.id.btnDec1, R.id.btnDec2, R.id.btnDec3)
        val decimals = SettingsManager.getChartDecimals(requireContext())
        tg.check(ids[Math.max(1, Math.min(3, decimals)) - 1])

        tg.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                for (i in ids.indices)
                    if (ids[i] == checkedId)
                        SettingsManager.setInt(requireContext(), SettingsManager.KEY_CHART_DECIMALS, i + 1)
            }
        }

        // Default chart time range — standardizes the x-axis window on every chart
        val tgTf: MaterialButtonToggleGroup = v.findViewById(R.id.tgTimeframe)
        val tfIds = intArrayOf(R.id.btnTf3m, R.id.btnTf6m, R.id.btnTf1y, R.id.btnTf2y, R.id.btnTf5y)
        val tfMonths = intArrayOf(3, 6, 12, 24, 60)
        val currentMonths = SettingsManager.getChartTimeframeMonths(requireContext())
        var sel = tfMonths.size - 1 // default -> 5Y
        for (i in tfMonths.indices) if (tfMonths[i] == currentMonths) { sel = i; break }
        tgTf.check(tfIds[sel])
        tgTf.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                for (i in tfIds.indices)
                    if (tfIds[i] == checkedId)
                        SettingsManager.setInt(requireContext(), SettingsManager.KEY_CHART_TIMEFRAME, tfMonths[i])
            }
        }
    }

    // -- Notifications --------------------------------------------------------------

    private fun setupNotifications(v: View) {
        bindNotifySwitch(v, R.id.switchNotifyMoves, SettingsManager.KEY_NOTIFY_BIG_MOVES)
        bindNotifySwitch(v, R.id.switchNotifyReleases, SettingsManager.KEY_NOTIFY_RELEASES)
        bindNotifySwitch(v, R.id.switchDailyBrief, SettingsManager.KEY_DAILY_BRIEF)

        val openNotificationSettings = View.OnClickListener {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE,
                    requireContext().packageName))
        }
        v.findViewById<View>(R.id.tvNotifBlocked).setOnClickListener(openNotificationSettings)
        v.findViewById<View>(R.id.tvNotifBlockedHint).setOnClickListener(openNotificationSettings)
        updateNotifBlockedBanner()
    }

    /** Shows OFF when the OS permission is missing, regardless of the stored pref. */
    private fun bindNotifySwitch(root: View, id: Int, prefKey: String) {
        val sw: SwitchMaterial = root.findViewById(id)
        sw.isChecked = notificationsAllowed() &&
            SettingsManager.getBool(requireContext(), prefKey, false)
        makeRowTappable(sw)
        sw.setOnCheckedChangeListener { _, checked ->
            SettingsManager.setBool(requireContext(), prefKey, checked)
            if (checked) host().ensureNotificationPermission()
        }
    }

    /** "Notifications are blocked" link row - visible only when permission is missing. */
    private fun updateNotifBlockedBanner() {
        val v = view ?: return
        val allowed = notificationsAllowed()
        v.findViewById<View>(R.id.tvNotifBlocked)
            .visibility = if (allowed) View.GONE else View.VISIBLE
        v.findViewById<View>(R.id.tvNotifBlockedHint)
            .visibility = if (allowed) View.GONE else View.VISIBLE
        v.findViewById<TextView>(R.id.tvNotifStatus).text =
            getString(if (allowed) R.string.notifications_enabled_summary
            else R.string.notifications_blocked_summary)
    }

    private fun notificationsAllowed(): Boolean {
        return Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    /** Called by MainActivity when the POST_NOTIFICATIONS request is denied. */
    fun onNotificationPermissionDenied() {
        val v = view ?: return
        // setChecked fires the listeners, which also revert the stored prefs
        v.findViewById<SwitchMaterial>(R.id.switchNotifyMoves).isChecked = false
        v.findViewById<SwitchMaterial>(R.id.switchNotifyReleases).isChecked = false
        v.findViewById<SwitchMaterial>(R.id.switchDailyBrief).isChecked = false
        updateNotifBlockedBanner()
        snack(R.string.notifications_denied)
    }

    // -- About ------------------------------------------------------------------------

    private fun setupAbout(v: View) {
        val tvVersion: TextView = v.findViewById(R.id.tvVersion)
        try {
            val name = requireContext().packageManager
                .getPackageInfo(requireContext().packageName, 0).versionName
            tvVersion.text = getString(R.string.about_version_label, name)
        } catch (e: Exception) {
            tvVersion.text = "Econ Monitor"
        }
    }

    // -- Helpers ------------------------------------------------------------------------

    /** Short confirmation shown inside the sheet after a consequential change. */
    private fun snack(messageRes: Int) {
        val v = view ?: return
        Snackbar.make(v, messageRes, Snackbar.LENGTH_SHORT).show()
    }

    /** Tapping anywhere on the row flips its switch (not just the small thumb). */
    private fun makeRowTappable(sw: SwitchMaterial) {
        val row = sw.parent as View
        row.setOnClickListener {
            if (sw.isEnabled) sw.toggle()
        }
    }

    /** Wires a switch to a boolean pref; runs onChange (if any) after every flip. */
    private fun bindSwitch(root: View, id: Int, prefKey: String, def: Boolean, onChange: (() -> Unit)?) {
        val sw: SwitchMaterial = root.findViewById(id)
        sw.isChecked = SettingsManager.getBool(requireContext(), prefKey, def)
        makeRowTappable(sw)
        sw.setOnCheckedChangeListener { _, checked ->
            SettingsManager.setBool(requireContext(), prefKey, checked)
            onChange?.invoke()
        }
    }

    companion object {
        /** Read as `SettingsBottomSheet.TAG` from MainActivity.java (still Java
         *  until 11c), so this must be a `const val` — a plain `val` would need
         *  `Companion.getTAG()`. */
        const val TAG = "SettingsBottomSheet"

        private val NEWS_KEYS = arrayOf(
            SettingsManager.KEY_NEWS_GOV, SettingsManager.KEY_NEWS_MEDIA,
            SettingsManager.KEY_NEWS_INTL, SettingsManager.KEY_NEWS_RESEARCH)

        private val NEWS_SWITCH_IDS = intArrayOf(
            R.id.switchNewsGov, R.id.switchNewsMedia,
            R.id.switchNewsIntl, R.id.switchNewsResearch)
    }
}
