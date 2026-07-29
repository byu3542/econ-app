package com.economic.dashboard.analyst

import com.economic.dashboard.news.NewsItem

import java.util.Locale

/**
 * Builds compact news context strings for injection into the AI Analyst's
 * Anthropic API call — one block for the system prompt (full context),
 * one abbreviated block appended to the user message (brief context).
 */
object NewsContextBuilder {

    private const val MAX_CHARS = 3000
    private const val FULL_COUNT = 15
    private const val BRIEF_COUNT = 5

    /**
     * Formats the top 15 cached headlines grouped by impact tier (high → medium → normal)
     * into a compact block for the system prompt. Caps at 3000 characters.
     *
     * @param items cached news list from NewsRepository.cachedItems
     * @return multi-line string to append to the system prompt, or "" if no news
     */
    @JvmStatic
    fun build(items: List<NewsItem>?): String {
        if (items == null || items.isEmpty()) return ""

        val sb = StringBuilder("\n\nRECENT ECONOMIC NEWS HEADLINES:\n")

        var count = 0
        var tier = 2
        while (tier >= 0 && count < FULL_COUNT) {
            for (item in items) {
                if (count >= FULL_COUNT) break
                if (item.impactLevel == tier && item.title != null) {
                    sb.append(formatLine(item))
                    count++
                }
            }
            tier--
        }

        var result = sb.toString()
        if (result.length > MAX_CHARS) {
            result = result.substring(0, MAX_CHARS) + "\n[...additional headlines truncated]"
        }
        return result
    }

    /**
     * Formats the top 5 headlines (high-impact first) into a compact inline note
     * appended to the user's message turn before sending to the API.
     *
     * @param items cached news list from NewsRepository.cachedItems
     * @return short bracketed string, or "" if no news
     */
    @JvmStatic
    fun buildBrief(items: List<NewsItem>?): String {
        if (items == null || items.isEmpty()) return ""

        val titles = ArrayList<String>()
        var tier = 2
        while (tier >= 0 && titles.size < BRIEF_COUNT) {
            for (item in items) {
                if (titles.size >= BRIEF_COUNT) break
                // Captured into a local val: Kotlin will not smart-cast another
                // class's `var` property across statements (migration lesson 6).
                val title = item.title
                if (item.impactLevel == tier && title != null) {
                    titles.add(title)
                }
            }
            tier--
        }

        if (titles.isEmpty()) return ""

        val sb = StringBuilder("\n\n[Recent news for context: ")
        for (i in titles.indices) {
            sb.append(titles[i])
            if (i < titles.size - 1) sb.append(" | ")
        }
        sb.append("]")
        return sb.toString()
    }

    private fun formatLine(item: NewsItem): String {
        val tag = item.tag ?: "GENERAL"
        val source = item.source ?: ""
        return String.format(Locale.US, "• [%s] %s (%s)\n", tag, item.title, source)
    }
}
