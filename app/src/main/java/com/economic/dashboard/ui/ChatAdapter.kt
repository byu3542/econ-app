package com.economic.dashboard.ui

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
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
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.transition.AutoTransition
import android.transition.TransitionManager
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
import androidx.core.graphics.ColorUtils
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.recyclerview.widget.RecyclerView
import com.economic.dashboard.analyst.ReleaseCalendar
import com.economic.dashboard.R
import com.economic.dashboard.analyst.AnalystIdentity
import com.economic.dashboard.analyst.AnalystToolExecutor
import com.economic.dashboard.analyst.ChatSeriesData
import com.economic.dashboard.analyst.EconomicSeriesRegistry
import com.economic.dashboard.models.ChatChart
import com.economic.dashboard.models.ChatMessage
import com.economic.dashboard.models.TraceStep
import com.economic.dashboard.models.TurnSource
import com.economic.dashboard.news.NewsAdapter
import com.economic.dashboard.ui.views.SourceStackView
import com.economic.dashboard.ui.views.StepStatusView
import com.economic.dashboard.utils.AppExecutors
import com.economic.dashboard.utils.MotionUtil
import com.economic.dashboard.utils.AxisDateFormat
import com.economic.dashboard.utils.ChartHelper
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.facebook.shimmer.ShimmerFrameLayout
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

    /** AIC-09: dims the streaming caret after [CARET_IDLE_MS] without text. */
    private val caretHandler = android.os.Handler(android.os.Looper.getMainLooper())

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

    /** Streams new text into an existing bubble (SSE partials) via a payload bind.
     *  AIC-09: [streaming] repaints the text only (no charts) and keeps the
     *  caret; any other update is final for now and drops the caret. */
    fun updateMessageText(index: Int, text: String?, streaming: Boolean = false) {
        if (index < 0 || index >= messages.size) return
        val m = messages[index]
        m.text = text
        m.isStreaming = streaming
        caretHandler.removeCallbacksAndMessages(null)
        if (streaming) {
            m.streamTouchMs = System.currentTimeMillis()
            // Repaint once the text goes quiet, so the caret can dim.
            caretHandler.postDelayed({
                val i = messages.indexOf(m)
                if (i >= 0 && m.isStreaming) notifyItemChanged(i, PAYLOAD_STREAM)
            }, CARET_IDLE_MS)
        }
        notifyItemChanged(index, if (streaming) PAYLOAD_STREAM else PAYLOAD_TEXT)
    }

    /** TICKET-P2-2: a render_chart call landed for the streaming bubble — set
     *  the typed chart list and redraw only the chart container. */
    fun updateMessageCharts(index: Int, charts: List<ChatChart>) {
        if (index < 0 || index >= messages.size) return
        messages[index].charts = charts
        notifyItemChanged(index, PAYLOAD_CHARTS)
    }

    /** MOT-04: the turn finished — attach the sources its tools read and
     *  redraw only the stack, closed. */
    fun updateMessageSources(index: Int, sources: List<TurnSource>) {
        if (index < 0 || index >= messages.size) return
        val m = messages[index]
        m.sources = sources
        m.sourcesExpanded = false
        notifyItemChanged(index, PAYLOAD_SOURCES)
    }

    /** MOT-04: binds the sources stack. [toggled] animates an open/close of
     *  the already-bound stack; otherwise it is a full bind with no motion. */
    /** AIC-11: "newer data out" chip when a series this answer used has had a
     *  newer release since it was fetched. Checked at bind, so a restored
     *  answer goes stale on its own as release dates pass. */
    private fun bindStale(holder: ViewHolder, m: ChatMessage) {
        val stale = !m.isUser && !m.isTrace && m.sources.any {
            it.kind == TurnSource.Kind.SERIES && ReleaseCalendar.isStale(it.seriesId, it.latestDate)
        }
        holder.tvStaleData.visibility = if (stale) View.VISIBLE else View.GONE
    }

    private fun bindSources(holder: ViewHolder, m: ChatMessage, toggled: Boolean) {
        bindStale(holder, m)
        val stack = holder.sourceStack
        if (m.isUser || m.isError || m.isTrace || m.isTyping || m.sources.isEmpty()) {
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

    fun getLastIndex(): Int = messages.size - 1

    /** T4: drops a streaming bubble whose only text was held back before a
     *  tool call, so no empty bubble sits under the trace row. */
    fun removeMessageAt(index: Int) {
        if (index < 0 || index >= messages.size) return
        messages.removeAt(index)
        notifyItemRemoved(index)
    }

    fun messageAt(index: Int): ChatMessage? = messages.getOrNull(index)

    /** AIC-05: the question the answer at [index] replies to, or null (the
     *  welcome bubble answers nothing). Read from the list, not the sheet's
     *  last query, which a restart does not restore. */
    fun questionFor(index: Int): String? {
        for (i in index - 1 downTo 0) if (messages[i].isUser) return messages[i].text
        return null
    }

    /** AIC-05: index of the newest finished-or-streaming analyst answer, or -1. */
    fun lastAnswerIndex(): Int {
        for (i in messages.indices.reversed()) {
            val m = messages[i]
            if (m.isUser) return -1
            if (isAnswer(m)) return i
        }
        return -1
    }

    /**
     * AIC-05: Regenerate takes the newest answer (and the trace row above it)
     * off screen and returns it, so the sheet can carry it as a variant of the
     * replacement. Null when there is no answer to replace.
     */
    fun removeLastAnswer(): ChatMessage? {
        val i = lastAnswerIndex()
        if (i < 0) return null
        val m = messages.removeAt(i)
        notifyItemRemoved(i)
        if (i > 0 && messages[i - 1].isTrace) {
            messages.removeAt(i - 1)
            notifyItemRemoved(i - 1)
        }
        return m
    }

    fun removeTypingIndicator() {
        // MOT-02: every call site is "first output arrived" or a terminal
        // path. AIC-09: the trace stays open while tool steps are still due;
        // terminal paths finish it themselves.
        finishTraceIfIdle()
        for (i in messages.indices.reversed()) {
            if (messages[i].isTyping) {
                messages.removeAt(i)
                notifyItemRemoved(i)
                break
            }
        }
    }

    // ── MOT-02: thinking trace ──────────────────────────────────────────────────

    /** Adds a running "Thinking" trace row, timed from [startMs] (send). */
    fun addTrace(startMs: Long) {
        val t = ChatMessage("", false)
        t.isTrace = true
        t.traceStartMs = startMs
        t.traceTurnOpen = true // MOT-05
        addMessage(t)
    }

    private fun runningTraceIndex(): Int {
        for (i in messages.indices.reversed()) {
            if (messages[i].isTraceRunning) return i
        }
        return -1
    }

    /**
     * MOT-05: the trace of the turn still in flight. Unlike [runningTraceIndex]
     * this survives first output, so tool rounds after the answer has started
     * still land as rows in the (collapsed) trace.
     */
    private fun openTraceIndex(): Int {
        for (i in messages.indices.reversed()) {
            val m = messages[i]
            if (m.isTrace && m.traceTurnOpen) return i
        }
        return -1
    }

    /** MOT-05: adds a pending step for tool call [id] to this turn's trace. */
    fun addTraceStep(id: String, label: String) {
        val i = openTraceIndex()
        if (i < 0) return
        val steps = messages[i].traceSteps
        if (steps.size >= MAX_TRACE_STEPS || steps.any { it.id == id }) return
        steps.add(TraceStep(id, label))
        // AIC-09: a tool round after preamble text reopens the trace, so the
        // steps show while they run instead of landing in a collapsed row.
        if (messages[i].traceElapsedMs >= 0) messages[i].traceElapsedMs = -1L
        notifyItemChanged(i, PAYLOAD_TRACE)
    }

    /**
     * MOT-05: changes step [id]'s state, label and/or duration. Matched by
     * tool_use id. A no-op once the turn is closed, so a tool that finishes
     * after Stop cannot repaint a closed trace.
     */
    fun updateTraceStep(
        id: String, state: TraceStep.State? = null,
        label: String? = null, durationMs: Long = -1L
    ) {
        val i = openTraceIndex()
        if (i < 0) return
        val s = messages[i].traceSteps.firstOrNull { it.id == id } ?: return
        if (state != null) s.state = state
        if (label != null) s.label = label
        if (durationMs >= 0) s.durationMs = durationMs
        notifyItemChanged(i, PAYLOAD_TRACE)
    }

    /**
     * MOT-05: a round is being re-streamed after a mid-stream failure. Its
     * steps never ran and the retry re-announces them under new ids, so drop
     * them instead of leaving orphans pending.
     */
    fun dropPendingTraceSteps() {
        val i = openTraceIndex()
        if (i < 0) return
        if (messages[i].traceSteps.removeAll { it.state == TraceStep.State.PENDING })
            notifyItemChanged(i, PAYLOAD_TRACE)
    }

    /**
     * MOT-05: ends the turn's step list. A step still running was cut short
     * (Stop or error) and becomes failed; a step still pending never ran (past
     * the depth cap, or never reached) and is removed rather than shown as an
     * error. Only now does the label gain " · K steps". Idempotent.
     *
     * Returns true when a successful multi-step turn just closed — the caller
     * fires the one confirm haptic.
     */
    fun closeTraceTurn(success: Boolean): Boolean {
        val i = openTraceIndex()
        if (i < 0) return false
        val m = messages[i]
        m.traceTurnOpen = false
        m.traceSteps.removeAll { it.state == TraceStep.State.PENDING }
        for (s in m.traceSteps)
            if (s.state == TraceStep.State.RUNNING) s.state = TraceStep.State.FAILED
        notifyItemChanged(i, PAYLOAD_TRACE)
        return success && m.traceSteps.size >= CHECKLIST_MIN_STEPS
    }

    /**
     * Ends the running trace, if any: stops the shimmer, collapses the steps
     * and swaps the label to "<verb> for N.Ns". Idempotent — every exit path
     * of a request may call it; only the first call lands.
     */
    fun finishTrace(verb: String) {
        val i = runningTraceIndex()
        if (i < 0) return
        val m = messages[i]
        m.traceElapsedMs = Math.max(0L, System.currentTimeMillis() - m.traceStartMs)
        m.traceVerb = verb
        m.traceExpanded = false
        notifyItemChanged(i, PAYLOAD_TRACE)
    }

    /** AIC-09: finishes the running trace once no tool step is pending or
     *  running — the answer text that follows the last tool round. */
    fun finishTraceIfIdle() {
        val i = runningTraceIndex()
        if (i < 0) return
        if (messages[i].traceSteps.any {
                it.state == TraceStep.State.PENDING || it.state == TraceStep.State.RUNNING })
            return
        finishTrace("Thought")
    }

    private fun bindTrace(holder: ViewHolder, m: ChatMessage, animate: Boolean) {
        val motion = MotionUtil.animationsEnabled(holder.itemView.context)
        val running = m.traceElapsedMs < 0
        val hasSteps = m.traceSteps.isNotEmpty()
        val stepsVisible = hasSteps && (running || m.traceExpanded)
        val steps = holder.llTraceSteps
        // MOT-05: only a collapse/expand gets the AutoTransition. Row state
        // changes arrive many times a turn and animate themselves; a 220ms
        // transition on each would fight the row springs.
        if (animate && motion && (steps.visibility == View.VISIBLE) != stepsVisible) {
            TransitionManager.beginDelayedTransition(
                holder.itemView as ViewGroup, AutoTransition().setDuration(TRACE_COLLAPSE_MS))
        }
        holder.tvTraceLabel.text = traceLabel(m)
        // Bound to stream state only: runs exactly while the trace is running.
        if (running && motion) holder.shimmerTrace.startShimmer()
        else holder.shimmerTrace.stopShimmer()

        // Collapsed rows (later tool rounds) update silently: no spin out of sight.
        bindTraceSteps(steps, m.traceSteps, motion && stepsVisible, animate && stepsVisible)
        steps.visibility = if (stepsVisible) View.VISIBLE else View.GONE
        holder.tvTraceChevron.visibility = if (hasSteps && !running) View.VISIBLE else View.GONE
        holder.tvTraceChevron.rotation = if (m.traceExpanded) 0f else -90f
        if (hasSteps && !running) {
            holder.traceRow.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                m.traceExpanded = !m.traceExpanded
                notifyItemChanged(pos, PAYLOAD_TRACE)
            }
        } else {
            holder.traceRow.setOnClickListener(null)
            holder.traceRow.isClickable = false
        }
    }

    /**
     * "Thinking" while running; "Thought for N.Ns" / "Stopped after N.Ns" once
     * finished. MOT-05: " · K steps" only after the turn closes — the model
     * reveals each call as it makes it, so no total is known before then.
     */
    private fun traceLabel(m: ChatMessage): String {
        if (m.traceElapsedMs < 0) return "Thinking"
        val joiner = if (m.traceVerb == "Stopped") "after" else "for"
        val base = String.format(Locale.US, "%s %s %.1fs", m.traceVerb, joiner, m.traceElapsedMs / 1000.0)
        val k = m.traceSteps.size
        return if (!m.traceTurnOpen && k >= CHECKLIST_MIN_STEPS) "$base · $k steps" else base
    }

    /**
     * MOT-05: reconciles the step rows in place, keyed by tool_use id (the
     * row's tag), so a state change never rebuilds the list — spinners keep
     * spinning and only genuinely new rows spring in. With fewer than
     * [CHECKLIST_MIN_STEPS] steps each row is the plain MOT-02 line: no icon,
     * no duration.
     */
    private fun bindTraceSteps(
        container: LinearLayout, steps: List<TraceStep>, motion: Boolean, live: Boolean
    ) {
        for (c in container.childCount - 1 downTo 0) {
            val id = container.getChildAt(c).tag
            if (steps.none { it.id == id }) container.removeViewAt(c) // detaching stops its icon
        }
        val checklist = steps.size >= CHECKLIST_MIN_STEPS
        for ((idx, s) in steps.withIndex()) {
            var row = container.getChildAt(idx)
            if (row == null || row.tag != s.id) {
                row = newStepRow(container)
                row.tag = s.id
                container.addView(row, idx)
                if (live && motion) springIn(row)
            }
            bindStepRow(row as LinearLayout, s, checklist, motion, live)
        }
    }

    private fun newStepRow(parent: ViewGroup): LinearLayout {
        val ctx = parent.context
        val res = ctx.resources
        val caption = res.getDimension(R.dimen.text_caption)
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = android.view.Gravity.CENTER_VERTICAL
        row.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES

        val icon = StepStatusView(ctx)
        val iconLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        iconLp.marginEnd = res.getDimensionPixelSize(R.dimen.space_sm)
        row.addView(icon, iconLp)

        val label = TextView(ctx)
        label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, caption)
        label.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        row.addView(label)

        val ms = TextView(ctx)
        ms.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, caption)
        ms.setTextColor(ContextCompat.getColor(ctx, R.color.text_muted))
        ms.fontFeatureSettings = "tnum"
        ms.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        val msLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        msLp.marginStart = res.getDimensionPixelSize(R.dimen.space_md)
        row.addView(ms, msLp)
        return row
    }

    private fun bindStepRow(
        row: LinearLayout, s: TraceStep, checklist: Boolean, motion: Boolean, live: Boolean
    ) {
        val ctx = row.context
        val icon = row.getChildAt(0) as StepStatusView
        val label = row.getChildAt(1) as TextView
        val ms = row.getChildAt(2) as TextView
        val pad = if (checklist) ctx.resources.getDimensionPixelSize(R.dimen.space_xs) / 2 else 0
        row.setPadding(0, pad, 0, pad)

        label.text = s.label
        label.setTextColor(ContextCompat.getColor(ctx,
            if (checklist && s.state == TraceStep.State.RUNNING) R.color.text_secondary
            else R.color.text_muted))

        icon.visibility = if (checklist) View.VISIBLE else View.GONE
        // A hidden icon must not spin, so motion is off for it in MOT-02 mode.
        icon.setState(s.state, motion && checklist, live)

        val showMs = checklist && s.durationMs >= 0
        ms.visibility = if (showMs) View.VISIBLE else View.GONE
        if (showMs) ms.text = String.format(Locale.US, "%d ms", s.durationMs)

        row.contentDescription = if (!checklist) s.label else {
            val state = when (s.state) {
                TraceStep.State.PENDING -> "waiting"
                TraceStep.State.RUNNING -> "in progress"
                TraceStep.State.DONE -> "done"
                TraceStep.State.FAILED -> "failed"
            }
            if (showMs) "${s.label}, $state, ${s.durationMs} milliseconds" else "${s.label}, $state"
        }
    }

    /**
     * MOT-05: on recycle, drop the rows outright. Detaching stops each icon's
     * spinner and check, and the next bind rebuilds rows in their current state
     * (stopping them in place would leave a running row frozen on rebind,
     * because setState skips an unchanged state).
     */
    private fun stopTraceSteps(holder: ViewHolder) {
        val steps = holder.llTraceSteps
        for (c in 0 until steps.childCount) steps.getChildAt(c).animate().cancel()
        steps.removeAllViews()
    }

    /** MOT-05: a new row rises 6dp on the app's 0.75 / 1200 spring and fades in. */
    private fun springIn(row: View) {
        row.alpha = 0f
        row.translationY = 6f * row.resources.displayMetrics.density
        row.animate().alpha(1f).setDuration(ROW_FADE_MS).start()
        val spring = SpringAnimation(row, DynamicAnimation.TRANSLATION_Y, 0f)
        spring.spring.setDampingRatio(ROW_DAMPING).setStiffness(ROW_STIFFNESS)
        spring.start()
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

    /**
     * AIC-09: a caret after streaming text, dimmed once no text has arrived for
     * [CARET_IDLE_MS]. Render-only: the stored text never holds it, and the
     * content description keeps TalkBack from reading it.
     */
    private fun appendCaret(tv: TextView, m: ChatMessage) {
        if (!m.isStreaming) {
            tv.contentDescription = null
            return
        }
        val body = tv.text
        val sb = android.text.SpannableStringBuilder(body).append(CARET)
        if (System.currentTimeMillis() - m.streamTouchMs >= CARET_IDLE_MS) {
            val dim = (tv.currentTextColor and 0x00FFFFFF) or 0x40000000
            sb.setSpan(android.text.style.ForegroundColorSpan(dim), sb.length - CARET.length,
                sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        tv.text = sb
        tv.contentDescription = body
    }

    private fun renderText(holder: ViewHolder, m: ChatMessage) {
        if (!m.isUser && !m.isError) {
            // ensureMarkwon() always runs before renderText() at both call sites
            // (onBindViewHolder, the PAYLOAD_TEXT path) — fail loudly if that
            // invariant is ever broken, same as the original's implicit trust.
            val mw = checkNotNull(markwon) { "ensureMarkwon() must run before renderText()" }
            // stripStrayMarkdown's signature stays nullable-in/nullable-out because
            // ChatAdapterTest calls it directly with null and asserts a null result.
            // stripChartTags always returns non-null, so this fallback is unreachable
            // in practice — it just satisfies the compiler without loosening that contract.
            // AIC-09: a half-streamed **bold** would show raw asterisks; close it
            // in this render copy only, never in the stored text.
            val display = closeDanglingBold(stripStrayMarkdown(stripChartTags(m.text))) ?: ""
            mw.setMarkdown(holder.tvMessage, display)
            addMetricCitations(holder.tvMessage)
            styleToolRoundStatus(holder.tvMessage)
            appendCaret(holder.tvMessage, m)
        } else {
            holder.tvMessage.text = m.text
        }
    }

    /**
     * TICKET-6 (AI Law 11): while a tool round runs, the bubble ends with the
     * display-only TOOL_STATUS line. Render that trailing line italic and
     * subdued so "fetching" reads visibly differently from "answering". The
     * status is never committed to the answer, so finished replies never
     * carry the style.
     */
    private fun styleToolRoundStatus(tv: TextView) {
        val cs = tv.text
        if (cs !is Spannable) return
        val s = cs.toString()
        if (!s.endsWith(TOOL_STATUS)) return
        val start = s.length - TOOL_STATUS.length
        cs.setSpan(StyleSpan(Typeface.ITALIC), start, s.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        val muted = ColorUtils.setAlphaComponent(tv.currentTextColor, 0x99)
        cs.setSpan(ForegroundColorSpan(muted), start, s.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
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
        val charts = if (m.isUser || m.isError || m.isTyping) emptyList() else m.charts

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

        // Reuse rows rather than rebuilding: a streaming reply rebinds on every
        // text delta, and re-inflating N charts each time would churn views for
        // the whole answer.
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
        ChartHelper.styleLineSeries(ds, ContextCompat.getColor(ctx, R.color.sheet_accent))
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
        // MOT-04: handle every payload present, not just the first —
        // finalizeTurn sends TEXT + CHARTS + SOURCES in the same frame.
        val m = messages[position]
        val text = payloads.contains(PAYLOAD_TEXT)
        if (!text && payloads.contains(PAYLOAD_STREAM)) {
            // AIC-09: streaming repaint — text and caret only, charts untouched.
            ensureMarkwon(holder.itemView.context)
            renderText(holder, m)
        }
        if (text) {
            // Streaming update: swap the text only — no full rebind, no flicker.
            ensureMarkwon(holder.itemView.context)
            renderText(holder, m)
            bindCharts(holder, m)
        }
        if (payloads.contains(PAYLOAD_TRACE)) {
            // MOT-02: state change on the trace row — animate the collapse.
            bindTrace(holder, m, true)
        }
        if (!text && payloads.contains(PAYLOAD_CHARTS)) {
            // A render_chart landed mid-stream: draw it without touching the
            // text, so the prose keeps streaming uninterrupted.
            bindCharts(holder, m)
        }
        if (payloads.contains(PAYLOAD_SOURCES)) bindSources(holder, m, false)
        else if (payloads.contains(PAYLOAD_SOURCES_TOGGLE)) bindSources(holder, m, true)
        if (payloads.contains(PAYLOAD_CUT)) bindHistoryCut(holder, position)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val m = messages[position]
        ensureMarkwon(holder.itemView.context)
        bindHistoryCut(holder, position)

        // MOT-02: a trace row shows only the trace inside the bubble.
        holder.traceRow.visibility = if (m.isTrace) View.VISIBLE else View.GONE
        if (m.isTrace) {
            holder.tvMessage.visibility = View.GONE
            holder.llTypingIndicator.visibility = View.GONE
            stopDancingDots(holder)
            bindTrace(holder, m, false)
        } else {
            holder.shimmerTrace.stopShimmer()
            holder.traceRow.setOnClickListener(null)
            // MOT-05: a GONE trace row stays attached, so its rows would keep
            // spinning out of sight; removing them detaches and stops them.
            holder.llTraceSteps.removeAllViews()
        }

        if (m.isTrace) {
            // handled above
        } else if (m.isTyping) {
            holder.tvMessage.visibility = View.GONE
            holder.llTypingIndicator.visibility = View.VISIBLE
            startDancingDots(holder)
        } else {
            holder.tvMessage.visibility = View.VISIBLE
            holder.llTypingIndicator.visibility = View.GONE
            stopDancingDots(holder)
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
            holder.tvMessage.setTextColor(ContextCompat.getColor(ctx, R.color.on_accent))
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
            if (!m.isTyping) {
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
        // AIC-04: streaming updates arrive as partial binds, so the handler
        // reads the item at press time instead of capturing it here.
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
    private fun attachAskAiSelection(tv: TextView) {
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
        if (m.isTyping || m.isTrace || text.isNullOrEmpty()) return false
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

    /** "Select text" from the sheet: replays the long-press on the text with
     *  the sheet's listener off, so the TextView starts its own selection (and
     *  the "Ask Econ Analyst" item) at the word that was pressed. */
    private fun startTextSelection(holder: ViewHolder) {
        val tv = holder.tvMessage
        tv.post {
            if (!tv.isTextSelectable) return@post
            tv.setOnLongClickListener(null)
            tv.performLongClick()
            tv.setOnLongClickListener { openActions(holder) }
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
        cm.setPrimaryClip(ClipData.newPlainText("Econ Analyst message", text))
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

    private fun startDancingDots(holder: ViewHolder) {
        stopDancingDots(holder)
        holder.dotAnimators.add(animateDot(holder.dot1, 0))
        holder.dotAnimators.add(animateDot(holder.dot2, 200))
        holder.dotAnimators.add(animateDot(holder.dot3, 400))
    }

    /**
     * ObjectAnimators are not stopped by View.clearAnimation() — they must be
     * cancelled explicitly, otherwise they run forever on recycled views.
     */
    private fun stopDancingDots(holder: ViewHolder) {
        for (a in holder.dotAnimators) a.cancel()
        holder.dotAnimators.clear()
        holder.dot1.translationY = 0f
        holder.dot2.translationY = 0f
        holder.dot3.translationY = 0f
    }

    private fun animateDot(dot: View, delay: Long): ObjectAnimator {
        // Use dp-based pixels so bounce scales correctly across all screen densities
        val bouncePx = 8f * dot.resources.displayMetrics.density
        val animator = ObjectAnimator.ofPropertyValuesHolder(
            dot,
            PropertyValuesHolder.ofFloat("translationY", 0f, -bouncePx, 0f)
        )
        animator.duration = 600
        animator.startDelay = delay
        animator.repeatCount = ValueAnimator.INFINITE
        animator.start()
        return animator
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        stopDancingDots(holder)
        holder.shimmerTrace.stopShimmer() // MOT-02
        stopTraceSteps(holder) // MOT-05
        holder.sourceStack.cancelMotion() // MOT-04
    }

    override fun getItemCount(): Int = messages.size

    class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val llBubbleContainer: LinearLayout = v.findViewById(R.id.llBubbleContainer)
        val llTypingIndicator: LinearLayout = v.findViewById(R.id.llTypingIndicator)
        val tvMessage: TextView = v.findViewById(R.id.tvMessage)
        val tvHistoryCut: TextView = v.findViewById(R.id.tvHistoryCut)
        val tvRetry: TextView = v.findViewById(R.id.tvRetry)
        val ivChatImage: ImageView = v.findViewById(R.id.ivChatImage)
        val llChartContainer: LinearLayout = v.findViewById(R.id.llChartContainer)
        val dot1: View = v.findViewById(R.id.dot1)
        val dot2: View = v.findViewById(R.id.dot2)
        val dot3: View = v.findViewById(R.id.dot3)
        val dotAnimators: MutableList<ObjectAnimator> = ArrayList()
        // MOT-02: thinking trace (item_chat_trace, included in the bubble)
        val traceRow: LinearLayout = v.findViewById(R.id.traceRow)
        val tvTraceChevron: TextView = v.findViewById(R.id.tvTraceChevron)
        val shimmerTrace: ShimmerFrameLayout = v.findViewById(R.id.shimmerTrace)
        val tvTraceLabel: TextView = v.findViewById(R.id.tvTraceLabel)
        val llTraceSteps: LinearLayout = v.findViewById(R.id.llTraceSteps)
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

        /** Display-only status the analyst bubble shows while a tool round runs
         *  (AI Law 11). Rendered italic + subdued (TICKET-6); never committed. */
        @JvmField
        val TOOL_STATUS = "Checking the data…"

        /** Partial-bind payload: only the message text changed (SSE streaming). */
        private val PAYLOAD_TEXT = Any()

        /** AIC-05: a real analyst answer, as opposed to a trace, typing dots,
         *  an error bubble or a user message. */
        private fun isAnswer(m: ChatMessage): Boolean =
            !m.isUser && !m.isTrace && !m.isTyping && !m.isError

        /** Partial-bind payload: only the chart list changed (TICKET-P2-2). */
        private val PAYLOAD_CHARTS = Any()

        /** MOT-02: partial-bind payload — only the trace row's state changed. */
        private val PAYLOAD_TRACE = Any()
        /** AIC-09: streaming text repaint — no charts, no action row. */
        private val PAYLOAD_STREAM = Any()
        private const val CARET = "\u258D"
        private const val CARET_IDLE_MS = 1500L
        /** AIC-08: the history-cut divider moved onto or off this row. */
        private val PAYLOAD_CUT = Any()

        /** MOT-04: sources attached (full stack bind) / stack opened or closed. */
        private val PAYLOAD_SOURCES = Any()
        private val PAYLOAD_SOURCES_TOGGLE = Any()
        /** MOT-05: was 3 (MOT-02 kept the last three), which would hide
         *  checklist rows. Now a safety cap only — MAX_TOOL_DEPTH rounds plus
         *  chart rounds stay well under it. */
        private const val MAX_TRACE_STEPS = 12
        private const val TRACE_COLLAPSE_MS = 220L
        /** MOT-05: below this many steps the trace keeps the MOT-02 plain line. */
        private const val CHECKLIST_MIN_STEPS = 2
        private const val ROW_FADE_MS = 200L
        private const val ROW_DAMPING = 0.75f
        private const val ROW_STIFFNESS = 1200f

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
         * AIC-09: a streamed answer can stop mid-**bold**, and Markwon then
         * shows the raw asterisks. An odd count of `**` gets a closing pair.
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
