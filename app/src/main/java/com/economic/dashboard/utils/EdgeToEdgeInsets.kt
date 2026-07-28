package com.economic.dashboard.utils

import android.view.View
import android.view.ViewGroup
import com.economic.dashboard.R
import java.lang.ref.WeakReference

/**
 * IOS-04 — content insets for the translucent header / bottom-nav "materials".
 *
 * ### Why this was rewritten (2026-07-27 nav fixes)
 * The first cut exposed a single `applyEdgeToEdge(View)` that padded a
 * view by *both* bars, and it was wired into the 11 leaf fragments.
 * Ten of those eleven live inside a `ViewPager2` owned by
 * `MarketsFragment` / `EconomyFragment` — so the top inset landed
 * one level too deep: the hosts' `TabLayout` rows sat at y=0 underneath
 * the header (invisible and untappable) while the leaves below them carried a
 * redundant status-bar + 64dp gap.
 *
 * The rule now is explicit and one-directional:
 *  - Whoever owns the topmost pixel of a screen takes the top inset —
 *    the tab-strip hosts, News' header block, Dashboard's scroll view.
 *  - Whoever owns the bottommost scrolling pixel takes the bottom
 *    inset — the leaf scroll views, News' RecyclerView.
 *  - Nobody takes both unless it genuinely occupies the full screen
 *    (only Dashboard does).
 *
 * ### Measured, not guessed
 * The bar heights are published by `MainActivity` from real layout
 * measurements ([setHeaderHeight] / [setNavHeight]) rather than
 * the old hardcoded `header_chrome_height` estimate. Because those
 * measurements arrive *after* a fragment's `onCreateView`, every
 * registered target is retained (weakly) and re-padded whenever a value
 * changes — so callers can register early and not care about ordering. The
 * dimens are kept only as a first-frame fallback.
 */
object EdgeToEdgeInsets {

    /** A registered target plus the padding it had before we touched it. */
    private class Target(
        v: View,
        val wantsTop: Boolean,
        val wantsBottom: Boolean
    ) {
        val ref: WeakReference<View> = WeakReference(v)
        val basePaddingTop: Int = v.paddingTop
        val basePaddingBottom: Int = v.paddingBottom
    }

    private val targets = mutableListOf<Target>()

    private var systemTop = 0
    private var systemBottom = 0
    /** Measured height of header_container, already inclusive of its status-bar padding. */
    private var headerHeight = 0
    /** Measured height of bottomNavWrapper, already inclusive of its nav-bar padding. */
    private var navHeight = 0

    // ── Publishers (MainActivity) ────────────────────────────────────────────

    /** Raw system-bar insets in px, from WindowInsetsCompat.Type.systemBars(). */
    @JvmStatic
    fun update(systemBarTopPx: Int, systemBarBottomPx: Int) {
        if (systemTop == systemBarTopPx && systemBottom == systemBarBottomPx) return
        systemTop = systemBarTopPx
        systemBottom = systemBarBottomPx
        reapplyAll()
    }

    /**
     * Real measured height of the translucent header, in px. MainActivity
     * pushes this from an OnLayoutChangeListener, so it stays correct if the
     * header's content ever changes height (font scale, a second line, etc.)
     * instead of drifting from a hardcoded estimate.
     */
    @JvmStatic
    fun setHeaderHeight(px: Int) {
        if (px <= 0 || headerHeight == px) return
        headerHeight = px
        reapplyAll()
    }

    /** Real measured height of the translucent bottom-nav wrapper, in px. */
    @JvmStatic
    fun setNavHeight(px: Int) {
        if (px <= 0 || navHeight == px) return
        navHeight = px
        reapplyAll()
    }

    /**
     * Raw bottom system-bar (gesture nav) inset in px, from
     * `WindowInsetsCompat.Type.systemBars()`. For windows that sit above
     * the app's own bottom-nav wrapper — like a dialog's sheet content — where
     * [getSystemBottomInsetPx] (which folds in nav-wrapper height) would over-pad.
     */
    @JvmStatic
    fun getSystemBottomInsetPx(): Int = systemBottom

    /** Raw top system-bar (status bar) inset in px. See [getSystemBottomInsetPx]. */
    @JvmStatic
    fun getSystemTopInsetPx(): Int = systemTop

    // ── Consumers (fragments) ────────────────────────────────────────────────

    /**
     * For the view that owns a screen's topmost pixel — a tab-strip host, a
     * News-style header block. Adds top padding only; clipToPadding is left
     * alone because these views don't scroll and shouldn't bleed.
     */
    @JvmStatic
    fun applyTopInset(view: View?) {
        register(view, wantsTop = true, wantsBottom = false)
    }

    /**
     * For a scrolling view that owns a screen's bottommost pixel. Adds bottom
     * padding and disables clipToPadding so content can still scroll up
     * underneath the translucent nav bar rather than stopping at a hard edge.
     */
    @JvmStatic
    fun applyBottomInset(scrollingRoot: View?) {
        register(scrollingRoot, wantsTop = false, wantsBottom = true)
    }

    /**
     * For a scrolling view that occupies the whole screen behind both bars
     * (Dashboard). Equivalent to the old `applyEdgeToEdge`.
     */
    @JvmStatic
    fun applyBothInsets(scrollingRoot: View?) {
        register(scrollingRoot, wantsTop = true, wantsBottom = true)
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private fun register(view: View?, wantsTop: Boolean, wantsBottom: Boolean) {
        if (view == null) return
        // Drop any stale registration for this same view (fragment recreation)
        // so the baseline padding isn't re-read from an already-padded view.
        val it = targets.iterator()
        while (it.hasNext()) {
            val existing = it.next().ref.get()
            if (existing == null || existing === view) it.remove()
        }
        val t = Target(view, wantsTop, wantsBottom)
        targets.add(t)
        apply(t, view)
    }

    private fun reapplyAll() {
        val it = targets.iterator()
        while (it.hasNext()) {
            val t = it.next()
            val v = t.ref.get()
            if (v == null) {
                it.remove()
                continue
            }
            apply(t, v)
        }
    }

    private fun apply(t: Target, v: View) {
        var top = t.basePaddingTop
        var bottom = t.basePaddingBottom
        if (t.wantsTop) top += topInset(v)
        if (t.wantsBottom) bottom += bottomInset(v)
        v.setPadding(v.paddingLeft, top, v.paddingRight, bottom)
        if (t.wantsBottom && v is ViewGroup) {
            v.clipToPadding = false
        }
    }

    /** Measured header height, or the old estimate on the very first frame. */
    private fun topInset(context: View): Int {
        if (headerHeight > 0) return headerHeight
        return systemTop + context.resources.getDimensionPixelSize(R.dimen.header_chrome_height)
    }

    /** Measured nav-wrapper height, or the dimen fallback on the first frame. */
    private fun bottomInset(context: View): Int {
        if (navHeight > 0) return navHeight
        return systemBottom + context.resources.getDimensionPixelSize(R.dimen.bottom_nav_height)
    }
}
