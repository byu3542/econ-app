package com.economic.dashboard.ui.onboarding

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.TextView

import com.economic.dashboard.R
import com.economic.dashboard.utils.SettingsManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

import java.util.Locale

/**
 * TICKET-25: first-run "pick the metrics you care about" sheet (Goal-Gradient).
 *
 * The order the user taps checkboxes in becomes the watchlist order, which the
 * Overview uses to rank its metric clusters (most-cared-for first). A progress
 * bar fills as metrics are picked. Skippable — skipping keeps the default
 * watchlist so the app works with zero configuration. Shown once, guarded by
 * [SettingsManager.isOnboardingComplete].
 */
class WatchlistSetupFragment : BottomSheetDialogFragment() {

    /**
     * Optional callback so the host can re-apply the Overview order on finish.
     *
     * Declared `fun interface` deliberately. DashboardFragment currently supplies
     * a *Java* lambda, which SAM-converts to any single-method interface — but
     * that file becomes Kotlin in batch 10c, and a Kotlin lambda will NOT convert
     * to a plain Kotlin interface. Without `fun` here, 10a builds green and 10c
     * breaks. (Same trap as AnalystToolExecutor.LiveSeriesProvider in batch 9b.)
     */
    fun interface Listener {
        fun onWatchlistSetupFinished()
    }

    private var listener: Listener? = null

    /** Metric keys in the order the user selected them. */
    private val selectionOrder = ArrayList<String>()

    fun setListener(l: Listener?) {
        this.listener = l
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_watchlist_setup, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val pb: ProgressBar? = view.findViewById(R.id.pbWatchlist)
        val tvProgress: TextView? = view.findViewById(R.id.tvWatchlistProgress)

        wireCheckbox(view, R.id.cbGdp, "gdp", pb, tvProgress)
        wireCheckbox(view, R.id.cbSpread, "spread_10y3m", pb, tvProgress)
        wireCheckbox(view, R.id.cbCpi, "cpi", pb, tvProgress)
        wireCheckbox(view, R.id.cbUnemployment, "employment", pb, tvProgress)
        wireCheckbox(view, R.id.cbMortgage, "mbs_mortgage", pb, tvProgress)
        wireCheckbox(view, R.id.cbVix, "vix", pb, tvProgress)

        val btnSkip: View? = view.findViewById(R.id.btnWatchlistSkip)
        val btnDone: View? = view.findViewById(R.id.btnWatchlistDone)

        btnSkip?.setOnClickListener { finish(false) }
        btnDone?.setOnClickListener { finish(true) }
    }

    private fun wireCheckbox(
        root: View, id: Int, key: String,
        pb: ProgressBar?, tvProgress: TextView?
    ) {
        val cb = root.findViewById<CheckBox>(id) ?: return
        cb.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                if (!selectionOrder.contains(key)) selectionOrder.add(key)
            } else {
                selectionOrder.remove(key)
            }
            pb?.progress = selectionOrder.size
            tvProgress?.text = String.format(Locale.US, "%d of 6", selectionOrder.size)
        }
    }

    /** @param save true = persist the picked order; false = skip (keep defaults). */
    private fun finish(save: Boolean) {
        val ctx = context
        if (ctx != null) {
            if (save && !selectionOrder.isEmpty()) {
                // Picked metrics first (in tap order), then the remaining
                // defaults so every metric still has a stable rank.
                val full = ArrayList(selectionOrder)
                for (k in SettingsManager.DEFAULT_WATCHLIST)
                    if (!full.contains(k)) full.add(k)
                SettingsManager.setWatchlist(ctx, full)
            }
            SettingsManager.setOnboardingComplete(ctx)
        }
        listener?.onWatchlistSetupFinished()
        dismissAllowingStateLoss()
    }

    companion object {
        const val TAG = "WatchlistSetupFragment"
    }
}
