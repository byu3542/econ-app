package com.economic.dashboard.utils

import android.content.Context
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import com.economic.dashboard.R
import com.economic.dashboard.ui.BenchmarkStyle

/**
 * TICKET-57 — the chart colour sheet. Every series line, axis label and
 * benchmark line on a chart resolves here, from tokens in `colors.xml`,
 * never from a `Color.parseColor("#…")` literal in a fragment.
 *
 * Five ordered series colours ([series]) plus named accessors for the
 * charts that assign meaning to a slot (Housing's MBS chart: bank = gold,
 * Fed = slate, mortgage = coral). Single-series panels draw in [primary]
 * (`info_blue`): it sits in the palette without competing with the gold
 * accent the cards already spend, and it is the colour those charts were
 * already using by hand. Benchmark lines take their colour from the
 * benchmark tier through [BenchmarkStyle.colorFor], so they follow the
 * colourblind-safe palette switch like the badges do.
 */
object ChartPalette {

    private val SERIES = intArrayOf(
        R.color.accent_gold,
        R.color.info_blue,
        R.color.signal_alert_soft,
        R.color.accent_purple,
        R.color.slate_soft
    )

    /** Ordered series colour; wraps past the fifth. */
    @JvmStatic
    @ColorInt
    fun series(ctx: Context, index: Int): Int =
        ContextCompat.getColor(ctx, SERIES[Math.floorMod(index, SERIES.size)])

    /** The one colour a single-series panel chart draws in. */
    @JvmStatic
    @ColorInt
    fun primary(ctx: Context): Int = ContextCompat.getColor(ctx, R.color.info_blue)

    @JvmStatic @ColorInt fun gold(ctx: Context): Int = ContextCompat.getColor(ctx, R.color.accent_gold)
    @JvmStatic @ColorInt fun blue(ctx: Context): Int = ContextCompat.getColor(ctx, R.color.info_blue)
    @JvmStatic @ColorInt fun coral(ctx: Context): Int = ContextCompat.getColor(ctx, R.color.signal_alert_soft)
    @JvmStatic @ColorInt fun purple(ctx: Context): Int = ContextCompat.getColor(ctx, R.color.accent_purple)
    @JvmStatic @ColorInt fun slate(ctx: Context): Int = ContextCompat.getColor(ctx, R.color.slate_soft)

    /** Dimmer slate for x-axis labels and "no data" text on navy panels. */
    @JvmStatic @ColorInt fun slateDim(ctx: Context): Int = ContextCompat.getColor(ctx, R.color.slate_dim)

    /** Benchmark (LimitLine) colour for a tier — colourblind-aware. */
    @JvmStatic
    @ColorInt
    fun limit(ctx: Context, tier: BenchmarkStyle.Tier): Int = BenchmarkStyle.colorFor(ctx, tier)

    /** The neutral "normal range" benchmark line, which has no tier. */
    @JvmStatic
    @ColorInt
    fun limitNormal(ctx: Context): Int = blue(ctx)
}
