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
import com.economic.dashboard.ui.BenchmarkStyle
import com.economic.dashboard.ui.DashboardIndicatorCatalog
import com.economic.dashboard.ui.EditCardsBottomSheet
import com.economic.dashboard.ui.EconomicViewModel
import com.economic.dashboard.ui.MetricPopupController
import com.economic.dashboard.ui.MetricPopupRegistry
import com.economic.dashboard.ui.onboarding.WatchlistSetupFragment
import com.economic.dashboard.ui.views.PressFeedback
import com.economic.dashboard.utils.DeltaFormatter
import com.economic.dashboard.utils.EdgeToEdgeInsets
import com.economic.dashboard.utils.HapticUtil
import com.economic.dashboard.utils.MotionUtil
import com.economic.dashboard.utils.SettingsManager
import com.economic.dashboard.utils.ValueAnimatorUtil
import com.economic.dashboard.views.SparklineView

import java.util.Calendar
import java.util.Locale

/**
 * Design branch 2a — "Customizable dashboard: pick any indicator for 7 slots".
 *
 * The hero card and the 6 KPI cards below it no longer track fixed metrics.
 * Each one persists which [DashboardIndicatorCatalog] entry it shows
 * ([SettingsManager.getDashboardHero]/[SettingsManager.getDashboardSlots]).
 * Editing is centralized behind the single "Edit cards" button, which opens
 * [EditCardsBottomSheet] (direction 1d — drag category blocks and slots to
 * reorder them, tap a slot's Swap chip for [IndicatorPickerBottomSheet]
 * scoped to that slot's own category) — the cards themselves keep their
 * original tap (status detail, where one exists) and long-press (ask
 * analyst) behavior.
 */
class DashboardFragment : Fragment() {

    private lateinit var viewModel: EconomicViewModel
    private var binding: FragmentDashboardBinding? = null

    // The 6 KPI slots, in the same fixed physical/visual order as
    // SettingsManager.DEFAULT_DASHBOARD_SLOTS: [gdp, spread] [cpi, unemployment] [mortgage, vix]
    // positions. Which INDICATOR each one shows is dynamic (slotIndicatorIds).
    private lateinit var slotCards: Array<View>
    private val slotIndicatorIds = ArrayList<String>(6)
    private var heroIndicatorId: String = SettingsManager.DEFAULT_DASHBOARD_HERO

    // Fed funds hero views — bound directly so they never fail silently.
    private var tvFedFundsHeroValue: TextView? = null
    private var changeFedFunds: TextView? = null
    private var tvFedFundsHeroCycle: TextView? = null
    private var tvFedFundsHeroDate: TextView? = null
    private var tvFedFundsRetry: TextView? = null
    private var cardFedFundsHero: View? = null
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private var skeletonShimmer: ShimmerFrameLayout? = null
    private var contentContainer: View? = null
    private var tvCacheAsOf: TextView? = null
    private var skeletonHidden = false
    private var wasLoading = false
    // Last failed-series set from the ViewModel, kept so the hero retry chip
    // can re-fetch exactly the fed-funds key(s) that failed (review finding 8).
    private var lastFailedSeries: Set<String> = emptySet()

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

        val ctx = context
        heroIndicatorId = if (ctx != null) SettingsManager.getDashboardHero(ctx) else SettingsManager.DEFAULT_DASHBOARD_HERO
        slotIndicatorIds.clear()
        slotIndicatorIds.addAll(if (ctx != null) SettingsManager.getDashboardSlots(ctx) else SettingsManager.DEFAULT_DASHBOARD_SLOTS.toList())

        bindViews(view)
        observeData()

        // Fragment-result wiring for the edit-cards sheet: registered every time
        // the view is (re)created, so a sheet that outlives a rotation or theme
        // switch still delivers Done into a listener that exists. The old
        // retained-lambda wiring lost the callback on recreation and Done
        // silently discarded the user's edits.
        parentFragmentManager.setFragmentResultListener(
            EditCardsBottomSheet.REQUEST_KEY, viewLifecycleOwner
        ) { _, result ->
            val heroId = result.getString(EditCardsBottomSheet.KEY_RESULT_HERO_ID) ?: return@setFragmentResultListener
            val slotIds = result.getStringArrayList(EditCardsBottomSheet.KEY_RESULT_SLOT_IDS) ?: return@setFragmentResultListener
            val categoryOrder = result.getStringArrayList(EditCardsBottomSheet.KEY_RESULT_CATEGORY_ORDER) ?: return@setFragmentResultListener
            onCardsSaved(heroId, slotIds, categoryOrder)
        }

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

        // Partial-failure: show a per-card retry chip for any series that failed
        // to refresh, without blanking the cards that succeeded.
        viewModel.getFailedSeries().observe(viewLifecycleOwner) { failed ->
            val f: Set<String> = failed ?: emptySet()
            lastFailedSeries = f
            for (i in slotCards.indices) toggleRetry(slotCards[i], slotIndicatorIds[i], f)
            // The hero paints from the daily series, but the metric popup's 5Y
            // history and the CACHE_FED_FUNDS alert basis read the monthly one —
            // so the chip appears when EITHER key fails (review finding 8).
            toggleHeroRetry(
                (f.contains(EconomicViewModel.CACHE_FED_FUNDS_DAILY) ||
                    f.contains(EconomicViewModel.CACHE_FED_FUNDS)) && heroIndicatorId == "fedfunds"
            )
        }

        // TICKET-25: rank the Overview clusters by the user's watchlist (or,
        // once the user has dragged Edit Cards' category blocks, by that
        // manual order instead — see applyCategoryOrder), and show the
        // one-time first-run watchlist setup sheet.
        applyCategoryOrder(view)
        if (ctx != null && !SettingsManager.isOnboardingComplete(ctx)) {
            val sheet = WatchlistSetupFragment()
            sheet.setListener { getView()?.let { applyCategoryOrder(it) } }
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

        // KPI slots — physical order matches SettingsManager.DEFAULT_DASHBOARD_SLOTS.
        slotCards = arrayOf(
            b.cardGdp.root, b.cardSpread.root, b.cardCpiYoy.root,
            b.cardUnemployment.root, b.cardMortgage.root, b.cardVix.root
        )

        for (i in slotCards.indices) {
            val card = slotCards[i]
            startSkeleton(card)
            // IOS-01: press-in spring instead of Material ripple.
            PressFeedback.attach(card)
            // Tap (status detail, where mapped) + long-press (ask analyst) are
            // wired per-slot in refreshSlot(), since they depend on whichever
            // indicator currently occupies that slot.
        }

        v.findViewById<View>(R.id.btnEditDashboard)?.setOnClickListener { openEditTargetSheet() }

        // Fed funds hero — bind views directly from the root to avoid null-chain issues
        tvFedFundsHeroValue = v.findViewById(R.id.tvFedFundsHeroValue)
        changeFedFunds = v.findViewById(R.id.change_fed_funds)
        tvFedFundsHeroCycle = v.findViewById(R.id.tvFedFundsHeroCycle)
        tvFedFundsHeroDate = v.findViewById(R.id.tvFedFundsHeroDate)
        tvFedFundsRetry = v.findViewById(R.id.tvFedFundsRetry)
        tvFedFundsRetry?.setOnClickListener {
            // Retry whichever fed-funds key(s) failed — retrySeries is per-key
            // and counter-balanced, so one tap can safely re-fetch both.
            val f = lastFailedSeries
            if (f.contains(EconomicViewModel.CACHE_FED_FUNDS_DAILY)) {
                viewModel.retrySeries(EconomicViewModel.CACHE_FED_FUNDS_DAILY)
            }
            if (f.contains(EconomicViewModel.CACHE_FED_FUNDS)) {
                viewModel.retrySeries(EconomicViewModel.CACHE_FED_FUNDS)
            }
        }

        updateNextFomcLabel()

        cardFedFundsHero = v.findViewById(R.id.cardFedFundsHero)
        cardFedFundsHero?.let { hero ->
            PressFeedback.attach(hero)
        }

        // Paint labels + refresh cards from whatever's already cached, so a
        // slot shows the right label/value immediately, before any new data
        // observer fires. This also wires each card's tap/long-press for
        // whichever indicator it currently holds.
        refreshAllSlots()
        refreshHeroLabel()
    }

    // ── Design branch 2a, direction 1d: single edit entry point ─────────────

    /** "Edit cards" button → the drag-to-reorder, tap-to-swap editor. */
    private fun openEditTargetSheet() {
        val heroRow = EditCardsBottomSheet.HeroRow(
            getString(R.string.edit_dashboard_hero_title), heroIndicatorId
        )
        val ctx = context
        val orderKeys = if (ctx != null) computeCategoryOrder(ctx) else SLOT_CATEGORIES.map { it.key }
        val blocks = ArrayList<EditCardsBottomSheet.CategoryBlock>()
        for (key in orderKeys) {
            val cat = SLOT_CATEGORIES.firstOrNull { it.key == key } ?: continue
            val slots = ArrayList<EditCardsBottomSheet.SlotRow>()
            for (idx in cat.slotIndices) slots.add(EditCardsBottomSheet.SlotRow(idx, slotIndicatorIds[idx]))
            blocks.add(EditCardsBottomSheet.CategoryBlock(cat.key, cat.title, cat.catalogGroups, slots))
        }

        EditCardsBottomSheet.show(parentFragmentManager, heroRow, blocks)
    }

    /** Persists a Done from the edit-cards sheet (delivered via fragment result). */
    private fun onCardsSaved(heroId: String, slotIds: List<String>, categoryOrder: List<String>) {
        heroIndicatorId = heroId
        context?.let { SettingsManager.setDashboardHero(it, heroId) }
        refreshHeroLabel()
        refreshHero()

        slotIndicatorIds.clear()
        slotIndicatorIds.addAll(slotIds)
        context?.let { SettingsManager.setDashboardSlots(it, slotIndicatorIds) }
        refreshAllSlots()

        context?.let { SettingsManager.setDashboardCategoryOrder(it, categoryOrder) }
        view?.let { applyCategoryOrder(it) }
    }

    /** Wires the hero card's tap (same generic benchmark/data popup every other
     *  badge opens, for whatever indicator the hero currently shows) and
     *  long-press (ask analyst about whatever it currently shows). */
    private fun refreshHeroLabel() {
        val v = view ?: return
        val llHeroLeft: LinearLayout? = v.findViewById(R.id.llHeroLeft)
        val label: TextView? = llHeroLeft?.getChildAt(0) as? TextView
        label?.text = DashboardIndicatorCatalog.label(heroIndicatorId)

        val hero = cardFedFundsHero ?: return
        if (MetricPopupRegistry.byId(heroIndicatorId) != null) {
            hero.setOnClickListener { v2 ->
                v2.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                val ctx = context ?: return@setOnClickListener
                MetricPopupController.show(ctx, viewModel, heroIndicatorId)
            }
        } else {
            hero.setOnClickListener(null)
        }
        AskAnalyst.wireCardLongPress(hero, requireActivity(), DashboardIndicatorCatalog.label(heroIndicatorId))
    }

    // ── TICKET-25 / direction 1d: category cluster order ────────────────────

    /**
     * The order the three Overview clusters should appear in, as category
     * keys (see [SLOT_CATEGORIES]). Once the user has dragged category blocks
     * in Edit Cards, that manual order sticks ([SettingsManager.getDashboardCategoryOrder]);
     * until then, it falls back to ranking each cluster by the user's
     * highest-ranked watchlist metric currently assigned to one of its slots
     * — a few watchlist keys are aliased to their matching catalog id
     * (spread_10y3m→spread, employment→unemployment, mbs_mortgage→mortgage).
     */
    private fun computeCategoryOrder(ctx: android.content.Context): List<String> {
        val allKeys = SLOT_CATEGORIES.map { it.key }
        val manual = SettingsManager.getDashboardCategoryOrder(ctx)
        if (manual != null && manual.toSet() == allKeys.toSet()) return manual

        if (slotIndicatorIds.size < 6) return allKeys
        val watch = SettingsManager.getWatchlist(ctx).map { WATCHLIST_ALIAS[it] ?: it }
        return SLOT_CATEGORIES
            .sortedBy { cat -> bestRank(watch, *cat.slotIndices.map { slotIndicatorIds[it] }.toTypedArray()) }
            .map { it.key }
    }

    /** Reorders the three Overview metric clusters (header + region pairs) to match [computeCategoryOrder]. */
    private fun applyCategoryOrder(root: View) {
        val ctx = context ?: return
        if (slotIndicatorIds.size < 6) return

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

        val views = mapOf(
            "growth" to Pair(hGrowth, rGrowth),
            "inflation" to Pair(hInflation, rInflation),
            "housing_markets" to Pair(hMarkets, rMarkets)
        )
        val orderKeys = computeCategoryOrder(ctx)

        var insertAt = column.indexOfChild(hGrowth)
        for ((_, pair) in views) insertAt = Math.min(insertAt, column.indexOfChild(pair.first))
        for (key in orderKeys) {
            val pair = views[key] ?: continue
            column.removeView(pair.first)
            column.removeView(pair.second)
        }
        for (key in orderKeys) {
            val pair = views[key] ?: continue
            column.addView(pair.first, insertAt++)
            column.addView(pair.second, insertAt++)
        }
    }

    /**
     * [labelLines] reserves that many lines for the overline so a short label
     * doesn't leave its card shorter than the one beside it.
     */
    private fun setupCardLabel(card: View, label: String, labelLines: Int = 1) {
        val tv: TextView? = card.findViewById(R.id.tvMetricLabel)
        if (tv != null) {
            tv.text = label
            tv.minLines = labelLines
        }
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

    private fun applyDelta(card: View, delta: Double?, fmt: String, goodWhenDown: Boolean) {
        val ctx = context ?: return
        val tv: TextView = card.findViewById(R.id.tvMetricDelta) ?: return
        if (delta == null || Math.abs(delta) < 1e-9) { tv.visibility = View.GONE; return }
        // Direction is carried by glyph + sign + color, so it survives grayscale
        // and colorblind viewing. Palette honors the colorblind-safe setting.
        tv.setTextColor(DeltaFormatter.color(ctx, delta, goodWhenDown))
        tv.text = DeltaFormatter.format(delta, fmt)
        tv.visibility = View.VISIBLE
    }

    // ── Partial-failure retry chips ───────────────────────────────────────────

    /** Shows/hides a KPI card's retry chip and wires a single-series re-fetch. */
    private fun toggleRetry(card: View, indicatorId: String, failed: Set<String>) {
        val tv: TextView = card.findViewById(R.id.tvRetry) ?: return
        val seriesKey = INDICATOR_CACHE_KEY[indicatorId]
        if (seriesKey != null && failed.contains(seriesKey)) {
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
        val hasData = vals != null && vals.size >= 2
        sp.visibility = if (hasData) View.VISIBLE else View.GONE
        if (hasData && vals != null) {
            sp.setFillEnabled(false)
            sp.setLineColor(0xFFC9A84C.toInt())
            val doubles = ArrayList<Double>(vals.size)
            for (v in vals) doubles.add(v.toDouble())
            sp.setData(doubles)
        }
    }

    // ── Data observers ──────────────────────────────────────────────────────

    /** Any relevant series arriving refreshes every slot + the hero — each one
     *  independently re-resolves its own indicator against the latest snapshot. */
    private fun observeData() {
        viewModel.getTreasuryData().observe(viewLifecycleOwner) { data -> if (data != null) onDataTick() }
        viewModel.getFedFundsData().observe(viewLifecycleOwner) { data -> if (data != null) onDataTick() }
        viewModel.getFedFundsDailyData().observe(viewLifecycleOwner) { data -> if (data != null) onDataTick() }
        viewModel.getEmploymentData().observe(viewLifecycleOwner) { data -> if (data != null) onDataTick() }
        viewModel.getCpiData().observe(viewLifecycleOwner) { data -> if (data != null) onDataTick() }
        viewModel.getGdpData().observe(viewLifecycleOwner) { data -> if (data != null) onDataTick() }
        viewModel.getMbsMortgageData().observe(viewLifecycleOwner) { data -> if (data != null) onDataTick() }
        viewModel.getVixData().observe(viewLifecycleOwner) { data -> if (data != null) onDataTick() }
        viewModel.getPceData().observe(viewLifecycleOwner) { data -> if (data != null) onDataTick() }
        viewModel.getWageData().observe(viewLifecycleOwner) { data -> if (data != null) onDataTick() }
        viewModel.getHousingData().observe(viewLifecycleOwner) { data -> if (data != null) onDataTick() }
        viewModel.getSp500Data().observe(viewLifecycleOwner) { data -> if (data != null) onDataTick() }
        viewModel.getNasdaqData().observe(viewLifecycleOwner) { data -> if (data != null) onDataTick() }
    }

    private fun onDataTick() {
        hideSkeleton()
        refreshAllSlots()
        refreshHero()
    }

    // ── Slot / hero rendering (design branch 2a) ────────────────────────────

    private fun refreshAllSlots() {
        for (i in slotCards.indices) refreshSlot(i)
    }

    private fun refreshSlot(index: Int) {
        val card = slotCards[index]
        val id = slotIndicatorIds[index]
        val meta = DashboardIndicatorCatalog.byId(id)
        setupCardLabel(card, meta?.label ?: "—", labelLines = if ((meta?.label?.length ?: 0) > 12) 2 else 1)
        AskAnalyst.wireCardLongPress(card, requireActivity(), meta?.label ?: id)

        // Tap → status/benchmark detail, for every indicator with an authored
        // MetricPopupRegistry spec (mockup 1c). Everything else has no popup
        // content yet, so the card just shows its data with no tap action.
        if (MetricPopupRegistry.byId(id) != null) {
            card.setOnClickListener { v ->
                v.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                val ctx = context ?: return@setOnClickListener
                MetricPopupController.show(ctx, viewModel, id)
            }
        } else {
            card.setOnClickListener(null)
        }

        val display = DashboardIndicatorCatalog.resolve(id, viewModel)
        val statusBadge: View? = card.findViewById(R.id.statusBadge)
        if (display == null) {
            // No data yet — leave the skeleton pulse running.
            statusBadge?.visibility = View.GONE
            return
        }
        setCardValue(card, display.value)
        setCardDate(card, display.date)
        applyDelta(card, display.delta, display.deltaFmt, display.goodWhenDown)
        setSparkline(card, display.spark)
        if (display.tier != null) {
            statusBadge?.visibility = View.VISIBLE
            BenchmarkStyle.applyBadge(card, requireContext(), display.tier)
        } else {
            statusBadge?.visibility = View.GONE
        }
    }

    private fun refreshHero() {
        val display = DashboardIndicatorCatalog.resolve(heroIndicatorId, viewModel) ?: return
        val heroValue = tvFedFundsHeroValue
        if (heroValue != null) {
            // TICKET-27: roll the hero value up/down on refresh.
            val heroOld = if (heroValue.text != null) heroValue.text.toString() else ""
            ValueAnimatorUtil.animateOrSet(heroValue, heroOld, display.value)
        }
        val change = changeFedFunds
        val ctx = context
        if (change != null && ctx != null) {
            val delta = display.delta
            if (delta != null && Math.abs(delta) > 1e-9) {
                change.setTextColor(DeltaFormatter.color(ctx, delta, display.goodWhenDown))
                change.text = DeltaFormatter.format(delta, display.deltaFmt)
                change.visibility = View.VISIBLE
            } else {
                change.visibility = View.GONE
            }
        }
        val cycle = tvFedFundsHeroCycle
        if (cycle != null) {
            if (heroIndicatorId == "fedfunds") {
                val delta = display.delta ?: 0.0
                if (delta < -0.01) cycle.text = "Cut cycle ongoing"
                else if (delta > 0.01) cycle.text = "Hike cycle ongoing"
                else cycle.text = "Rates on hold"
                cycle.visibility = View.VISIBLE
            } else {
                // The cut/hike narration only makes sense for the policy rate —
                // any other indicator in the hero slot just shows its date.
                cycle.visibility = View.GONE
            }
        }
        val heroDate = tvFedFundsHeroDate
        if (heroDate != null && heroIndicatorId != "fedfunds") {
            heroDate.text = display.date
            heroDate.visibility = View.VISIBLE
        } else if (heroIndicatorId == "fedfunds") {
            updateNextFomcLabel()
        }
    }

    /** Next scheduled FOMC meeting from the published calendar — only relevant
     *  when the hero is actually showing the Fed Funds rate. */
    private fun updateNextFomcLabel() {
        val heroDate = tvFedFundsHeroDate ?: return
        val now = Calendar.getInstance()
        val year = now.get(Calendar.YEAR)
        if (year < 2026) { heroDate.text = "Next FOMC: Jan 27–28"; heroDate.visibility = View.VISIBLE; return }
        if (year == 2026) {
            val month = now.get(Calendar.MONTH) + 1
            val day = now.get(Calendar.DAY_OF_MONTH)
            for (m in FOMC_2026) {
                if (m[0] > month || (m[0] == month && m[2] >= day)) {
                    heroDate.text = String.format(Locale.US,
                        "Next FOMC: %s %d–%d", MONTH_ABBR[m[0] - 1], m[1], m[2])
                    heroDate.visibility = View.VISIBLE
                    return
                }
            }
        }
        // Past Dec 2026 — schedule not embedded; hide rather than show stale info
        heroDate.visibility = View.GONE
    }

    override fun onResume() { super.onResume() }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }

    companion object {
        /** One Edit Cards / Overview cluster: its display title, which
         *  [DashboardIndicatorCatalog] groups a slot in it may be swapped to
         *  (direction 1d's category-scoped swap), and its fixed physical slot
         *  indices (into [slotCards]/[slotIndicatorIds] — always [0,1], [2,3]
         *  or [4,5]; reordering within a category changes which indicator id
         *  occupies which index, never the indices themselves). */
        private class SlotCategory(
            @JvmField val key: String, @JvmField val title: String,
            @JvmField val catalogGroups: List<String>, @JvmField val slotIndices: IntArray
        )

        private val SLOT_CATEGORIES = listOf(
            SlotCategory("growth", "GROWTH & RATES", listOf(DashboardIndicatorCatalog.GROUP_GROWTH), intArrayOf(0, 1)),
            SlotCategory("inflation", "INFLATION & LABOR", listOf(DashboardIndicatorCatalog.GROUP_INFLATION), intArrayOf(2, 3)),
            SlotCategory(
                "housing_markets", "HOUSING & MARKETS",
                listOf(DashboardIndicatorCatalog.GROUP_HOUSING, DashboardIndicatorCatalog.GROUP_MARKETS), intArrayOf(4, 5)
            )
        )

        /** 2026 FOMC meetings: {month(1-12), startDay, endDay} — federalreserve.gov. */
        private val FOMC_2026 = arrayOf(
            intArrayOf(1, 27, 28), intArrayOf(3, 17, 18), intArrayOf(4, 28, 29), intArrayOf(6, 16, 17),
            intArrayOf(7, 28, 29), intArrayOf(9, 15, 16), intArrayOf(10, 27, 28), intArrayOf(12, 8, 9)
        )
        private val MONTH_ABBR = arrayOf(
            "Jan", "Feb", "Mar", "Apr", "May", "Jun",
            "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
        )

        /** Maps a customizable-dashboard indicator id to the CACHE_ key its data
         *  arrives under, for retry-chip wiring. Indicators without a cache key
         *  (corepce/wages/housing/sp500/nasdaq) never show a retry chip today —
         *  those series don't yet report per-series refresh failures.
         *  Note: the map holds ONE key per indicator, so a fedfunds GRID slot's
         *  chip only covers the daily series; the monthly CACHE_FED_FUNDS
         *  failure is covered by the hero chip (review finding 8). */
        private val INDICATOR_CACHE_KEY = mapOf(
            "fedfunds" to EconomicViewModel.CACHE_FED_FUNDS_DAILY,
            "gdp" to EconomicViewModel.CACHE_GDP,
            "cpi" to EconomicViewModel.CACHE_CPI,
            "unemployment" to EconomicViewModel.CACHE_EMPLOYMENT,
            "mortgage" to EconomicViewModel.CACHE_MBS,
            "vix" to EconomicViewModel.CACHE_VIX,
            "yield10y" to EconomicViewModel.CACHE_TREASURY,
            "spread" to EconomicViewModel.CACHE_TREASURY
        )

        /** Watchlist keys (SettingsManager.DEFAULT_WATCHLIST) → catalog ids, where they differ. */
        private val WATCHLIST_ALIAS = mapOf(
            "spread_10y3m" to "spread",
            "employment" to "unemployment",
            "mbs_mortgage" to "mortgage"
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
    }
}
