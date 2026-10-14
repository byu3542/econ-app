package com.economic.dashboard.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment

import com.economic.dashboard.R
import com.economic.dashboard.cache.CacheManager
import com.economic.dashboard.databinding.FragmentSettingsBinding
import com.economic.dashboard.news.NewsRepository
import com.economic.dashboard.ui.views.SegmentedView
import com.economic.dashboard.utils.EdgeToEdgeInsets
import com.economic.dashboard.utils.SettingsManager
import com.economic.dashboard.utils.markDestructive
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.card.MaterialCardView
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.switchmaterial.SwitchMaterial

/**
 * Settings as a full-screen page (design 1b), opened from the gear icon in
 * the header like any other tab — replacing the old bottom-sheet pattern.
 * Two panes share one layout, toggled by visibility:
 *   - list pane: grouped rows with live value summaries (SettingsList mock)
 *   - detail pane: one section's controls at a time (SettingsDetail mock)
 * Every control still reads/writes SettingsManager directly with no Save
 * button, same as before.
 */
class SettingsFragment : Fragment() {

    private var binding: FragmentSettingsBinding? = null

    /** Section id currently shown in the detail pane, or null when the list is showing. */
    private var activeSection: String? = null

    private fun host(): MainActivity = requireActivity() as MainActivity

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val b = FragmentSettingsBinding.inflate(inflater, container, false)
        binding = b
        // MainActivity hides bottomNavWrapper while Settings is open, so these
        // scroll views only need to clear the header (top) and the raw system
        // gesture/nav-bar inset (bottom) — not the app's own bottom-nav height.
        EdgeToEdgeInsets.applyTopInset(b.settingsListScroll)
        EdgeToEdgeInsets.applyTopInset(b.settingsDetailScroll)
        padForSystemBottomInset(b.settingsListScroll)
        padForSystemBottomInset(b.settingsDetailScroll)
        return b.root
    }

    private fun padForSystemBottomInset(scrollView: View) {
        scrollView.setPadding(
            scrollView.paddingLeft, scrollView.paddingTop, scrollView.paddingRight,
            scrollView.paddingBottom + EdgeToEdgeInsets.getSystemBottomInsetPx()
        )
        (scrollView as? ViewGroup)?.clipToPadding = false
    }

    override fun onViewCreated(v: View, savedInstanceState: Bundle?) {
        super.onViewCreated(v, savedInstanceState)
        val b = binding ?: return

        setupTheme(v)
        setupData(v)
        setupDefaultTab(v)
        setupNews(v)
        setupAi(v)
        setupCharts(v)
        setupNotifications(v)
        setupAbout(v)

        b.rowBackToList.setOnClickListener { showList() }
        b.etSettingsSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { buildList() }
        })

        buildList()
    }

    override fun onResume() {
        super.onResume()
        updateNotifBlockedBanner()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }

    /** Consulted by MainActivity's back handler. Returns true if the press was
     *  handled here (detail -> list); false means the caller should close Settings. */
    fun handleBack(): Boolean {
        if (activeSection != null) {
            showList()
            return true
        }
        return false
    }

    // -- List <-> detail navigation ------------------------------------------------

    private fun showSection(id: String) {
        val b = binding ?: return
        activeSection = id
        val meta = SECTIONS.first { it.id == id }
        b.tvDetailTitle.text = getString(meta.titleRes)
        for ((key, card) in detailCards(b)) card.visibility = if (key == id) View.VISIBLE else View.GONE
        b.settingsListScroll.visibility = View.GONE
        b.settingsDetailScroll.visibility = View.VISIBLE
        b.settingsDetailScroll.scrollTo(0, 0)
    }

    private fun showList() {
        val b = binding ?: return
        activeSection = null
        b.settingsDetailScroll.visibility = View.GONE
        b.settingsListScroll.visibility = View.VISIBLE
        buildList() // values may have changed while a section was open
    }

    private fun detailCards(b: FragmentSettingsBinding): Map<String, MaterialCardView> = mapOf(
        "appearance" to b.cardAppearance,
        "startup" to b.cardStartup,
        "ai" to b.cardAi,
        "data" to b.cardData,
        "news" to b.cardNews,
        "charts" to b.cardCharts,
        "notifications" to b.cardNotifications,
        "about" to b.cardAbout,
    )

    // -- List pane: grouped rows with live summaries -------------------------------

    private fun buildList() {
        val b = binding ?: return
        b.llGroupFrequent.removeAllViews()
        b.llGroupMore.removeAllViews()
        b.llGroupAbout.removeAllViews()

        val query = b.etSettingsSearch.text?.toString()?.trim()?.lowercase() ?: ""
        val visible = SECTIONS.filter { query.isEmpty() || getString(it.titleRes).lowercase().contains(query) }

        addRows(b.llGroupFrequent, visible.filter { it.group == Group.FREQUENT })
        addRows(b.llGroupMore, visible.filter { it.group == Group.MORE })
        addRows(b.llGroupAbout, visible.filter { it.group == Group.ABOUT })

        val hasFrequent = visible.any { it.group == Group.FREQUENT }
        val hasMore = visible.any { it.group == Group.MORE }
        val hasAbout = visible.any { it.group == Group.ABOUT }
        b.tvGroupFrequent.visibility = if (hasFrequent) View.VISIBLE else View.GONE
        b.cardGroupFrequent.visibility = if (hasFrequent) View.VISIBLE else View.GONE
        b.tvGroupMore.visibility = if (hasMore) View.VISIBLE else View.GONE
        b.cardGroupMore.visibility = if (hasMore) View.VISIBLE else View.GONE
        b.tvGroupAbout.visibility = if (hasAbout) View.VISIBLE else View.GONE
        b.cardGroupAbout.visibility = if (hasAbout) View.VISIBLE else View.GONE
    }

    private fun addRows(container: LinearLayout, rows: List<SectionMeta>) {
        val inflater = LayoutInflater.from(requireContext())
        rows.forEachIndexed { i, meta ->
            val row = inflater.inflate(R.layout.item_settings_row, container, false)
            val tvBadge: TextView = row.findViewById(R.id.tvBadge)
            val tvTitle: TextView = row.findViewById(R.id.tvRowTitle)
            val tvSummary: TextView = row.findViewById(R.id.tvRowSummary)
            val divider: View = row.findViewById(R.id.rowDivider)
            val content: View = row.findViewById(R.id.rowContent)

            tvBadge.text = meta.initials
            val (bg, fg) = if (meta.group == Group.ABOUT)
                Pair(R.color.tab_bg_warm_gray, R.color.text_secondary)
            else
                Pair(R.color.accent_purple_soft, R.color.accent_purple)
            tvBadge.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), bg))
            tvBadge.setTextColor(ContextCompat.getColor(requireContext(), fg))
            tvTitle.text = getString(meta.titleRes)
            tvSummary.text = summaryFor(meta.id)
            divider.visibility = if (i == rows.lastIndex) View.GONE else View.VISIBLE
            content.setOnClickListener { showSection(meta.id) }
            container.addView(row)
        }
    }

    private fun summaryFor(id: String): String {
        val c = requireContext()
        return when (id) {
            "appearance" -> {
                val themeLabel = when (SettingsManager.getNightMode(c)) {
                    AppCompatDelegate.MODE_NIGHT_NO -> getString(R.string.theme_light)
                    AppCompatDelegate.MODE_NIGHT_YES -> getString(R.string.theme_dark)
                    else -> getString(R.string.theme_system)
                }
                val hapticsOn = SettingsManager.getBool(c, SettingsManager.KEY_HAPTICS, true)
                getString(R.string.settings_summary_appearance, themeLabel, onOff(hapticsOn))
            }
            "startup" -> {
                val labels = intArrayOf(R.string.tab_overview, R.string.tab_markets, R.string.tab_economy, R.string.tab_news)
                getString(labels[SettingsManager.getDefaultTab(c).coerceIn(0, 3)])
            }
            "ai" -> {
                val chipsOn = SettingsManager.getBool(c, SettingsManager.KEY_SMART_CHIPS, true)
                val detailed = SettingsManager.getBool(c, SettingsManager.KEY_DETAILED_AI, false)
                getString(
                    R.string.settings_summary_ai,
                    getString(if (chipsOn) R.string.settings_summary_ai_chips_on else R.string.settings_summary_ai_chips_off),
                    getString(if (detailed) R.string.settings_summary_ai_detailed else R.string.settings_summary_ai_concise)
                )
            }
            "data" -> getString(
                if (SettingsManager.getBool(c, SettingsManager.KEY_REFRESH_ON_OPEN, false))
                    R.string.settings_summary_data_on_open else R.string.settings_summary_data_manual
            )
            "news" -> getString(R.string.settings_summary_news, NEWS_KEYS.count { SettingsManager.getBool(c, it, true) })
            "charts" -> getString(
                R.string.settings_summary_charts,
                onOff(SettingsManager.gridlinesEnabled(c)),
                timeframeLabel(SettingsManager.getChartTimeframeMonths(c))
            )
            "notifications" -> getString(
                R.string.settings_summary_notifications,
                NOTIFY_KEYS.count { SettingsManager.getBool(c, it, false) }
            )
            "about" -> try {
                val name = c.packageManager.getPackageInfo(c.packageName, 0).versionName
                getString(R.string.about_version_label, name)
            } catch (e: Exception) {
                "Economii"
            }
            else -> ""
        }
    }

    private fun onOff(v: Boolean) = getString(if (v) R.string.settings_summary_on else R.string.settings_summary_off)

    private fun timeframeLabel(months: Int): String = when (months) {
        3 -> getString(R.string.tf_3m)
        6 -> getString(R.string.tf_6m)
        12 -> getString(R.string.tf_1y)
        24 -> getString(R.string.tf_2y)
        else -> getString(R.string.tf_5y)
    }

    // -- Appearance -----------------------------------------------------------

    private fun setupTheme(v: View) {
        val seg: SegmentedView = v.findViewById(R.id.segTheme)
        val mode = SettingsManager.getNightMode(requireContext())
        val tag = when (mode) {
            AppCompatDelegate.MODE_NIGHT_NO -> "light"
            AppCompatDelegate.MODE_NIGHT_YES -> "dark"
            else -> "system"
        }
        seg.select(tag, animate = false)

        seg.onSelect = SegmentedView.OnSelect { picked ->
            val newMode = when (picked) {
                "light" -> AppCompatDelegate.MODE_NIGHT_NO
                "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            if (newMode != SettingsManager.getNightMode(requireContext())) {
                SettingsManager.setNightMode(requireContext(), newMode)
                // Recreates the activity with the new theme. MainActivity's
                // setupBottomNav keeps the restored Settings on screen (and
                // re-applies its chrome), so the user can keep comparing themes.
                AppCompatDelegate.setDefaultNightMode(newMode)
            }
        }

        // Colorblind-safe delta palette. Re-fetch so every delta chip repaints
        // with the new palette immediately.
        bindSwitch(v, R.id.switchColorblind, SettingsManager.KEY_COLORBLIND, false) {
            host().getViewModel().fetchAllData()
        }
        bindSwitch(v, R.id.switchHaptics, SettingsManager.KEY_HAPTICS, true, null)
    }

    // -- Data -----------------------------------------------------------------

    private fun setupData(v: View) {
        val btnRefresh: MaterialButton = v.findViewById(R.id.btnRefreshNow)
        val btnClear: MaterialButton = v.findViewById(R.id.btnClearCache)

        // Refresh runs inline: Settings stays open and the button shows progress.
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
            MaterialAlertDialogBuilder(act)
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
                .markDestructive()
        }

        bindSwitch(v, R.id.switchRefreshOnOpen, SettingsManager.KEY_REFRESH_ON_OPEN, false, null)
        updateLastUpdated()
    }

    /** Refreshes the "Data updated X ago" line in the Data card. */
    private fun updateLastUpdated() {
        val v = view ?: return
        val tv: TextView = v.findViewById(R.id.tvLastUpdated)
        val act = host() // capture now: safe if the fragment is torn down before the callback
        CacheManager.getStatus(act) { status ->
            act.runOnUiThread {
                if (isAdded) tv.text = status.toDisplayString()
            }
        }
    }

    // -- Startup tab ------------------------------------------------------------

    private fun setupDefaultTab(v: View) {
        val seg: SegmentedView = v.findViewById(R.id.segDefaultTab)
        val tab = SettingsManager.getDefaultTab(requireContext())
        seg.select(tab.coerceIn(0, 3).toString(), animate = false)

        seg.onSelect = SegmentedView.OnSelect { picked ->
            val i = picked.toIntOrNull() ?: return@OnSelect
            SettingsManager.setInt(requireContext(), SettingsManager.KEY_DEFAULT_TAB, i)
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
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.clear_chat_title)
                .setMessage(R.string.clear_chat_confirm)
                .setPositiveButton(R.string.clear_chat_action) { _, _ ->
                    host().clearChatHistory()
                    snack(R.string.chat_cleared)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
                .markDestructive()
        }
    }

    // -- Charts -------------------------------------------------------------------

    private fun setupCharts(v: View) {
        // A note under the controls tells the user changes apply on next chart open
        bindSwitch(v, R.id.switchGridlines, SettingsManager.KEY_CHART_GRIDLINES, true, null)

        val segDec: SegmentedView = v.findViewById(R.id.segDecimals)
        val decimals = SettingsManager.getChartDecimals(requireContext())
        segDec.select(decimals.coerceIn(1, 3).toString(), animate = false)
        segDec.onSelect = SegmentedView.OnSelect { picked ->
            val n = picked.toIntOrNull() ?: return@OnSelect
            SettingsManager.setInt(requireContext(), SettingsManager.KEY_CHART_DECIMALS, n)
        }

        // Default chart time range — standardizes the x-axis window on every chart
        val segTf: SegmentedView = v.findViewById(R.id.segTimeframe)
        val tfMonths = intArrayOf(3, 6, 12, 24, 60)
        val currentMonths = SettingsManager.getChartTimeframeMonths(requireContext())
        val sel = if (currentMonths in tfMonths) currentMonths else 60 // default -> 5Y
        segTf.select(sel.toString(), animate = false)
        segTf.onSelect = SegmentedView.OnSelect { picked ->
            val months = picked.toIntOrNull() ?: return@OnSelect
            SettingsManager.setInt(requireContext(), SettingsManager.KEY_CHART_TIMEFRAME, months)
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
            tvVersion.text = "Economii"
        }
    }

    // -- Helpers ------------------------------------------------------------------------

    /** Short confirmation shown after a consequential change. */
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

    // -- Section catalog ------------------------------------------------------------

    private enum class Group { FREQUENT, MORE, ABOUT }

    private data class SectionMeta(val id: String, val titleRes: Int, val initials: String, val group: Group)

    companion object {
        /** Fragment-manager tag, shared by MainActivity's open/close/back logic. */
        const val TAG = "SettingsFragment"

        private val SECTIONS = listOf(
            SectionMeta("appearance", R.string.section_appearance, "Ap", Group.FREQUENT),
            SectionMeta("startup", R.string.section_startup_tab, "St", Group.FREQUENT),
            SectionMeta("ai", R.string.section_ai_analyst, "AI", Group.FREQUENT),
            SectionMeta("data", R.string.section_data, "Da", Group.MORE),
            SectionMeta("news", R.string.section_news_sources, "Ne", Group.MORE),
            SectionMeta("charts", R.string.section_charts, "Ch", Group.MORE),
            SectionMeta("notifications", R.string.section_notifications, "No", Group.MORE),
            SectionMeta("about", R.string.section_about, "Ab", Group.ABOUT),
        )

        private val NEWS_KEYS = arrayOf(
            SettingsManager.KEY_NEWS_GOV, SettingsManager.KEY_NEWS_MEDIA,
            SettingsManager.KEY_NEWS_INTL, SettingsManager.KEY_NEWS_RESEARCH)

        private val NEWS_SWITCH_IDS = intArrayOf(
            R.id.switchNewsGov, R.id.switchNewsMedia,
            R.id.switchNewsIntl, R.id.switchNewsResearch)

        private val NOTIFY_KEYS = arrayOf(
            SettingsManager.KEY_NOTIFY_BIG_MOVES, SettingsManager.KEY_NOTIFY_RELEASES,
            SettingsManager.KEY_DAILY_BRIEF)
    }
}
