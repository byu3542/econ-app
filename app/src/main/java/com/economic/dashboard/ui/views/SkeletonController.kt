package com.economic.dashboard.ui.views

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.economic.dashboard.R
import com.economic.dashboard.utils.MotionUtil
import com.facebook.shimmer.ShimmerFrameLayout

/**
 * Wraps a fragment's content view in a FrameLayout and overlays a shimmer
 * skeleton (view_chart_skeleton) on top. The content starts invisible; call
 * [reveal] the first time data arrives to cross-fade the real content
 * in and the skeleton out.
 *
 * Usage in a fragment:
 * ```
 *   binding = FragmentXBinding.inflate(inflater, container, false);
 *   skeleton = SkeletonController.wrap(binding.getRoot());
 *   return skeleton.getRoot();
 *   // ...later, inside each data observer:
 *   if (skeleton != null) skeleton.reveal();
 * ```
 *
 * This keeps the skeleton out of every leaf layout XML — one include, one
 * helper, applied uniformly.
 */
class SkeletonController private constructor(
    private val root: FrameLayout,
    private val content: View,
    private val shimmer: ShimmerFrameLayout
) {

    private var revealed = false

    /** The wrapper to return from onCreateView(). */
    fun getRoot(): View = root

    /** Cross-fades real content in and the skeleton out. Idempotent. */
    fun reveal() {
        if (revealed) return
        revealed = true

        // TICKET-28: respect "reduce motion" — swap instantly, no cross-fade.
        if (!MotionUtil.animationsEnabled(content.context)) {
            content.alpha = 1f
            content.visibility = View.VISIBLE
            shimmer.stopShimmer()
            shimmer.visibility = View.GONE
            return
        }

        content.alpha = 0f
        content.visibility = View.VISIBLE
        content.animate().alpha(1f).setDuration(300).start()

        shimmer.stopShimmer()
        shimmer.animate().alpha(0f).setDuration(300)
            .withEndAction { shimmer.visibility = View.GONE }
            .start()
    }

    companion object {
        /** Builds the overlay around an already-inflated content view. */
        @JvmStatic
        fun wrap(content: View): SkeletonController {
            val ctx = content.context
            val frame = FrameLayout(ctx)

            val lp = content.layoutParams
            frame.layoutParams = lp ?: ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

            content.visibility = View.INVISIBLE
            frame.addView(content, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

            val shimmer = LayoutInflater.from(ctx)
                .inflate(R.layout.view_chart_skeleton, frame, false) as ShimmerFrameLayout
            frame.addView(shimmer)
            shimmer.startShimmer()

            return SkeletonController(frame, content, shimmer)
        }
    }
}
