package com.economic.dashboard.ui.views

import android.view.View
import android.view.ViewGroup
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.economic.dashboard.utils.MotionUtil

/**
 * MOT-03 — reveals a row of chips from the middle outward.
 *
 * Each child starts at alpha 0 / scale 0.82 and springs in after
 * `abs(index - centre) * 45ms`, so the middle chips land first and the outer
 * ones last. A left-to-right sweep implies the first chip is the best one;
 * centre-out says they are equal. Springs reuse [PressFeedback]'s constants
 * so the app keeps one motion signature.
 *
 * With motion off ([MotionUtil.animationsEnabled] false) every child is shown
 * at full opacity and scale immediately — no stagger.
 */
object CentreOutStagger {

    private const val STAGGER_MS = 45f
    private const val START_SCALE = 0.82f
    private const val FADE_MS = 340L
    private const val DAMPING_RATIO = 0.75f
    private const val STIFFNESS = 1200f

    @JvmStatic
    fun animateIn(group: ViewGroup?) {
        if (group == null || group.childCount == 0) return
        val motion = MotionUtil.animationsEnabled(group.context)
        val centre = (group.childCount - 1) / 2f
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            child.animate().cancel()
            if (!motion) {
                child.alpha = 1f
                child.scaleX = 1f
                child.scaleY = 1f
                continue
            }
            child.alpha = 0f
            child.scaleX = START_SCALE
            child.scaleY = START_SCALE
            val delay = (Math.abs(i - centre) * STAGGER_MS).toLong()
            child.animate().alpha(1f).setStartDelay(delay).setDuration(FADE_MS).start()
            child.postDelayed({
                spring(child, DynamicAnimation.SCALE_X)
                spring(child, DynamicAnimation.SCALE_Y)
            }, delay)
        }
    }

    private fun spring(view: View, property: DynamicAnimation.ViewProperty) {
        val anim = SpringAnimation(view, property, 1f)
        anim.spring = SpringForce(1f)
            .setDampingRatio(DAMPING_RATIO)
            .setStiffness(STIFFNESS)
        anim.start()
    }
}
