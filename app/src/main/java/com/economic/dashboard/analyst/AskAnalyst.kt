package com.economic.dashboard.analyst

import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.TextView

import androidx.fragment.app.FragmentActivity

import com.economic.dashboard.ui.AiAnalystBottomSheet
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
        val existing = fm.findFragmentByTag(AiAnalystBottomSheet.TAG)
        if (existing is AiAnalystBottomSheet && existing.isAdded) {
            existing.submitExternalQuery(query, screenContext, concise)
        } else {
            AiAnalystBottomSheet.newInstanceForGesture(query, screenContext, concise)
                .show(fm, AiAnalystBottomSheet.TAG)
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
     */
    @JvmStatic
    fun wireCardLongPress(card: View?, activity: FragmentActivity?, label: String) {
        if (card == null || activity == null) return
        card.setOnLongClickListener { v ->
            val menu = PopupMenu(v.context, v)
            menu.menu.add(0, 1, 0, "Ask Econ Analyst")
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
    fun attachChartExplain(chart: LineChart?, activity: FragmentActivity?, description: String) {
        if (chart == null || activity == null) return
        // OnChartGestureListener has seven abstract methods, so it is not a SAM
        // type — an object expression is required, and every override must be
        // reproduced including the empty ones.
        chart.setOnChartGestureListener(object : OnChartGestureListener {
            override fun onChartLongPressed(me: MotionEvent?) {
                val menu = PopupMenu(chart.context, chart)
                menu.menu.add(0, 1, 0, "✨ Explain this chart")
                menu.setOnMenuItemClickListener { item ->
                    if (item.itemId == 1) {
                        val stats = describeChartData(chart)
                        val query = "Explain the chart showing " + description +
                            ". What's the trend and what's driving it?"
                        val context = if (stats.isEmpty()) "" else
                            "\nSCREEN CONTEXT: The user long-pressed a chart showing " +
                                description + ". On-screen chart data: " + stats +
                                ". Use these values directly and do not fetch them again.\n"
                        openWithQuery(activity, query, context, true)
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
            }
            return sb.toString()
        } catch (e: Exception) {
            return ""
        }
    }
}
