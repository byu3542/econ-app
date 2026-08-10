package com.economic.dashboard.ui.views

import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
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

    /**
     * Same as [snapTo], but tolerant of [target] living inside a subtree that's
     * `GONE` right now (e.g. the DATA pane's TABLE/GRAPH switch and timeframe
     * selector, both initialized while the pane is still collapsed). A single
     * `post {}` fires with `target.width == 0` in that case and [snapTo] bails
     * out silently — the thumb never gets a width, so the "active" segment's
     * text (colored for on-thumb contrast) renders directly on the bare sheet
     * background with no pill behind it, i.e. invisible in dark mode. This
     * retries via the view tree observer until a real layout pass gives the
     * target nonzero width, then snaps once and detaches.
     */
    @JvmStatic
    fun snapToWhenReady(selector: View?, thumb: View?, target: View?) {
        if (selector == null || thumb == null || target == null) return
        if (target.width > 0) {
            snapTo(thumb, target)
            return
        }
        val vto = selector.viewTreeObserver
        if (vto == null || !vto.isAlive) return
        vto.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (target.width <= 0) return
                snapTo(thumb, target)
                val liveVto = selector.viewTreeObserver
                if (liveVto != null && liveVto.isAlive) liveVto.removeOnGlobalLayoutListener(this)
            }
        })
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
