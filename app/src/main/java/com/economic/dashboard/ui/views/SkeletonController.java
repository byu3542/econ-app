package com.economic.dashboard.ui.views;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.economic.dashboard.R;
import com.facebook.shimmer.ShimmerFrameLayout;

/**
 * Wraps a fragment's content view in a FrameLayout and overlays a shimmer
 * skeleton (view_chart_skeleton) on top. The content starts invisible; call
 * {@link #reveal()} the first time data arrives to cross-fade the real content
 * in and the skeleton out.
 *
 * Usage in a fragment:
 * <pre>
 *   binding = FragmentXBinding.inflate(inflater, container, false);
 *   skeleton = SkeletonController.wrap(binding.getRoot());
 *   return skeleton.getRoot();
 *   // ...later, inside each data observer:
 *   if (skeleton != null) skeleton.reveal();
 * </pre>
 *
 * This keeps the skeleton out of every leaf layout XML — one include, one
 * helper, applied uniformly.
 */
public final class SkeletonController {

    private final FrameLayout root;
    private final View content;
    private final ShimmerFrameLayout shimmer;
    private boolean revealed = false;

    private SkeletonController(FrameLayout root, View content, ShimmerFrameLayout shimmer) {
        this.root = root;
        this.content = content;
        this.shimmer = shimmer;
    }

    /** Builds the overlay around an already-inflated content view. */
    public static SkeletonController wrap(View content) {
        Context ctx = content.getContext();
        FrameLayout frame = new FrameLayout(ctx);

        ViewGroup.LayoutParams lp = content.getLayoutParams();
        frame.setLayoutParams(lp != null ? lp
                : new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                             ViewGroup.LayoutParams.MATCH_PARENT));

        content.setVisibility(View.INVISIBLE);
        frame.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ShimmerFrameLayout shimmer = (ShimmerFrameLayout) LayoutInflater.from(ctx)
                .inflate(R.layout.view_chart_skeleton, frame, false);
        frame.addView(shimmer);
        shimmer.startShimmer();

        return new SkeletonController(frame, content, shimmer);
    }

    /** The wrapper to return from onCreateView(). */
    public View getRoot() {
        return root;
    }

    /** Cross-fades real content in and the skeleton out. Idempotent. */
    public void reveal() {
        if (revealed) return;
        revealed = true;

        // TICKET-28: respect "reduce motion" — swap instantly, no cross-fade.
        if (!com.economic.dashboard.utils.MotionUtil.animationsEnabled(content.getContext())) {
            content.setAlpha(1f);
            content.setVisibility(View.VISIBLE);
            shimmer.stopShimmer();
            shimmer.setVisibility(View.GONE);
            return;
        }

        content.setAlpha(0f);
        content.setVisibility(View.VISIBLE);
        content.animate().alpha(1f).setDuration(300).start();

        shimmer.stopShimmer();
        shimmer.animate().alpha(0f).setDuration(300)
                .withEndAction(() -> shimmer.setVisibility(View.GONE))
                .start();
    }
}
