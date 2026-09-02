package com.economic.dashboard.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast

import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.fragment.app.Fragment
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.lifecycle.ViewModelProvider

import com.economic.dashboard.R
import com.economic.dashboard.analyst.AskAnalyst
import com.economic.dashboard.cache.CacheManager
import com.economic.dashboard.database.YieldDatabase
import com.economic.dashboard.databinding.ActivityMainBinding
import com.economic.dashboard.models.ChatMessage
import com.economic.dashboard.models.ChatMessageEntity
import com.economic.dashboard.models.EconomicDataPoint
import com.economic.dashboard.news.NewsFragment
import com.economic.dashboard.news.NewsRepository
import com.economic.dashboard.ui.fragments.DashboardFragment
import com.economic.dashboard.ui.fragments.EconomyFragment
import com.economic.dashboard.ui.fragments.MarketsFragment
import com.economic.dashboard.ui.views.PressFeedback
import com.economic.dashboard.utils.AppExecutors
import com.economic.dashboard.utils.EdgeToEdgeInsets
import com.economic.dashboard.utils.HapticUtil
import com.economic.dashboard.utils.SeriesMath
import com.economic.dashboard.utils.SettingsManager
import com.economic.dashboard.utils.markDestructive
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.economic.dashboard.workers.DailyBriefWorker

import com.google.android.material.snackbar.Snackbar

import org.json.JSONArray
import org.json.JSONObject

import java.util.Locale
import java.util.concurrent.TimeUnit

import okhttp3.OkHttpClient

/**
 * Host activity. Referenced by `android:name=".ui.MainActivity"` in
 * AndroidManifest.xml, so the package and class name must not change.
 *
 * Note on accessor shape: `getChatAdapter()`, `getViewModel()`, and friends stay
 * FUNCTIONS rather than becoming properties. Every Kotlin caller reaches them
 * through `host().getViewModel()`-style calls with parens (16 sites across the
 * analyst sheet and the fragments), and Kotlin's synthetic-property sugar does
 * not apply to a Kotlin-declared `getX()` function. Same rule as
 * EconomicViewModel in 11b — the call sites decide.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var viewModel: EconomicViewModel

    /** Title of the visible tab ("Overview", "Markets", ...) — read by the AI
        Analyst so the model knows what screen the user is looking at. */
    private var currentScreenLabel = "Overview"

    fun getCurrentScreenLabel(): String = currentScreenLabel

    private lateinit var binding: ActivityMainBinding

    /** True only on a fresh launch (not on theme-change/rotation recreates) —
        gates "open to default tab" and "refresh cache on open". */
    private var freshLaunch = false

    // These were package-private in the Java. Nothing outside the class reads
    // them directly (the tests do not touch MainActivity), so they are private
    // here and reached through the accessors below.
    private lateinit var chatAdapter: ChatAdapter
    private val conversationHistory = JSONArray()
    private var lastUserQuery = ""
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        // Per-call ceiling on one analyst round. The 60s read timeout only
        // bounds a single silent gap — a stream that trickles keep-alives can
        // otherwise hold a turn open forever, and the non-streamed fallback
        // path buffers the whole body under no total bound at all.
        .callTimeout(180, TimeUnit.SECONDS)
        .build()

    fun getChatAdapter(): ChatAdapter = chatAdapter
    fun getConversationHistory(): JSONArray = conversationHistory
    fun getHttpClient(): OkHttpClient = httpClient
    fun getLastUserQuery(): String = lastUserQuery
    fun setLastUserQuery(q: String) { lastUserQuery = q }
    fun getViewModel(): EconomicViewModel = viewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        freshLaunch = (savedInstanceState == null)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Edge-to-edge: draw behind system bars, then pad the header and
        // bottom nav so content clears them (required on Android 15+).
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars: Insets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.headerContainer.setPadding(0, bars.top, 0, 0)
            binding.bottomNavWrapper.setPadding(0, 0, 0, bars.bottom)
            // IOS-04: every screen pads its own topmost/bottommost view off
            // this (see EdgeToEdgeInsets) so content clears both translucent
            // bars and can still scroll underneath them.
            EdgeToEdgeInsets.update(bars.top, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        // IOS-04 (2026-07-27 nav fix): publish the bars' REAL measured heights
        // instead of leaving screens to guess with the header_chrome_height
        // dimen. Both views already carry their own system-bar padding by the
        // time they lay out, so the measured height is the full clearance a
        // screen needs. OnLayoutChangeListener (not a one-shot post()) so this
        // stays correct across rotation, font-scale changes, and the header
        // ever growing a second line.
        // The setters re-pad every registered screen, which calls setPadding →
        // requestLayout; posting keeps that out of the layout pass that just
        // fired this callback (both setters no-op when the value is unchanged,
        // so the posts don't loop).
        binding.headerContainer.addOnLayoutChangeListener { barView, _, nt, _, nb, _, _, _, _ ->
            barView.post { EdgeToEdgeInsets.setHeaderHeight(nb - nt) }
        }
        binding.bottomNavWrapper.addOnLayoutChangeListener { barView, _, nt, _, nb, _, _, _, _ ->
            barView.post { EdgeToEdgeInsets.setNavHeight(nb - nt) }
        }

        // IOS-04 — API 31+ frosted glass was evaluated and deliberately NOT
        // added here: View#setRenderEffect blurs that view's OWN rendered
        // subtree, not the sibling content already drawn behind it, and both
        // header_container and bottomNavWrapper hold real foreground content
        // (the masthead title, the settings icon, nav icons/labels). Applying
        // a blur effect to either would blur that content into mush, not
        // "blur the KPI cards scrolling underneath" — Android has no per-view
        // backdrop-sampling API to do the latter in a single opaque window
        // (Window#setBackgroundBlurRadius blurs behind the *window*, e.g. the
        // home screen, which doesn't apply here). The translucent fill
        // (header_navy_translucent / bg_bottom_nav_border, 85% then 95%) was
        // the "material" signal on every API level instead — until TICKET-56
        // (2026-09-01) made both fills opaque because content still read
        // through; the depth cue is now the navScrim fade above the bar.

        supportActionBar?.hide()

        viewModel = ViewModelProvider(this).get(EconomicViewModel::class.java)

        chatAdapter = ChatAdapter(
            { retryLastQuery() },
            object : ChatAdapter.OnAnalystActionListener {
                override fun onMetricTapped(tabKey: String) { navigateToTab(tabKey) }
                override fun onAskAi(query: String) {
                    AskAnalyst.openWithQuery(this@MainActivity, query)
                }
                override fun onAskAiMetric(metricName: String) {
                    // TICKET-5 (AI Law 12): selecting a metric name behaves
                    // like a card long-press — inject the snapshot value +
                    // screen context and use the concise gesture tier.
                    val snapshot = describeMetricSnapshot(metricName)
                    val query = "Tell me more about $metricName right now."
                    val context = if (snapshot.isEmpty()) "" else
                        "\nSCREEN CONTEXT: The user selected \"" + metricName +
                            "\" inside an analyst reply. Current dashboard reading: " +
                            snapshot + ". Use these values directly and do not " +
                            "fetch them again.\n"
                    AskAnalyst.openWithQuery(this@MainActivity, query, context, true)
                }
            })
        loadPersistedChat()

        setupBottomNav()

        // TICKET-41: bottom-nav tab switches don't push to the back stack, so
        // without this, back from Markets/Economy/News exits the app instead
        // of returning to Overview first (the standard bottom-nav contract).
        // Only fires when no dialog/bottom sheet has focus — those own the
        // back gesture on their own Window and never reach this dispatcher.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isSettingsShowing()) {
                    handleSettingsBackOrClose()
                } else if (binding.bottomNav.selectedItemId != R.id.nav_overview) {
                    binding.bottomNav.selectedItemId = R.id.nav_overview
                } else {
                    // Already on Overview — let this press fall through to the
                    // system default (finish the Activity), unchanged from today.
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        PressFeedback.attach(binding.fabAiAnalyst) // same bouncy spring feedback as the overview KPI badges
        binding.fabAiAnalyst.setOnClickListener {
            markAnalystSeen(); clearAnalystBadge() // TICKET-26: opening Analyst clears its badge
            if (supportFragmentManager.findFragmentByTag(AiAnalystBottomSheet.TAG) == null) {
                HapticUtil.confirm(binding.fabAiAnalyst) // firmer pulse — primary action, not a tab switch
                AiAnalystBottomSheet().show(supportFragmentManager, AiAnalystBottomSheet.TAG)
            }
        }
        // Don't clobber the back-chevron handler applySettingsChrome() just
        // bound when setupBottomNav() kept a restored Settings — rebinding to
        // openSettings() here would leave the chevron dead (openSettings
        // early-returns while Settings is showing).
        if (!isSettingsShowing()) binding.btnSettings.setOnClickListener { openSettings() }
        // TICKET-58: header pencil opens the Overview's card editor.
        binding.btnEditCards.setOnClickListener {
            (supportFragmentManager.findFragmentByTag("overview") as? DashboardFragment)
                ?.takeIf { it.isAdded }?.openEditTargetSheet()
        }
        updateEditCardsButton()
        observeViewModel()
        // Skip on a theme-toggle/rotation recreate: the ViewModel survives
        // those (ViewModelStore), so its LiveData already holds the current
        // values and every fragment gets them back the moment it re-observes
        // — re-fetching here just re-primes from disk and re-posts the same
        // data, forcing every chart to rebuild for nothing during the
        // recreate itself. That redundant burst was the main cause of the
        // multi-second freeze a theme switch used to cause. A genuinely
        // fresh ViewModel (cold launch, or the process was killed and
        // restored) always has hasFetchedOnce() == false, so this still
        // fetches exactly when it needs to.
        if (!viewModel.hasFetchedOnce()) {
            viewModel.fetchAllData()
        }
        if (freshLaunch && SettingsManager.getBool(this, SettingsManager.KEY_REFRESH_ON_OPEN, false)) {
            // Callback intentionally empty: the refresh itself is the point, and
            // the header has nothing to redraw (see setupBottomNav()).
            CacheManager.forceRefreshAll(this) { }
        }
        // TICKET-17: recompute the News unread badge whenever a fetch lands.
        NewsRepository.getInstance().setOnFetchCompleteListener {
            runOnUiThread { refreshNewsBadge() }
        }
        NewsRepository.getInstance().fetchAllFeedsIfStale()
        binding.bottomNav.post { refreshNewsBadge() } // in case the cache is already warm
        binding.bottomNav.post { refreshAnalystBadge() } // TICKET-26

        // Daily AI brief scheduling (no-op unless the setting is on; KEEP policy)
        DailyBriefWorker.scheduleDaily(this)

        // If the app was launched via the share sheet, route into the AI Analyst
        handleShareIntent(intent)
    }

    // ── Settings (design 1b: full-screen page, opens like any other tab) ───────

    private fun isSettingsShowing(): Boolean =
        supportFragmentManager.findFragmentByTag(SettingsFragment.TAG)?.isAdded == true

    /** Gear icon in the header — opens Settings into fragmentContainer, same
     *  mechanism as switching to Markets/Economy/News. Never touches
     *  bottomNav.selectedItemId, so closeSettings() can just re-load whatever
     *  tab is still selected there to restore the screen underneath. */
    private fun openSettings() {
        if (isSettingsShowing()) return
        loadFragment(SettingsFragment(), SettingsFragment.TAG)
        applySettingsChrome()
    }

    /** TICKET-58: the header pencil belongs to Overview only — hidden on every
     *  other tab and while Settings covers the tab. */
    private fun updateEditCardsButton() {
        val onOverview = !isSettingsShowing() &&
            binding.bottomNav.selectedItemId == R.id.nav_overview
        binding.btnEditCards.visibility = if (onOverview) View.VISIBLE else View.GONE
    }

    /** Header/nav chrome for the full-screen Settings page — split out of
     *  [openSettings] because an Activity recreate (theme switch, rotation)
     *  restores the fragment via the FragmentManager without running
     *  openSettings(), so [setupBottomNav] re-applies the chrome alone. */
    private fun applySettingsChrome() {
        resetHeaderCompactAlwaysVisible()
        binding.tvHeaderCompactTitle.text = getString(R.string.settings)
        currentScreenLabel = getString(R.string.settings)
        binding.btnSettings.setImageResource(R.drawable.ic_chevron_left)
        binding.btnSettings.contentDescription = getString(R.string.settings_back_to_list)
        binding.btnSettings.setOnClickListener { handleSettingsBackOrClose() }
        // Full-screen page, no bottom chrome — the FAB floats on top of the
        // nav bar, so it goes with it.
        binding.bottomNavWrapper.visibility = View.GONE
        binding.navScrim.visibility = View.GONE
        binding.fabAiAnalyst.visibility = View.GONE
        binding.btnEditCards.visibility = View.GONE
    }

    /** Restores whichever tab bottomNav still has selected and puts the gear
     *  icon back to normal. */
    private fun closeSettings() {
        val id = binding.bottomNav.selectedItemId
        loadFragmentForNavId(id)
        val (_, title) = fragmentIndexAndTitle(id)
        binding.tvHeaderCompactTitle.text = title
        currentScreenLabel = title
        resetSettingsGearIcon()
        binding.bottomNavWrapper.visibility = View.VISIBLE
        binding.navScrim.visibility = View.VISIBLE
        binding.fabAiAnalyst.visibility = View.VISIBLE
        updateEditCardsButton()
        // If this recreate started inside Settings, the nav was GONE during
        // setupBottomNav() and the indicator was never sized (width was 0).
        binding.bottomNav.doOnLayout { sizeNavIndicator() }
    }

    /** Gear icon back to its normal look + action. Idempotent — also called
     *  from the bottom-nav listener since tapping another tab while Settings
     *  is open should always leave Settings behind. */
    private fun resetSettingsGearIcon() {
        binding.btnSettings.setImageResource(R.drawable.ic_settings)
        binding.btnSettings.contentDescription = getString(R.string.settings)
        binding.btnSettings.setOnClickListener { openSettings() }
    }

    /** Shared by the back gesture and by tapping the header's back chevron
     *  while Settings is open: detail → list first, list → close Settings. */
    private fun handleSettingsBackOrClose() {
        val f = supportFragmentManager.findFragmentByTag(SettingsFragment.TAG) as? SettingsFragment
        if (f != null && f.handleBack()) return
        closeSettings()
    }

    /** Maps a bottom-nav item id to the fragment shown underneath the header —
     *  shared by the nav listener (on tap) and closeSettings() (on restore). */
    private fun loadFragmentForNavId(id: Int) {
        if (id == R.id.nav_markets) loadFragment(MarketsFragment(), "markets")
        else if (id == R.id.nav_economy) loadFragment(EconomyFragment(), "economy")
        else if (id == R.id.navigation_news) loadFragment(NewsFragment(), "news")
        else loadFragment(DashboardFragment(), "overview")
    }

    /** Active-indicator slot index + header title for a bottom-nav item id. */
    private fun fragmentIndexAndTitle(id: Int): Pair<Int, String> {
        return if (id == R.id.nav_markets) 1 to "Markets"
        else if (id == R.id.nav_economy) 3 to "Economy"
        else if (id == R.id.navigation_news) 4 to "News"
        else 0 to "Overview"
    }

    /** Asks for the Android 13+ notification permission if not yet granted. */
    fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this,
                android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this,
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }

    /** If the user denies the notification prompt, turn the toggles back off. */
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 1001) return
        val granted = grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            SettingsManager.setBool(this, SettingsManager.KEY_NOTIFY_BIG_MOVES, false)
            SettingsManager.setBool(this, SettingsManager.KEY_NOTIFY_RELEASES, false)
            SettingsManager.setBool(this, SettingsManager.KEY_DAILY_BRIEF, false)
            Toast.makeText(this, R.string.notifications_denied, Toast.LENGTH_LONG).show()
            val f = supportFragmentManager.findFragmentByTag(SettingsFragment.TAG)
            if (f is SettingsFragment) f.onNotificationPermissionDenied()
        }
    }

    // ── Chat persistence ─────────────────────────────────────────────────────

    /** Restores the saved conversation; falls back to the welcome message. */
    private fun loadPersistedChat() {
        AppExecutors.diskIO().execute {
            val saved = YieldDatabase.getInstance(this).chatMessageDao().getAll()
            val restored = ArrayList<ChatMessage>()
            for (e in saved) restored.add(e.toChatMessage())
            runOnUiThread {
                if (restored.isEmpty()) {
                    chatAdapter.addMessage(ChatMessage(WELCOME_TEXT, false))
                } else {
                    chatAdapter.setMessages(restored)
                    rebuildConversationHistory(restored)
                }
            }
        }
    }

    /**
     * Rebuilds the Claude-facing history from restored messages.
     * Only complete user→assistant pairs are kept — the API requires
     * strictly alternating roles, and a failed request can leave an
     * unanswered user message in the persisted log.
     */
    private fun rebuildConversationHistory(restored: List<ChatMessage>) {
        try {
            while (conversationHistory.length() > 0) conversationHistory.remove(0)
            var i = 0
            while (i < restored.size - 1) {
                val q = restored[i]
                val a = restored[i + 1]
                if (q.isUser && !a.isUser) {
                    val user = JSONObject()
                    user.put("role", "user"); user.put("content", q.text)
                    conversationHistory.put(user)
                    val assistant = JSONObject()
                    assistant.put("role", "assistant"); assistant.put("content", a.text)
                    conversationHistory.put(assistant)
                    i++
                }
                i++
            }
        } catch (ignored: Exception) {}
    }

    /** Saves a message so the conversation survives app restarts. */
    fun persistChatMessage(m: ChatMessage?) {
        if (m == null || m.isTyping || m.isError) return
        AppExecutors.diskIO().execute {
            val db = YieldDatabase.getInstance(this)
            db.chatMessageDao().insert(ChatMessageEntity.from(m))
            db.chatMessageDao().trim(CHAT_PERSIST_LIMIT)
        }
    }

    /** Wipes the stored conversation and resets the visible chat. */
    fun clearChatHistory() {
        AppExecutors.diskIO().execute {
            YieldDatabase.getInstance(this).chatMessageDao().clearAll()
        }
        while (conversationHistory.length() > 0) conversationHistory.remove(0)
        lastUserQuery = ""
        chatAdapter.setMessages(ArrayList())
        chatAdapter.addMessage(ChatMessage(WELCOME_TEXT, false))
    }

    // ── Share target (text / images shared from other apps) ──────────────────

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    /** Routes ACTION_SEND text or images into the AI Analyst for analysis. */
    private fun handleShareIntent(intent: Intent?) {
        if (intent == null || Intent.ACTION_SEND != intent.action) return
        val type = intent.type ?: return
        intent.action = ""  // consume so rotation doesn't re-trigger

        if (type.startsWith("image/")) {
            val uri: Uri = intent.getParcelableExtra(Intent.EXTRA_STREAM) ?: return
            if (supportFragmentManager.findFragmentByTag(AiAnalystBottomSheet.TAG) == null)
                AiAnalystBottomSheet.newInstanceForImage(uri, null)
                    .show(supportFragmentManager, AiAnalystBottomSheet.TAG)
        } else if ("text/plain" == type) {
            val shared = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (shared == null || shared.trim().isEmpty()) return
            AskAnalyst.openWithQuery(this,
                "Analyze this in the context of the current U.S. economic data. If it's a " +
                    "link or headline, explain what it means for the economy:\n\n" + shared.trim())
        }
    }

    /**
     * TICKET-5 (AI Law 12): current dashboard reading for a metric name the
     * user selected inside an analyst reply, so selection "Ask AI" can inject
     * context like a card long-press does. Returns "" when the series hasn't
     * loaded yet — callers fall back to a plain query.
     */
    private fun describeMetricSnapshot(metricName: String): String {
        try {
            val key = metricName.lowercase(Locale.US)
                .replace(Regex("[\\s.,:;!?]+$"), "").trim()
            when (key) {
                "cpi", "inflation" -> {
                    val l = viewModel.getCpiData().value
                    val rows = if (l != null)
                        EconomicViewModel.filterBySeries(l, "CPI-U All Items") else null
                    if (rows != null && rows.size >= 13) {
                        // SeriesMath skips a point when its 12-months-ago base is ~0, so
                        // only report when the derived series still reaches the latest row —
                        // preserving the old ""-on-zero-base behavior.
                        val latest = SeriesMath.yoyPercentSeries(rows).lastOrNull()
                        if (latest != null && latest.date == rows[rows.size - 1].date) {
                            return String.format(Locale.US, "CPI inflation %.2f%% YoY (%s)",
                                latest.value, latest.date)
                        }
                    }
                    return ""
                }
                "unemployment", "unemployment rate" -> {
                    val p = EconomicViewModel.getLatest(
                        viewModel.getEmploymentData().value, "Unemployment Rate")
                    return if (p == null) "" else String.format(Locale.US,
                        "unemployment %.1f%% (%s)", p.value, p.date)
                }
                "fed funds rate", "federal funds rate" -> {
                    val p = EconomicViewModel.getLatest(
                        viewModel.getFedFundsData().value, "Federal Funds Effective Rate")
                    return if (p == null) "" else String.format(Locale.US,
                        "fed funds rate %.2f%% (%s)", p.value, p.date)
                }
                "gdp" -> {
                    val l = viewModel.getGdpData().value
                    val rows = if (l != null)
                        EconomicViewModel.filterBySeries(l, "Gross domestic product") else null
                    if (rows == null || rows.isEmpty()) return ""
                    val p = rows[rows.size - 1]
                    return String.format(Locale.US, "GDP growth %.2f%% (latest quarter, %s)",
                        p.value, p.date)
                }
                "mortgage rate" -> {
                    val p = EconomicViewModel.getLatest(
                        viewModel.getMbsMortgageData().value, "30-Yr Mortgage Rate")
                    return if (p == null) "" else String.format(Locale.US,
                        "30-year mortgage rate %.2f%% (%s)", p.value, p.date)
                }
                "housing starts" -> {
                    val p = EconomicViewModel.getLatest(
                        viewModel.getHousingData().value, "Housing Starts")
                    return if (p == null) "" else String.format(Locale.US,
                        "housing starts %.0fK units annualized (%s)", p.value, p.date)
                }
                "s&p 500" -> {
                    val l = viewModel.getSp500Data().value
                    if (l == null || l.isEmpty()) return ""
                    val p = l[l.size - 1]
                    return String.format(Locale.US, "S&P 500 at %.0f (%s)", p.value, p.date)
                }
                "nasdaq" -> {
                    val l = viewModel.getNasdaqData().value
                    if (l == null || l.isEmpty()) return ""
                    val p = l[l.size - 1]
                    return String.format(Locale.US, "Nasdaq at %.0f (%s)", p.value, p.date)
                }
                "vix" -> {
                    val l = viewModel.getVixData().value
                    if (l == null || l.isEmpty()) return ""
                    val p = l[l.size - 1]
                    return String.format(Locale.US, "VIX at %.2f (%s)", p.value, p.date)
                }
                "yield curve", "treasury" -> {
                    val t = viewModel.getTreasuryData().value
                    val tenY = EconomicViewModel.getLatest(t, "10 Year")
                    val threeM = EconomicViewModel.getLatest(t, "3 Month")
                    if (tenY == null || threeM == null) return ""
                    return String.format(Locale.US,
                        "10Y %.2f%%, 3M %.2f%%, 10Y-3M spread %+.2f%%",
                        tenY.value, threeM.value,
                        tenY.value - threeM.value)
                }
                else -> return ""
            }
        } catch (e: Exception) {
            return ""
        }
    }

    /** Metric citation tapped in chat — close the sheet and jump to that tab. */
    fun navigateToTab(tabKey: String) {
        val sheet = supportFragmentManager.findFragmentByTag(AiAnalystBottomSheet.TAG)
        if (sheet is AiAnalystBottomSheet && sheet.isAdded) sheet.dismiss()
        val targetId =
            if ("markets" == tabKey) R.id.nav_markets
            else if ("economy" == tabKey) R.id.nav_economy
            else if ("news" == tabKey) R.id.navigation_news
            else R.id.nav_overview
        if (binding.bottomNav.selectedItemId == targetId) {
            // Already on the target tab, so the item-selected listener won't
            // fire — but Settings may be covering it; close it explicitly or
            // the navigation is silently dropped with Settings left on screen.
            if (isSettingsShowing()) closeSettings()
        } else {
            binding.bottomNav.selectedItemId = targetId
        }
    }

    // ── Header ───────────────────────────────────────────────────────────────

    /**
     * 2026-07-28: every tab shows a static compact title + hairline now (IOS-02's
     * collapsing large title was reverted for Overview — see the dated changes
     * doc). Called on every tab switch so no tab is special-cased.
     */
    private fun resetHeaderCompactAlwaysVisible() {
        binding.tvHeaderCompactTitle.alpha = 1f
        binding.headerSeparator.alpha = 1f
    }

    // ── Navigation ───────────────────────────────────────────────────────────

    /** Sizes the active-tab indicator and parks it under the selected tab,
     *  from the nav's CURRENT width. Re-runnable because that width is 0
     *  until the nav has been laid out visible — a recreate straight into a
     *  restored Settings reaches [setupBottomNav] with the nav still GONE,
     *  so sizing must happen again once the nav shows (see [closeSettings]
     *  and the zero-width branch in the nav listener). No-op while 0. */
    private fun sizeNavIndicator() {
        val itemWidth = binding.bottomNav.width / 5
        if (itemWidth == 0) return
        binding.navActiveIndicator.layoutParams.width = (itemWidth * 0.6f).toInt()
        binding.navActiveIndicator.requestLayout()
        val (index, _) = fragmentIndexAndTitle(binding.bottomNav.selectedItemId)
        binding.navActiveIndicator.translationX =
            (index * itemWidth + (itemWidth * 0.2f).toInt()).toFloat()
    }

    private fun setupBottomNav() {
        // On an Activity recreate with Settings open, the FragmentManager has
        // already restored SettingsFragment into fragmentContainer — but the
        // "overview" tag is absent (Settings replaced it), so loadFragment's
        // early-return wouldn't trigger and this unconditional load would
        // clobber the restored Settings with a fresh Dashboard. Keep Settings
        // and re-apply its chrome instead; closeSettings() restores the tab.
        if (isSettingsShowing()) {
            applySettingsChrome()
        } else if (freshLaunch) {
            loadFragment(DashboardFragment(), "overview")
        } else {
            // Recreate (rotation, theme switch, process death): BottomNavigationView
            // restores its own selectedItemId from the view hierarchy's state, which
            // lands AFTER onCreate — reading it here would still give the menu
            // default, and the unconditional Dashboard load above clobbered whatever
            // tab the user was on while the nav kept highlighting it. post{} runs
            // after the whole launch transaction (onCreate → onStart →
            // onRestoreInstanceState → onResume), the same ordering sizeNavIndicator()
            // below already relies on to park the indicator under the restored tab.
            // loadFragment() early-returns on a matching tag, so the FragmentManager's
            // own restored fragment is kept rather than replaced with a blank one.
            binding.bottomNav.post {
                loadFragmentForNavId(binding.bottomNav.selectedItemId)
                updateEditCardsButton()
            }
        }

        binding.bottomNav.post {
            sizeNavIndicator()

            binding.bottomNav.setOnItemSelectedListener { item ->
                val id = item.itemId
                HapticUtil.selection(binding.bottomNav) // IOS-09

                // Center slot (nav_ai_placeholder) is disabled — the floating
                // fabAiAnalyst button handles the AI Analyst instead.
                loadFragmentForNavId(id)
                // A tab selected while Settings was open must fully restore the
                // normal chrome, not just the gear icon — programmatic switches
                // (share intent, analyst metric tap) left the nav bar and FAB
                // GONE with no way back. Unconditional: any tab tap shows them.
                resetSettingsGearIcon()
                binding.bottomNavWrapper.visibility = View.VISIBLE
                binding.navScrim.visibility = View.VISIBLE
                binding.fabAiAnalyst.visibility = View.VISIBLE
                binding.btnEditCards.visibility = if (id == R.id.nav_overview) View.VISIBLE else View.GONE
                if (id == R.id.navigation_news) {
                    markNewsSeen(); clearNewsBadge() // opening News clears the unread badge
                }
                val (index, title) = fragmentIndexAndTitle(id)
                resetHeaderCompactAlwaysVisible()
                binding.tvHeaderCompactTitle.text = title
                currentScreenLabel = title

                // Width is read at tap time, not captured at setup: when
                // setupBottomNav() ran with the nav GONE (recreate straight
                // into a restored Settings) the captured width was 0, which
                // would pin the indicator at width 0 / x=0 forever.
                val itemWidth = binding.bottomNav.width / 5
                if (itemWidth == 0) {
                    binding.bottomNav.doOnLayout { sizeNavIndicator() }
                } else {
                    binding.navActiveIndicator.animate()
                        .translationX((index * itemWidth + (itemWidth * 0.2f).toInt()).toFloat())
                        .setDuration(200)
                        .setInterpolator(FastOutSlowInInterpolator())
                        .start()
                }

                true
            }

            // Open to the user's preferred tab (fresh launches only, so a
            // theme change or rotation doesn't yank the user off their screen)
            if (freshLaunch) {
                freshLaunch = false
                val defTab = SettingsManager.getDefaultTab(this@MainActivity)
                if (defTab == 1) binding.bottomNav.selectedItemId = R.id.nav_markets
                else if (defTab == 2) binding.bottomNav.selectedItemId = R.id.nav_economy
                else if (defTab == 3) binding.bottomNav.selectedItemId = R.id.navigation_news
            }
        }
    }

    // ── News unread badge (TICKET-17) ─────────────────────────────────────────

    /**
     * Shows a small gold count badge on the News tab for items published since
     * the user last viewed News. The first successful fetch adopts the current
     * newest item as the baseline, so we never badge the whole backlog — only
     * genuinely new content nudges a return visit.
     */
    private fun refreshNewsBadge() {
        if (!::binding.isInitialized) return
        val repo = NewsRepository.getInstance()
        val newest = repo.getNewestPubDate()
        if (newest == 0L) return                        // cache still cold
        val lastSeen = SettingsManager.getNewsLastSeen(this)
        if (lastSeen == 0L || "News" == currentScreenLabel) {
            markNewsSeen()                              // baseline / already reading News
            clearNewsBadge()
            return
        }
        val unread = repo.getUnreadCount(lastSeen)
        if (unread <= 0) { clearNewsBadge(); return }
        val badge = binding.bottomNav.getOrCreateBadge(R.id.navigation_news)
        // Explicit setters on BadgeDrawable (Material, third-party): Drawable
        // also declares a two-arg setVisible(boolean, boolean), so property
        // syntax here would be needlessly ambiguous (migration lesson 12).
        badge.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_gold))
        badge.setBadgeTextColor(ContextCompat.getColor(this, R.color.header_navy))
        badge.setMaxCharacterCount(2)                   // "9+" style past 99
        badge.setNumber(unread)
        badge.setVisible(true)
    }

    private fun markNewsSeen() {
        SettingsManager.setNewsLastSeen(this, NewsRepository.getInstance().getNewestPubDate())
    }

    private fun clearNewsBadge() {
        if (::binding.isInitialized) binding.bottomNav.removeBadge(R.id.navigation_news)
    }

    // ── Analyst unread badge (TICKET-26) ──────────────────────────────────────

    /**
     * Mirrors the News badge on the center AI-Analyst slot. Badges when the
     * daily-brief worker has produced an insight newer than the user's last
     * Analyst visit; clears when the Analyst opens. On first run it baselines to
     * "now" so a pre-existing brief never shows as a backlog badge — identical
     * spirit to the News baseline.
     */
    private fun refreshAnalystBadge() {
        if (!::binding.isInitialized) return
        val lastSeen = SettingsManager.getAnalystLastSeen(this)
        if (lastSeen == 0L) {                       // first run — baseline, no backlog badge
            SettingsManager.setAnalystLastSeen(this, System.currentTimeMillis())
            clearAnalystBadge()
            return
        }
        val newest = SettingsManager.getAnalystLastInsight(this)
        if (newest > lastSeen) {
            val badge = binding.bottomNav.getOrCreateBadge(R.id.nav_ai_placeholder)
            badge.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_gold))
            badge.setVisible(true)                  // dot — a single "new insight" nudge
        } else {
            clearAnalystBadge()
        }
    }

    private fun markAnalystSeen() {
        SettingsManager.setAnalystLastSeen(this, System.currentTimeMillis())
    }

    private fun clearAnalystBadge() {
        if (::binding.isInitialized) binding.bottomNav.removeBadge(R.id.nav_ai_placeholder)
    }

    private fun loadFragment(fragment: Fragment, tag: String) {
        val existing = supportFragmentManager.findFragmentByTag(tag)
        if (existing != null && existing.isAdded) return
        supportFragmentManager.beginTransaction().replace(R.id.fragmentContainer, fragment, tag).commit()
    }

    /** Tap-to-retry on an error bubble — resends via the open analyst sheet. */
    private fun retryLastQuery() {
        val f = supportFragmentManager.findFragmentByTag(AiAnalystBottomSheet.TAG)
        if (f is AiAnalystBottomSheet && f.isAdded) f.resendLastQuery()
    }

    private fun observeViewModel() {
        viewModel.getIsLoading().observe(this) { loading ->
            binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        }
        viewModel.getErrorMsg().observe(this) { error ->
            if (error != null && !error.isEmpty()) {
                android.util.Log.w("MainActivity", "Data fetch error: $error")
                HapticUtil.reject(binding.root) // IOS-09
            }
        }
        // Per-series errors above are logged only — they arrive up to 13 at a
        // time and are far too noisy to show. This is the aggregated one-shot
        // summary instead: exactly one message per refresh that didn't fully
        // land, on whichever tab the user is looking at.
        viewModel.getRefreshFailure().observe(this) { message ->
            if (message != null && message.isNotEmpty()) {
                Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
                viewModel.consumeRefreshFailure()
            }
        }
    }

    override fun onResume() {
        super.onResume(); refreshNewsBadge(); refreshAnalystBadge()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu); return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_refresh) {
            // Refresh live dashboard data AND force-refresh the local cache.
            // TICKET-15: report the outcome, not just the start, so the user
            // always knows whether the refresh actually landed.
            Toast.makeText(this, R.string.refreshing_data, Toast.LENGTH_SHORT).show()
            viewModel.fetchAllData()
            CacheManager.forceRefreshAll(this) { success ->
                runOnUiThread {
                    Toast.makeText(this,
                        if (success) R.string.refresh_done else R.string.refresh_failed,
                        Toast.LENGTH_SHORT).show()
                }
            }
            return true
        }
        if (item.itemId == R.id.action_clear_cache) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.clear_cache)
                .setMessage(R.string.clear_cache_confirm)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    CacheManager.clearAllCaches(this) {
                        runOnUiThread {
                            Toast.makeText(this,
                                R.string.cache_cleared,
                                Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
                .markDestructive()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    companion object {
        private const val WELCOME_TEXT =
            "Hello! I am your Econ Analyst, powered by Claude. " +
                "I can see all of the dashboard's live data — ask me anything about it. " +
                "Tip: long-press any card on the Overview screen to ask about that metric."

        /** Newest rows kept in the persisted chat table. */
        private const val CHAT_PERSIST_LIMIT = 200
    }
}
