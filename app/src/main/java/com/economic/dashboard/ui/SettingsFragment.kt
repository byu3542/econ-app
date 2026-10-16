package com.economic.dashboard.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.util.TypedValue
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment

import com.economic.dashboard.R
import com.economic.dashboard.cache.CacheManager
import com.economic.dashboard.databinding.FragmentSettingsBinding
import com.economic.dashboard.news.NewsRepository
import com.economic.dashboard.ui.views.PressFeedback
import com.economic.dashboard.ui.views.SegmentedView
import com.economic.dashboard.utils.EdgeToEdgeInsets
import com.economic.dashboard.utils.SettingsManager
import com.economic.dashboard.ui.shortcuts.ShortcutCatalog
import com.economic.dashboard.ui.shortcuts.ShortcutEditor
import com.economic.dashboard.ui.shortcuts.ShortcutsEditorAdapter
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import com.economic.dashboard.utils.markDestructive
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.materialswitch.MaterialSwitch

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
    /** Settings parity U7: the open editor's working copy; null when closed. */
    private var shortcutEditor: ShortcutEditor? = null

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
        EdgeToEdgeInsets.applyTopInset(b.shortcutsEditorPane)
        padForSystemBottomInset(b.shortcutsEditorPane)
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
        // U4: a theme or accent change recreates the Activity; land back on
        // the page the user was on (Appearance), not the Settings root.
        val restored = savedInstanceState?.getString(STATE_SECTION)
            ?: if (savedInstanceState == null) arguments?.getString(ARG_SECTION) else null

        setupTheme(v)
        setupData(v)
        setupDefaultTab(v)
        setupNews(v)
        setupAi(v)
        setupCharts(v)
        setupNotifications(v)
        setupAbout(v)

        b.etSettingsSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { buildList() }
        })

        setupFooter(b)
        buildList()
        if (restored != null && SECTIONS.any { it.id == restored }) showSection(restored)
        else host().setSettingsTitle(getString(R.string.settings))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_SECTION, activeSection)
    }

    override fun onResume() {
        super.onResume()
        updateNotifBlockedBanner()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }

    /** Settings parity U6: open straight to a page when Settings is already up. */
    fun openSection(id: String) {
        if (SECTIONS.any { it.id == id && !it.placeholder }) showSection(id)
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
        host().setSettingsTitle(getString(titleFor(id)))
        for ((key, card) in detailCards(b)) card.visibility = if (key == id) View.VISIBLE else View.GONE
        b.settingsListScroll.visibility = View.GONE
        if (id == SECTION_SHORTCUTS) {
            b.settingsDetailScroll.visibility = View.GONE
            openShortcutsEditor(b)
            return
        }
        b.shortcutsEditorPane.visibility = View.GONE
        b.settingsDetailScroll.visibility = View.VISIBLE
        b.settingsDetailScroll.scrollTo(0, 0)
    }

    /** Settings parity U7 (R11): a fresh working copy every time the page
     *  opens, so leaving without Save changes discards the edit. */
    private fun openShortcutsEditor(b: FragmentSettingsBinding) {
        val editor = ShortcutEditor(SettingsManager.getShortcuts(requireContext()))
        shortcutEditor = editor
        val rv = b.rvShortcutsEditor
        val adapter = ShortcutsEditorAdapter(editor) { updateSaveButton(b) }
        if (rv.layoutManager == null) {
            rv.layoutManager = LinearLayoutManager(requireContext())
            rv.addItemDecoration(ShortcutsEditorAdapter.CardDecoration(rv))
        }
        val touchHelper = ItemTouchHelper(ShortcutsEditorAdapter.DragCallback(adapter))
        adapter.touchHelper = touchHelper
        touchHelper.attachToRecyclerView(rv)
        rv.adapter = adapter
        b.btnShortcutsSave.setOnClickListener {
            val e = shortcutEditor ?: return@setOnClickListener
            if (!e.isDirty) return@setOnClickListener
            SettingsManager.setShortcuts(requireContext(), e.ids)
            Toast.makeText(requireContext(), R.string.shortcuts_editor_saved, Toast.LENGTH_SHORT).show()
            showList()
        }
        updateSaveButton(b)
        b.shortcutsEditorPane.visibility = View.VISIBLE
    }

    /** Save changes is live only once something changed. */
    private fun updateSaveButton(b: FragmentSettingsBinding) {
        val dirty = shortcutEditor?.isDirty == true
        b.btnShortcutsSave.isEnabled = dirty
        b.btnShortcutsSave.alpha = if (dirty) 1f else 0.45f
    }

    private fun showList() {
        val b = binding ?: return
        activeSection = null
        shortcutEditor = null // unsaved edits are discarded (R11)
        host().setSettingsTitle(getString(R.string.settings))
        b.settingsDetailScroll.visibility = View.GONE
        b.shortcutsEditorPane.visibility = View.GONE
        b.settingsListScroll.visibility = View.VISIBLE
        buildList() // values may have changed while a section was open
    }

    private fun detailCards(b: FragmentSettingsBinding): Map<String, View> = mapOf(
        "appearance" to b.appearancePage,
        "startup" to b.cardStartup,
        "ai" to b.aiPage,
        "data" to b.dataPage,
        "news" to b.cardNews,
        "charts" to b.cardCharts,
        "notifications" to b.cardNotifications,
        "sources" to b.cardSources,
        "about" to b.cardAbout,
    )

    private fun titleFor(id: String?): Int =
        SECTIONS.firstOrNull { it.id == id }?.titleRes ?: R.string.settings

    // -- List pane: grouped rows with live summaries -------------------------------

    private fun buildList() {
        val b = binding ?: return
        val query = b.etSettingsSearch.text?.toString()?.trim()?.lowercase() ?: ""
        // Search matches the subtitle too, so "grid" finds Charts (Gridlines on).
        val visible = SECTIONS.filter {
            query.isEmpty() || getString(it.titleRes).lowercase().contains(query) ||
                summaryFor(it.id).lowercase().contains(query)
        }
        val groups = listOf(
            Triple(Group.PREFERENCES, b.tvGroupPreferences, b.cardGroupPreferences) to b.llGroupPreferences,
            Triple(Group.DATA, b.tvGroupData, b.cardGroupData) to b.llGroupData,
            Triple(Group.SUPPORT, b.tvGroupSupport, b.cardGroupSupport) to b.llGroupSupport,
        )
        for ((g, container) in groups) {
            val (group, header, card) = g
            val rows = visible.filter { it.group == group }
            container.removeAllViews()
            addRows(container, rows)
            val shown = if (rows.isEmpty()) View.GONE else View.VISIBLE
            header.visibility = shown
            card.visibility = shown
        }
        b.settingsFooter.visibility = if (query.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun addRows(container: LinearLayout, rows: List<SectionMeta>) {
        val inflater = LayoutInflater.from(requireContext())
        rows.forEachIndexed { i, meta ->
            val row = inflater.inflate(R.layout.item_settings_row, container, false)
            val icon: ImageView = row.findViewById(R.id.ivRowIcon)
            val tvTitle: TextView = row.findViewById(R.id.tvRowTitle)
            val tvSummary: TextView = row.findViewById(R.id.tvRowSummary)
            val divider: View = row.findViewById(R.id.rowDivider)
            val content: View = row.findViewById(R.id.rowContent)

            icon.setImageResource(meta.iconRes)
            tvTitle.text = getString(meta.titleRes)
            tvSummary.text = summaryFor(meta.id)
            divider.visibility = if (i == rows.lastIndex) View.GONE else View.VISIBLE
            // TalkBack reads the row as one button: title, subtitle, "button".
            content.accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = Button::class.java.name
                }
            }
            if (meta.placeholder) {
                // Placeholder until Aaron names the target (2026-10-16).
                content.isEnabled = false
                content.alpha = 0.55f
                row.findViewById<View>(R.id.ivRowChevron).visibility = View.GONE
            } else {
                content.setOnClickListener { showSection(meta.id) }
                PressFeedback.attach(content)
            }
            container.addView(row)
        }
    }

    private fun setupFooter(b: FragmentSettingsBinding) {
        b.tvFooterVersion.text = summaryFor("about")
    }

    private fun summaryFor(id: String): String {
        val c = requireContext()
        return when (id) {
            SECTION_SHORTCUTS -> getString(R.string.settings_summary_shortcuts,
                SettingsManager.getShortcuts(c).size, ShortcutCatalog.MAX)
            "appearance" -> {
                val themeLabel = when (SettingsManager.getNightMode(c)) {
                    AppCompatDelegate.MODE_NIGHT_NO -> getString(R.string.theme_light)
                    AppCompatDelegate.MODE_NIGHT_YES -> getString(R.string.theme_dark)
                    else -> getString(R.string.theme_system)
                }
                getString(R.string.settings_summary_appearance_accent, themeLabel, SettingsManager.getAccent(c).label)
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
            "sources" -> getString(R.string.settings_summary_sources)
            "feedback", "privacy" -> getString(R.string.settings_coming_soon)
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
        val b = binding ?: return
        buildThemeRows(b)
        buildAccentSwatches(b)

        // Colorblind-safe delta palette. Re-fetch so every delta chip repaints
        // with the new palette immediately.
        bindSwitch(v, R.id.switchColorblind, SettingsManager.KEY_COLORBLIND, false) {
            host().getViewModel().fetchAllData()
        }
        bindSwitch(v, R.id.switchHaptics, SettingsManager.KEY_HAPTICS, true, null)
    }

    private data class ThemeChoice(val mode: Int, val titleRes: Int, val subRes: Int, val iconRes: Int)

    /** U4 (R5, KTD4): System, Light and Dark as choice rows; the current one
     *  gets an accent-soft tile and an accent check. Replaces the segmented control. */
    private fun buildThemeRows(b: FragmentSettingsBinding) {
        val c = requireContext()
        val choices = listOf(
            ThemeChoice(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, R.string.theme_system, R.string.theme_system_sub, R.drawable.ic_settings),
            ThemeChoice(AppCompatDelegate.MODE_NIGHT_NO, R.string.theme_light, R.string.theme_light_sub, R.drawable.ic_theme_light),
            ThemeChoice(AppCompatDelegate.MODE_NIGHT_YES, R.string.theme_dark, R.string.theme_dark_sub, R.drawable.ic_theme_dark),
        )
        val current = SettingsManager.getNightMode(c)
        val container = b.llThemeRows
        container.removeAllViews()
        val inflater = LayoutInflater.from(c)
        choices.forEachIndexed { i, choice ->
            val row = inflater.inflate(R.layout.item_settings_row, container, false)
            val icon: ImageView = row.findViewById(R.id.ivRowIcon)
            val content: View = row.findViewById(R.id.rowContent)
            val selected = choice.mode == current
            icon.setImageResource(choice.iconRes)
            row.findViewById<TextView>(R.id.tvRowTitle).setText(choice.titleRes)
            row.findViewById<TextView>(R.id.tvRowSummary).setText(choice.subRes)
            row.findViewById<View>(R.id.ivRowChevron).visibility = View.GONE
            row.findViewById<View>(R.id.ivRowCheck).visibility = if (selected) View.VISIBLE else View.GONE
            row.findViewById<View>(R.id.rowDivider).visibility = if (i == choices.lastIndex) View.GONE else View.VISIBLE
            if (selected) {
                icon.backgroundTintList = ColorStateList.valueOf(themeColor(R.attr.econAccentSoft))
                icon.imageTintList = ColorStateList.valueOf(themeColor(R.attr.econAccent))
            }
            content.accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = RadioButton::class.java.name
                    info.isCheckable = true
                    info.isChecked = selected
                }
            }
            content.setOnClickListener {
                if (choice.mode != SettingsManager.getNightMode(c)) {
                    SettingsManager.setNightMode(c, choice.mode)
                    // Recreates the activity with the new theme; the saved
                    // section brings the user straight back to Appearance.
                    AppCompatDelegate.setDefaultNightMode(choice.mode)
                }
            }
            PressFeedback.attach(content)
            container.addView(row)
        }
    }

    /** U4 (R5, R6): seven 52dp swatches, five to a row. A tap previews the
     *  accent on the Example card, then saves it and recreates onto Appearance. */
    private fun buildAccentSwatches(b: FragmentSettingsBinding) {
        val c = requireContext()
        val night = isNight()
        val current = SettingsManager.getAccent(c)
        val container = b.llAccentSwatches
        container.removeAllViews()
        val inflater = LayoutInflater.from(c)
        val gap = resources.getDimensionPixelSize(R.dimen.space_sm)
        var line: LinearLayout? = null
        AccentPalette.values().forEachIndexed { i, accent ->
            if (i % SWATCHES_PER_ROW == 0) {
                line = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
                container.addView(line, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { if (i > 0) topMargin = gap })
            }
            val swatch = inflater.inflate(R.layout.view_accent_swatch, line, false)
            val fill = (if (night) accent.night else accent.light).toInt()
            val selected = accent == current
            swatch.findViewById<View>(R.id.swatchFill).backgroundTintList = ColorStateList.valueOf(fill)
            swatch.findViewById<View>(R.id.swatchRing).visibility = if (selected) View.VISIBLE else View.INVISIBLE
            swatch.findViewById<ImageView>(R.id.swatchCheck).apply {
                visibility = if (selected) View.VISIBLE else View.GONE
                imageTintList = ColorStateList.valueOf(onAccent(night))
            }
            swatch.contentDescription = getString(R.string.accent_swatch_cd, accent.label)
            swatch.isSelected = selected
            swatch.setOnClickListener {
                if (accent == SettingsManager.getAccent(c)) return@setOnClickListener
                previewExample(b, fill, onAccent(night))
                SettingsManager.setAccent(c, accent)
                swatch.postDelayed({ if (isAdded) requireActivity().recreate() }, ACCENT_APPLY_DELAY_MS)
            }
            PressFeedback.attach(swatch)
            // Keep the layout's 52dp size; only add the gap between swatches.
            (swatch.layoutParams as LinearLayout.LayoutParams).apply {
                if (i % SWATCHES_PER_ROW > 0) marginStart = gap
            }
            line!!.addView(swatch)
        }
    }

    /** Paints the Example card in a not-yet-applied accent (R5). */
    private fun previewExample(b: FragmentSettingsBinding, fill: Int, on: Int) {
        val ex = b.accentExample
        val fillList = ColorStateList.valueOf(fill)
        ex.exChipOn.backgroundTintList = fillList
        ex.exChipOn.setTextColor(on)
        ex.exButton.backgroundTintList = fillList
        ex.exButton.setTextColor(on)
        ex.exPercent.setTextColor(fill)
        ex.exProgress.setIndicatorColor(fill)
        ex.exSwitch.trackTintList = fillList
    }

    private fun isNight(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun onAccent(night: Boolean): Int =
        (if (night) AccentPalette.ON_ACCENT_NIGHT else AccentPalette.ON_ACCENT_LIGHT).toInt()

    private fun themeColor(attr: Int): Int {
        val tv = TypedValue()
        requireContext().theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    // -- Data -----------------------------------------------------------------

    private fun setupData(v: View) {
        val btnRefresh: View = v.findViewById(R.id.rowRefreshNow)
        val tvRefresh: TextView = v.findViewById(R.id.tvRefreshTitle)
        val btnClear: View = v.findViewById(R.id.btnClearCache)

        // Refresh runs inline: Settings stays open and the button shows progress.
        btnRefresh.setOnClickListener {
            val act = host()
            btnRefresh.isEnabled = false
            btnClear.isEnabled = false
            tvRefresh.setText(R.string.refreshing_data)
            act.getViewModel().fetchAllData()             // live data behind the visible UI
            CacheManager.forceRefreshAll(act) { success -> // cached 24-month history
                act.runOnUiThread {
                    if (isAdded) {
                        btnRefresh.isEnabled = true
                        btnClear.isEnabled = true
                        tvRefresh.setText(R.string.refresh_now)
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
        val sw: MaterialSwitch = root.findViewById(id)
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
            val sw: MaterialSwitch = v.findViewById(NEWS_SWITCH_IDS[i])
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
        // Allow asks for the permission where the OS still offers the prompt;
        // below Android 13, or once the prompt is gone, it opens system settings.
        v.findViewById<View>(R.id.btnNotifAllow).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33 && !promptBlocked()) host().ensureNotificationPermission()
            else openNotificationSettings.onClick(it)
        }
        updateNotifBlockedBanner()
    }

    /** Shows OFF when the OS permission is missing, regardless of the stored pref. */
    private fun bindNotifySwitch(root: View, id: Int, prefKey: String) {
        val sw: MaterialSwitch = root.findViewById(id)
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
        v.findViewById<View>(R.id.btnNotifAllow)
            .visibility = if (allowed) View.GONE else View.VISIBLE
        v.findViewById<TextView>(R.id.tvNotifStatusTitle).setText(
            if (allowed) R.string.notif_status_on else R.string.notif_blocked_title)
        v.findViewById<TextView>(R.id.tvNotifStatus).text =
            getString(if (allowed) R.string.notifications_enabled_summary
            else R.string.notifications_blocked_summary)
    }

    /** True once the user has denied the prompt for good: the OS no longer
     *  shows it, so Allow must send them to system settings instead. */
    private fun promptBlocked(): Boolean =
        SettingsManager.getBool(requireContext(), KEY_NOTIF_ASKED, false) &&
            !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)

    private fun notificationsAllowed(): Boolean {
        return Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    /** Called by MainActivity when the POST_NOTIFICATIONS request is denied. */
    fun onNotificationPermissionDenied() {
        val v = view ?: return
        SettingsManager.setBool(requireContext(), KEY_NOTIF_ASKED, true)
        // setChecked fires the listeners, which also revert the stored prefs
        v.findViewById<MaterialSwitch>(R.id.switchNotifyMoves).isChecked = false
        v.findViewById<MaterialSwitch>(R.id.switchNotifyReleases).isChecked = false
        v.findViewById<MaterialSwitch>(R.id.switchDailyBrief).isChecked = false
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
    private fun makeRowTappable(sw: MaterialSwitch) {
        val row = sw.parent as View
        row.setOnClickListener {
            if (sw.isEnabled) sw.toggle()
        }
    }

    /** Wires a switch to a boolean pref; runs onChange (if any) after every flip. */
    private fun bindSwitch(root: View, id: Int, prefKey: String, def: Boolean, onChange: (() -> Unit)?) {
        val sw: MaterialSwitch = root.findViewById(id)
        sw.isChecked = SettingsManager.getBool(requireContext(), prefKey, def)
        makeRowTappable(sw)
        sw.setOnCheckedChangeListener { _, checked ->
            SettingsManager.setBool(requireContext(), prefKey, checked)
            onChange?.invoke()
        }
    }

    // -- Section catalog ------------------------------------------------------------

    private enum class Group { PREFERENCES, DATA, SUPPORT }

    /** One Settings row (R3, KTD4). A placeholder row shows but does not open. */
    private data class SectionMeta(
        val id: String, val titleRes: Int, val iconRes: Int, val group: Group,
        val placeholder: Boolean = false,
    )

    companion object {
        /** Fragment-manager tag, shared by MainActivity's open/close/back logic. */
        const val TAG = "SettingsFragment"

        /** Settings parity U7: the editor page, also opened by the sheet's gear. */
        const val SECTION_SHORTCUTS = "shortcuts"

        // Settings parity U3 (R3); Shortcuts joined Preferences in U7.
        private val SECTIONS = listOf(
            SectionMeta("appearance", R.string.section_appearance, R.drawable.ic_settings_appearance, Group.PREFERENCES),
            SectionMeta(SECTION_SHORTCUTS, R.string.section_shortcuts, R.drawable.ic_settings_shortcuts, Group.PREFERENCES),
            SectionMeta("notifications", R.string.section_notifications, R.drawable.ic_settings_notifications, Group.PREFERENCES),
            SectionMeta("ai", R.string.section_ai_analyst, R.drawable.ic_settings_analyst, Group.PREFERENCES),
            SectionMeta("startup", R.string.section_startup_tab, R.drawable.ic_settings_startup, Group.PREFERENCES),
            SectionMeta("data", R.string.section_data, R.drawable.ic_settings_data, Group.DATA),
            SectionMeta("news", R.string.section_news_sources, R.drawable.ic_settings_news, Group.DATA),
            SectionMeta("charts", R.string.section_charts, R.drawable.ic_settings_charts, Group.DATA),
            SectionMeta("feedback", R.string.section_feedback, R.drawable.ic_settings_feedback, Group.SUPPORT, placeholder = true),
            SectionMeta("sources", R.string.section_sources, R.drawable.ic_settings_sources, Group.SUPPORT),
            SectionMeta("privacy", R.string.section_privacy, R.drawable.ic_settings_privacy, Group.SUPPORT, placeholder = true),
            SectionMeta("about", R.string.section_about, R.drawable.ic_settings_about, Group.SUPPORT),
        )

        private const val STATE_SECTION = "settings_section"
        private const val ARG_SECTION = "settings_open_section"

        /** Settings parity U6: Settings opened on one page, e.g. "shortcuts"
         *  from the Shortcuts sheet's gear. An unknown id lands on the root. */
        @JvmStatic
        fun newInstance(section: String?): SettingsFragment = SettingsFragment().apply {
            if (section != null) arguments = Bundle().apply { putString(ARG_SECTION, section) }
        }
        /** Set after the first denied prompt, to tell "never asked" from "blocked". */
        private const val KEY_NOTIF_ASKED = "notif_prompt_denied_once"
        private const val SWATCHES_PER_ROW = 5
        /** Long enough to see the Example preview before the recreate. */
        private const val ACCENT_APPLY_DELAY_MS = 250L

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
