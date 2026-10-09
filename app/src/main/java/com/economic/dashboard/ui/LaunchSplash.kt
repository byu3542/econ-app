package com.economic.dashboard.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.doOnLayout
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.interpolator.view.animation.LinearOutSlowInInterpolator

import com.economic.dashboard.R

/**
 * Animated cold-start splash: the "Econom" wordmark with its double "ii"
 * drawn as two gold bar-chart columns that rise, plus three pulsing dots.
 *
 * The system splash (Theme.EconomicDashboard.Starting) is plain navy with no
 * icon, so this overlay picks up on the same ground without a visible seam.
 * It sits on top of the activity's content for [HOLD_MS], then fades out and
 * removes itself. All timing runs through ValueAnimator, so it collapses to
 * an instant fade when the user has turned animations off.
 */
object LaunchSplash {

    private const val BAR_RISE_MS = 900L
    private const val BAR_STAGGER_MS = 150L
    private const val HOLD_MS = 1400L
    private const val FADE_MS = 280L

    fun show(activity: Activity) {
        val root = activity.window.decorView as ViewGroup
        val overlay = LayoutInflater.from(activity)
            .inflate(R.layout.view_launch_splash, root, false)
        root.addView(overlay)

        val word = overlay.findViewById<TextView>(R.id.splash_word)
        val bars = overlay.findViewById<LinearLayout>(R.id.splash_bars)
        val short = overlay.findViewById<View>(R.id.splash_bar_short)
        val tall = overlay.findViewById<View>(R.id.splash_bar_tall)
        val dots = overlay.findViewById<LinearLayout>(R.id.splash_dots)

        // Sit the bars on the text baseline instead of the descender line, so
        // they read as the letters "ii" rather than floating below "m".
        bars.setPadding(0, 0, 0, word.paint.fontMetricsInt.descent)

        listOf(short, tall).forEachIndexed { i, bar ->
            bar.doOnLayout {
                bar.pivotY = bar.height.toFloat()
                bar.scaleY = 0.15f
                bar.animate()
                    .scaleY(1f)
                    .setStartDelay(300L + i * BAR_STAGGER_MS)
                    .setDuration(BAR_RISE_MS)
                    .setInterpolator(LinearOutSlowInInterpolator())
                    .start()
            }
        }

        val pulses = (0 until dots.childCount).map { i ->
            ObjectAnimator.ofFloat(dots.getChildAt(i), View.ALPHA, 0.3f, 1f).apply {
                duration = 600L
                startDelay = i * 200L
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        }

        overlay.postDelayed({
            overlay.animate()
                .alpha(0f)
                .setDuration(FADE_MS)
                .setInterpolator(FastOutSlowInInterpolator())
                .withEndAction {
                    pulses.forEach { it.cancel() }
                    root.removeView(overlay)
                }
                .start()
        }, HOLD_MS)
    }
}
