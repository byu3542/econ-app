package com.economic.dashboard.ui.views;

import android.view.MotionEvent;
import android.view.View;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import com.economic.dashboard.utils.MotionUtil;

/**
 * IOS-01 — press-in spring feedback for tappable cards, replacing Material's
 * ripple. Springs are interruptible (unlike a duration-based tween), so
 * pressing and dragging off mid-animation settles smoothly instead of
 * snapping — that interruptibility is what a duration tween can't do.
 */
public final class PressFeedback {

    private static final float PRESS_SCALE = 0.97f;
    private static final float PRESS_ALPHA = 0.85f;
    private static final float DAMPING_RATIO = 0.75f;
    private static final float STIFFNESS = 1200f;

    private PressFeedback() {}

    /** Wires press-in/spring-back touch feedback onto {@code view}. */
    public static void attach(View view) {
        if (view == null) return;
        final Springs springs = new Springs(view);
        view.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    springs.animateTo(v, PRESS_SCALE, PRESS_ALPHA);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    springs.animateTo(v, 1f, 1f);
                    break;
                default:
                    break;
            }
            // Never consume the event — click/long-click listeners on the
            // same view still need to see ACTION_UP/ACTION_DOWN.
            return false;
        });
    }

    /** Held per-view so retargeting an in-flight spring is smooth, not a restart. */
    private static final class Springs {
        final SpringAnimation scaleX;
        final SpringAnimation scaleY;
        final SpringAnimation alpha;

        Springs(View view) {
            scaleX = springFor(view, DynamicAnimation.SCALE_X);
            scaleY = springFor(view, DynamicAnimation.SCALE_Y);
            alpha = springFor(view, DynamicAnimation.ALPHA);
        }

        void animateTo(View view, float scale, float alphaValue) {
            if (!MotionUtil.animationsEnabled(view.getContext())) {
                view.setScaleX(scale);
                view.setScaleY(scale);
                view.setAlpha(alphaValue);
                return;
            }
            scaleX.animateToFinalPosition(scale);
            scaleY.animateToFinalPosition(scale);
            alpha.animateToFinalPosition(alphaValue);
        }

        private static SpringAnimation springFor(View view, DynamicAnimation.ViewProperty property) {
            SpringAnimation anim = new SpringAnimation(view, property);
            anim.setSpring(new SpringForce()
                    .setDampingRatio(DAMPING_RATIO)
                    .setStiffness(STIFFNESS));
            return anim;
        }
    }
}
