package com.economic.dashboard.analyst;

import com.economic.dashboard.news.NewsItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds compact news context strings for injection into the AI Analyst's
 * Anthropic API call — one block for the system prompt (full context),
 * one abbreviated block appended to the user message (brief context).
 */
public class NewsContextBuilder {

    private static final int MAX_CHARS   = 3000;
    private static final int FULL_COUNT  = 15;
    private static final int BRIEF_COUNT = 5;

    /**
     * Formats the top 15 cached headlines grouped by impact tier (high → medium → normal)
     * into a compact block for the system prompt. Caps at 3000 characters.
     *
     * @param items cached news list from NewsRepository.getCachedItems()
     * @return multi-line string to append to the system prompt, or "" if no news
     */
    public static String build(List<NewsItem> items) {
        if (items == null || items.isEmpty()) return "";

        StringBuilder sb = new StringBuilder("\n\nRECENT ECONOMIC NEWS HEADLINES:\n");

        int count = 0;
        for (int tier = 2; tier >= 0 && count < FULL_COUNT; tier--) {
            for (NewsItem item : items) {
                if (count >= FULL_COUNT) break;
                if (item.impactLevel == tier && item.title != null) {
                    sb.append(formatLine(item));
                    count++;
                }
            }
        }

        String result = sb.toString();
        if (result.length() > MAX_CHARS) {
            result = result.substring(0, MAX_CHARS) + "\n[...additional headlines truncated]";
        }
        return result;
    }

    /**
     * Formats the top 5 headlines (high-impact first) into a compact inline note
     * appended to the user's message turn before sending to the API.
     *
     * @param items cached news list from NewsRepository.getCachedItems()
     * @return short bracketed string, or "" if no news
     */
    public static String buildBrief(List<NewsItem> items) {
        if (items == null || items.isEmpty()) return "";

        List<String> titles = new ArrayList<>();
        for (int tier = 2; tier >= 0 && titles.size() < BRIEF_COUNT; tier--) {
            for (NewsItem item : items) {
                if (titles.size() >= BRIEF_COUNT) break;
                if (item.impactLevel == tier && item.title != null) {
                    titles.add(item.title);
                }
            }
        }

        if (titles.isEmpty()) return "";

        StringBuilder sb = new StringBuilder("\n\n[Recent news for context: ");
        for (int i = 0; i < titles.size(); i++) {
            sb.append(titles.get(i));
            if (i < titles.size() - 1) sb.append(" | ");
        }
        sb.append("]");
        return sb.toString();
    }

    private static String formatLine(NewsItem item) {
        String tag    = item.tag    != null ? item.tag    : "GENERAL";
        String source = item.source != null ? item.source : "";
        return String.format(Locale.US, "• [%s] %s (%s)\n", tag, item.title, source);
    }
}
