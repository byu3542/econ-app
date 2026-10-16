package com.economic.dashboard.search

/**
 * Settings parity U8 (R13, KTD8): what global search can return.
 *
 * [key] is what the result routes on: the indicator id, the article URL, the
 * conversation's grouping key, or the settings section id.
 */
enum class SearchScope { INDICATORS, NEWS, CHATS, SETTINGS }

data class SearchResult(
    val scope: SearchScope,
    val key: String,
    val title: String,
    val subtitle: String? = null
)

/** One scope's hits, best first. Search never returns an empty group. */
data class SearchGroup(val scope: SearchScope, val results: List<SearchResult>)

/**
 * A searchable item. The query matches against [text]; the best match across
 * its entries sets the item's rank.
 */
data class SearchDoc(
    val key: String,
    val title: String,
    val subtitle: String? = null,
    val text: List<String> = listOf(title)
)

/**
 * The snapshots one search runs over. Chats come in as one doc per message
 * carrying the conversation's key and title, so a thread with two matching
 * messages still yields one hit.
 */
data class SearchSources(
    val indicators: List<SearchDoc> = emptyList(),
    val news: List<SearchDoc> = emptyList(),
    val chatMessages: List<SearchDoc> = emptyList(),
    val settings: List<SearchDoc> = emptyList()
)
