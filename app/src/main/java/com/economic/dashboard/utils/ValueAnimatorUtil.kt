package com.economic.dashboard.utils

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.widget.TextView
import java.util.Locale
import java.util.regex.Pattern

/**
 * TICKET-27 — short count-up/down animation for KPI value TextViews.
 *
 * When a fresh value replaces a cached one, the number rolls to its new value
 * instead of hard-swapping, so the "values updated" moment is felt. Works
 * directly off the formatted strings already produced by [NumberFormatUtil]
 * (e.g. "4.33%", "$34,567.80", "18.4 pt", "5,900", "3.90M"): it splits out the
 * numeric core, animates it, and re-attaches the prefix/suffix each frame.
 *
 * Guards (per the ticket + TICKET-28):
 *  - unchanged value  → set instantly (no animation)
 *  - non-numeric text ("—", first paint) → set instantly
 *  - unit/format change → set instantly
 *  - motion disabled ([MotionUtil]) → set instantly
 * Duration is capped at ~350ms to respect the Doherty threshold.
 */
object ValueAnimatorUtil {

    private const val DURATION_MS = 350L
    // First signed, comma-grouped decimal run in the string.
    private val NUMBER: Pattern = Pattern.compile("[-+]?\\d[\\d,]*\\.?\\d*")

    /**
     * Animates [tv] from its current text to [newText], or sets it
     * instantly when animation isn't appropriate (see class guards).
     */
    @JvmStatic
    fun animateOrSet(tv: TextView?, oldText: String?, newText: String?) {
        if (tv == null || newText == null) return
        NumberFormatUtil.applyTabularFigures(tv) // IOS-03: no horizontal jitter mid-roll
        val ctx = tv.context

        val mNew = NUMBER.matcher(newText)
        if (oldText == null || !mNew.find()) {
            tv.text = newText
            return
        }
        val mOld = NUMBER.matcher(oldText)
        if (!mOld.find() || !MotionUtil.animationsEnabled(ctx)) {
            tv.text = newText
            return
        }

        val prefix = newText.substring(0, mNew.start())
        val suffix = newText.substring(mNew.end())
        val oldPrefix = oldText.substring(0, mOld.start())
        val oldSuffix = oldText.substring(mOld.end())

        val from = parse(mOld.group())
        val to = parse(mNew.group())
        // Bail to an instant set if the number won't parse, the unit/format
        // changed, or the value is effectively unchanged.
        if (from == null || to == null
            || prefix != oldPrefix || suffix != oldSuffix
            || Math.abs(from - to) < 1e-9) {
            tv.text = newText
            return
        }

        val decimals = decimalsOf(mNew.group())
        val anim = ValueAnimator.ofFloat(from.toFloat(), to.toFloat())
        anim.duration = DURATION_MS
        anim.addUpdateListener { a ->
            val v = (a.animatedValue as Number).toDouble()
            tv.text = prefix + String.format(Locale.US, "%,.${decimals}f", v) + suffix
        }
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(a: Animator) {
                tv.text = newText // guarantee the exact final formatting
            }
        })
        anim.start()
    }

    private fun parse(numeric: String?): Double? {
        if (numeric == null) return null
        return try {
            numeric.replace(",", "").toDouble()
        } catch (e: NumberFormatException) {
            null
        }
    }

    private fun decimalsOf(numeric: String): Int {
        val dot = numeric.indexOf('.')
        return if (dot < 0) 0 else numeric.length - dot - 1
    }
}
