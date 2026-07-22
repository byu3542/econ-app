package com.economic.dashboard.utils;

import android.animation.ValueAnimator;
import android.content.Context;
import android.widget.TextView;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TICKET-27 — short count-up/down animation for KPI value TextViews.
 *
 * When a fresh value replaces a cached one, the number rolls to its new value
 * instead of hard-swapping, so the "values updated" moment is felt. Works
 * directly off the formatted strings already produced by {@link NumberFormatUtil}
 * (e.g. "4.33%", "$34,567.80", "18.4 pt", "5,900", "3.90M"): it splits out the
 * numeric core, animates it, and re-attaches the prefix/suffix each frame.
 *
 * Guards (per the ticket + TICKET-28):
 *  - unchanged value  → set instantly (no animation)
 *  - non-numeric text ("—", first paint) → set instantly
 *  - unit/format change → set instantly
 *  - motion disabled ({@link MotionUtil}) → set instantly
 * Duration is capped at ~350ms to respect the Doherty threshold.
 */
public final class ValueAnimatorUtil {

    private ValueAnimatorUtil() {}

    private static final long DURATION_MS = 350L;
    // First signed, comma-grouped decimal run in the string.
    private static final Pattern NUMBER = Pattern.compile("[-+]?\\d[\\d,]*\\.?\\d*");

    /**
     * Animates {@code tv} from its current text to {@code newText}, or sets it
     * instantly when animation isn't appropriate (see class guards).
     */
    public static void animateOrSet(TextView tv, String oldText, String newText) {
        if (tv == null || newText == null) return;
        Context ctx = tv.getContext();

        Matcher mNew = NUMBER.matcher(newText);
        Matcher mOld = oldText != null ? NUMBER.matcher(oldText) : null;
        if (!mNew.find() || mOld == null || !mOld.find() || !MotionUtil.animationsEnabled(ctx)) {
            tv.setText(newText);
            return;
        }

        String prefix = newText.substring(0, mNew.start());
        String suffix = newText.substring(mNew.end());
        String oldPrefix = oldText.substring(0, mOld.start());
        String oldSuffix = oldText.substring(mOld.end());

        Double from = parse(mOld.group());
        Double to   = parse(mNew.group());
        // Bail to an instant set if the number won't parse, the unit/format
        // changed, or the value is effectively unchanged.
        if (from == null || to == null
                || !prefix.equals(oldPrefix) || !suffix.equals(oldSuffix)
                || Math.abs(from - to) < 1e-9) {
            tv.setText(newText);
            return;
        }

        final int decimals = decimalsOf(mNew.group());
        final String pre = prefix, suf = suffix;
        ValueAnimator anim = ValueAnimator.ofFloat(from.floatValue(), to.floatValue());
        anim.setDuration(DURATION_MS);
        anim.addUpdateListener(a -> {
            double v = ((Number) a.getAnimatedValue()).doubleValue();
            tv.setText(pre + String.format(Locale.US, "%,." + decimals + "f", v) + suf);
        });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                tv.setText(newText); // guarantee the exact final formatting
            }
        });
        anim.start();
    }

    private static Double parse(String numeric) {
        if (numeric == null) return null;
        try {
            return Double.parseDouble(numeric.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int decimalsOf(String numeric) {
        int dot = numeric.indexOf('.');
        return dot < 0 ? 0 : (numeric.length() - dot - 1);
    }
}
