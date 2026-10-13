package com.economic.dashboard.ui.views

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.transition.ChangeBounds
import android.transition.Fade
import android.transition.Transition
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import com.economic.dashboard.R
import com.economic.dashboard.models.TurnSource
import com.economic.dashboard.ui.MetricPopupRegistry
import com.economic.dashboard.utils.MotionUtil

/**
 * MOT-04 — "Sources that open from a stack". Sits under a finished analyst
 * answer: one row of overlapping letter badges plus "N sources". Tapping the
 * row fans the badges apart and opens the list beneath; tapping again closes it.
 *
 * - One badge per distinct provider (FRED, BLS, Reuters…), at most
 *   [MAX_BADGES], then "+N". The count is the number of list rows.
 * - Badges are letters on a colour picked from a fixed palette by provider
 *   name. No favicons, no network.
 * - Series rows open that metric's popup and headline rows open the article,
 *   both through [onOpen]. A row with nowhere to go is shown but not tappable.
 *
 * Motion (all of it snaps when [MotionUtil.animationsEnabled] is false):
 * - Badges spread by springing `translationX`, one every 30ms, at the
 *   PressFeedback constants (0.75 / 1200, copied here as Batch 1 did). Each gap
 *   goes from −6dp to +2dp. The spec says margins; translation is used instead
 *   because the list's ChangeBounds transition suppresses layout on the parents
 *   of what it animates, which would freeze a margin spring for its duration.
 *   It looks the same, and the header reserves the open width as end padding
 *   so nothing draws past the bubble.
 * - The list opens with TransitionManager.beginDelayedTransition on the chat
 *   row ([setExpanded]'s sceneRoot), and its rows come in top to bottom with
 *   CentreOutStagger's fade (340ms, 45ms apart) and a 4dp rise on the same spring.
 *
 * Expanded state lives on the ChatMessage (the adapter owns it), so a recycled
 * row comes back in the state the user left it.
 */
class SourceStackView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    /** Header tapped. The adapter flips ChatMessage.sourcesExpanded and calls [setExpanded]. */
    var onToggle: (() -> Unit)? = null

    /** A row with somewhere to go was tapped. */
    var onOpen: ((TurnSource) -> Unit)? = null

    private val header = LinearLayout(context)
    private val stack = LinearLayout(context)
    private val tvCount = TextView(context)
    private val list = LinearLayout(context)

    private var sources: List<TurnSource> = emptyList()
    private var expanded = false
    private val springs = ArrayList<SpringAnimation>()
    private val pending = ArrayList<Runnable>()

    init {
        orientation = VERTICAL
        visibility = GONE

        header.orientation = HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.minimumHeight = dp(HEADER_MIN_DP)
        // Spread badges and the count label draw into the reserved end padding.
        header.clipChildren = false
        header.clipToPadding = false
        header.isClickable = true
        header.isFocusable = true
        header.setBackgroundResource(rippleRes())
        header.setOnClickListener { onToggle?.invoke() }
        addView(header, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        stack.orientation = HORIZONTAL
        stack.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        header.addView(stack, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        tvCount.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
        tvCount.setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_caption))
        tvCount.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        val countLp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        countLp.marginStart = dp(COUNT_GAP_DP)
        header.addView(tvCount, countLp)

        list.orientation = VERTICAL
        list.visibility = GONE
        list.showDividers = SHOW_DIVIDER_BEGINNING or SHOW_DIVIDER_MIDDLE
        val divider = GradientDrawable()
        divider.setColor(ContextCompat.getColor(context, R.color.divider_sand))
        divider.setSize(1, 1)
        list.dividerDrawable = divider
        addView(list, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    /** Full bind: shows [sources] in the given state, with no motion. Empty hides the view. */
    fun bind(sources: List<TurnSource>, expanded: Boolean) {
        cancelMotion()
        if (sources.isEmpty()) {
            clear()
            return
        }
        // Same list (a rebind of the same message) keeps its views.
        if (sources !== this.sources) {
            this.sources = sources
            buildHeader()
            buildRows()
        }
        this.expanded = expanded
        visibility = VISIBLE
        snapTo(expanded)
    }

    fun clear() {
        cancelMotion()
        sources = emptyList()
        stack.removeAllViews()
        list.removeAllViews()
        visibility = GONE
    }

    /**
     * Opens or closes the stack. [sceneRoot] is the chat row, so the bubble
     * grows and shrinks with the list rather than jumping.
     */
    fun setExpanded(expanded: Boolean, sceneRoot: ViewGroup) {
        if (sources.isEmpty()) return
        cancelMotion()
        this.expanded = expanded
        if (!MotionUtil.animationsEnabled(context)) {
            snapTo(expanded)
            return
        }

        val bounds = ChangeBounds()
        val transition: Transition = if (expanded) bounds
            else TransitionSet()
                .addTransition(Fade(Fade.OUT))
                .addTransition(bounds)
                .setOrdering(TransitionSet.ORDERING_TOGETHER)
        transition.setDuration(LIST_MS)
        TransitionManager.beginDelayedTransition(sceneRoot, transition)
        list.visibility = if (expanded) VISIBLE else GONE

        // Opening fans left to right; closing folds back right to left.
        val step = spreadPx().toFloat()
        val n = stack.childCount
        for (i in 0 until n) {
            val order = if (expanded) i else n - 1 - i
            springTo(stack.getChildAt(i), DynamicAnimation.TRANSLATION_X,
                if (expanded) i * step else 0f, order * BADGE_STAGGER_MS)
        }
        // The count rides with the last badge.
        springTo(tvCount, DynamicAnimation.TRANSLATION_X,
            if (expanded) (n - 1) * step else 0f,
            (if (expanded) n - 1 else 0) * BADGE_STAGGER_MS)

        if (expanded) staggerRowsIn()
        updateState()
    }

    /** Stops everything in flight; values stay where they are until the next set. */
    fun cancelMotion() {
        for (r in pending) removeCallbacks(r)
        pending.clear()
        for (s in springs) s.cancel()
        springs.clear()
        for (i in 0 until list.childCount) list.getChildAt(i).animate().cancel()
    }

    override fun onDetachedFromWindow() {
        // A row re-attached later must never show a half-finished spread.
        cancelMotion()
        if (sources.isNotEmpty()) snapTo(expanded)
        super.onDetachedFromWindow()
    }

    // ── Building ──────────────────────────────────────────────────────────────

    private fun buildHeader() {
        val providers = TurnSource.providers(sources)
        stack.removeAllViews()
        val shown = providers.take(MAX_BADGES)
        for ((i, p) in shown.withIndex())
            stack.addView(badge(TurnSource.badgeLetters(p), colourFor(p)), badgeLp(i))
        val extra = providers.size - shown.size
        if (extra > 0) stack.addView(badge("+$extra", OVERFLOW_COLOUR), badgeLp(stack.childCount))

        val count = if (sources.size == 1) "1 source" else "${sources.size} sources"
        tvCount.text = count
        header.contentDescription = count + ": " + providers.joinToString(", ")
        // Reserve the fully spread width so the count never draws past the bubble.
        header.setPaddingRelative(0, 0, (stack.childCount - 1) * spreadPx(), 0)
    }

    private fun buildRows() {
        list.removeAllViews()
        for (s in sources)
            list.addView(row(s), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun badge(text: String, colour: Int): TextView {
        val tv = TextView(context)
        tv.text = text
        tv.gravity = Gravity.CENTER
        tv.includeFontPadding = false
        tv.setTextColor(Color.WHITE)
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, BADGE_TEXT_SP)
        tv.setTypeface(tv.typeface, Typeface.BOLD) // theme face (Jakarta), bold
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(colour)
        // Ring in the bubble colour, so an overlapped badge reads as a separate disc.
        bg.setStroke(dp(RING_DP), ContextCompat.getColor(context, R.color.surface_card))
        tv.background = bg
        return tv
    }

    private fun badgeLp(index: Int): LayoutParams {
        val lp = LayoutParams(dp(BADGE_DP), dp(BADGE_DP))
        if (index > 0) lp.marginStart = -dp(OVERLAP_DP)
        return lp
    }

    private fun row(s: TurnSource): View {
        val row = LinearLayout(context)
        row.orientation = HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.minimumHeight = dp(ROW_MIN_DP)

        val title = TextView(context)
        title.text = if (s.kind == TurnSource.Kind.SERIES) "${s.seriesId} · ${s.title}" else s.title
        title.setTextColor(ContextCompat.getColor(context, R.color.text_navy))
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_secondary))
        title.maxLines = 2
        title.ellipsize = TextUtils.TruncateAt.END
        row.addView(title, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, 1f))

        val provider = TextView(context)
        provider.text = s.provider
        provider.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
        provider.setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_caption))
        provider.maxLines = 1
        val providerLp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        providerLp.marginStart = dp(COUNT_GAP_DP)
        row.addView(provider, providerLp)

        if (canOpen(s)) {
            row.setBackgroundResource(rippleRes())
            row.isClickable = true
            row.isFocusable = true
            row.contentDescription = title.text.toString() + ", " + s.provider + ", " +
                (if (s.kind == TurnSource.Kind.SERIES) "opens the metric" else "opens the article")
            row.setOnClickListener { onOpen?.invoke(s) }
        }
        return row
    }

    private fun canOpen(s: TurnSource): Boolean = when (s.kind) {
        TurnSource.Kind.SERIES -> MetricPopupRegistry.popupIdForSeries(s.seriesId) != null
        TurnSource.Kind.HEADLINE -> !s.url.isNullOrEmpty()
    }

    // ── State and motion ──────────────────────────────────────────────────────

    private fun snapTo(expanded: Boolean) {
        val step = spreadPx().toFloat()
        val n = stack.childCount
        for (i in 0 until n) stack.getChildAt(i).translationX = if (expanded) i * step else 0f
        tvCount.translationX = if (expanded) (n - 1) * step else 0f
        list.visibility = if (expanded) VISIBLE else GONE
        for (i in 0 until list.childCount) {
            val r = list.getChildAt(i)
            r.alpha = 1f
            r.translationY = 0f
        }
        updateState()
    }

    private fun staggerRowsIn() {
        val rise = dp(ROW_RISE_DP).toFloat()
        for (i in 0 until list.childCount) {
            val r = list.getChildAt(i)
            r.alpha = 0f
            r.translationY = rise
            val delay = i * ROW_STAGGER_MS
            r.animate().alpha(1f).setStartDelay(delay).setDuration(ROW_FADE_MS).start()
            springTo(r, DynamicAnimation.TRANSLATION_Y, 0f, delay)
        }
    }

    private fun springTo(
        v: View, property: DynamicAnimation.ViewProperty, target: Float, delayMs: Long
    ) {
        val anim = SpringAnimation(v, property, target)
        anim.spring.setDampingRatio(DAMPING_RATIO).setStiffness(STIFFNESS)
        springs.add(anim)
        if (delayMs <= 0L) {
            anim.start()
            return
        }
        val r = Runnable { anim.start() }
        pending.add(r)
        postDelayed(r, delayMs)
    }

    private fun updateState() {
        ViewCompat.setStateDescription(header, if (expanded) "Expanded" else "Collapsed")
    }

    private fun spreadPx(): Int = dp(OVERLAP_DP + GAP_DP)

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun rippleRes(): Int {
        val tv = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        return tv.resourceId
    }

    companion object {
        private const val MAX_BADGES = 4
        private const val BADGE_DP = 20f
        private const val RING_DP = 2f
        private const val OVERLAP_DP = 6f
        private const val GAP_DP = 2f
        private const val BADGE_TEXT_SP = 8.5f
        private const val COUNT_GAP_DP = 8f
        private const val HEADER_MIN_DP = 44f
        private const val ROW_MIN_DP = 48f

        private const val BADGE_STAGGER_MS = 30L
        private const val LIST_MS = 220L
        private const val ROW_STAGGER_MS = 45L
        private const val ROW_FADE_MS = 340L
        private const val ROW_RISE_DP = 4f
        // PressFeedback / CentreOutStagger constants, copied so the app keeps one signature.
        private const val DAMPING_RATIO = 0.75f
        private const val STIFFNESS = 1200f

        /** Fixed badge palette: navy, gold, green, slate, brick, steel. */
        private val PALETTE = intArrayOf(
            0xFF0F1729.toInt(), 0xFF8A733A.toInt(), 0xFF3D8F68.toInt(),
            0xFF5B6478.toInt(), 0xFFA8473C.toInt(), 0xFF3B5B8A.toInt()
        )
        private val OVERFLOW_COLOUR = 0xFF7C8493.toInt()

        /** Stable per name: String.hashCode is specified, so FRED is always the same colour. */
        private fun colourFor(name: String): Int = PALETTE[Math.floorMod(name.hashCode(), PALETTE.size)]
    }
}
