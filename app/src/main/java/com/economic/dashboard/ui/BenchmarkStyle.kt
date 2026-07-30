package com.economic.dashboard.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.view.View
import android.widget.TextView

import androidx.annotation.DimenRes
import androidx.core.content.ContextCompat

import com.economic.dashboard.R
import com.economic.dashboard.utils.SettingsManager

/**
 * Single source of truth for how a status/benchmark badge is coloured.
 *
 * **The ordered severity scale (TICKET-02).** Every badge in the app maps to exactly one
 * [Tier], and each tier owns exactly one colour meaning app-wide:
 *
 * ```
 *   GOOD        green   (#6FA97A)  healthy / on-target / calm  -- the ONLY "good" tier
 *   WATCH       yellow  (#DCC873)  mild concern, either direction
 *   ELEVATED    orange  (#D98E4F)  elevated concern
 *   ALERT       red     (#C75B4E)  critical
 *   ALERT_DEEP  d-red   (#8E3B32)  most severe
 *   IMBALANCE   purple  (#8A6E9E)  "too much of a good thing" -- GDP overheating,
 *                                  extreme curve steepness. NEVER reused for "best".
 * ```
 *
 * Blue is intentionally NOT a tier: it previously meant both "deflation risk" (bad) and
 * "very strong" (better-than-green), so it was dropped. Green never means both "best" and
 * "middle" -- growth metrics collapse STRONG/VERY STRONG onto `GOOD`.
 *
 * Colours are read from `colors.xml` via resources (never `Color.parseColor`),
 * so badges re-theme under `values-night` and can swap to a colourblind-safe set.
 */
object BenchmarkStyle {

    enum class Tier { GOOD, WATCH, ELEVATED, ALERT, ALERT_DEEP, IMBALANCE }

    /** @return the ARGB colour for `tier`, honouring colourblind mode. */
    @JvmStatic
    fun colorFor(context: Context, tier: Tier): Int {
        val cb = SettingsManager.getBool(context, SettingsManager.KEY_COLORBLIND, false)
        val res = when (tier) {
            Tier.GOOD -> if (cb) R.color.signal_good_cb else R.color.signal_good
            Tier.WATCH -> if (cb) R.color.signal_watch_cb else R.color.signal_watch
            Tier.ELEVATED -> if (cb) R.color.signal_elevated_cb else R.color.signal_elevated
            Tier.ALERT -> if (cb) R.color.signal_alert_cb else R.color.signal_alert
            Tier.ALERT_DEEP -> if (cb) R.color.signal_alert_deep_cb else R.color.signal_alert_deep
            Tier.IMBALANCE -> if (cb) R.color.signal_imbalance_cb else R.color.signal_imbalance
        }
        return ContextCompat.getColor(context, res)
    }

    /** Render a status dot as a filled oval in the tier colour. Centralises dot drawing. */
    @JvmStatic
    fun tintDot(dot: View?, context: Context?, tier: Tier) {
        if (dot == null || context == null) return
        val gd = GradientDrawable()
        gd.shape = GradientDrawable.OVAL
        gd.setColor(colorFor(context, tier))
        dot.background = gd
    }

    /**
     * Apply a benchmark result to a status row: colour the dot and set the status word.
     * The dot view + status TextView are looked up by the given ids on `card`.
     */
    @JvmStatic
    fun apply(card: View?, context: Context?, r: Benchmarks.Result?, dotId: Int, statusTextId: Int) {
        if (card == null || r == null) return
        tintDot(card.findViewById(dotId), context, r.tier)
        val tv: TextView? = card.findViewById(statusTextId)
        if (tv != null) tv.text = r.word
    }

    /**
     * Apply a benchmark result to an included `layout/view_status_badge` row
     * (R2-TICKET-03). This is the only call a screen needs to fill a badge:
     *
     * ```
     *   BenchmarkStyle.applyBadge(binding.statusUnemp.root, requireContext(), result)
     * ```
     *
     * The word's colour, size, weight, caps and tracking come from
     * `@style/Badge.Status` in the layout — never set them here. Five screens
     * used to call `setTextColor(R.color.text_slate)` right after setting the
     * word, which is how the layout and the shipped pixel ended up disagreeing.
     *
     * @param badgeRow the root of an included `view_status_badge`, or any
     *                 ancestor of one — `findViewById` searches descendants.
     */
    @JvmStatic
    fun applyBadge(badgeRow: View?, context: Context?, r: Benchmarks.Result?) {
        apply(badgeRow, context, r, R.id.viewTierDot, R.id.tvTierStatus)
    }

    /**
     * Gold "this card drives the chart" outline (R3-TICKET-01), sized to the card's own
     * corner radius so the arc stays concentric with the CardView clip.
     *
     * Hang it on a selected card with
     * `card.foreground = BenchmarkStyle.activeCardBorder(ctx, R.dimen.radius_card_kpi)`
     * and clear it with `foreground = null`.
     *
     * **Pass the card's radius dimen; never hardcode it.** This method replaces five
     * copy-pasted `makeActiveBorder()` privates that each baked in `16f * density`.
     * When the KPI cards moved to `@dimen/radius_card_kpi` (12dp) only one copy was
     * updated, so the other four drew a 16dp arc inside a 12dp clip and leaked navy card fill
     * at the corners — see `TICKET_2026-07-24_selector_border_radius.md`.
     *
     * The [InsetDrawable] compensates for [GradientDrawable] centering its
     * stroke on the shape edge, which is what keeps the straight edges flush. That inset is
     * correct — only the radius term was ever wrong.
     *
     * @param ctx         any context able to resolve app resources
     * @param radiusDimen the `cardCornerRadius` dimen the target card uses
     */
    @JvmStatic
    fun activeCardBorder(ctx: Context, @DimenRes radiusDimen: Int): Drawable {
        val density = ctx.resources.displayMetrics.density
        val strokePx = Math.max(2, Math.round(1.5f * density))
        val insetPx = Math.ceil((strokePx / 2f).toDouble()).toInt()
        val radiusPx = ctx.resources.getDimension(radiusDimen)
        val gd = GradientDrawable()
        gd.setColor(Color.TRANSPARENT)
        gd.cornerRadius = radiusPx - insetPx
        gd.setStroke(strokePx, ContextCompat.getColor(ctx, R.color.accent_gold))
        return InsetDrawable(gd, insetPx)
    }
}
