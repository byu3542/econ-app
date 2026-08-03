package com.economic.dashboard.utils

import android.view.View
import android.widget.TextView

import androidx.fragment.app.Fragment

import com.economic.dashboard.R
import com.economic.dashboard.ui.EconomicViewModel

/**
 * TICKET-18: the per-screen retry chip shown when one of a screen's series
 * fails to fetch.
 *
 * Extracted in kotlin batch 12c from ten chart fragments that each carried a
 * byte-identical 14-line copy of this block, differing only in which series
 * keys they watched.
 */
object RetryChip {

    /**
     * Shows `R.id.tvRetry` in [view] whenever any of [keys] is in the
     * ViewModel's failed-series set, wiring the tap to retry *every* failed
     * series on the screen; hides it otherwise.
     *
     * Screens whose layout has no `tvRetry` are a no-op.
     *
     * This used to retry only `keys.firstOrNull { failed.contains(it) }`. On a
     * screen watching several series that fail together — Inflation watches
     * CPI, PCE and wages — the tap re-fetched whichever key happened to be
     * listed first and silently left the others broken, so the card the user
     * was actually looking at never came back no matter how often they tapped.
     */
    fun wire(
        fragment: Fragment, view: View,
        viewModel: EconomicViewModel, vararg keys: String
    ) {
        val chip: TextView = view.findViewById(R.id.tvRetry) ?: return
        viewModel.getFailedSeries().observe(fragment.viewLifecycleOwner) { failed ->
            val hits = if (failed == null) emptyList() else keys.filter { failed.contains(it) }
            if (hits.isNotEmpty()) {
                chip.setOnClickListener { for (k in hits) viewModel.retrySeries(k) }
                chip.visibility = View.VISIBLE
            } else {
                chip.visibility = View.GONE
            }
        }
    }
}
