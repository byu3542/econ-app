package com.economic.dashboard.ui.views;

import android.view.View;
import android.widget.TextView;

import androidx.core.widget.NestedScrollView;

/**
 * IOS-02 — drives one screen's collapsing large title.
 *
 * The "large" masthead (serif, gold rule) lives at the top of the screen's
 * own scrolling content; the "compact" title lives in the persistent header
 * bar shared across every tab (see activity_main.xml / MainActivity). As the
 * screen scrolls through the first {@link #COLLAPSE_DISTANCE_DP} of content,
 * the large title fades out while the header's compact title and separator
 * hairline fade in — and back, scrolling to the top. The large title needs
 * no explicit translation of its own: it already scrolls with its content.
 *
 * <h3>2026-07-27 nav fix</h3>
 * {@code attach} used to hard-set the compact title to alpha 0 and then wait
 * for a scroll event. That left the header out of sync in two situations:
 * a fragment re-created while its scroll position was restored non-zero (the
 * large title and a partly-faded compact title both visible at once — which is
 * what the Overview screenshot showed), and any tab switch that ran
 * {@code resetHeaderCompactAlwaysVisible()} to alpha 1 and then landed back on
 * Overview before a scroll event fired. Both are fixed by seeding the fade
 * from the scroll view's <em>actual</em> scrollY once it has laid out, rather
 * than assuming it is at the top.
 */
public final class LargeTitleController {

    private static final float COLLAPSE_DISTANCE_DP = 56f;

    private LargeTitleController() {}

    /**
     * @param scrollView   the screen's own NestedScrollView
     * @param largeTitle   the large masthead block living in that scroll content
     * @param compactTitle the persistent header's compact title TextView
     * @param separator    the persistent header's bottom hairline
     */
    public static void attach(NestedScrollView scrollView, View largeTitle,
                               TextView compactTitle, View separator) {
        if (scrollView == null || largeTitle == null) return;
        float density = scrollView.getResources().getDisplayMetrics().density;
        final float collapseDistancePx = COLLAPSE_DISTANCE_DP * density;

        // Seed from the real scroll position rather than assuming 0. On a fresh
        // screen this is 0 and behaves exactly as before; on a restored or
        // already-scrolled one it stops the header from disagreeing with the
        // content underneath it.
        applyProgress(scrollView.getScrollY() / collapseDistancePx,
                largeTitle, compactTitle, separator);
        // ...and again after layout, because scrollY isn't restored yet at the
        // point onViewCreated runs.
        scrollView.post(() -> applyProgress(scrollView.getScrollY() / collapseDistancePx,
                largeTitle, compactTitle, separator));

        scrollView.setOnScrollChangeListener((NestedScrollView.OnScrollChangeListener)
                (v, scrollX, scrollY, oldScrollX, oldScrollY) ->
                        applyProgress(scrollY / collapseDistancePx,
                                largeTitle, compactTitle, separator));
    }

    /**
     * Detaches the scroll listener. Call from the owning fragment's
     * onDestroyView so a torn-down screen can't keep driving the shared
     * header's alpha after another tab has taken it over.
     */
    public static void detach(NestedScrollView scrollView) {
        if (scrollView == null) return;
        scrollView.setOnScrollChangeListener((NestedScrollView.OnScrollChangeListener) null);
    }

    private static void applyProgress(float rawProgress, View largeTitle,
                                      TextView compactTitle, View separator) {
        float progress = Math.max(0f, Math.min(1f, rawProgress));
        largeTitle.setAlpha(1f - progress);
        if (compactTitle != null) compactTitle.setAlpha(progress);
        if (separator != null) separator.setAlpha(progress);
    }
}
