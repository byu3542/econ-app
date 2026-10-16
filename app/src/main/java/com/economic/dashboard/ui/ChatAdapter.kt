package com.economic.dashboard.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Typeface
import android.text.Spannable
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.util.TypedValue
import android.view.ActionMode
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors
import androidx.recyclerview.widget.RecyclerView
import com.economic.dashboard.analyst.ReleaseCalendar
import com.economic.dashboard.R
import com.economic.dashboard.analyst.AnalystIdentity
import com.economic.dashboard.analyst.AnalystToolExecutor
import com.economic.dashboard.analyst.ChatSeriesData
import com.economic.dashboard.analyst.EconomicSeriesRegistry
import com.economic.dashboard.models.ChatChart
import com.economic.dashboard.models.ChatMessage
import com.economic.dashboard.models.TurnSource
import com.economic.dashboard.news.NewsAdapter
import com.economic.dashboard.ui.assistant.AssistantLoaderView
import com.economic.dashboard.ui.views.SourceStackView
import com.economic.dashboard.utils.AppExecutors
import com.economic.dashboard.utils.MotionUtil
import com.economic.dashboard.utils.AxisDateFormat
import com.economic.dashboard.utils.ChartHelper
import com.economic.dashboard.utils.ChartPalette
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import io.noties.markwon.Markwon
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap
import java.util.regex.Pattern

class ChatAdapter(
    private val retryListener: OnRetryListener?,
    private val actionListener: OnAnalystActionListener?
) : RecyclerView.Adapter<ChatAdapter.ViewHolder>() {

    fun interface OnRetryListener {
        fun onRetry()
    }

    /** Callbacks into MainActivity for interactive analyst replies. */
    interface OnAnalystActionListener {
        /** A tappable metric citation was tapped — navigate to that tab. */
        fun onMetricTapped(tabKey: String)

        /** "Ask AI" chosen from the text-selection menu. */
        fun onAskAi(query: String)

        /** TICKET-5 (AI Law 12): "Ask AI" on a selection that exactly matches a
         *  known metric name — the host injects that metric's snapshot value +
         *  screen context and asks concisely, mirroring a card long-press. */
        fun onAskAiMetric(metricName: String)

        /** MOT-04: a series row in the sources stack was tapped — open that
         *  metric's popup. */
        fun onOpenMetricPopup(popupId: String)

        /** 2026-10-12 analyst cards: open a metric sheet on a pane ("graph" /
         *  "table") at a window, over the chat. The sheet rounds [months] up
         *  to the nearest window it offers, past the cadence floor. */
        fun onOpenMetricSheet(popupId: String, pane: String?, months: Int?) = onOpenMetricPopup(popupId)

        /** The ViewModel chat cards read sheet history from; null → cards
         *  draw registry values with no pill. */
        fun metricViewModel(): EconomicViewModel? = null
    }

    /** AIC-05: what the row under an answer can do, served by the open
     *  analyst sheet. Null while no sheet is showing — the row hides. */
    interface TurnActions {
        /** A request is in flight: the newest answer is not finished yet. */
        fun isBusy(): Boolean
        fun onRegenerate()
        fun onContinue()
        /** The newest answer switched to another variant; the sheet keeps the
         *  conversation history on the answer the user is looking at. */
        fun onVariantShown(text: String?)
        /** AIC-08: "Start new chat" tapped on the history-cut divider. */
        fun onStartNewChat()
    }

    /** AIC-08: row that gets the "older messages aren't sent" divider above it —
     *  the oldest question still inside the analyst's history window; -1 = none. */
    private var historyCutIndex = -1

    /** AIC-08: places the divider before the [keepPairs]-th newest question.
     *  Matches the sheet's HISTORY_CAP slice, which is whole Q&A pairs. */
    fun refreshHistoryCut(keepPairs: Int) {
        val users = messages.indices.filter { messages[it].isUser }
        val cut = if (users.size > keepPairs) users[users.size - keepPairs] else -1
        if (cut == historyCutIndex) return
        val old = historyCutIndex
        historyCutIndex = cut
        if (old in messages.indices) notifyItemChanged(old, PAYLOAD_CUT)
        if (cut >= 0) notifyItemChanged(cut, PAYLOAD_CUT)
    }

    private fun bindHistoryCut(holder: ViewHolder, position: Int) {
        val show = position == historyCutIndex
        holder.tvHistoryCut.visibility = if (show) View.VISIBLE else View.GONE
        holder.tvHistoryCut.setOnClickListener(
            if (show) View.OnClickListener { turnActions?.onStartNewChat() } else null)
    }

    /** Read when the long-press sheet opens (KTD6), so nothing redraws on change. */
    var turnActions: TurnActions? = null

    private val messages = ArrayList<ChatMessage>()
    private var markwon: Markwon? = null

    /** TICKET-12 (Peak): analyst insights we've already played the reveal for,
     *  so each insight animates in exactly once (WeakHashMap avoids leaks). */
    private val revealed: MutableSet<ChatMessage> = Collections.newSetFromMap(WeakHashMap())

    fun addMessage(message: ChatMessage) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
        // Rebind the previous row: its run-grouping / timestamp context changed
        if (messages.size > 1) notifyItemChanged(messages.size - 2)
    }

    /** Replaces the whole list — used when restoring a persisted conversation. */
    fun setMessages(restored: List<ChatMessage>?) {
        messages.clear()
        historyCutIndex = -1
        if (restored != null) messages.addAll(restored)
        notifyDataSetChanged()
    }

    // ── U7 (R5, KTD2): one loader row stands in for a pending reply ──────────

    private fun loaderIndex(): Int {
        for (i in messages.indices.reversed()) if (messages[i].isLoader) return i
        return -1
    }

    /** Adds the loader row for a reply that has just been asked for. */
    fun showLoader() {
        if (loaderIndex() < 0) addMessage(ChatMessage("", false, isLoader = true))
    }

    /** AE1: [phrase] replaces the themed rotation while a tool runs; null
     *  hands back to it. */
    fun setLoaderTool(phrase: String?) {
        val i = loaderIndex()
        if (i < 0 || messages[i].loaderTool == phrase) return
        messages[i].loaderTool = phrase
        notifyItemChanged(i, PAYLOAD_LOADER)
    }

    /**
     * The finished answer, a stopped partial or an error bubble takes the
     * loader's place in one full bind, so its text, charts and sources appear
     * in the same frame and no partial text is ever on screen (AE2). With no
     * loader showing, [m] is added at the end.
     */
    fun replaceLoader(m: ChatMessage) {
        val i = loaderIndex()
        if (i < 0) {
            addMessage(m)
            return
        }
        messages[i] = m
        notifyItemChanged(i)
    }

    /** Backstop for every way a request ends: no spinner is left behind. */
    fun removeLoader() {
        val i = loaderIndex()
        if (i < 0) return
        messages.removeAt(i)
        notifyItemRemoved(i)
    }

    /** AIC-11: "newer data out" chip when a series this answer used has had a
     *  newer release since it was fetched. Checked at bind, so a restored
     *  answer goes stale on its own as release dates pass. */
    private fun bindStale(holder: ViewHolder, m: ChatMessage) {
        val stale = !m.isUser && !m.isLoader && m.sources.any {
            it.kind == TurnSource.Kind.SERIES && ReleaseCalendar.isStale(it.seriesId, it.latestDate)
        }
        holder.tvStaleData.visibility = if (stale) View.VISIBLE else View.GONE
    }

    /** MOT-04: binds the sources stack. [toggled] animates an open/close of
     *  the already-bound stack; otherwise it is a full bind with no motion. */
    private fun bindSources(holder: ViewHolder, m: ChatMessage, toggled: Boolean) {
        bindStale(holder, m)
        val stack = holder.sourceStack
        if (m.isUser || m.isError || m.isLoader || m.sources.isEmpty()) {
            stack.clear()
            return
        }
        if (toggled) {
            stack.setExpanded(m.sourcesExpanded, holder.itemView as ViewGroup)
            return
        }
        stack.bind(m.sources, m.sourcesExpanded)
        stack.onToggle = {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                val msg = messages[pos]
                msg.sourcesExpanded = !msg.sourcesExpanded
                notifyItemChanged(pos, PAYLOAD_SOURCES_TOGGLE)
            }
        }
        stack.onOpen = { s ->
            when (s.kind) {
                TurnSource.Kind.SERIES ->
                    s.seriesId?.let { MetricPopupRegistry.popupIdForSeries(it) }
                        ?.let { actionListener?.onOpenMetricPopup(it) }
                TurnSource.Kind.HEADLINE ->
                    s.url?.let { NewsAdapter.openArticle(stack.context, it) }
            }
        }
    }

    fun messageAt(index: Int): ChatMessage? = messages.getOrNull(index)

    /** AIC-05: the question the answer at [index] replies to, or null (the
     *  welcome bubble answers nothing). Read from the list, not the sheet's
     *  last query, which a restart does not restore. */
    fun questionFor(index: Int): String? {
        for (i in index - 1 downTo 0) if (messages[i].isUser) return messages[i].text
        return null
    }

    /** AIC-05: index of the newest analyst answer, or -1. */
    fun lastAnswerIndex(): Int {
        for (i in messages.indices.reversed()) {
            val m = messages[i]
            if (m.isUser) return -1
            if (isAnswer(m)) return i
        }
        return -1
    }

    /**
     * AIC-05: Regenerate takes the newest answer off screen and returns it, so the sheet can carry it as a variant of the
     * replacement. Null when there is no answer to replace.
     */
    fun removeLastAnswer(): ChatMessage? {
        val i = lastAnswerIndex()
        if (i < 0) return null
        val m = messages.removeAt(i)
        notifyItemRemoved(i)
        return m
    }

    /** Removes the most recent error bubble (before a retry). */
    fun removeLastError() {
        for (i in messages.indices.reversed()) {
            if (messages[i].isError) {
                messages.removeAt(i)
                notifyItemRemoved(i)
                break
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_chat_message, parent, false)
        return ViewHolder(v)
    }

    /** Lazily builds Markwon; **bold** key figures render as plain bold text
     *  (TICKET-43 follow-up: gold ink on every bold span overloaded the accent). */
    private fun ensureMarkwon(ctx: Context) {
        if (markwon != null) return
        markwon = Markwon.builder(ctx).build()
    }

    private fun renderText(holder: ViewHolder, m: ChatMessage) {
        if (!m.isUser && !m.isError) {
            // ensureMarkwon() always runs before renderText() in onBindViewHolder
            // — fail loudly if that invariant is ever broken.
            val mw = checkNotNull(markwon) { "ensureMarkwon() must run before renderText()" }
            // stripStrayMarkdown's signature stays nullable-in/nullable-out because
            // ChatAdapterTest calls it directly with null and asserts a null result.
            // stripChartTags always returns non-null, so this fallback is unreachable
            // in practice — it just satisfies the compiler without loosening that contract.
            // AIC-09: an answer cut short by Stop can end mid-**bold**; close
            // it in this render copy only, never in the stored text.
            val display = closeDanglingBold(stripStrayMarkdown(stripChartTags(m.text))) ?: ""
            mw.setMarkdown(holder.tvMessage, display)
            addMetricCitations(holder.tvMessage)
        } else {
            holder.tvMessage.text = m.text
        }
    }

    /**
     * Turns the first occurrence of each known indicator name into a tappable
     * link that navigates to the matching dashboard tab. Chat becomes navigation.
     */
    private fun addMetricCitations(tv: TextView) {
        val listener = actionListener ?: return
        val cs = tv.text
        if (cs !is Spannable) return
        val lower = cs.toString().lowercase(Locale.US)

        val claimed = ArrayList<IntArray>()
        var any = false
        for ((key, tabKey) in METRIC_TABS) {
            val idx = lower.indexOf(key)
            if (idx < 0) continue
            val end = idx + key.length
            var overlaps = false
            for (c in claimed) {
                if (idx < c[1] && end > c[0]) { overlaps = true; break }
            }
            if (overlaps) continue
            claimed.add(intArrayOf(idx, end))
            // T3: a metric with its own sheet opens it in place; the rest
            // ("yield curve", "treasury") still jump to their dashboard tab.
            val popupId = METRIC_SHEETS[key]
            cs.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) {
                    if (popupId != null) listener.onOpenMetricSheet(popupId, null, null)
                    else listener.onMetricTapped(tabKey)
                }
                override fun updateDrawState(ds: TextPaint) {
                    ds.isUnderlineText = true // keep bubble text color, just underline
                }
            }, idx, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            any = true
        }
        if (any) tv.movementMethod = LinkMovementMethod.getInstance()
    }

    /**
     * TICKET-P2-2: binds the reply's charts — one row per render_chart call, in
     * the order the analyst asked for them.
     *
     * Reads [ChatMessage.charts], never the message text. The old renderer
     * regex-parsed a [CHART:...] tag out of the prose on every bind, which
     * capped a reply at one chart and made the client a parser of model output.
     */
    private fun bindCharts(holder: ViewHolder, m: ChatMessage) {
        val container = holder.llChartContainer
        val charts = if (m.isUser || m.isError || m.isLoader) emptyList() else m.charts

        // Invalidate any load still in flight for this holder. The old guard
        // compared a cache key against a single SparklineView's `tag`, which
        // cannot identify a *slot* once a bubble holds several charts — a fast
        // scroll could paint one message's series under another's answer, and
        // that reads as a data bug, not a UI bug. A per-bind token is the
        // cheapest thing that can't confuse two rows.
        holder.chartBindToken++
        val token = holder.chartBindToken

        if (charts.isEmpty()) {
            container.visibility = View.GONE
            container.removeAllViews()
            return
        }
        container.visibility = View.VISIBLE

        // Reuse rows rather than rebuilding: a recycled holder keeps its chart
        // rows, and re-inflating N charts on every rebind would churn views.
        while (container.childCount > charts.size)
            container.removeViewAt(container.childCount - 1)
        val inflater = LayoutInflater.from(container.context)

        for (i in charts.indices) {
            // Rows are reused by index, but a slot can change type (chart vs
            // table) between messages — each row is tagged with its layout id
            // and swapped when the type no longer matches.
            val layout = if (charts[i].isTable) R.layout.view_chat_table else R.layout.view_chat_chart
            var row = if (i < container.childCount) container.getChildAt(i) else null
            if (row == null || row.getTag(R.id.llChartContainer) != layout) {
                if (row != null) container.removeViewAt(i)
                row = inflater.inflate(layout, container, false)
                row.setTag(R.id.llChartContainer, layout)
                container.addView(row, i)
            }
            if (charts[i].isTable) bindOneTable(row!!, charts[i], holder, token)
            else bindOneChart(row!!, charts[i], holder, token)
        }
    }

    /** 2026-10-12 analyst cards T2: one table card. Same resolve, cache and
     *  token rules as [bindOneChart]. */
    private fun bindOneTable(row: View, chart: ChatChart, holder: ViewHolder, token: Long) {
        row.findViewById<TextView>(R.id.tvTableLabel).text =
            SERIES_LABELS[chart.seriesId] ?: chart.seriesId

        val cacheKey = "${chart.seriesId}:${chart.months}"
        val cached: ChatSeriesData.Card?
        synchronized(seriesCache) { cached = seriesCache[cacheKey] }
        if (cached != null) { applyTableCard(row, cached); return }

        row.visibility = View.INVISIBLE
        val appCtx = row.context.applicationContext
        val vm = actionListener?.metricViewModel()
        AppExecutors.diskIO().execute {
            val card = ChatSeriesData.resolve(appCtx, vm, chart.seriesId, chart.months)
            if (card.popupId == null || card.grade != null)
                synchronized(seriesCache) { seriesCache[cacheKey] = card }
            row.post { if (token == holder.chartBindToken) applyTableCard(row, card) }
        }
    }

    /** Daily series collapse to month-end rows; the last [TABLE_ROWS] months
     *  show newest first (newest bold), each with its change from the month
     *  before. Fewer than two months hides the row, like a chart. */
    private fun applyTableCard(row: View, card: ChatSeriesData.Card) {
        val monthly = LinkedHashMap<String, Double>()
        for (p in card.points) if (p.date.length >= 7) monthly[p.date.substring(0, 7)] = p.value
        val months = monthly.entries.toList()
        if (months.size < 2) {
            row.visibility = View.GONE
            return
        }
        row.visibility = View.VISIBLE
        val ctx = row.context
        row.findViewById<TextView>(R.id.tvTableLabel).text = card.label
        bindPill(row.findViewById(R.id.llTablePill), row.findViewById(R.id.viewTablePillDot),
            row.findViewById(R.id.tvTablePill), card)
        val shown = minOf(TABLE_ROWS, months.size)
        row.findViewById<TextView>(R.id.tvTableCaption).text =
            "Last $shown of ${months.size} months"

        val rows: LinearLayout = row.findViewById(R.id.llTableRows)
        rows.removeAllViews()
        val size = ctx.resources.getDimension(R.dimen.text_caption)
        val ink = ContextCompat.getColor(ctx, R.color.ink)
        val muted = ContextCompat.getColor(ctx, R.color.muted)
        for (k in 0 until shown) {
            val idx = months.size - 1 - k
            val cur = months[idx]
            val change = if (idx > 0) cur.value - months[idx - 1].value else null
            val line = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            fun cell(text: String, weight: Float, gravity: Int, color: Int) =
                TextView(ctx).apply {
                    this.text = text
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
                    setTextColor(color)
                    this.gravity = gravity
                    if (k == 0) setTypeface(typeface, Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, weight)
                }
            line.addView(cell(AxisDateFormat.axisLabel(cur.key + "-01", "MMM yyyy"),
                1.2f, Gravity.START, ink))
            line.addView(cell(card.format(cur.value), 1f, Gravity.END, ink))
            line.addView(cell(change?.let { signedChange(it) } ?: "—", 1f, Gravity.END, muted))
            rows.addView(line)
        }

        val popupId = card.popupId
        row.isClickable = popupId != null
        row.setOnClickListener(if (popupId == null) null else View.OnClickListener {
            actionListener?.onOpenMetricSheet(popupId, "table", card.months)
        })
    }

    /** "+0.12" / "−0.12" (U+2212 minus, 2 decimals). */
    private fun signedChange(d: Double): String {
        val s = String.format(Locale.US, "%.2f", Math.abs(d))
        return if (d < 0 && s != "0.00") "−$s" else "+$s"
    }

    /**
     * 2026-10-12 analyst cards T1: one chart card. Numbers come from
     * [ChatSeriesData] (sheet history + grade when the series has a sheet), so
     * the card, the prose and the sheet agree. The resolve runs off the main
     * thread; the per-bind token drops a result that lands after the row was
     * rebound to another message.
     */
    private fun bindOneChart(row: View, chart: ChatChart, holder: ViewHolder, token: Long) {
        val lineChart: LineChart = row.findViewById(R.id.chatLineChart)
        if (lineChart.getTag(R.id.chatLineChart) == null) {
            lineChart.setTag(R.id.chatLineChart, true)
            ChartHelper.styleCompactChart(lineChart, object : ValueFormatter() {})
        }
        row.findViewById<TextView>(R.id.tvChartLabel).text =
            SERIES_LABELS[chart.seriesId] ?: chart.seriesId

        val cacheKey = "${chart.seriesId}:${chart.months}"
        val cached: ChatSeriesData.Card?
        synchronized(seriesCache) { cached = seriesCache[cacheKey] }
        if (cached != null) { applyChartCard(row, cached); return }

        row.visibility = View.INVISIBLE
        val appCtx = row.context.applicationContext
        val vm = actionListener?.metricViewModel()
        AppExecutors.diskIO().execute {
            val card = ChatSeriesData.resolve(appCtx, vm, chart.seriesId, chart.months)
            // Don't pin a pre-load fallback: a sheet series resolved before
            // its ViewModel data arrived must resolve again on the next bind.
            if (card.popupId == null || card.grade != null)
                synchronized(seriesCache) { seriesCache[cacheKey] = card }
            row.post { if (token == holder.chartBindToken) applyChartCard(row, card) }
        }
    }

    /** Fewer than two points can't draw a line — hide the row instead of
     *  leaving an empty box under the answer (degrade to text, never to a
     *  visible artifact). */
    private fun applyChartCard(row: View, card: ChatSeriesData.Card) {
        if (card.points.size < 2) {
            row.visibility = View.GONE
            return
        }
        row.visibility = View.VISIBLE
        val ctx = row.context
        row.findViewById<TextView>(R.id.tvChartLabel).text = card.label
        row.findViewById<TextView>(R.id.tvChartValue).text = card.latestText()
        bindPill(row.findViewById(R.id.llChartPill), row.findViewById(R.id.viewChartPillDot),
            row.findViewById(R.id.tvChartPill), card)
        row.findViewById<TextView>(R.id.tvChartCaption).text = card.windowText()

        val lineChart: LineChart = row.findViewById(R.id.chatLineChart)
        lineChart.axisLeft.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String = card.format(value.toDouble())
        }
        val entries = ArrayList<Entry>(card.points.size)
        for (i in card.points.indices) entries.add(Entry(i.toFloat(), card.points[i].value.toFloat()))
        AxisDateFormat.attachLabels(lineChart,
            card.points.map { AxisDateFormat.axisLabel(it.date, AxisDateFormat.COMPACT) })
        val ds = LineDataSet(entries, card.label)
        ChartHelper.styleLineSeries(ds, ChartPalette.primary(ctx)) // U10, KTD10 rule 3: never the accent
        ChartHelper.setLineData(lineChart, LineData(ds))
        ChartHelper.highlightLatest(lineChart)

        val open: TextView = row.findViewById(R.id.btnChartOpen)
        val popupId = card.popupId
        open.visibility = if (popupId != null) View.VISIBLE else View.GONE
        open.setOnClickListener {
            if (popupId != null) actionListener?.onOpenMetricSheet(popupId, "graph", card.months)
        }
    }

    /** Status pill: tier dot + word, graded series only. Tier color on the dot,
     *  never the text — same rule as the sheet's subtitle and bands. */
    private fun bindPill(pill: View, dot: View, word: TextView, card: ChatSeriesData.Card) {
        val g = card.grade
        if (g == null) { pill.visibility = View.GONE; return }
        pill.visibility = View.VISIBLE
        BenchmarkStyle.tintDot(dot, pill.context, g.tier)
        word.text = card.pillText()
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: List<Any>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
            return
        }
        val m = messages[position]
        if (payloads.contains(PAYLOAD_LOADER) && m.isLoader) holder.loader.bind(m.loaderTool) // AE1
        if (payloads.contains(PAYLOAD_SOURCES_TOGGLE)) bindSources(holder, m, true)
        if (payloads.contains(PAYLOAD_CUT)) bindHistoryCut(holder, position)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val m = messages[position]
        ensureMarkwon(holder.itemView.context)
        bindHistoryCut(holder, position)

        // U7 (R5): a pending reply is the loader alone — no text, caret or dots.
        if (m.isLoader) {
            holder.tvMessage.visibility = View.GONE
            holder.loader.visibility = View.VISIBLE
            holder.loader.bind(m.loaderTool)
        } else {
            holder.loader.stop()
            holder.loader.visibility = View.GONE
            holder.tvMessage.visibility = View.VISIBLE
            renderText(holder, m)
        }

        bindCharts(holder, m)
        bindSources(holder, m, false) // MOT-04

        // Attached image (vision messages) — transient, shown only this session
        val bitmap = m.imageBitmap
        if (bitmap != null) {
            holder.ivChatImage.visibility = View.VISIBLE
            holder.ivChatImage.setImageBitmap(bitmap)
        } else {
            holder.ivChatImage.visibility = View.GONE
            holder.ivChatImage.setImageDrawable(null)
        }

        // Pantrii port U6 (R4): user and error rows are bubbles capped at 82%
        // of the thread width; an answer is bare, full-width text. No sender
        // label or timestamp on the row; the time is in the long-press sheet.
        val ctx = holder.itemView.context
        val res = holder.itemView.resources
        val bubble = holder.llBubbleContainer
        val bubbleParams = bubble.layoutParams as ConstraintLayout.LayoutParams
        val threadWidth = res.displayMetrics.widthPixels -
            2 * res.getDimensionPixelSize(R.dimen.space_lg)
        val bubbleMax = (threadWidth * BUBBLE_MAX_FRACTION).toInt()

        if (m.isUser) {
            bubbleParams.matchConstraintDefaultWidth = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT_WRAP
            bubbleParams.matchConstraintMaxWidth = bubbleMax
            bubbleParams.horizontalBias = 1.0f
            bubble.setBackgroundResource(R.drawable.bg_chat_bubble_user)
            bubble.setPadding(dp(res, 15f), dp(res, 11f), dp(res, 15f), dp(res, 11f))
            holder.tvMessage.setTextColor(MaterialColors.getColor(holder.tvMessage, R.attr.econOnAccent))
            holder.tvMessage.setTextIsSelectable(false)
            holder.tvMessage.customSelectionActionModeCallback = null
            holder.tvRetry.visibility = View.GONE
            bubble.isClickable = false
            bubble.setOnClickListener(null)
        } else if (m.isError) {
            bubbleParams.matchConstraintDefaultWidth = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT_WRAP
            bubbleParams.matchConstraintMaxWidth = bubbleMax
            bubbleParams.horizontalBias = 0.0f
            bubble.setBackgroundResource(R.drawable.bg_chat_bubble_error)
            val padH = res.getDimensionPixelSize(R.dimen.space_lg)
            val padV = res.getDimensionPixelSize(R.dimen.space_md)
            bubble.setPadding(padH, padV, padH, padV)
            holder.tvMessage.setTextColor(ContextCompat.getColor(ctx, R.color.error_text))
            holder.tvMessage.setTextIsSelectable(false)
            holder.tvRetry.visibility = View.VISIBLE
            val retry = retryListener
            if (retry != null) {
                holder.tvRetry.setOnClickListener { retry.onRetry() }
                bubble.isClickable = true
                bubble.setOnClickListener { retry.onRetry() }
            }
        } else {
            bubbleParams.matchConstraintDefaultWidth = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT_SPREAD
            bubbleParams.matchConstraintMaxWidth = 0
            bubbleParams.horizontalBias = 0.0f
            bubble.background = null
            bubble.setPadding(0, 0, 0, 0)
            holder.tvMessage.setTextColor(ContextCompat.getColor(ctx, R.color.ink))
            holder.tvRetry.visibility = View.GONE
            bubble.isClickable = false
            bubble.setOnClickListener(null)
            if (!m.isLoader) {
                // Toggle off/on so selection keeps working on recycled views
                holder.tvMessage.setTextIsSelectable(false)
                holder.tvMessage.setTextIsSelectable(true)
                attachAskAiSelection(holder.tvMessage)
                maybeRevealInsight(holder, m)
            }
        }

        // KTD6: long-press anywhere on the message opens its actions sheet. On
        // the text itself this listener wins over the selection long-press;
        // "Select text" in the sheet hands one long-press back to selection.
        // AIC-04: the handler reads the item at press time instead of
        // capturing it here, so a replaced row never acts on the old message.
        val longPress = View.OnLongClickListener { openActions(holder) }
        bubble.setOnLongClickListener(longPress)
        holder.tvMessage.setOnLongClickListener(longPress)

        bubble.layoutParams = bubbleParams
    }

    /**
     * TICKET-12 (Peak): plays a subtle rise-and-fade the first time an analyst
     * insight is shown, so the reply lands as a clear visual "peak" rather than
     * appearing as a flat block. Runs once per message.
     */
    private fun maybeRevealInsight(holder: ViewHolder, m: ChatMessage) {
        if (revealed.contains(m)) return
        revealed.add(m)
        val bubble = holder.llBubbleContainer
        // TICKET-28: respect "reduce motion" — show the insight instantly.
        if (!MotionUtil.animationsEnabled(bubble.context)) {
            bubble.alpha = 1f
            bubble.translationY = 0f
            return
        }
        val dy = 12f * bubble.resources.displayMetrics.density
        bubble.alpha = 0f
        bubble.translationY = dy
        bubble.animate().alpha(1f).translationY(0f).setDuration(320)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    /** Adds an "Ask AI" item to the text-selection toolbar of analyst bubbles. */
    private fun attachAskAiSelection(tv: TextView, afterAsk: (() -> Unit)? = null) {
        val listener = actionListener ?: return
        tv.customSelectionActionModeCallback = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                // TICKET-P2-8: named action, not the "Ask AI" tech label.
                menu.add(0, R.id.menu_ask_ai, 0,
                    AnalystIdentity.label("Ask " + AnalystIdentity.NAME))
                return true
            }
            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false
            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                if (item.itemId != R.id.menu_ask_ai) return false
                var start = tv.selectionStart
                var end = tv.selectionEnd
                if (start > end) { val t = start; start = end; end = t }
                val tvText = tv.text
                if (start >= 0 && end <= tvText.length && start < end) {
                    val selected = tvText.subSequence(start, end).toString().trim()
                    if (selected.isNotEmpty()) {
                        // TICKET-5 (AI Law 12): a selection that IS a known metric
                        // name behaves like the long-press it resembles — the host
                        // injects the snapshot value + context and asks concisely.
                        // Free-text selections keep the plain full-length path.
                        val key = selected.lowercase(Locale.US)
                            .replace(Regex("[\\s.,:;!?]+$"), "").trim()
                        if (METRIC_TABS.containsKey(key))
                            listener.onAskAiMetric(selected)
                        else
                            listener.onAskAi("Tell me more about this: \"$selected\"")
                    }
                }
                mode.finish()
                afterAsk?.invoke()
                return true
            }
            override fun onDestroyActionMode(mode: ActionMode) {}
        }
    }

    /**
     * KTD6 (was AIC-05's row under each answer): the long-press sheet. Any
     * real message gets Copy and Share. A finished answer also gets Regenerate
     * and Continue (newest answer only, not while busy), the variant steps,
     * Select text and About; its quote line carries the model label.
     */
    private fun openActions(holder: ViewHolder): Boolean {
        val pos = holder.bindingAdapterPosition
        if (pos == RecyclerView.NO_POSITION) return false
        val m = messages[pos]
        val text = m.text
        if (m.isLoader || text.isNullOrEmpty()) return false
        val ctx = holder.itemView.context
        val fromAnalyst = !m.isUser && !m.isError
        val actions = ArrayList<AnswerActionsSheet.Action>()
        actions.add(AnswerActionsSheet.Action(R.drawable.ic_copy,
            if (fromAnalyst) "Copy answer" else "Copy") {
            currentAt(holder)?.text?.takeIf { it.isNotEmpty() }?.let { copyToClipboard(ctx, it) }
        })
        actions.add(AnswerActionsSheet.Action(R.drawable.ic_share, "Share") {
            currentAt(holder)?.text?.takeIf { it.isNotEmpty() }?.let { shareMessage(ctx, it, fromAnalyst) }
        })

        val answer = isAnswer(m) && questionFor(pos) != null
        if (answer) {
            val actionsHost = turnActions
            val canAct = pos == lastAnswerIndex() && actionsHost != null && !actionsHost.isBusy()
            if (canAct) {
                actions.add(AnswerActionsSheet.Action(R.drawable.ic_regenerate, "Regenerate") {
                    turnActions?.onRegenerate()
                })
                if (m.isStopped) actions.add(AnswerActionsSheet.Action(R.drawable.ic_arrow_forward, "Continue") {
                    turnActions?.onContinue()
                })
            }
            val n = m.variants.size
            if (n > 1) {
                val i = m.variantIndex
                val of = "${i + 1} of $n"
                if (i > 0) actions.add(AnswerActionsSheet.Action(R.drawable.ic_chevron_left,
                    "Previous answer", of) { showVariant(holder, -1) })
                if (i < n - 1) actions.add(AnswerActionsSheet.Action(R.drawable.ic_chevron_right,
                    "Next answer", of) { showVariant(holder, +1) })
            }
        }
        if (fromAnalyst) {
            actions.add(AnswerActionsSheet.Action(R.drawable.ic_select_text, "Select text") {
                startTextSelection(holder)
            })
            // TICKET-P2-8: the about entry the removed sender label used to open.
            actions.add(AnswerActionsSheet.Action(R.drawable.ic_set_info,
                "About " + AnalystIdentity.NAME) { AnalystIdentity.showAbout(ctx) })
        }

        val quote = AnswerActionsSheet.quoteLine(holder.tvMessage.text, m.timestamp,
            if (answer) m.modelLabel else null)
        AnswerActionsSheet.show(ctx, quote, actions)
        return true
    }

    /** "Select text" from the sheet: the answer opens in a selection sheet
     *  whose toolbar keeps "Ask economii Analyst"; asking closes that sheet. */
    private fun startTextSelection(holder: ViewHolder) {
        val text = holder.tvMessage.text
        AnswerActionsSheet.showSelectable(holder.itemView.context, text) { tv, close ->
            attachAskAiSelection(tv, close)
        }
    }

    /** AIC-05: steps the answer under [holder] to its previous / next variant. */
    private fun showVariant(holder: ViewHolder, step: Int) {
        val pos = holder.bindingAdapterPosition
        if (pos == RecyclerView.NO_POSITION) return
        val m = messages[pos]
        val target = m.variantIndex + step
        if (target !in m.variants.indices) return
        val v = m.variants[target]
        m.variantIndex = target
        m.text = v.text
        m.charts = v.charts
        m.modelLabel = v.modelLabel
        m.sources = emptyList()
        notifyItemChanged(pos)
        if (pos == lastAnswerIndex()) turnActions?.onVariantShown(v.text)
    }

    private fun currentAt(holder: ViewHolder): ChatMessage? {
        val pos = holder.bindingAdapterPosition
        return if (pos == RecyclerView.NO_POSITION) null else messages[pos]
    }

    private fun copyToClipboard(ctx: Context, text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText("economii Analyst message", text))
        Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun shareMessage(ctx: Context, text: String, fromAnalyst: Boolean) {
        val share = Intent(Intent.ACTION_SEND)
        share.type = "text/plain"
        // TICKET-P2-8: analyst content leaving the app carries its marker;
        // Copy stays raw — clipboard users expect verbatim.
        val shared = if (fromAnalyst) AnalystIdentity.shareAttribution() + text else text
        share.putExtra(Intent.EXTRA_TEXT, shared)
        ctx.startActivity(Intent.createChooser(share, "Share message"))
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        holder.loader.stop() // U7
        holder.sourceStack.cancelMotion() // MOT-04
    }

    override fun getItemCount(): Int = messages.size

    class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val llBubbleContainer: LinearLayout = v.findViewById(R.id.llBubbleContainer)
        val tvMessage: TextView = v.findViewById(R.id.tvMessage)
        val tvHistoryCut: TextView = v.findViewById(R.id.tvHistoryCut)
        val tvRetry: TextView = v.findViewById(R.id.tvRetry)
        val ivChatImage: ImageView = v.findViewById(R.id.ivChatImage)
        val llChartContainer: LinearLayout = v.findViewById(R.id.llChartContainer)
        // U7: the loader row's spinner and phrase (view_assistant_loader)
        val loader: AssistantLoaderView = v.findViewById(R.id.loaderRow)
        // MOT-04: sources the turn's tools read
        val sourceStack: SourceStackView = v.findViewById(R.id.sourceStack)
        val tvStaleData: TextView = v.findViewById(R.id.tvStaleData)

        /** Recycling guard for async chart loads (TICKET-P2-2): bumped on every
         *  bindCharts, checked when a background load posts back. Replaces the
         *  single-view `sparkline.tag` key, which couldn't name a slot once a
         *  bubble held several charts. */
        var chartBindToken: Long = 0
    }

    companion object {
        /** Rows a table card shows (T2). */
        private const val TABLE_ROWS = 5

        /** AIC-05: a real analyst answer, as opposed to the loader, an error
         *  bubble or a user message. */
        private fun isAnswer(m: ChatMessage): Boolean =
            !m.isUser && !m.isLoader && !m.isError

        /** U7 (AE1): the loader's tool phrase changed. */
        private val PAYLOAD_LOADER = Any()
        /** AIC-08: the history-cut divider moved onto or off this row. */
        private val PAYLOAD_CUT = Any()

        /** MOT-04: the sources stack was opened or closed. */
        private val PAYLOAD_SOURCES_TOGGLE = Any()

        /** U6 (R4): user and error bubbles are at most this share of the thread width. */
        private const val BUBBLE_MAX_FRACTION = 0.82f

        private fun dp(res: Resources, v: Float): Int =
            (v * res.displayMetrics.density + 0.5f).toInt()

        /**
         * LEGACY (TICKET-P2-2): the model no longer emits [CHART:...] tags —
         * charts arrive as render_chart tool calls and live in
         * [ChatMessage.charts]. This pattern survives for one reason only:
         * conversations persisted before the switch carry the tag inside their
         * stored text, and without [stripChartTags] on the restore path they
         * would render it literally. Sanitiser, not renderer.
         */
        private val CHART_TAG: Pattern = Pattern.compile("\\[CHART:([A-Z0-9_]+):(\\d{1,2})M?\\]")

        /** Human labels for chart captions — derived from EconomicSeriesRegistry,
         *  the single source of truth for series ids (TICKET-F / review F6). */
        private val SERIES_LABELS: Map<String, String> = EconomicSeriesRegistry.chartLabels()

        /**
         * Metric names → bottom-nav tab. First occurrence of each keyword in an
         * analyst reply becomes a tappable link that jumps to that screen.
         * LinkedHashMap: longer/more specific phrases first so they win the range.
         */
        private val METRIC_TABS: Map<String, String> = LinkedHashMap<String, String>().apply {
            put("yield curve", "markets")
            put("fed funds rate", "overview")
            put("federal funds rate", "overview")
            put("unemployment rate", "economy")
            put("unemployment", "economy")
            put("cpi", "economy")
            put("inflation", "economy")
            put("gdp", "economy")
            put("mortgage rate", "economy")
            put("housing starts", "economy")
            put("treasury", "markets")
            put("s&p 500", "markets")
            put("nasdaq", "markets")
            put("vix", "markets")
        }

        /** 2026-10-12 analyst cards T3: [METRIC_TABS] keys that map to a
         *  MetricPopupRegistry sheet. Keys absent here keep the tab jump. */
        private val METRIC_SHEETS: Map<String, String> = mapOf(
            "fed funds rate" to "fedfunds",
            "federal funds rate" to "fedfunds",
            "unemployment rate" to "unemployment",
            "unemployment" to "unemployment",
            "cpi" to "cpi",
            "inflation" to "cpi",
            "gdp" to "gdp",
            "mortgage rate" to "mortgage",
            "housing starts" to "housing",
            "s&p 500" to "sp500",
            "nasdaq" to "nasdaq",
            "vix" to "vix"
        )

        /** Series data cache so recycled rows don't re-query Room. */
        private val seriesCache = HashMap<String, ChatSeriesData.Card>()

        /**
         * U7 (AE1): resolves the cards for [charts] into the bind cache before
         * the answer lands, so its chart and table cards draw in the same frame
         * as its text rather than a load later. Same cache rules as the bind.
         * Reads Room: call off the main thread.
         */
        @JvmStatic
        fun prefetchCards(ctx: Context, vm: EconomicViewModel?, charts: List<ChatChart>) {
            for (c in charts) {
                val key = "${c.seriesId}:${c.months}"
                val cached = synchronized(seriesCache) { seriesCache.containsKey(key) }
                if (cached) continue
                val card = ChatSeriesData.resolve(ctx, vm, c.seriesId, c.months)
                if (card.popupId == null || card.grade != null)
                    synchronized(seriesCache) { seriesCache[key] = card }
            }
        }

        /** Removes legacy [CHART:...] tags from pre-P2-2 persisted text so old
         *  conversations restore cleanly (see CHART_TAG). New replies never
         *  contain the tag. */
        @JvmStatic
        fun stripChartTags(text: String?): String {
            if (text == null) return ""
            return CHART_TAG.matcher(text).replaceAll("").replace("\n\n\n", "\n\n").trim()
        }

        /**
         * AI Law 10 (one formatting language, enforced): the system prompt bans
         * markdown headers and bullet lists, but a small model drifts. Defensively
         * convert any slip so it never renders as raw markdown structure —
         * "## Title" becomes a bold line, "- item"/"* item" becomes a "• " line.
         */
        @JvmStatic
        fun stripStrayMarkdown(text: String?): String? {
            if (text.isNullOrEmpty()) return text
            if (text.indexOf('#') < 0 && !text.contains("\n- ") && !text.contains("\n* ") &&
                !text.startsWith("- ") && !text.startsWith("* ")
            ) return text
            var t = text.replace(Regex("(?m)^\\s{0,3}#{1,6}\\s+(.*)$"), "**$1**")
            t = t.replace(Regex("(?m)^\\s{0,3}[-*]\\s+"), "• ")
            // Keep each bullet on its own line (markdown collapses single newlines).
            t = t.replace("\n• ", "\n\n• ")
            return t
        }

        /**
         * AIC-09: an answer cut short by Stop can end mid-**bold**, and Markwon
         * then shows the raw asterisks. An odd count of `**` gets a closing pair.
         * Render copy only — callers never store the result.
         */
        @JvmStatic
        fun closeDanglingBold(text: String?): String? {
            if (text == null) return null
            var n = 0
            var i = text.indexOf("**")
            while (i >= 0) { n++; i = text.indexOf("**", i + 2) }
            return if (n % 2 == 1) text.trimEnd() + "**" else text
        }
    }
}
