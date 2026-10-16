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
    /** splash_branding's 200x80dp box, its top this far below the spinner. */
    private const val BRAND_W_DP = 200f
    private const val BRAND_H_DP = 80f
    private const val BRAND_GAP_DP = 4f
    /** The wordmark fades in under the wheel and holds before the collapse. */
    private const val BRAND_IN_MS = 160L
    private const val BRAND_HOLD_MS = 600L

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

        val accent = splashAccent(activity)
        val art = ExitDrawable(
            cx, cy, discR, icon.width, accent.bright.toInt(), fullR,
            ContextCompat.getDrawable(activity, R.drawable.splash_logo)!!,
            branding(activity, cx, cy + icon.height / 2f, accent),
        )
        val view = View(activity).apply { background = art }
        root.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        view.pivotX = cx
        view.pivotY = cy

        var running: AnimatorSet? = null
        val start = Runnable {
            provider.remove()
            val ease = FastOutSlowInInterpolator()
            val hold = if (art.hasBrand) BRAND_HOLD_MS else 0L
            val collapse = ValueAnimator.ofFloat(fullR, ringR).apply {
                duration = COLLAPSE_MS
                interpolator = ease
                addUpdateListener {
                    art.fieldR = it.animatedValue as Float
                    art.brandAlpha = (1f - it.animatedFraction * 2f).coerceAtLeast(0f)
                }
                startDelay = hold
            }
            val scale = (target.width / 2f) / ringR
            val brandIn = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = if (art.hasBrand) BRAND_IN_MS else 0L
                addUpdateListener { art.brandAlpha = it.animatedValue as Float }
            }
            val drop = AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(view, View.TRANSLATION_X, targetLoc[0] - rootLoc[0] + target.width / 2f - cx),
                    ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, targetLoc[1] - rootLoc[1] + target.height / 2f - cy),
                    ObjectAnimator.ofFloat(view, View.SCALE_X, scale),
                    ObjectAnimator.ofFloat(view, View.SCALE_Y, scale),
                )
                startDelay = hold + DROP_DELAY_MS
                duration = DROP_MS
                interpolator = ease
            }
            val fade = ObjectAnimator.ofFloat(view, View.ALPHA, 0f).apply {
                startDelay = hold + FADE_DELAY_MS
                duration = FADE_MS
            }
            running = AnimatorSet().apply {
                playTogether(brandIn, collapse, drop, fade)
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
    private fun splashAccent(context: Context): AccentPalette =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            SettingsManager.getAccent(context)
        } else {
            AccentPalette.DEFAULT
        }

    /**
     * The wordmark, centred under the spinner (whose outer edge is [top]).
     * White on navy, black on the lighter accents.
     */
    private fun branding(context: Context, cx: Float, top: Float, accent: AccentPalette): Drawable? {
        val res = if (accent == AccentPalette.NAVY) R.drawable.splash_branding_light else R.drawable.splash_branding
        val d = ContextCompat.getDrawable(context, res) ?: return null
        val dp = context.resources.displayMetrics.density
        val w = (BRAND_W_DP * dp).toInt()
        val h = (BRAND_H_DP * dp).toInt()
        val left = (cx - w / 2f).toInt()
        val t = (top + BRAND_GAP_DP * dp).toInt()
        d.setBounds(left, t, left + w, t + h)
        return d
    }

    /** The splash, redrawn: accent field, navy disc, finished logo, wordmark. */
    private class ExitDrawable(
        private val cx: Float,
        private val cy: Float,
        private val discR: Float,
        iconSize: Int,
        ground: Int,
        initialFieldR: Float,
        private val logo: Drawable,
        private val brand: Drawable?,
    ) : Drawable() {
        private val fieldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ground }
        private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = DISC_COLOR }

        var fieldR = initialFieldR
            set(value) {
                field = value
                invalidateSelf()
            }

        val hasBrand get() = brand != null

        /** Fades in under the wheel, then out in the first half of the collapse, before the drop moves it. */
        var brandAlpha = 0f
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
            brand?.let {
                it.alpha = (brandAlpha * 255).toInt()
                it.draw(canvas)
            }
        }

        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
