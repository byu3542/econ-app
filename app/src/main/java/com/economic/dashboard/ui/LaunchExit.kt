package com.economic.dashboard.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreenViewProvider
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.economic.dashboard.R
import com.economic.dashboard.utils.SettingsManager
import kotlin.math.hypot
import kotlin.math.max

/**
 * Launch variation C, the exit half. The system splash (accent ground, navy
 * logo disc, drawable/splash_logo_anim) builds the logo. This then swaps in
 * an identical copy drawn by [ExitDrawable], removes the system splash, shrinks
 * the accent field to a ring around the disc and drops ring and logo into the
 * Analyst FAB, fading as they land on the real one.
 *
 * A copy, not the system splash view itself: that view does not keep its
 * outline clip and ground while it is translated and scaled.
 *
 * Plays once, adds no hold: it starts the moment the icon animation ends (at
 * once below API 31, where the icon is still). With animations off it just
 * removes the splash.
 */
object LaunchExit {

    private const val COLLAPSE_MS = 300L
    private const val DROP_DELAY_MS = 160L
    private const val DROP_MS = 340L
    private const val FADE_DELAY_MS = 400L
    private const val FADE_MS = 100L

    /**
     * The system icon view is the 160dp icon disc; the icon drawable inside it
     * is laid out on the 240dp adaptive-icon box, so the logo draws 1.5x the
     * view.
     */
    private const val LOGO_SCALE = 240f / 160f
    /** The navy disc inside splash_logo: radius 30 of the 36 the icon view shows. */
    private const val DISC_FRACTION = 30f / 36f
    /** The accent ring left around the disc, like the FAB's hairline. */
    private const val RING_SCALE = 1.12f
    /** windowSplashScreenIconBackgroundColor on Theme.EconomicDashboard.Starting. */
    private const val DISC_COLOR = 0xFF1A3470.toInt()

    fun play(activity: Activity, provider: SplashScreenViewProvider, target: View, onGone: () -> Unit) {
                val icon = provider.iconView
        if (!ValueAnimator.areAnimatorsEnabled() || icon.width == 0 || target.width == 0) {
            provider.remove()
            onGone()
            return
        }

        // Both iconAnimation* values are wall-clock (currentTimeMillis).
        val buildLeft = provider.iconAnimationStartMillis +
            provider.iconAnimationDurationMillis - System.currentTimeMillis()

        val root = activity.window.decorView as ViewGroup
        val rootLoc = IntArray(2).also { root.getLocationOnScreen(it) }
        val iconLoc = IntArray(2).also { icon.getLocationOnScreen(it) }
        val targetLoc = IntArray(2).also { target.getLocationOnScreen(it) }
        val cx = iconLoc[0] - rootLoc[0] + icon.width / 2f
        val cy = iconLoc[1] - rootLoc[1] + icon.height / 2f
        val discR = icon.width / 2f * DISC_FRACTION
        val ringR = discR * RING_SCALE
        val fullR = hypot(max(cx, root.width - cx), max(cy, root.height - cy))

        val art = ExitDrawable(
            cx, cy, discR, icon.width, groundColor(activity), fullR,
            ContextCompat.getDrawable(activity, R.drawable.splash_logo)!!,
        )
        val view = View(activity).apply { background = art }
        root.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        view.pivotX = cx
        view.pivotY = cy

        var running: AnimatorSet? = null
        val start = Runnable {
            provider.remove()
            val ease = FastOutSlowInInterpolator()
            val collapse = ValueAnimator.ofFloat(fullR, ringR).apply {
                duration = COLLAPSE_MS
                interpolator = ease
                addUpdateListener { art.fieldR = it.animatedValue as Float }
            }
            val scale = (target.width / 2f) / ringR
            val drop = AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(view, View.TRANSLATION_X, targetLoc[0] - rootLoc[0] + target.width / 2f - cx),
                    ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, targetLoc[1] - rootLoc[1] + target.height / 2f - cy),
                    ObjectAnimator.ofFloat(view, View.SCALE_X, scale),
                    ObjectAnimator.ofFloat(view, View.SCALE_Y, scale),
                )
                startDelay = DROP_DELAY_MS
                duration = DROP_MS
                interpolator = ease
            }
            val fade = ObjectAnimator.ofFloat(view, View.ALPHA, 0f).apply {
                startDelay = FADE_DELAY_MS
                duration = FADE_MS
            }
            running = AnimatorSet().apply {
                playTogether(collapse, drop, fade)
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        root.removeView(view)
                        if (!activity.isDestroyed) onGone()
                    }
                })
                start()
            }
        }
        // The copy draws on its next frame; drop the system splash after it.
        // If the activity goes away first (rotation, accent recreate), the
        // window detaches the copy: stop everything there.
        view.postDelayed(start, buildLeft.coerceAtLeast(0L) + 1)
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                v.removeCallbacks(start)
                running?.cancel()
            }
        })
    }

    /**
     * The ground the system splash just showed: the chosen accent on API 33+
     * (setSplashScreenTheme), the manifest theme's navy below that.
     */
    private fun groundColor(context: Context): Int {
        val accent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            SettingsManager.getAccent(context)
        } else {
            AccentPalette.DEFAULT
        }
        return accent.bright.toInt()
    }

    /** The splash, redrawn: accent field, navy disc, finished logo. */
    private class ExitDrawable(
        private val cx: Float,
        private val cy: Float,
        private val discR: Float,
        iconSize: Int,
        ground: Int,
        initialFieldR: Float,
        private val logo: Drawable,
    ) : Drawable() {
        private val fieldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ground }
        private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = DISC_COLOR }

        var fieldR = initialFieldR
            set(value) {
                field = value
                invalidateSelf()
            }

        init {
            val half = (iconSize * LOGO_SCALE / 2f).toInt()
            logo.setBounds(cx.toInt() - half, cy.toInt() - half, cx.toInt() + half, cy.toInt() + half)
        }

        override fun draw(canvas: Canvas) {
            canvas.drawCircle(cx, cy, fieldR, fieldPaint)
            canvas.drawCircle(cx, cy, discR, discPaint)
            logo.draw(canvas)
        }

        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
