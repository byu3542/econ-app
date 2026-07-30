package com.economic.dashboard.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.LinearLayout
import android.widget.TextView

import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

import com.facebook.shimmer.ShimmerFrameLayout

import com.economic.dashboard.R
import com.economic.dashboard.analyst.AskAnalyst
import com.economic.dashboard.databinding.FragmentDashboardBinding
import com.economic.dashboard.models.EconomicDataPoint
import com.economic.dashboard.ui.BenchmarkStyle
import com.economic.dashboard.ui.Benchmarks
import com.economic.dashboard.ui.EconomicViewModel
import com.economic.dashboard.ui.MainActivity
import com.economic.dashboard.ui.MetricBottomSheet
import com.economic.dashboard.ui.onboarding.WatchlistSetupFragment
import com.economic.dashboard.ui.views.PressFeedback
import com.economic.dashboard.utils.DeltaFormatter
import com.economic.dashboard.utils.EdgeToEdgeInsets
import com.economic.dashboard.utils.HapticUtil
import com.economic.dashboard.utils.MotionUtil
import com.economic.dashboard.utils.NumberFormatUtil
import com.economic.dashboard.utils.SettingsManager
import com.economic.dashboard.utils.ValueAnimatorUtil
import com.economic.dashboard.views.SparklineView

import java.util.Calendar
import java.util.Locale

class DashboardFragment : Fragment() {

    private lateinit var viewModel: EconomicViewModel
    private var binding: FragmentDashboardBinding? = null

    // KPI cards come from ViewBinding <include> roots, so they are non-null.
    private lateinit var cardUnemployment: View
    private lateinit var cardCpiYoy: View
    private lateinit var cardGdp: View
    private lateinit var cardSpread: View
    private lateinit var cardMortgage: View
    private lateinit var cardVix: View

    // Fed funds hero views — bound directly so they never fail silently.
    // These come from findViewById and the Java null-checks every use, so they
    // stay nullable rather than lateinit (nullability reflects reality).
    private var tvFedFundsHeroValue: TextView? = null
    private var changeFedFunds: TextView? = null
    private var tvFedFundsHeroCycle: TextView? = null
    private var tvFedFundsHeroDate: TextView? = null
    private var tvFedFundsRetry: TextView? = null
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private var skeletonShimmer: ShimmerFrameLayout? = null
    private var contentContainer: View? = null
    private var tvCacheAsOf: TextView? = null
    private var skeletonHidden = false
    private var wasLoading = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val b = FragmentDashboardBinding.inflate(inflater, container, false)
        binding = b
        // IOS-04: the scroll view (SwipeRefreshLayout's direct child) clears
        // the translucent header/bottom-nav and can scroll underneath them.
        // Overview is the ONLY screen that takes both insets — it owns the full
        // height behind both bars, with no tab strip between it and the header.
        EdgeToEdgeInsets.applyBothInsets(b.dashboardScroll)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(requireActivity()).get(EconomicViewModel::class.java)
        bindViews(view)
        observeData()

        // Cache-first paint: if we have a cached snapshot, reveal content
        // immediately and never show the skeleton — only a first-ever launch
        // (empty cache) waits on the shimmer.
        if (viewModel.hasCache()) {
            hideSkeleton()
        }
        viewModel.getCacheAsOf().observe(viewLifecycleOwner) { label ->
            val tv = tvCacheAsOf
            if (tv != null) {
                if (label == null || label.isEmpty()) {
                    tv.visibility = View.GONE
                } else {
                    tv.text = label
                    tv.visibility = View.VISIBLE
                }
            }
        }

        // TICKET-22: "Since you last opened" strip — warm launches only,
        // dismissible for the session (flag lives on the VM).
        val stripSince: View? = view.findViewById(R.id.stripSinceLastOpen)
        val tvSince: TextView? = view.findViewById(R.id.tvSinceLastOpen)
        val btnSinceClose: View? = view.findViewById(R.id.btnSinceLastOpenClose)
        btnSinceClose?.setOnClickListener { viewModel.dismissSinceLastOpen() }
        viewModel.getSinceLastOpen().observe(viewLifecycleOwner) { text ->
            if (stripSince != null && tvSince != null) {
                if (text == null || text.isEmpty() || viewModel.isSinceLastOpenDismissed()) {
                    stripSince.visibility = View.GONE
                } else {
                    tvSince.text = text
                    stripSince.visibility = View.VISIBLE
                }
            }
        }

        // Partial-failure: show a per-card retry chip for any series that failed
        // to refresh, without blanking the cards that succeeded.
        viewModel.getFailedSeries().observe(viewLifecycleOwner) { failed ->
            val f: Set<String> = failed ?: emptySet()
            toggleRetry(cardGdp, EconomicViewModel.CACHE_GDP, f)
            toggleRetry(cardCpiYoy, EconomicViewModel.CACHE_CPI, f)
            toggleRetry(cardSpread, EconomicViewModel.CACHE_TREASURY, f)
            toggleRetry(cardUnemployment, EconomicViewModel.CACHE_EMPLOYMENT, f)
            toggleRetry(cardMortgage, EconomicViewModel.CACHE_MBS, f)
            toggleRetry(cardVix, EconomicViewModel.CACHE_VIX, f)
            toggleHeroRetry(f.contains(EconomicViewModel.CACHE_FED_FUNDS))
        }

        // TICKET-25: rank the Overview clusters by the user's watchlist, and
        // show the one-time first-run watchlist setup sheet.
        applyWatchlistOrder(view)
        val ctx = context
        if (ctx != null && !SettingsManager.isOnboardingComplete(ctx)) {
            val sheet = WatchlistSetupFragment()
            // Kotlin lambda: only compiles because WatchlistSetupFragment.Listener
            // was declared `fun interface` in batch 10a, ahead of this conversion.
            sheet.setListener { getView()?.let { applyWatchlistOrder(it) } }
            sheet.show(parentFragmentManager, WatchlistSetupFragment.TAG)
        }

        swipeRefresh.setOnRefreshListener { viewModel.fetchAllData() }
        viewModel.getIsLoading().observe(viewLifecycleOwner) { loading ->
            // Haptic tick when a refresh completes
            if (wasLoading && loading == false) {
                hideSkeleton()
                HapticUtil.confirm(swipeRefresh) // IOS-09
            }
            wasLoading = loading == true
            swipeRefresh.isRefreshing = loading == true
        }
    }

    private fun bindViews(v: View) {
        val b = binding ?: return
        swipeRefresh = b.swipeRefresh
        skeletonShimmer = v.findViewById(R.id.skeletonShimmer)
        contentContainer = v.findViewById(R.id.contentContainer)
        tvCacheAsOf = v.findViewById(R.id.tvCacheAsOf)

        // KPI badges
        // These are <include layout="@layout/card_kpi_badge"> entries, so view
        // binding exposes them as CardKpiBadgeBinding — getRoot() gives the View.
        cardGdp = b.cardGdp.root
        cardCpiYoy = b.cardCpiYoy.root
        cardSpread = b.cardSpread.root
        cardUnemployment = b.cardUnemployment.root
        cardMortgage = b.cardMortgage.root
        cardVix = b.cardVix.root

        setupCardLabel(cardGdp, "GDP GROWTH")
        setupCardLabel(cardCpiYoy, "CPI-U YOY")
        setupCardLabel(cardSpread, "10Y-3M SPREAD")
        setupCardLabel(cardUnemployment, "UNEMPLOYMENT")
        setupCardLabel(cardMortgage, "30 YEAR MORTGAGE")
        setupCardLabel(cardVix, "VIX INDEX")

        // Skeleton pulse until real data arrives
        startSkeleton(cardGdp); startSkeleton(cardCpiYoy); startSkeleton(cardSpread)
        startSkeleton(cardUnemployment); startSkeleton(cardMortgage); startSkeleton(cardVix)

        // IOS-01: press-in spring instead of Material ripple (foreground is
        // now @null in card_kpi_badge.xml).
        PressFeedback.attach(cardGdp)
        PressFeedback.attach(cardCpiYoy)
        PressFeedback.attach(cardSpread)
        PressFeedback.attach(cardUnemployment)
        PressFeedback.attach(cardMortgage)
        PressFeedback.attach(cardVix)

        // Tap KPI card → matching benchmark/status dialog
        // TICKET-24: pass the series key/label so each sheet offers "Add alert".
        wireCardDialog(cardGdp, R.layout.dialog_gdp_status, EconomicViewModel.CACHE_GDP, "GDP growth")
        wireCardDialog(cardCpiYoy, R.layout.dialog_cpi_status, EconomicViewModel.CACHE_CPI, "CPI YoY")
        wireCardDialog(cardSpread, R.layout.dialog_treasury_3m_status, EconomicViewModel.ALERT_SPREAD_3M, "10Y-3M spread")
        wireCardDialog(cardUnemployment, R.layout.dialog_unemployment_status, EconomicViewModel.CACHE_EMPLOYMENT, "Unemployment")

        wireCardDialog(cardMortgage, R.layout.dialog_mortgage_status,
            EconomicViewModel.CACHE_MBS, "30-year mortgage rate")
        wireCardDialog(cardVix, R.layout.dialog_vix_status,
            EconomicViewModel.CACHE_VIX, "VIX index")

        // Long-press KPI card → ask the Econ Analyst about that metric. Routed
        // through the same AskAnalyst helper every other fragment uses, so
        // these gesture turns get concise=true + screen-context injection
        // (Laws 6-8, 12) instead of the bespoke path's plain full-length query.
        AskAnalyst.wireCardLongPress(cardGdp, requireActivity(), "GDP growth")
        AskAnalyst.wireCardLongPress(cardCpiYoy, requireActivity(), "CPI inflation (YoY)")
        AskAnalyst.wireCardLongPress(cardSpread, requireActivity(), "the 10Y-3M Treasury spread")
        AskAnalyst.wireCardLongPress(cardUnemployment, requireActivity(), "the unemployment rate")
        AskAnalyst.wireCardLongPress(cardMortgage, requireActivity(), "the 30-year mortgage rate")
        AskAnalyst.wireCardLongPress(cardVix, requireActivity(), "the VIX index")

        // Fed funds hero — bind views directly from the root to avoid null-chain issues
        tvFedFundsHeroValue = v.findViewById(R.id.tvFedFundsHeroValue)
        changeFedFunds = v.findViewById(R.id.change_fed_funds)
        tvFedFundsHeroCycle = v.findViewById(R.id.tvFedFundsHeroCycle)
        tvFedFundsHeroDate = v.findViewById(R.id.tvFedFundsHeroDate)
        tvFedFundsRetry = v.findViewById(R.id.tvFedFundsRetry)
        tvFedFundsRetry?.setOnClickListener {
            viewModel.retrySeries(EconomicViewModel.CACHE_FED_FUNDS)
        }

        updateNextFomcLabel()

        // Tap hero card → show Fed Funds history dialog
        val cardFedFundsHero: View? = v.findViewById(R.id.cardFedFundsHero)
        if (cardFedFundsHero != null) {
            PressFeedback.attach(cardFedFundsHero)
            cardFedFundsHero.setOnClickListener {
                (activity as? MainActivity)?.showFedFundsHistory()
            }
            AskAnalyst.wireCardLongPress(cardFedFundsHero, requireActivity(), "the Fed Funds rate")
        }
    }

    // ── TICKET-25: watchlist-driven cluster order ──────────────────────────

    /**
     * Reorders the three Overview metric clusters (header + region pairs) so
     * the cluster containing the user's highest-ranked watchlist metric comes
     * first. No-op when views are missing or the order is already right.
     */
    private fun applyWatchlistOrder(root: View) {
        val ctx = context ?: return
        val watch = SettingsManager.getWatchlist(ctx)

        val hGrowth: View? = root.findViewById(R.id.headerGroupGrowth)
        val rGrowth: View? = root.findViewById(R.id.regionGroupGrowth)
        val hInflation: View? = root.findViewById(R.id.headerGroupInflation)
        val rInflation: View? = root.findViewById(R.id.regionGroupInflation)
        val hMarkets: View? = root.findViewById(R.id.headerGroupMarkets)
        val rMarkets: View? = root.findViewById(R.id.regionGroupMarkets)
        if (hGrowth == null || rGrowth == null || hInflation == null
            || rInflation == null || hMarkets == null || rMarkets == null
        ) return
        val column = contentContainer as? LinearLayout ?: return

        // Cluster rank = best (lowest) watchlist position of its metrics.
        class Group(val header: View, val region: View, val rank: Int)

        val groups = ArrayList<Group>()
        groups.add(Group(hGrowth, rGrowth, bestRank(watch, "gdp", "spread_10y3m")))
        groups.add(Group(hInflation, rInflation, bestRank(watch, "cpi", "employment")))
        groups.add(Group(hMarkets, rMarkets, bestRank(watch, "mbs_mortgage", "vix")))
        groups.sortBy { it.rank }

        // Re-insert the pairs, in rank order, at the first cluster's position.
        var insertAt = column.indexOfChild(hGrowth)
        for (g in groups) {
            insertAt = Math.min(insertAt, column.indexOfChild(g.header))
        }
        for (g in groups) {
            column.removeView(g.header)
            column.removeView(g.region)
        }
        for (g in groups) {
            column.addView(g.header, insertAt++)
            column.addView(g.region, insertAt++)
        }
    }

    private fun setupCardLabel(card: View, label: String) {
        val tv: TextView? = card.findViewById(R.id.tvMetricLabel)
        if (tv != null) tv.text = label
    }

    /** Unused today (no caller) — kept verbatim per the dead-code decision. */
    private fun wireCardDialog(card: View, layoutRes: Int) {
        wireCardDialog(card, layoutRes, null, null)
    }

    /** TICKET-24: metric-aware overload — the sheet gains an "Add alert" row. */
    private fun wireCardDialog(card: View, layoutRes: Int, seriesKey: String?, seriesLabel: String?) {
        card.setOnClickListener { v ->
            v.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
            showBenchmarkDialog(layoutRes, seriesKey, seriesLabel)
        }
    }

    /** Unused today (no caller) — kept verbatim per the dead-code decision. */
    private fun showBenchmarkDialog(layoutRes: Int) {
        showBenchmarkDialog(layoutRes, null, null)
    }

    private fun showBenchmarkDialog(layoutRes: Int, seriesKey: String?, seriesLabel: String?) {
        val ctx = context ?: return
        MetricBottomSheet.show(ctx, layoutRes, seriesKey, seriesLabel)
    }

    // ── Skeleton loading state ──────────────────────────────────────────────

    private fun startSkeleton(card: View) {
        val tv: TextView = card.findViewById(R.id.tvMetricValue) ?: return
        val pulse = AlphaAnimation(1f, 0.35f)
        pulse.duration = 700
        pulse.repeatCount = Animation.INFINITE
        pulse.repeatMode = Animation.REVERSE
        tv.startAnimation(pulse)
    }

    /** Fades out the full-screen shimmer skeleton and reveals real content. */
    private fun hideSkeleton() {
        if (skeletonHidden) return
        skeletonHidden = true
        // TICKET-28: respect "reduce motion" — reveal instantly, no cross-fade.
        val ctx = context
        val animate = ctx == null || MotionUtil.animationsEnabled(ctx)
        val content = contentContainer
        if (content != null) {
            if (animate) {
                content.alpha = 0f
                content.visibility = View.VISIBLE
                content.animate().alpha(1f).setDuration(300).start()
            } else {
                content.alpha = 1f
                content.visibility = View.VISIBLE
            }
        }
        val shimmer = skeletonShimmer
        if (shimmer != null) {
            shimmer.stopShimmer()
            if (animate) {
                shimmer.animate().alpha(0f).setDuration(300)
                    .withEndAction { shimmer.visibility = View.GONE }
                    .start()
            } else {
                shimmer.visibility = View.GONE
            }
        }
    }

    /**
     * Sets the metric value, stops the skeleton pulse, and — when a fresh value
     * replaces a real cached one — rolls the number to its new value (TICKET-27).
     * Count-up is skipped for unchanged values, first paint, and when motion is
     * disabled (TICKET-28); in those cases the text is set instantly.
     */
    private fun setCardValue(card: View, text: String) {
        val tv: TextView = card.findViewById(R.id.tvMetricValue) ?: return
        val old = if (tv.text != null) tv.text.toString() else ""
        tv.clearAnimation()
        tv.alpha = 1f
        ValueAnimatorUtil.animateOrSet(tv, old, text)
    }

    private fun setCardDate(card: View, text: String) {
        val tv: TextView? = card.findViewById(R.id.tvMetricDate)
        if (tv != null) tv.text = text
    }

    // ── Delta chip ──────────────────────────────────────────────────────────

    /**
     * Shows the change vs. the previous release next to the value.
     * goodWhenDown: true for metrics where a decrease is favorable (CPI,
     * unemployment, mortgage rate, VIX).
     */
    private fun applyDelta(card: View, delta: Double, fmt: String, goodWhenDown: Boolean) {
        val ctx = context ?: return
        val tv: TextView = card.findViewById(R.id.tvMetricDelta) ?: return
        if (Math.abs(delta) < 1e-9) { tv.visibility = View.GONE; return }
        // Direction is carried by glyph + sign + color, so it survives grayscale
        // and colorblind viewing. Palette honors the colorblind-safe setting.
        tv.setTextColor(DeltaFormatter.color(ctx, delta, goodWhenDown))
        tv.text = DeltaFormatter.format(delta, fmt)
        tv.visibility = View.VISIBLE
    }

    // ── Partial-failure retry chips ───────────────────────────────────────────

    /** Shows/hides a KPI card's retry chip and wires a single-series re-fetch. */
    private fun toggleRetry(card: View, seriesKey: String, failed: Set<String>) {
        val tv: TextView = card.findViewById(R.id.tvRetry) ?: return
        if (failed.contains(seriesKey)) {
            tv.setOnClickListener { viewModel.retrySeries(seriesKey) }
            tv.visibility = View.VISIBLE
        } else {
            tv.visibility = View.GONE
        }
    }

    private fun toggleHeroRetry(show: Boolean) {
        tvFedFundsRetry?.visibility = if (show) View.VISIBLE else View.GONE
    }

    // ── Sparkline ───────────────────────────────────────────────────────────

    private fun setSparkline(card: View, vals: List<Float>?) {
        val sp: SparklineView = card.findViewById(R.id.sparkline) ?: return
        // TICKET-H: consolidated onto the shared SparklineView (was a second,
        // independently maintained class). Visibility toggling and the
        // plain-stroke (no gradient fill) look are reproduced here since the
        // shared view no longer owns that behavior internally.
        val hasData = vals != null && vals.size >= 2
        sp.visibility = if (hasData) View.VISIBLE else View.GONE
        if (hasData && vals != null) {
            sp.setFillEnabled(false)
            // .toInt(): 8-digit hex is inferred as Long in Kotlin and
            // setLineColor takes int (migration lesson 4).
            sp.setLineColor(0xFFC9A84C.toInt())
            val doubles = ArrayList<Double>(vals.size)
            for (v in vals) doubles.add(v.toDouble())
            sp.setData(doubles)
        }
    }

    // ── Data observers ──────────────────────────────────────────────────────

    private fun observeData() {
        viewModel.getTreasuryData().observe(viewLifecycleOwner) { data ->
            if (data != null) { hideSkeleton(); updateSpread3M(data); applySpread3MTier(data) }
        }
        viewModel.getFedFundsData().observe(viewLifecycleOwner) { data ->
            if (data != null) { hideSkeleton(); updateFedFundsRate(data) }
        }
        viewModel.getEmploymentData().observe(viewLifecycleOwner) { data ->
            if (data != null) { hideSkeleton(); updateEmployment(data); applyUnemploymentTier(data) }
        }
        viewModel.getCpiData().observe(viewLifecycleOwner) { data ->
            if (data != null) { hideSkeleton(); updateCpiYoY(data); applyCpiYoYTier(data) }
        }
        viewModel.getGdpData().observe(viewLifecycleOwner) { data ->
            if (data != null) { hideSkeleton(); updateGdpGrowth(data); applyGdpTier(data) }
        }
        viewModel.getMbsMortgageData().observe(viewLifecycleOwner) { data ->
            if (data != null) { hideSkeleton(); updateMortgage(data); applyMortgageTier(data) }
        }
        viewModel.getVixData().observe(viewLifecycleOwner) { data ->
            if (data != null) { hideSkeleton(); updateVix(data); applyVixTier(data) }
        }
    }

    // ── Tier badge helpers ──────────────────────────────────────────────────

    private fun applyUnemploymentTier(data: List<EconomicDataPoint>) {
        val rows = EconomicViewModel.filterBySeries(data, "Unemployment Rate")
        if (rows.isEmpty()) return
        val current = rows[rows.size - 1].value
        var low = Double.MAX_VALUE
        for (i in Math.max(0, rows.size - 12) until rows.size) low = Math.min(low, rows[i].value)
        val rise = current - low
        val pct = EconomicViewModel.calculatePercentile(data, "Unemployment Rate", current)
        applyTierToCard(cardUnemployment, Benchmarks.unemployment(current, rise),
            EconomicViewModel.formatPercentile(pct))
    }

    private fun applyCpiYoYTier(data: List<EconomicDataPoint>) {
        val rows = EconomicViewModel.filterBySeries(data, "CPI-U All Items")
        if (rows.size < 13) return
        val latest = rows[rows.size - 1].value
        val yoy = ((latest - rows[rows.size - 13].value) / rows[rows.size - 13].value) * 100.0
        val pct = EconomicViewModel.calculatePercentile(data, "CPI-U All Items", latest)
        applyTierToCard(cardCpiYoy, Benchmarks.cpiYoY(yoy), EconomicViewModel.formatPercentile(pct))
    }

    private fun applySpread3MTier(data: List<EconomicDataPoint>) {
        val longRate = EconomicViewModel.getLatest(data, "10 Year")
        val shortRate = EconomicViewModel.getLatest(data, "3 Month")
        if (longRate == null || shortRate == null) return
        val spread = longRate.value - shortRate.value
        applyTierToCard(cardSpread, Benchmarks.spread3m10y(spread), "")
    }

    private fun applyGdpTier(data: List<EconomicDataPoint>) {
        val rows = EconomicViewModel.filterBySeries(data, "Gross domestic product")
        if (rows.isEmpty()) return
        var sum = 0.0
        var count = 0
        for (i in Math.max(0, rows.size - 4) until rows.size) { sum += rows[i].value; count++ }
        val avg = if (count > 0) sum / count else 0.0
        applyTierToCard(cardGdp, Benchmarks.gdpGrowth(avg), "")
    }

    private fun applyMortgageTier(data: List<EconomicDataPoint>) {
        val rows = EconomicViewModel.filterBySeries(data, "30-Yr Mortgage Rate")
        if (rows.isEmpty()) return
        val current = rows[rows.size - 1].value
        applyTierToCard(cardMortgage, Benchmarks.mortgage30(current), "")
    }

    private fun applyVixTier(data: List<EconomicDataPoint>) {
        val rows = EconomicViewModel.filterBySeries(data, "VIX Volatility Index")
        if (rows.isEmpty()) return
        val current = rows[rows.size - 1].value
        applyTierToCard(cardVix, Benchmarks.vix(current), "")
    }

    private fun applyTierToCard(card: View, result: Benchmarks.Result?, percentile: String) {
        if (result == null) return
        BenchmarkStyle.tintDot(card.findViewById(R.id.viewTierDot), requireContext(), result.tier)
        val tvStatus: TextView? = card.findViewById(R.id.tvTierStatus)
        val tvPct: TextView? = card.findViewById(R.id.tvPercentile)
        if (tvStatus != null) tvStatus.text = result.word
        if (tvPct != null) tvPct.text = percentile
    }

    // ── Fed funds hero ──────────────────────────────────────────────────────

    private fun updateFedFundsRate(data: List<EconomicDataPoint>) {
        val rows = EconomicViewModel.filterBySeries(data, "Federal Funds Effective Rate")
        if (rows.isEmpty()) return
        val current = rows[rows.size - 1]
        val heroValue = tvFedFundsHeroValue
        if (heroValue != null) {
            // TICKET-27: roll the hero value up/down on refresh.
            val heroOld = if (heroValue.text != null) heroValue.text.toString() else ""
            ValueAnimatorUtil.animateOrSet(heroValue, heroOld,
                NumberFormatUtil.percent(current.value))
        }

        var lastChange = 0.0
        for (i in rows.size - 1 downTo 1) {
            val delta = rows[i].value - rows[i - 1].value
            if (Math.abs(delta) > 0.001) { lastChange = delta; break }
        }
        val change = changeFedFunds
        if (change != null) {
            if (Math.abs(lastChange) > 0.001) {
                val arrow = if (lastChange < 0) "↓" else "↑"
                change.text = String.format(Locale.US, "%s %.2f from prev.", arrow, Math.abs(lastChange))
                change.visibility = View.VISIBLE
            } else {
                change.visibility = View.GONE
            }
        }
        // Cycle note driven by the direction of the last actual move
        val cycle = tvFedFundsHeroCycle
        if (cycle != null) {
            if (lastChange < -0.001) cycle.text = "Cut cycle ongoing"
            else if (lastChange > 0.001) cycle.text = "Hike cycle ongoing"
            else cycle.text = "Rates on hold"
            cycle.visibility = View.VISIBLE
        }
    }

    /** Next scheduled FOMC meeting from the published calendar. */
    private fun updateNextFomcLabel() {
        val heroDate = tvFedFundsHeroDate ?: return
        val now = Calendar.getInstance()
        val year = now.get(Calendar.YEAR)
        if (year < 2026) { heroDate.text = "Next FOMC: Jan 27–28"; return }
        if (year == 2026) {
            val month = now.get(Calendar.MONTH) + 1
            val day = now.get(Calendar.DAY_OF_MONTH)
            for (m in FOMC_2026) {
                if (m[0] > month || (m[0] == month && m[2] >= day)) {
                    heroDate.text = String.format(Locale.US,
                        "Next FOMC: %s %d–%d", MONTH_ABBR[m[0] - 1], m[1], m[2])
                    return
                }
            }
        }
        // Past Dec 2026 — schedule not embedded; hide rather than show stale info
        heroDate.visibility = View.GONE
    }

    // ── Value update helpers ────────────────────────────────────────────────

    private fun updateSpread3M(data: List<EconomicDataPoint>) {
        // Build the spread series by matching 10Y and 3M observations by date
        val tens = EconomicViewModel.filterBySeries(data, "10 Year")
        val threes = EconomicViewModel.filterBySeries(data, "3 Month")
        val threeByDate = HashMap<String, Double>()
        for (p in threes) threeByDate[p.date] = p.value
        val spreads = ArrayList<EconomicDataPoint>()
        for (p in tens) {
            val s = threeByDate[p.date]
            if (s != null) spreads.add(EconomicDataPoint("FRED", "Treasury", "10Y-3M Spread", p.date, p.value - s, "%"))
        }
        if (!spreads.isEmpty()) {
            val latest = spreads[spreads.size - 1]
            setCardValue(cardSpread, NumberFormatUtil.percent(latest.value))
            setCardDate(cardSpread, latest.date)
            if (spreads.size >= 2)
                applyDelta(cardSpread, latest.value - spreads[spreads.size - 2].value,
                    "%.2f", false)
            setSparkline(cardSpread, tailValues(spreads, 30))
        }
    }

    private fun updateCpiYoY(data: List<EconomicDataPoint>) {
        val rows = EconomicViewModel.filterBySeries(data, "CPI-U All Items")
        if (rows.size < 13) return
        // YoY series for value, delta, and sparkline
        val yoySeries = ArrayList<Float>()
        for (i in 12 until rows.size) {
            val base = rows[i - 12].value
            if (Math.abs(base) > 1e-9)
                yoySeries.add((((rows[i].value - base) / base) * 100.0).toFloat())
        }
        if (yoySeries.isEmpty()) return
        val latest = yoySeries[yoySeries.size - 1]
        setCardValue(cardCpiYoy, NumberFormatUtil.percent(latest.toDouble()))
        setCardDate(cardCpiYoy, rows[rows.size - 1].date)
        if (yoySeries.size >= 2)
            applyDelta(cardCpiYoy, (latest - yoySeries[yoySeries.size - 2]).toDouble(), "%.2fpp", true)
        setSparkline(cardCpiYoy, yoySeries.subList(Math.max(0, yoySeries.size - 12), yoySeries.size))
    }

    private fun updateGdpGrowth(data: List<EconomicDataPoint>) {
        val rows = EconomicViewModel.filterBySeries(data, "Gross domestic product")
        if (rows.isEmpty()) return
        var sum = 0.0
        var count = 0
        for (i in Math.max(0, rows.size - 4) until rows.size) { sum += rows[i].value; count++ }
        setCardValue(cardGdp, NumberFormatUtil.percent(if (count > 0) sum / count else 0.0))
        setCardDate(cardGdp, rows[rows.size - 1].date)
        if (rows.size >= 2)
            applyDelta(cardGdp, rows[rows.size - 1].value - rows[rows.size - 2].value,
                "%.1fpp", false)
        setSparkline(cardGdp, tailValues(rows, 8))
    }

    private fun updateVix(data: List<EconomicDataPoint>) {
        // VIX is an index level — carry a "pt" unit so it isn't a bare number.
        updateSimpleCard(data, "VIX Volatility Index", cardVix, "%.1f pt", "%.1f", true, 30)
    }

    private fun updateEmployment(data: List<EconomicDataPoint>) {
        updateSimpleCard(data, "Unemployment Rate", cardUnemployment, "%.1f%%", "%.1fpp", true, 12)
    }

    private fun updateMortgage(data: List<EconomicDataPoint>) {
        updateSimpleCard(data, "30-Yr Mortgage Rate", cardMortgage, "%.2f%%", "%.2fpp", true, 12)
    }

    /** Shared: value + delta vs. previous observation + sparkline of the last n points. */
    private fun updateSimpleCard(
        data: List<EconomicDataPoint>, series: String, card: View,
        fmt: String, deltaFmt: String, goodWhenDown: Boolean, sparkN: Int
    ) {
        val rows = EconomicViewModel.filterBySeries(data, series)
        if (rows.isEmpty()) return
        val latest = rows[rows.size - 1]
        setCardValue(card, String.format(Locale.US, fmt, latest.value))
        setCardDate(card, latest.date)
        if (rows.size >= 2)
            applyDelta(card, latest.value - rows[rows.size - 2].value, deltaFmt, goodWhenDown)
        setSparkline(card, tailValues(rows, sparkN))
    }

    override fun onResume() { super.onResume() }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }

    companion object {
        /** 2026 FOMC meetings: {month(1-12), startDay, endDay} — federalreserve.gov. */
        private val FOMC_2026 = arrayOf(
            intArrayOf(1, 27, 28), intArrayOf(3, 17, 18), intArrayOf(4, 28, 29), intArrayOf(6, 16, 17),
            intArrayOf(7, 28, 29), intArrayOf(9, 15, 16), intArrayOf(10, 27, 28), intArrayOf(12, 8, 9)
        )
        private val MONTH_ABBR = arrayOf(
            "Jan", "Feb", "Mar", "Apr", "May", "Jun",
            "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
        )

        /** Lowest index of any of the keys in the watchlist; large if none listed. */
        private fun bestRank(watch: List<String>, vararg keys: String): Int {
            var best = Integer.MAX_VALUE
            for (k in keys) {
                val i = watch.indexOf(k)
                if (i >= 0 && i < best) best = i
            }
            return best
        }

        /** Last n values of a series, oldest first. */
        private fun tailValues(rows: List<EconomicDataPoint>, n: Int): List<Float> {
            val out = ArrayList<Float>()
            for (i in Math.max(0, rows.size - n) until rows.size)
                out.add(rows[i].value.toFloat())
            return out
        }
    }
}
