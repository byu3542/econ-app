package com.economic.dashboard.news

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.RecyclerView
import com.economic.dashboard.R
import com.economic.dashboard.analyst.AnalystIdentity
import com.economic.dashboard.analyst.AskAnalyst
import com.economic.dashboard.analyst.SmartPromptGenerator
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * News list: hero for the newest story, then rows grouped under
 * Today / Yesterday / This week / Earlier section headers.
 * Read articles are dimmed (state kept in SharedPreferences).
 */
class NewsAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    /** One displayed row: either a section header or a news item. */
    private class Row(val type: Int, val item: NewsItem?, val header: String?)

    private val rows = ArrayList<Row>()

    fun submitList(items: List<NewsItem>?) {
        rows.clear()
        if (items != null && items.isNotEmpty()) {
            // MarketWatch publishes a broad mix of syndicated and non-economic
            // content. Keep those stories in the feed, but never promote one to
            // the featured/top-story position.
            var heroIndex = -1
            for (i in items.indices) {
                if (!isMarketWatch(items[i])) {
                    heroIndex = i
                    break
                }
            }
            if (heroIndex >= 0) rows.add(Row(VIEW_TYPE_HERO, items[heroIndex], null))
            var lastBucket: String? = null
            val todayStart = todayStartMillis() // same for every row of this render
            for (i in items.indices) {
                if (i == heroIndex) continue
                val item = items[i]
                val bucket = bucketFor(item.pubDateMillis, todayStart)
                if (bucket != lastBucket) {
                    rows.add(Row(VIEW_TYPE_HEADER, null, bucket))
                    lastBucket = bucket
                }
                rows.add(Row(VIEW_TYPE_ROW, item, null))
            }
        }
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int = rows[position].type

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_HERO -> HeroViewHolder(inflater.inflate(R.layout.item_news_hero, parent, false))
            VIEW_TYPE_HEADER -> HeaderViewHolder(inflater.inflate(R.layout.item_news_header, parent, false))
            else -> RowViewHolder(inflater.inflate(R.layout.item_news_row, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val row = rows[position]
        when (holder) {
            is HeroViewHolder -> holder.bind(row.item, this)
            is HeaderViewHolder -> holder.bind(row.header)
            is RowViewHolder -> holder.bind(row.item, this)
        }
    }

    // ─── Read state ─────────────────────────────────────────────────────────────

    private fun openAndMarkRead(holder: RecyclerView.ViewHolder, item: NewsItem) {
        val ctx = holder.itemView.context
        markRead(ctx, item.url)
        val pos = holder.bindingAdapterPosition
        if (pos != RecyclerView.NO_POSITION) notifyItemChanged(pos)
        openUrl(ctx, item.url)
    }

    // ─── Long-press → AI Analyst ────────────────────────────────────────────────

    /** Long-press menu: analyze the article, or ask one of the smart prompts. */
    private fun showAnalyzeMenu(anchor: View, item: NewsItem) {
        val ctx = anchor.context
        val activity = findActivity(ctx)
        val title = item.title
        if (activity == null || title == null) return

        val menu = PopupMenu(ctx, anchor)
        menu.menu.add(0, 1, 0,
            AnalystIdentity.label("Analyze with " + AnalystIdentity.NAME))
        menu.menu.add(0, 10, 0, ctx.getString(R.string.news_open_original))
        val prompts: List<String> = SmartPromptGenerator.generatePromptsForHeadline(title)
        val shown = minOf(2, prompts.size)
        for (i in 0 until shown) menu.menu.add(0, 2 + i, 1 + i, prompts[i])

        menu.setOnMenuItemClickListener { mi ->
            val query: String
            when {
                mi.itemId == 1 -> {
                    val sb = StringBuilder("Analyze this news story in the context of the current economic data:\n\n")
                    sb.append("\"").append(title).append("\"")
                    item.source?.let { sb.append(" — ").append(it) }
                    item.summary?.let { summary ->
                        if (summary.isNotEmpty()) {
                            val sum = if (summary.length > 300) summary.substring(0, 300) + "…" else summary
                            sb.append("\n\n").append(sum)
                        }
                    }
                    sb.append("\n\nWhat does this mean for the economic outlook?")
                    query = sb.toString()
                }
                mi.itemId == 10 -> {
                    openUrl(ctx, item.url)
                    return@setOnMenuItemClickListener true
                }
                else -> {
                    val idx = mi.itemId - 2
                    if (idx < 0 || idx >= prompts.size) return@setOnMenuItemClickListener false
                    val sb = StringBuilder("Regarding this news story:\n\n")
                    sb.append("\"").append(title).append("\"")
                    item.source?.let { sb.append(" — ").append(it) }
                    item.summary?.let { summary ->
                        if (summary.isNotEmpty()) {
                            val sum = if (summary.length > 300) summary.substring(0, 300) + "…" else summary
                            sb.append("\n\n").append(sum)
                        }
                    }
                    sb.append("\n\n").append(prompts[idx])
                    query = sb.toString()
                }
            }
            AskAnalyst.openWithQuery(activity, query)
            true
        }
        menu.show()
    }

    // ─── Header ViewHolder ──────────────────────────────────────────────────────

    private class HeaderViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val tv: TextView = v.findViewById(R.id.tv_section_header)
        fun bind(header: String?) {
            tv.text = header
        }
    }

    // ─── Hero ViewHolder ────────────────────────────────────────────────────────

    private class HeroViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val tvSource: TextView = v.findViewById(R.id.tv_hero_source)
        val tvTimeAgo: TextView = v.findViewById(R.id.tv_hero_time)
        val tvHeadline: TextView = v.findViewById(R.id.tv_hero_headline)
        val tvSummary: TextView = v.findViewById(R.id.tv_hero_summary)
        val ivThumb: ImageView = v.findViewById(R.id.iv_hero_thumb)
        val more: View = v.findViewById(R.id.btn_hero_more)

        fun bind(item: NewsItem?, adapter: NewsAdapter) {
            if (item == null) return
            val source = item.source
            tvSource.text = source?.uppercase(Locale.US) ?: ""
            tvTimeAgo.text = formatTimeAgo(item.pubDateMillis)
            tvHeadline.text = item.title
            tvSummary.text = item.summary
            val summary = item.summary
            tvSummary.visibility = if (!summary.isNullOrEmpty()) View.VISIBLE else View.GONE
            applyReadDim(tvHeadline, itemView.context, item.url)
            ThumbLoader.load(ivThumb, item.imageUrl)

            itemView.setOnClickListener { adapter.openAndMarkRead(this, item) }
            itemView.setOnLongClickListener { v -> adapter.showAnalyzeMenu(v, item); true }
            more.setOnClickListener { adapter.showAnalyzeMenu(it, item) }
        }
    }

    // ─── Row ViewHolder ─────────────────────────────────────────────────────────

    private class RowViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val impactBar: View = v.findViewById(R.id.view_impact_bar)
        val tvSource: TextView = v.findViewById(R.id.tv_row_source)
        val tvTimeAgo: TextView = v.findViewById(R.id.tv_row_time)
        val tvHeadline: TextView = v.findViewById(R.id.tv_row_headline)
        val tvTag: TextView = v.findViewById(R.id.tv_row_tag)
        val tvImpact: TextView = v.findViewById(R.id.tv_row_impact)
        val ivThumb: ImageView = v.findViewById(R.id.iv_row_thumb)
        val more: View = v.findViewById(R.id.btn_row_more)

        fun bind(item: NewsItem?, adapter: NewsAdapter) {
            if (item == null) return
            val source = item.source
            tvSource.text = source?.uppercase(Locale.US) ?: ""
            tvTimeAgo.text = formatTimeAgo(item.pubDateMillis)
            tvHeadline.text = item.title
            val tag = item.tag ?: NewsConstants.TAG_GENERAL
            tvTag.text = tag
            setTagColor(tvTag, tag)
            setImpactBarColor(impactBar, item.impactLevel)
            when {
                item.impactLevel >= 2 -> {
                    tvImpact.text = itemView.context.getString(R.string.news_high_impact)
                    tvImpact.visibility = View.VISIBLE
                }
                item.impactLevel == 1 -> {
                    tvImpact.text = itemView.context.getString(R.string.news_medium_impact)
                    tvImpact.visibility = View.VISIBLE
                }
                else -> tvImpact.visibility = View.GONE
            }
            applyReadDim(tvHeadline, itemView.context, item.url)
            ThumbLoader.load(ivThumb, item.imageUrl)

            itemView.setOnClickListener { adapter.openAndMarkRead(this, item) }
            itemView.setOnLongClickListener { v -> adapter.showAnalyzeMenu(v, item); true }
            more.setOnClickListener { adapter.showAnalyzeMenu(it, item) }
        }
    }

    companion object {
        private const val VIEW_TYPE_HERO = 0
        private const val VIEW_TYPE_ROW = 1
        private const val VIEW_TYPE_HEADER = 2

        private const val PREFS_READ = "news_read_state"
        private const val KEY_READ_URLS = "read_urls"
        private const val MAX_READ_URLS = 400

        private fun isMarketWatch(item: NewsItem?): Boolean {
            val source = item?.source ?: return false
            return source.trim().equals("MarketWatch", ignoreCase = true)
        }

        /** Midnight of "today" — computed once per submitList render, not per row. */
        private fun todayStartMillis(): Long {
            val cal = Calendar.getInstance()
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }

        private fun bucketFor(millis: Long, todayStart: Long): String {
            if (millis >= todayStart) return "Today"
            if (millis >= todayStart - 86_400_000L) return "Yesterday"
            if (millis >= todayStart - 6 * 86_400_000L) return "This week"
            return "Earlier"
        }

        private fun isRead(ctx: Context, url: String?): Boolean {
            if (url == null) return false
            return prefs(ctx).getStringSet(KEY_READ_URLS, HashSet())?.contains(url) == true
        }

        private fun markRead(ctx: Context, url: String?) {
            if (url == null) return
            val current = HashSet(prefs(ctx).getStringSet(KEY_READ_URLS, HashSet()) ?: HashSet())
            if (!current.add(url)) return
            if (current.size > MAX_READ_URLS) current.clear() // crude cap; re-dims naturally
            prefs(ctx).edit().putStringSet(KEY_READ_URLS, current).apply()
        }

        private fun prefs(ctx: Context): SharedPreferences =
            ctx.getSharedPreferences(PREFS_READ, Context.MODE_PRIVATE)

        private fun applyReadDim(headline: View, ctx: Context, url: String?) {
            headline.alpha = if (isRead(ctx, url)) 0.5f else 1f
        }

        private fun findActivity(context: Context): FragmentActivity? {
            var ctx = context
            while (ctx is ContextWrapper) {
                if (ctx is FragmentActivity) return ctx
                ctx = ctx.baseContext
            }
            return null
        }

        private fun formatTimeAgo(pubMillis: Long): String {
            val diff = System.currentTimeMillis() - pubMillis
            val minutes = diff / 60_000
            val hours = diff / 3_600_000
            val days = diff / 86_400_000

            if (minutes < 60) return "${minutes}m ago"
            if (hours < 24) return "${hours}h ago"
            if (days < 7) return "${days}d ago"
            return SimpleDateFormat("MMM d", Locale.US).format(Date(pubMillis))
        }

        private fun isNight(ctx: Context): Boolean =
            (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        private fun setTagColor(tv: TextView, tag: String) {
            val night = isNight(tv.context)
            val bgColor: Int
            val textColor: Int
            when (tag) {
                NewsConstants.TAG_FED -> {
                    bgColor = if (night) 0x2E8fa3c8.toInt() else 0x140f1729.toInt()
                    textColor = if (night) 0xFFB9C6E0.toInt() else 0xFF0f1729.toInt()
                }
                NewsConstants.TAG_INFLATION -> {
                    bgColor = 0x18f0a500.toInt()
                    textColor = if (night) 0xFFF0B23E.toInt() else 0xFFa06000.toInt()
                }
                NewsConstants.TAG_JOBS -> {
                    bgColor = 0x1822c55e.toInt()
                    textColor = if (night) 0xFF6EE7A0.toInt() else 0xFF166534.toInt()
                }
                NewsConstants.TAG_YIELDS -> {
                    bgColor = 0x1ec9a84c.toInt()
                    textColor = if (night) 0xFFDDBE6B.toInt() else 0xFF7a5a10.toInt()
                }
                NewsConstants.TAG_ECONOMY -> {
                    bgColor = 0x183b82f6.toInt()
                    textColor = if (night) 0xFF8FB8F8.toInt() else 0xFF1e40af.toInt()
                }
                NewsConstants.TAG_RESEARCH -> {
                    bgColor = 0x186366f1.toInt()
                    textColor = if (night) 0xFFA5A8F5.toInt() else 0xFF3730a3.toInt()
                }
                NewsConstants.TAG_HOUSING -> {
                    bgColor = 0x1806b6d4.toInt()
                    textColor = if (night) 0xFF67D9EE.toInt() else 0xFF0e7490.toInt()
                }
                else -> { // GENERAL
                    bgColor = 0x14888888.toInt()
                    textColor = if (night) 0xFF9AA3B5.toInt() else 0xFF666666.toInt()
                }
            }
            val bg = GradientDrawable()
            bg.shape = GradientDrawable.RECTANGLE
            bg.cornerRadius = dp(tv.context, 10f)
            bg.setColor(bgColor)
            tv.background = bg
            tv.setTextColor(textColor)
        }

        private fun setImpactBarColor(bar: View, impactLevel: Int) {
            when (impactLevel) {
                2 -> {
                    bar.setBackgroundColor(0xFFc9a84c.toInt()) // gold
                    bar.contentDescription = "High impact story"
                    bar.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                }
                1 -> {
                    bar.setBackgroundColor(0x59c9a84c.toInt()) // gold @ ~35% alpha
                    bar.contentDescription = "Medium impact story"
                    bar.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                }
                else -> {
                    bar.setBackgroundColor(0x00000000.toInt()) // transparent
                    bar.contentDescription = null
                    bar.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
            }
        }

        /**
         * MOT-04: opens a story from outside the feed (the analyst's sources
         * list) the same way a tap in the feed does — marked read, then opened
         * in a Custom Tab.
         */
        @JvmStatic
        fun openArticle(context: Context, url: String?) {
            markRead(context, url)
            openUrl(context, url)
        }

        private fun openUrl(context: Context, url: String?) {
            if (url.isNullOrEmpty()) return
            try {
                val colorParams = CustomTabColorSchemeParams.Builder()
                    .setToolbarColor(0xFF0F1729.toInt())
                    .build()
                val customTab = CustomTabsIntent.Builder()
                    .setDefaultColorSchemeParams(colorParams)
                    .build()
                customTab.launchUrl(context, Uri.parse(url))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        private fun dp(ctx: Context, dp: Float): Float = dp * ctx.resources.displayMetrics.density
    }
}
