package com.economic.dashboard.ui

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.text.Spannable
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.DisplayMetrics
import android.view.ActionMode
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import com.economic.dashboard.R
import com.economic.dashboard.analyst.AnalystToolExecutor
import com.economic.dashboard.analyst.EconomicSeriesRegistry
import com.economic.dashboard.database.YieldDatabase
import com.economic.dashboard.models.ChatMessage
import com.economic.dashboard.models.EconomicDataPoint
import com.economic.dashboard.utils.AppExecutors
import com.economic.dashboard.utils.MotionUtil
import com.economic.dashboard.views.SparklineView
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonSpansFactory
import org.commonmark.node.StrongEmphasis
import java.text.SimpleDateFormat
import java.util.Calendar
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
    }

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
        if (restored != null) messages.addAll(restored)
        notifyDataSetChanged()
    }

    /** Streams new text into an existing bubble (SSE partials) via a payload bind. */
    fun updateMessageText(index: Int, text: String?) {
        if (index < 0 || index >= messages.size) return
        messages[index].text = text
        notifyItemChanged(index, PAYLOAD_TEXT)
    }

    fun getLastIndex(): Int = messages.size - 1

    fun removeTypingIndicator() {
        for (i in messages.indices.reversed()) {
            if (messages[i].isTyping) {
                messages.removeAt(i)
                notifyItemRemoved(i)
                break
            }
        }
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

    /** Lazily builds Markwon; **bold** key figures render in gold ink. */
    private fun ensureMarkwon(ctx: Context) {
        if (markwon != null) return
        val goldInk = ContextCompat.getColor(ctx, R.color.gold_ink)
        markwon = Markwon.builder(ctx)
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureSpansFactory(builder: MarkwonSpansFactory.Builder) {
                    builder.appendFactory(StrongEmphasis::class.java) { _, _ -> ForegroundColorSpan(goldInk) }
                }
            })
            .build()
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
            val display = stripStrayMarkdown(stripChartTags(m.text)) ?: ""
            mw.setMarkdown(holder.tvMessage, display)
            addMetricCitations(holder.tvMessage)
            styleToolRoundStatus(holder.tvMessage)
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
            cs.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) {
                    listener.onMetricTapped(tabKey)
                }
                override fun updateDrawState(ds: TextPaint) {
                    ds.isUnderlineText = true // keep bubble text color, just underline
                }
            }, idx, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            any = true
        }
        if (any) tv.movementMethod = LinkMovementMethod.getInstance()
    }

    /** Binds the inline sparkline if the analyst reply contains a chart tag. */
    private fun bindSparkline(holder: ViewHolder, m: ChatMessage) {
        val text = m.text
        if (m.isUser || m.isError || m.isTyping || text == null) {
            holder.hideSparkline(); return
        }
        val matcher = CHART_TAG.matcher(text)
        if (!matcher.find()) { holder.hideSparkline(); return }

        // group(1)/group(2) are non-optional in CHART_TAG's pattern, so a
        // successful find() guarantees both — but fall back gracefully (matching
        // the original's behavior of never crashing on this) rather than force-unwrap.
        val seriesId = matcher.group(1)
        var months = 24
        try {
            val monthsGroup = matcher.group(2) ?: throw NumberFormatException("no group 2")
            months = maxOf(1, minOf(24, monthsGroup.toInt()))
        } catch (ignored: Exception) {
        }

        val label = if (seriesId != null) SERIES_LABELS[seriesId] else null
        if (label == null || seriesId == null) { holder.hideSparkline(); return }

        holder.sparkline.visibility = View.VISIBLE
        holder.tvChartCaption.visibility = View.VISIBLE
        holder.tvChartCaption.text = String.format(Locale.US, "%s — last %d months", label, months)
        holder.sparkline.setLineColor(ContextCompat.getColor(holder.itemView.context, R.color.gold_ink))

        val cacheKey = "$seriesId:$months"
        val cached: List<Double>?
        synchronized(seriesCache) { cached = seriesCache[cacheKey] }
        if (cached != null) { holder.sparkline.setData(cached); return }

        holder.sparkline.tag = cacheKey
        val appCtx = holder.itemView.context.applicationContext
        val monthsFinal = months
        AppExecutors.diskIO().execute {
            val cal = Calendar.getInstance()
            cal.add(Calendar.MONTH, -monthsFinal)
            val cutoff = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
            val values = ArrayList<Double>()
            if (AnalystToolExecutor.LIVE_SERIES.containsKey(seriesId)) {
                // TICKET-2 (AI Law 13): live ViewModel-backed series chart from
                // the same provider the get_series tool reads, so analysis and
                // visualization cover the same metrics. LiveData.getValue() is
                // safe off the main thread. The Room path below is untouched.
                val pts: List<EconomicDataPoint>? = AnalystToolExecutor.getLiveSeriesPoints(seriesId)
                if (pts != null) {
                    for (dp in pts) {
                        val d = dp.date
                        // "yyyy-MM" dates pad to a late day so the month survives
                        // a lexicographic compare against the cutoff; unparseable
                        // dates pass (matches AnalystToolExecutor.getLiveSeries).
                        val norm = if (d.length == 7) "$d-28" else d
                        if (norm.length < 10 || norm.compareTo(cutoff) >= 0) values.add(dp.value)
                    }
                }
            } else {
                val rows = YieldDatabase.getInstance(appCtx).economicHistoryDao().getSeriesSync(seriesId)
                for (row in rows) {
                    if (row.date.compareTo(cutoff) >= 0) values.add(row.value)
                }
            }
            synchronized(seriesCache) { seriesCache[cacheKey] = values }
            holder.sparkline.post {
                // Guard against recycling: only draw if this row still wants this series
                if (cacheKey == holder.sparkline.tag) {
                    if (values.size >= 2) holder.sparkline.setData(values) else holder.hideSparkline()
                }
            }
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: List<Any>) {
        if (payloads.contains(PAYLOAD_TEXT)) {
            // Streaming update: swap the text only — no full rebind, no flicker.
            ensureMarkwon(holder.itemView.context)
            renderText(holder, messages[position])
            bindSparkline(holder, messages[position])
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val m = messages[position]
        ensureMarkwon(holder.itemView.context)

        if (m.isTyping) {
            holder.tvMessage.visibility = View.GONE
            holder.llTypingIndicator.visibility = View.VISIBLE
            startDancingDots(holder)
        } else {
            holder.tvMessage.visibility = View.VISIBLE
            holder.llTypingIndicator.visibility = View.GONE
            stopDancingDots(holder)
            renderText(holder, m)
        }

        bindSparkline(holder, m)

        // Attached image (vision messages) — transient, shown only this session
        val bitmap = m.imageBitmap
        if (bitmap != null) {
            holder.ivChatImage.visibility = View.VISIBLE
            holder.ivChatImage.setImageBitmap(bitmap)
        } else {
            holder.ivChatImage.visibility = View.GONE
            holder.ivChatImage.setImageDrawable(null)
        }

        // Run grouping: label only on the first message of a run from the same
        // sender; timestamp only at the end of a run AND when a real time break
        // follows (or it's the newest message) — keeps the transcript quiet.
        val runStart = position == 0 ||
            messages[position - 1].isUser != m.isUser ||
            messages[position - 1].isTyping
        val runEnd = position == messages.size - 1 || messages[position + 1].isUser != m.isUser
        val showTime = runEnd && !m.isTyping &&
            (position == messages.size - 1 ||
                messages[position + 1].timeMillis - m.timeMillis > TIME_GAP_MS)
        holder.tvSenderLabel.visibility = if (runStart) View.VISIBLE else View.GONE
        holder.tvTimestamp.visibility = if (showTime) View.VISIBLE else View.GONE
        holder.tvTimestamp.text = m.timestamp

        val dm: DisplayMetrics = holder.itemView.resources.displayMetrics
        // Short user questions read better narrower; analyst prose gets more room.
        val maxBubbleWidth = (dm.widthPixels * (if (m.isUser) 0.72f else 0.80f)).toInt()

        val bubbleParams = holder.llBubbleContainer.layoutParams as ConstraintLayout.LayoutParams
        bubbleParams.matchConstraintMaxWidth = maxBubbleWidth
        val labelParams = holder.tvSenderLabel.layoutParams as ConstraintLayout.LayoutParams
        val timeParams = holder.tvTimestamp.layoutParams as ConstraintLayout.LayoutParams

        if (m.isUser) {
            holder.tvSenderLabel.text = "YOU"
            bubbleParams.horizontalBias = 1.0f
            labelParams.horizontalBias = 1.0f
            timeParams.horizontalBias = 1.0f
            holder.llBubbleContainer.setBackgroundResource(R.drawable.bg_chat_bubble_user)
            holder.tvMessage.setTextColor(Color.WHITE)
            holder.tvMessage.setTextIsSelectable(false)
            holder.tvMessage.customSelectionActionModeCallback = null
            holder.tvRetry.visibility = View.GONE
            holder.llBubbleContainer.isClickable = false
            holder.llBubbleContainer.setOnClickListener(null)
        } else {
            holder.tvSenderLabel.text = if (m.isError) "ERROR" else "ECON ANALYST"
            bubbleParams.horizontalBias = 0.0f
            labelParams.horizontalBias = 0.0f
            timeParams.horizontalBias = 0.0f
            if (m.isError) {
                holder.llBubbleContainer.setBackgroundResource(R.drawable.bg_chat_bubble_error)
                holder.tvMessage.setTextColor(
                    ContextCompat.getColor(holder.itemView.context, R.color.error_text)
                )
                holder.tvMessage.setTextIsSelectable(false)
                holder.tvRetry.visibility = View.VISIBLE
                val retry = retryListener
                if (retry != null) {
                    holder.tvRetry.setOnClickListener { retry.onRetry() }
                    holder.llBubbleContainer.isClickable = true
                    holder.llBubbleContainer.setOnClickListener { retry.onRetry() }
                }
            } else {
                holder.llBubbleContainer.setBackgroundResource(R.drawable.bg_chat_bubble_analyst)
                holder.tvMessage.setTextColor(
                    ContextCompat.getColor(holder.itemView.context, R.color.text_navy)
                )
                holder.tvRetry.visibility = View.GONE
                holder.llBubbleContainer.isClickable = false
                holder.llBubbleContainer.setOnClickListener(null)
                if (!m.isTyping) {
                    // Toggle off/on so selection keeps working on recycled views
                    holder.tvMessage.setTextIsSelectable(false)
                    holder.tvMessage.setTextIsSelectable(true)
                    attachAskAiSelection(holder.tvMessage)
                    maybeRevealInsight(holder, m)
                }
            }
        }

        // Long-press → copy / share (any real message)
        val text = m.text
        if (!m.isTyping && !text.isNullOrEmpty()) {
            holder.llBubbleContainer.setOnLongClickListener {
                showMessageMenu(it, text)
                true
            }
        } else {
            holder.llBubbleContainer.setOnLongClickListener(null)
        }

        holder.llBubbleContainer.layoutParams = bubbleParams
        holder.tvSenderLabel.layoutParams = labelParams
        holder.tvTimestamp.layoutParams = timeParams
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
                menu.add(0, R.id.menu_ask_ai, 0, "Ask AI")
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

    private fun showMessageMenu(anchor: View, text: String) {
        val ctx = anchor.context
        val menu = PopupMenu(ctx, anchor)
        menu.menu.add(0, 1, 0, "Copy")
        menu.menu.add(0, 2, 1, "Share")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("Econ Analyst message", text))
                        Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
                    }
                    true
                }
                2 -> {
                    val share = Intent(Intent.ACTION_SEND)
                    share.type = "text/plain"
                    share.putExtra(Intent.EXTRA_TEXT, text)
                    ctx.startActivity(Intent.createChooser(share, "Share message"))
                    true
                }
                else -> false
            }
        }
        menu.show()
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
    }

    override fun getItemCount(): Int = messages.size

    class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val llBubbleContainer: LinearLayout = v.findViewById(R.id.llBubbleContainer)
        val llTypingIndicator: LinearLayout = v.findViewById(R.id.llTypingIndicator)
        val tvMessage: TextView = v.findViewById(R.id.tvMessage)
        val tvSenderLabel: TextView = v.findViewById(R.id.tvSenderLabel)
        val tvTimestamp: TextView = v.findViewById(R.id.tvTimestamp)
        val tvRetry: TextView = v.findViewById(R.id.tvRetry)
        val tvChartCaption: TextView = v.findViewById(R.id.tvChartCaption)
        val ivChatImage: ImageView = v.findViewById(R.id.ivChatImage)
        val sparkline: SparklineView = v.findViewById(R.id.sparklineChart)
        val dot1: View = v.findViewById(R.id.dot1)
        val dot2: View = v.findViewById(R.id.dot2)
        val dot3: View = v.findViewById(R.id.dot3)
        val dotAnimators: MutableList<ObjectAnimator> = ArrayList()

        fun hideSparkline() {
            sparkline.visibility = View.GONE
            tvChartCaption.visibility = View.GONE
            sparkline.tag = null
        }
    }

    companion object {
        /** Display-only status the analyst bubble shows while a tool round runs
         *  (AI Law 11). Rendered italic + subdued (TICKET-6); never committed. */
        @JvmField
        val TOOL_STATUS = "Checking the data…"

        /** Partial-bind payload: only the message text changed (SSE streaming). */
        private val PAYLOAD_TEXT = Any()

        /** Timestamps only render when the next message is at least this far away. */
        private const val TIME_GAP_MS = 15 * 60 * 1000L

        /** Inline chart tag emitted by the model: [CHART:DGS10:24M] */
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

        /** Series data cache so recycled rows don't re-query Room. */
        private val seriesCache = HashMap<String, List<Double>>()

        /** Removes [CHART:...] tags from the visible text (rendered separately). */
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
    }
}
