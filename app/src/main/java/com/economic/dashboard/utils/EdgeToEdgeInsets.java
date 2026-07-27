package com.economic.dashboard.utils;

import android.view.View;
import android.view.ViewGroup;

import com.economic.dashboard.R;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * IOS-04 — content insets for the translucent header / bottom-nav "materials".
 *
 * <h3>Why this was rewritten (2026-07-27 nav fixes)</h3>
 * The first cut exposed a single {@code applyEdgeToEdge(View)} that padded a
 * view by <em>both</em> bars, and it was wired into the 11 leaf fragments.
 * Ten of those eleven live inside a {@code ViewPager2} owned by
 * {@code MarketsFragment} / {@code EconomyFragment} — so the top inset landed
 * one level too deep: the hosts' {@code TabLayout} rows sat at y=0 underneath
 * the header (invisible and untappable) while the leaves below them carried a
 * redundant status-bar + 64dp gap.
 *
 * The rule now is explicit and one-directional:
 * <ul>
 *   <li><b>Whoever owns the topmost pixel of a screen</b> takes the top inset —
 *       the tab-strip hosts, News' header block, Dashboard's scroll view.</li>
 *   <li><b>Whoever owns the bottommost scrolling pixel</b> takes the bottom
 *       inset — the leaf scroll views, News' RecyclerView.</li>
 *   <li>Nobody takes both unless it genuinely occupies the full screen
 *       (only Dashboard does).</li>
 * </ul>
 *
 * <h3>Measured, not guessed</h3>
 * The bar heights are published by {@code MainActivity} from real layout
 * measurements ({@link #setHeaderHeight} / {@link #setNavHeight}) rather than
 * the old hardcoded {@code header_chrome_height} estimate. Because those
 * measurements arrive <em>after</em> a fragment's {@code onCreateView}, every
 * registered target is retained (weakly) and re-padded whenever a value
 * changes — so callers can register early and not care about ordering. The
 * dimens are kept only as a first-frame fallback.
 */
public final class EdgeToEdgeInsets {

    private EdgeToEdgeInsets() {}

    /** A registered target plus the padding it had before we touched it. */
    private static final class Target {
        final WeakReference<View> ref;
        final int basePaddingTop;
        final int basePaddingBottom;
        final boolean wantsTop;
        final boolean wantsBottom;

        Target(View v, boolean wantsTop, boolean wantsBottom) {
            this.ref = new WeakReference<>(v);
            this.basePaddingTop = v.getPaddingTop();
            this.basePaddingBottom = v.getPaddingBottom();
            this.wantsTop = wantsTop;
            this.wantsBottom = wantsBottom;
        }
    }

    private static final List<Target> TARGETS = new ArrayList<>();

    private static int systemTop = 0;
    private static int systemBottom = 0;
    /** Measured height of header_container, already inclusive of its status-bar padding. */
    private static int headerHeight = 0;
    /** Measured height of bottomNavWrapper, already inclusive of its nav-bar padding. */
    private static int navHeight = 0;

    // ── Publishers (MainActivity) ────────────────────────────────────────────

    /** Raw system-bar insets in px, from WindowInsetsCompat.Type.systemBars(). */
    public static void update(int systemBarTopPx, int systemBarBottomPx) {
        if (systemTop == systemBarTopPx && systemBottom == systemBarBottomPx) return;
        systemTop = systemBarTopPx;
        systemBottom = systemBarBottomPx;
        reapplyAll();
    }

    /**
     * Real measured height of the translucent header, in px. MainActivity
     * pushes this from an OnLayoutChangeListener, so it stays correct if the
     * header's content ever changes height (font scale, a second line, etc.)
     * instead of drifting from a hardcoded estimate.
     */
    public static void setHeaderHeight(int px) {
        if (px <= 0 || headerHeight == px) return;
        headerHeight = px;
        reapplyAll();
    }

    /** Real measured height of the translucent bottom-nav wrapper, in px. */
    public static void setNavHeight(int px) {
        if (px <= 0 || navHeight == px) return;
        navHeight = px;
        reapplyAll();
    }

    /**
     * Raw bottom system-bar (gesture nav) inset in px, from
     * {@code WindowInsetsCompat.Type.systemBars()}. For windows that sit above
     * the app's own bottom-nav wrapper — like a dialog's sheet content — where
     * {@link #bottomInset} (which folds in nav-wrapper height) would over-pad.
     */
    public static int getSystemBottomInsetPx() {
        return systemBottom;
    }

    /** Raw top system-bar (status bar) inset in px. See {@link #getSystemBottomInsetPx}. */
    public static int getSystemTopInsetPx() {
        return systemTop;
    }

    // ── Consumers (fragments) ────────────────────────────────────────────────

    /**
     * For the view that owns a screen's topmost pixel — a tab-strip host, a
     * News-style header block. Adds top padding only; clipToPadding is left
     * alone because these views don't scroll and shouldn't bleed.
     */
    public static void applyTopInset(View view) {
        register(view, true, false);
    }

    /**
     * For a scrolling view that owns a screen's bottommost pixel. Adds bottom
     * padding and disables clipToPadding so content can still scroll up
     * underneath the translucent nav bar rather than stopping at a hard edge.
     */
    public static void applyBottomInset(View scrollingRoot) {
        register(scrollingRoot, false, true);
    }

    /**
     * For a scrolling view that occupies the whole screen behind both bars
     * (Dashboard). Equivalent to the old {@code applyEdgeToEdge}.
     */
    public static void applyBothInsets(View scrollingRoot) {
        register(scrollingRoot, true, true);
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private static void register(View view, boolean wantsTop, boolean wantsBottom) {
        if (view == null) return;
        // Drop any stale registration for this same view (fragment recreation)
        // so the baseline padding isn't re-read from an already-padded view.
        for (Iterator<Target> it = TARGETS.iterator(); it.hasNext(); ) {
            View existing = it.next().ref.get();
            if (existing == null || existing == view) it.remove();
        }
        Target t = new Target(view, wantsTop, wantsBottom);
        TARGETS.add(t);
        apply(t, view);
    }

    private static void reapplyAll() {
        for (Iterator<Target> it = TARGETS.iterator(); it.hasNext(); ) {
            Target t = it.next();
            View v = t.ref.get();
            if (v == null) { it.remove(); continue; }
            apply(t, v);
        }
    }

    private static void apply(Target t, View v) {
        int top = t.basePaddingTop;
        int bottom = t.basePaddingBottom;
        if (t.wantsTop) top += topInset(v);
        if (t.wantsBottom) bottom += bottomInset(v);
        v.setPadding(v.getPaddingLeft(), top, v.getPaddingRight(), bottom);
        if (t.wantsBottom && v instanceof ViewGroup) {
            ((ViewGroup) v).setClipToPadding(false);
        }
    }

    /** Measured header height, or the old estimate on the very first frame. */
    private static int topInset(View context) {
        if (headerHeight > 0) return headerHeight;
        return systemTop + context.getResources()
                .getDimensionPixelSize(R.dimen.header_chrome_height);
    }

    /** Measured nav-wrapper height, or the dimen fallback on the first frame. */
    private static int bottomInset(View context) {
        if (navHeight > 0) return navHeight;
        return systemBottom + context.getResources()
                .getDimensionPixelSize(R.dimen.bottom_nav_height);
    }
}
