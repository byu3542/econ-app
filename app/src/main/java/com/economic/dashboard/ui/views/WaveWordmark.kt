package com.economic.dashboard.ui.views

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.economic.dashboard.R

/**
 * The Home header's "economii" wordmark, one TextView per letter so each can
 * rise on its own. [playWave] runs one left-to-right wave, Pantrii-style:
 * every letter grows to [PEAK] and lifts [LIFT_EM] em over [PULSE_MS], each
 * starting [STAGGER_MS] after its left neighbour, then settles. It plays once
 * and does not repeat (Aaron, 2026-10-16).
 *
 * The whole word is announced as one label; the letters are hidden from
 * accessibility so TalkBack does not spell it out.
 */
class WaveWordmark @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val letters = ArrayList<TextView>()
    private var wave: AnimatorSet? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = false
        clipToPadding = false
        contentDescription = TEXT
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES

        val color = TypedValue().also {
            context.theme.resolveAttribute(R.attr.econAccent, it, true)
        }.data
        val size = resources.getDimension(R.dimen.text_page_title)
        val font = ResourcesCompat.getFont(context, R.font.plus_jakarta_sans_extrabold)
        for (c in TEXT) {
            val tv = TextView(context)
            tv.text = c.toString()
            tv.setTextColor(color)
            tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
            tv.typeface = font
            tv.includeFontPadding = false
            tv.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(tv, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            letters.add(tv)
        }
    }

    /** One wave across the word. Safe to call before layout. */
    fun playWave() {
        wave?.cancel()
        post {
            val liftPx = -LIFT_EM * letters.first().textSize
            val parts = letters.mapIndexed { i, tv ->
                // transform-origin 50% 75%, as in the reference.
                tv.pivotX = tv.width / 2f
                tv.pivotY = tv.height * 0.75f
                AnimatorSet().apply {
                    playSequentially(step(tv, 1f, PEAK, 0f, liftPx), step(tv, PEAK, 1f, liftPx, 0f))
                    startDelay = i * STAGGER_MS
                }
            }
            wave = AnimatorSet().apply {
                playTogether(parts)
                start()
            }
        }
    }

    /** Half a pulse: scale and lift together, eased in and out. */
    private fun step(tv: View, s0: Float, s1: Float, y0: Float, y1: Float) = AnimatorSet().apply {
        playTogether(
            ObjectAnimator.ofFloat(tv, View.SCALE_X, s0, s1),
            ObjectAnimator.ofFloat(tv, View.SCALE_Y, s0, s1),
            ObjectAnimator.ofFloat(tv, View.TRANSLATION_Y, y0, y1))
        duration = PULSE_MS / 2
        interpolator = AccelerateDecelerateInterpolator()
    }

    override fun onDetachedFromWindow() {
        wave?.cancel()
        letters.forEach { it.scaleX = 1f; it.scaleY = 1f; it.translationY = 0f }
        super.onDetachedFromWindow()
    }

    private companion object {
        const val TEXT = "economii"
        const val PEAK = 1.3f
        const val LIFT_EM = 0.4f
        const val PULSE_MS = 400L
        const val STAGGER_MS = 80L
    }
}
