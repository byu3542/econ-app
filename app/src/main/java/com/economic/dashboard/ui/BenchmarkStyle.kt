package com.economic.dashboard.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.TextView

import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils

import com.economic.dashboard.R
import com.economic.dashboard.utils.SettingsManager
import com.google.android.material.card.MaterialCardView

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

    /**
     * TICKET-59: the one badge word a tier owns. Six colours, six words, fixed
     * app-wide, so the dot and the word never disagree about severity.
     */
    @JvmStatic
    fun tierWord(context: Context, tier: Tier): String = context.getString(when (tier) {
        Tier.GOOD -> R.string.tier_word_good
        Tier.WATCH -> R.string.tier_word_watch
        Tier.ELEVATED -> R.string.tier_word_elevated
        Tier.ALERT -> R.string.tier_word_alert
        Tier.ALERT_DEEP -> R.string.tier_word_alert_deep
        Tier.IMBALANCE -> R.string.tier_word_imbalance
    })

    /**
     * The metric-specific reading for the badge's detail line ("Recovering",
     * "Losing ground"), or null when [Benchmarks.Result.word] is already the
     * tier word (every HEALTHY badge) — nothing to add.
     */
    @JvmStatic
    fun detailWord(context: Context, r: Benchmarks.Result): String? {
        val w = r.word.trim()
        if (w.isEmpty() || w.equals(tierWord(context, r.tier), ignoreCase = true)) return null
        return w.substring(0, 1).uppercase() + w.substring(1).lowercase()
    }

    /**
     * Pantrii port U10 (KTD10 rule 2): the leading glyph a tier owns. Paired
     * with [tierWord], so severity reads from shape and word even where two
     * tier colours (or WATCH and the gold accent) are close.
     */
    @JvmStatic
    fun tierGlyph(context: Context, tier: Tier): String = context.getString(when (tier) {
        Tier.GOOD -> R.string.tier_glyph_good
        Tier.WATCH -> R.string.tier_glyph_watch
        Tier.ELEVATED -> R.string.tier_glyph_elevated
        Tier.ALERT -> R.string.tier_glyph_alert
        Tier.ALERT_DEEP -> R.string.tier_glyph_alert_deep
        Tier.IMBALANCE -> R.string.tier_glyph_imbalance
    })

    /** Dark ink or white, whichever reads better on the tier disc [disc]. */
    private fun glyphInkOn(context: Context, @ColorInt disc: Int): Int {
        val ink = ContextCompat.getColor(context, R.color.badge_glyph_dark)
        return if (ColorUtils.calculateContrast(ink, disc) >= ColorUtils.calculateContrast(Color.WHITE, disc)) ink
        else Color.WHITE
    }

    /** What TalkBack says for a badge: "WATCH, recovering" or just "HEALTHY". */
    @JvmStatic
    fun spoken(context: Context, r: Benchmarks.Result): String {
        val d = detailWord(context, r) ?: return tierWord(context, r.tier)
        return tierWord(context, r.tier) + ", " + d.lowercase()
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
        val dot: View? = card.findViewById(dotId)
        tintDot(dot, context, r.tier)
        // U10: a row with a glyph slot (view_status_badge) leads with the glyph
        // instead of the dot; the dot stays the loading placeholder.
        val glyph: TextView? = card.findViewById(R.id.tvTierGlyph)
        if (glyph != null && context != null) {
            val tierColor = colorFor(context, r.tier)
            glyph.text = tierGlyph(context, r.tier)
            glyph.setTextColor(glyphInkOn(context, tierColor))
            tintDot(glyph, context, r.tier)
            glyph.visibility = View.VISIBLE
            dot?.visibility = View.GONE
        }
        val tv: TextView? = card.findViewById(statusTextId)
        // TICKET-59: the coloured word is the tier word; the metric-specific
        // reading goes to the detail line when the row (view_status_badge) has one.
        if (tv != null) tv.text = if (context != null) tierWord(context, r.tier) else r.word
        val detail: TextView? = card.findViewById(R.id.tvBadgeDetail)
        if (detail != null) {
            val d = if (context != null) detailWord(context, r) else null
            detail.text = d ?: ""
            detail.visibility = if (d == null) View.GONE else View.VISIBLE
        }
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
     * Marks the card that drives the chart below it (R3-TICKET-01). Pantrii
     * port U10: the card's own stroke turns accent and one step heavier, the
     * same accent hairline a selected chip carries; otherwise it is the plain
     * `line` hairline of @style/Widget.Econ.Card.
     *
     * It used to hang a gold [GradientDrawable] on `card.foreground`, sized to
     * the card radius. MaterialCardView draws its stroke in that foreground, so
     * the old way would erase the hairline on every card it cleared.
     */
    @JvmStatic
    fun setCardActive(card: MaterialCardView, active: Boolean) {
        val res = card.resources
        card.strokeColor = ContextCompat.getColor(card.context, if (active) R.color.accent else R.color.line)
        card.strokeWidth = res.getDimensionPixelSize(if (active) R.dimen.stroke_card_active else R.dimen.stroke_card)
    }
}
