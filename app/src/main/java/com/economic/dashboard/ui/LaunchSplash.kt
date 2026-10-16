package com.economic.dashboard.ui

import android.app.Activity
import android.graphics.drawable.Animatable
import android.os.Build
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import androidx.interpolator.view.animation.FastOutSlowInInterpolator

import com.economic.dashboard.R

/**
 * Animated cold-start splash: the "Econom" wordmark with its double "ii"
 * drawn as two gold bar-chart columns that rise, plus three pulsing dots.
 *
 * API 31+: the system splash (Theme.EconomicDashboard.Starting) plays
 * drawable/splash_wordmark_anim itself from the moment the icon is tapped.
 * The system draws it, not our main thread, so the bars rise and the dots keep
 * pulsing however long startup takes. MainActivity calls [show] from the
 * splash's exit listener; this overlay (bars already up, dots pulsing:
 * drawable/splash_wordmark_hold) cross-fades in over the system splash and only
 * then removes it. The two dot pulses are out of phase, and the cross-fade
 * blends them instead of cutting from one to the other.
 *
 * Below API 31 the system splash can only show the static first frame, so the
 * overlay plays the full rise-and-pulse animation instead, with no fade-in.
 *
 * The overlay stays up until the caller reports the dashboard ready via
 * [Handle.markReady] and its minimum hold has passed, capped at [MAX_SHOW_MS]
 * so a slow network never holds the app hostage. Animator duration scale
 * applies, so it collapses to near-instant with animations off.
 */
object LaunchSplash {

    /** Below API 31 the overlay is the only motion, so it plays the full rise. */
    private const val MIN_SHOW_MS_STATIC_SYSTEM = 1400L
    /** API 31+: the system splash already animated; just finish the cross-fade. */
    private const val MIN_SHOW_MS_ANIMATED_SYSTEM = 300L
    private const val MAX_SHOW_MS = 4000L
    private const val CROSSFADE_MS = 200L
    private const val FADE_MS = 280L

    class Handle internal constructor(private val onReady: () -> Unit) {
        private var ready = false
        /** Data is on screen; the overlay leaves once the minimum time has passed. */
        fun markReady() {
            if (ready) return
            ready = true
            onReady()
        }
    }

    /**
     * Adds the overlay. [onCovered] runs once it fully covers the system
     * splash; the caller removes the system splash there. [onGone] runs once
     * the overlay has faded out and the app is in view.
     */
    fun show(activity: Activity, onGone: () -> Unit = {}, onCovered: () -> Unit): Handle {
        val systemAnimated = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        val root = activity.window.decorView as ViewGroup
        val overlay = LayoutInflater.from(activity)
            .inflate(R.layout.view_launch_splash, root, false)
        val wordmark = overlay.findViewById<ImageView>(R.id.splash_wordmark)
        if (systemAnimated) wordmark.setImageResource(R.drawable.splash_wordmark_hold)
        root.addView(overlay)
        val motion = wordmark.drawable as? Animatable
        motion?.start()

        // Once only: the fade-out below can cancel the cross-fade (and its end
        // action) if it starts first, so it covers itself too.
        var covered = false
        val cover = {
            if (!covered) {
                covered = true
                onCovered()
            }
        }
        if (systemAnimated) {
            overlay.alpha = 0f
            overlay.animate()
                .alpha(1f)
                .setDuration(CROSSFADE_MS)
                .withEndAction(cover)
                .start()
        } else {
            cover()
        }

        val minShowMs = if (systemAnimated) MIN_SHOW_MS_ANIMATED_SYSTEM else MIN_SHOW_MS_STATIC_SYSTEM
        val shownAt = SystemClock.uptimeMillis()
        var leaving = false
        val leave = Runnable {
            if (leaving) return@Runnable
            leaving = true
            cover()
            overlay.animate()
                .alpha(0f)
                .setDuration(FADE_MS)
                .setInterpolator(FastOutSlowInInterpolator())
                .withEndAction {
                    motion?.stop()
                    root.removeView(overlay)
                    onGone()
                }
                .start()
        }
        overlay.postDelayed(leave, MAX_SHOW_MS)
        return Handle {
            val wait = minShowMs - (SystemClock.uptimeMillis() - shownAt)
            overlay.postDelayed(leave, wait.coerceAtLeast(0L))
        }
    }
}
