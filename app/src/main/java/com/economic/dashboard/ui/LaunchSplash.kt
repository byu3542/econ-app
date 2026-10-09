package com.economic.dashboard.ui

import android.app.Activity
import android.graphics.drawable.Animatable
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
 * The system splash (Theme.EconomicDashboard.Starting) already shows the
 * wordmark's first frame (drawable/splash_wordmark) from the moment the app
 * icon is tapped, however long startup takes. MainActivity calls [show] from
 * the splash's exit listener; this overlay puts the same drawable on the same
 * pixels, so the only visible change is that the motion starts.
 *
 * The overlay stays up until both the animation has played ([MIN_SHOW_MS]) and
 * the caller reports the dashboard ready via [Handle.markReady], capped at
 * [MAX_SHOW_MS] so a slow network never holds the app hostage. Animator
 * duration scale applies, so it collapses to near-instant with animations off.
 */
object LaunchSplash {

    private const val MIN_SHOW_MS = 1400L
    private const val MAX_SHOW_MS = 4000L
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

    fun show(activity: Activity): Handle {
        val root = activity.window.decorView as ViewGroup
        val overlay = LayoutInflater.from(activity)
            .inflate(R.layout.view_launch_splash, root, false)
        root.addView(overlay)
        val motion = overlay.findViewById<ImageView>(R.id.splash_wordmark).drawable as? Animatable
        motion?.start()

        val shownAt = SystemClock.uptimeMillis()
        var leaving = false
        val leave = Runnable {
            if (leaving) return@Runnable
            leaving = true
            overlay.animate()
                .alpha(0f)
                .setDuration(FADE_MS)
                .setInterpolator(FastOutSlowInInterpolator())
                .withEndAction {
                    motion?.stop()
                    root.removeView(overlay)
                }
                .start()
        }
        overlay.postDelayed(leave, MAX_SHOW_MS)
        return Handle {
            val wait = MIN_SHOW_MS - (SystemClock.uptimeMillis() - shownAt)
            overlay.postDelayed(leave, wait.coerceAtLeast(0L))
        }
    }
}
