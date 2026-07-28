package com.economic.dashboard.ui.views

import android.view.MotionEvent
import android.view.View
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.economic.dashboard.utils.MotionUtil

/**
 * IOS-01 — press-in spring feedback for tappable cards, replacing Material's
 * ripple. Springs are interruptible (unlike a duration-based tween), so
 * pressing and dragging off mid-animation settles smoothly instead of
 * snapping — that interruptibility is what a duration tween can't do.
 */
object PressFeedback {

    private const val PRESS_SCALE = 0.97f
    private const val PRESS_ALPHA = 0.85f
    private const val DAMPING_RATIO = 0.75f
    private const val STIFFNESS = 1200f

    /** Wires press-in/spring-back touch feedback onto [view]. */
    @JvmStatic
    fun attach(view: View?) {
        if (view == null) return
        val springs = Springs(view)
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    springs.animateTo(v, PRESS_SCALE, PRESS_ALPHA)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    springs.animateTo(v, 1f, 1f)
                else -> {}
            }
            // Never consume the event — click/long-click listeners on the
            // same view still need to see ACTION_UP/ACTION_DOWN.
            false
        }
    }

    /** Held per-view so retargeting an in-flight spring is smooth, not a restart. */
    private class Springs(view: View) {
        val scaleX: SpringAnimation = springFor(view, DynamicAnimation.SCALE_X)
        val scaleY: SpringAnimation = springFor(view, DynamicAnimation.SCALE_Y)
        val alpha: SpringAnimation = springFor(view, DynamicAnimation.ALPHA)

        fun animateTo(view: View, scale: Float, alphaValue: Float) {
            if (!MotionUtil.animationsEnabled(view.context)) {
                view.scaleX = scale
                view.scaleY = scale
                view.alpha = alphaValue
                return
            }
            scaleX.animateToFinalPosition(scale)
            scaleY.animateToFinalPosition(scale)
            alpha.animateToFinalPosition(alphaValue)
        }

        companion object {
            private fun springFor(view: View, property: DynamicAnimation.ViewProperty): SpringAnimation {
                val anim = SpringAnimation(view, property)
                anim.spring = SpringForce()
                    .setDampingRatio(DAMPING_RATIO)
                    .setStiffness(STIFFNESS)
                return anim
            }
        }
    }
}
