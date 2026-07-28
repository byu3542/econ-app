package com.economic.dashboard.ui.views

import android.view.View
import android.view.ViewGroup
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce

/**
 * IOS-05 — sliding-thumb behavior for a segmented control. Layered under
 * TimeframeSelector's chip row (view_timeframe_selector.xml): `thumb`
 * is a plain View sized/positioned to match whichever segment is active.
 *
 * One persistent SpringAnimation is kept per thumb (tagged on the view, same
 * pattern as PressFeedback), so retargeting mid-flight is a smooth redirect
 * instead of a restart — rapid alternating taps don't queue up.
 */
object SegmentedControl {

    private const val DAMPING_RATIO = 0.9f
    private const val STIFFNESS = 900f

    /** Springs [thumb] to sit under [target]. */
    @JvmStatic
    fun slideTo(thumb: View?, target: View?) {
        if (thumb == null || target == null || target.width <= 0) return
        matchWidth(thumb, target)
        spring(thumb).animateToFinalPosition(target.left.toFloat())
    }

    /** Places [thumb] under [target] with no animation. */
    @JvmStatic
    fun snapTo(thumb: View?, target: View?) {
        if (thumb == null || target == null || target.width <= 0) return
        matchWidth(thumb, target)
        thumb.translationX = target.left.toFloat()
    }

    private fun matchWidth(thumb: View, target: View) {
        val lp: ViewGroup.LayoutParams = thumb.layoutParams
        if (lp.width != target.width) {
            lp.width = target.width
            thumb.requestLayout()
        }
    }

    private fun spring(v: View): SpringAnimation {
        var anim = v.tag as? SpringAnimation
        if (anim == null) {
            anim = SpringAnimation(v, DynamicAnimation.TRANSLATION_X)
            anim.spring = SpringForce()
                .setDampingRatio(DAMPING_RATIO)
                .setStiffness(STIFFNESS)
            v.tag = anim
        }
        return anim
    }
}
