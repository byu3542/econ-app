package com.economic.dashboard.analyst

import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.TextView

import com.economic.dashboard.R
import android.content.res.ColorStateList
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.fragment.app.FragmentActivity

import com.economic.dashboard.ui.assistant.AssistantFragment
import com.economic.dashboard.ui.views.RollingNumberView
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.listener.ChartTouchListener
import com.github.mikephil.charting.listener.OnChartGestureListener

import java.util.Locale

/** Shared entry points into the AI Analyst from anywhere in the app. */
object AskAnalyst {

    /**
     * Opens the analyst sheet with a query; reuses an open sheet if present.
     *
     * Kept as two explicit overloads rather than one function with default
     * arguments: 39 Java call sites across 12 fragments and MainActivity bind
     * to these exact signatures, and default args alone would not synthesize
     * the 2-arg form for them.
     */
    @JvmStatic
    fun openWithQuery(activity: FragmentActivity?, query: String?) {
        openWithQuery(activity, query, "", false)
    }

    /**
     * Opens the analyst sheet with a query, an optional screen-context block for
     * the system prompt, and a concise-mode flag for one-tap gesture queries
     * (AI Laws 7, 8, 12: gestures inject live values and get shorter answers).
     *
     * Reference parameters are nullable on purpose. Callers are Java fragments
     * that can genuinely pass null (e.g. a findViewById result), and the guard
     * below silently returns for them today. Declaring them non-null would make
     * Kotlin's generated null-check throw instead — a runtime crash at fragment
     * setup where there is currently a no-op.
     */
    @JvmStatic
    fun openWithQuery(
        activity: FragmentActivity?, query: String?,
        screenContext: String, concise: Boolean
    ) {
        if (activity == null || query == null || query.trim().isEmpty()) return
        val fm = activity.supportFragmentManager
        val existing = fm.findFragmentByTag(AssistantFragment.TAG)
        if (existing is AssistantFragment && existing.isAdded) {
            existing.submitExternalQuery(query, screenContext, concise)
        } else {
            AssistantFragment.show(fm, AssistantFragment.newInstanceForGesture(query, screenContext, concise))
        }
    }

    /**
     * Long-press on a metric card → confirmation menu → ask the AI Analyst.
     * Mirrors the Overview screen's behavior so every card in the app works
     * the same way.
     *
     * AI Law 8: the card already shows the value the user is asking about, so
     * harvest its on-screen text at press time and hand it to the model inline.
     * A model that's been given the number has no reason to announce a lookup —
     * this closes the "it says it'll fetch data then ends" path at its source.
     *
     * @param seriesId the [EconomicSeriesRegistry] id backing this card, named
     *   in the screen context so any get_series call the turn makes pulls the
     *   pressed metric's own history — or null when the card shows a derived
     *   value no single catalogued series carries (the Treasury spreads).
     *   Deliberately a required parameter with no default: an earlier revision
     *   resolved the series by matching tokens in [label], and replayed against
     *   the app's 27 real card labels 7 resolved wrongly — four to nothing, and
     *   both Treasury spread cards silently to DGS10, so a card showing a
     *   spread was answered with 10-Year yield levels.
     */
    @JvmStatic
    fun wireCardLongPress(
        card: View?, activity: FragmentActivity?, label: String, seriesId: String?
    ) {
        if (card == null || activity == null) return
        markLongPressable(card)
        card.setOnLongClickListener { v ->
            val menu = PopupMenu(v.context, v)
            // TICKET-P2-8: one marker + name across every analyst door.
            menu.menu.add(0, 1, 0, AnalystIdentity.label("Ask " + AnalystIdentity.NAME))
            menu.setOnMenuItemClickListener { item ->
                if (item.itemId == 1) {
                    // Harvest at press time, not wire time — values load async.
                    val cardText = harvestCardText(card)
                    val query: String
                    var context = ""
                    if (!cardText.isEmpty()) {
                        // AI Law 7: one focused ask, not a three-part essay prompt.
                        query = "The dashboard card for " + label + " currently shows: " +
                            cardText + ". What does this reading mean?"
                        context = "\nSCREEN CONTEXT: The user long-pressed the " + label +
                            " card, which currently shows: " + cardText +
                            ". These are the live on-screen values — use them " +
                            "directly and do not fetch them again.\n"
                    } else {
                        query = "What does the current reading for " + label +
                            " on the dashboard mean?"
                    }
                    context += pressedSeriesBlock(seriesId)
                    openWithQuery(activity, query, context, true)
                    return@setOnMenuItemClickListener true
                }
                false
            }
            menu.show()
            true
        }
    }

    /**
     * TICKET-59: every card wired here shows a 12dp sparkle at 50% alpha in its
     * top-trailing corner — the same glyph as the analyst FAB — so the
     * long-press has a visible affordance, and TalkBack names the action
     * ("double-tap and hold to ask the Econ Analyst"). Cards are CardViews
     * (FrameLayouts), so the marker is an overlay child; a card re-wired on
     * refresh keeps its single marker (tag check).
     */
    private fun markLongPressable(card: View) {
        ViewCompat.replaceAccessibilityAction(
            card, AccessibilityActionCompat.ACTION_LONG_CLICK,
            card.context.getString(R.string.a11y_ask_analyst), null
        )
        if (card !is FrameLayout || card.findViewWithTag<View>(TAG_SPARKLE) != null) return
        val res = card.resources
        val size = res.getDimensionPixelSize(R.dimen.icon_sparkle_marker)
        val margin = res.getDimensionPixelSize(R.dimen.space_sm)
        val iv = ImageView(card.context)
        iv.tag = TAG_SPARKLE
        iv.setImageResource(R.drawable.ic_sparkle_12)
        iv.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(card.context, R.color.ink))
        iv.alpha = 0.5f
        iv.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        val lp = FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.END)
        lp.setMargins(margin, margin, margin, margin)
        card.addView(iv, lp)
    }

    private const val TAG_SPARKLE = "askAnalystSparkle"

    /**
     * Names the pressed card's series so a get_series call in this turn pulls
     * the metric the user actually pressed, instead of one the model infers
     * from the card's wording.
     *
     * Returns an empty block when the id is null or is not in the registry:
     * an unresolved card falls back to the on-screen values plus the system
     * prompt's honesty rule, which reads as unhelpful rather than as
     * confidently wrong. That trade is the point — the guessing version this
     * replaced injected the wrong series without ever saying so.
     */
    private fun pressedSeriesBlock(seriesId: String?): String {
        if (seriesId == null || seriesId.isEmpty())
            return "\nPRESSED METRIC: This card's value is derived from more than one " +
                "series, so no single get_series id backs it. Answer from the on-screen " +
                "values above and say plainly what you don't have — do not pull one leg " +
                "of it and describe that as the card's number.\n"
        val entry = EconomicSeriesRegistry.byId(seriesId) ?: return ""
        return "\nPRESSED METRIC: The card is backed by series_id \"" + entry.id + "\" (" +
            entry.chartLabel + "). If this turn needs its history, call get_series with " +
            "exactly that id — never infer a series from the card's label.\n"
    }

    /**
     * Long-press on any MPAndroidChart → "Explain this chart" menu.
     * Uses the chart's own gesture listener so it doesn't fight pan/zoom.
     *
     * AI Law 8: the chart's latest value and range are read off the chart data
     * at press time and injected, so the model never needs to fetch what the
     * screen already shows.
     *
     * @param description e.g. "the 10-year Treasury yield over the past 24 months"
     */
    @JvmStatic
    @JvmOverloads
    fun attachChartExplain(
        chart: LineChart?, activity: FragmentActivity?, description: String,
        seriesId: String? = null
    ) {
        if (chart == null || activity == null) return
        // OnChartGestureListener has seven abstract methods, so it is not a SAM
        // type — an object expression is required, and every override must be
        // reproduced including the empty ones.
        chart.setOnChartGestureListener(object : OnChartGestureListener {
            override fun onChartLongPressed(me: MotionEvent?) {
                val menu = PopupMenu(chart.context, chart)
                // TICKET-P2-8: action first, then the assistant's name — the
                // marker alone left this door anonymous.
                menu.menu.add(0, 1, 0,
                    AnalystIdentity.label("Explain this chart — " + AnalystIdentity.NAME))
                menu.setOnMenuItemClickListener { item ->
                    if (item.itemId == 1) {
                        val stats = describeChartData(chart)
                        val query = "Explain the chart showing " + description +
                            ". What's the trend and what's driving it?"
                        val context = if (stats.isEmpty()) "" else
                            "\nSCREEN CONTEXT: The user long-pressed a chart showing " +
                                description + ". On-screen chart data: " + stats +
                                ". Use these values directly and do not fetch them again.\n"
                        // AIC-01: only a chart that names its series gets the
                        // PRESSED METRIC block; null means "not wired", not
                        // "derived", so the derived-value warning is skipped.
                        val seriesBlock =
                            if (seriesId.isNullOrEmpty()) "" else pressedSeriesBlock(seriesId)
                        openWithQuery(activity, query, context + seriesBlock, true)
                        return@setOnMenuItemClickListener true
                    }
                    false
                }
                menu.show()
            }

            override fun onChartGestureStart(me: MotionEvent?, g: ChartTouchListener.ChartGesture?) {}

            override fun onChartGestureEnd(me: MotionEvent?, g: ChartTouchListener.ChartGesture?) {
                // TICKET-23: clear the crosshair marker when a scrub gesture ends.
                chart.highlightValue(null)
            }

            override fun onChartDoubleTapped(me: MotionEvent?) {}
            override fun onChartSingleTapped(me: MotionEvent?) {}
            override fun onChartFling(me1: MotionEvent?, me2: MotionEvent?, vX: Float, vY: Float) {}
            override fun onChartScale(me: MotionEvent?, sX: Float, sY: Float) {}
            override fun onChartTranslate(me: MotionEvent?, dX: Float, dY: Float) {}
        })
    }

    /** Collects the visible text a metric card is showing (label, value, delta, date). */
    private fun harvestCardText(card: View): String {
        val sb = StringBuilder()
        collectText(card, sb)
        val s = sb.toString().trim()
        return if (s.endsWith("·")) s.substring(0, s.length - 1).trim() else s
    }

    private fun collectText(v: View?, sb: StringBuilder) {
        if (v == null || v.visibility != View.VISIBLE) return
        // AIC-01: a RollingNumberView is built from digit columns that each
        // hold all ten digits, so walking its children read "0 · 1 · 2 … 9".
        // Take the value it was set to instead, and don't descend.
        if (v is RollingNumberView) {
            val t = v.getText()
            if (t.isNotEmpty()) {
                if (sb.length > 0) sb.append(" · ")
                sb.append(t.toString().trim())
            }
            return
        }
        if (v is TextView) {
            val t: CharSequence? = v.text
            if (t != null && t.length > 0) {
                if (sb.length > 0) sb.append(" · ")
                sb.append(t.toString().trim())
            }
        } else if (v is ViewGroup) {
            for (i in 0 until v.childCount) collectText(v.getChildAt(i), sb)
        }
    }

    /** Latest value + range for each dataset currently plotted on the chart. */
    private fun describeChartData(chart: LineChart): String {
        try {
            // Explicit getters on MPAndroidChart, matching ChartHelper.kt: the
            // library cannot be built against in the tooling sandbox, so its
            // accessor shapes are never assumed (migration lesson 12).
            val data = chart.getData()
            if (data == null || data.getDataSetCount() == 0) return ""
            val sb = StringBuilder()
            for (ds in data.getDataSets()) {
                val n = ds.getEntryCount()
                if (n == 0) continue
                val last = ds.getEntryForIndex(n - 1)
                if (sb.length > 0) sb.append("; ")
                val label = ds.getLabel()
                sb.append(if (label == null || label.isEmpty()) "series" else label)
                    .append(String.format(Locale.US,
                        " — latest %.2f, range %.2f to %.2f across %d points",
                        last.getY(), ds.getYMin(), ds.getYMax(), n))
                // AIC-01: date the latest point with the chart's own x-axis
                // labels, so the model knows how fresh the reading is.
                val date = try {
                    chart.getXAxis().getValueFormatter().getFormattedValue(last.getX())
                } catch (e: Exception) { "" }
                if (!date.isNullOrBlank()) sb.append(" (latest point ").append(date).append(")")
            }
            return sb.toString()
        } catch (e: Exception) {
            return ""
        }
    }
}
