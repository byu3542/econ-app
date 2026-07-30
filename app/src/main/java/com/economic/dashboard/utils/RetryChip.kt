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
     * ViewModel's failed-series set, wiring the tap to retry that series;
     * hides it otherwise.
     *
     * Screens whose layout has no `tvRetry` are a no-op.
     *
     * [keys] order is significant: when several of a screen's series have
     * failed at once, the chip retries the first one listed here. That matches
     * the behaviour of the per-fragment loops this replaced — iterate the
     * screen's declared keys and take the first hit, rather than iterating the
     * failed set, which is unordered.
     */
    fun wire(
        fragment: Fragment, view: View,
        viewModel: EconomicViewModel, vararg keys: String
    ) {
        val chip: TextView = view.findViewById(R.id.tvRetry) ?: return
        viewModel.getFailedSeries().observe(fragment.viewLifecycleOwner) { failed ->
            val hit = if (failed == null) null else keys.firstOrNull { failed.contains(it) }
            if (hit != null) {
                chip.setOnClickListener { viewModel.retrySeries(hit) }
                chip.visibility = View.VISIBLE
            } else {
                chip.visibility = View.GONE
            }
        }
    }
}
