package com.economic.dashboard.ui.views;

import android.view.View;
import android.view.ViewGroup;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

/**
 * IOS-05 — sliding-thumb behavior for a segmented control. Layered under
 * TimeframeSelector's chip row (view_timeframe_selector.xml): {@code thumb}
 * is a plain View sized/positioned to match whichever segment is active.
 *
 * One persistent SpringAnimation is kept per thumb (tagged on the view, same
 * pattern as PressFeedback), so retargeting mid-flight is a smooth redirect
 * instead of a restart — rapid alternating taps don't queue up.
 */
public final class SegmentedControl {

    private static final float DAMPING_RATIO = 0.9f;
    private static final float STIFFNESS = 900f;

    private SegmentedControl() {}

    /** Springs {@code thumb} to sit under {@code target}. */
    public static void slideTo(View thumb, View target) {
        if (thumb == null || target == null || target.getWidth() <= 0) return;
        matchWidth(thumb, target);
        spring(thumb).animateToFinalPosition(target.getLeft());
    }

    /** Places {@code thumb} under {@code target} with no animation. */
    public static void snapTo(View thumb, View target) {
        if (thumb == null || target == null || target.getWidth() <= 0) return;
        matchWidth(thumb, target);
        thumb.setTranslationX(target.getLeft());
    }

    private static void matchWidth(View thumb, View target) {
        ViewGroup.LayoutParams lp = thumb.getLayoutParams();
        if (lp.width != target.getWidth()) {
            lp.width = target.getWidth();
            thumb.requestLayout();
        }
    }

    private static SpringAnimation spring(View v) {
        SpringAnimation anim = (SpringAnimation) v.getTag();
        if (anim == null) {
            anim = new SpringAnimation(v, DynamicAnimation.TRANSLATION_X);
            anim.setSpring(new SpringForce()
                    .setDampingRatio(DAMPING_RATIO)
                    .setStiffness(STIFFNESS));
            v.setTag(anim);
        }
        return anim;
    }
}
