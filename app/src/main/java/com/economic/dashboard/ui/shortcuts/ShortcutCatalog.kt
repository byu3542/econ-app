package com.economic.dashboard.ui.shortcuts

import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.economic.dashboard.R

/**
 * Settings parity U6 (KTD6, KTD7): the decorative tile tints. Chosen away from
 * the signal hues (green, yellow, orange, red, purple) so a shortcut tile never
 * reads as a signal state. Each pairs a soft fill with a deeper icon colour,
 * with night values in values-night/colors.xml.
 */
enum class ShortcutTint(@ColorRes val fill: Int, @ColorRes val icon: Int) {
    BLUE(R.color.shortcut_blue_fill, R.color.shortcut_blue_icon),
    TEAL(R.color.shortcut_teal_fill, R.color.shortcut_teal_icon),
    SLATE(R.color.shortcut_slate_fill, R.color.shortcut_slate_icon),
    SAND(R.color.shortcut_sand_fill, R.color.shortcut_sand_icon),
}

/** What a tile does. MainActivity performs each one; the catalog only names it. */
sealed class ShortcutAction {
    /** Select a bottom-nav tab ("markets", "economy", "news") and, optionally, a sub-tab. */
    data class OpenTab(val tabKey: String, val subTab: Int = -1) : ShortcutAction()
    object EditCards : ShortcutAction()
    object RefreshData : ShortcutAction()
    object ChatHistory : ShortcutAction()
    object Search : ShortcutAction()
}

data class Shortcut(
    val id: String,
    @StringRes val label: Int,
    @DrawableRes val icon: Int,
    val tint: ShortcutTint,
    val action: ShortcutAction,
    /** False until the destination exists. Unavailable shortcuts never show. */
    val available: Boolean = true,
)

/**
 * Settings parity U6 (KTD6): every shortcut the sheet can show, and the rules
 * for reading the user's stored order back. Pure Kotlin so a JVM test can
 * cover the read rules without a device.
 */
object ShortcutCatalog {

    const val MAX = 6

    // Markets and Economy sub-tab positions, as their TabLayouts order them.
    private const val MARKETS_STOCKS = 0
    private const val MARKETS_BONDS = 1
    private const val MARKETS_YIELDS = 2
    private const val MARKETS_SPREADS = 3
    private const val ECONOMY_GDP = 0
    private const val ECONOMY_EMPLOYMENT = 1
    private const val ECONOMY_INFLATION = 2
    private const val ECONOMY_HOUSING = 3

    @JvmField
    val ALL: List<Shortcut> = listOf(
        // Search opens in U9; until then it is unavailable and drops out of
        // every list, including the defaults.
        Shortcut("search", R.string.shortcut_search, R.drawable.ic_search, ShortcutTint.SLATE,
            ShortcutAction.Search, available = false),
        Shortcut("yield_curve", R.string.shortcut_yield_curve, R.drawable.ic_shortcut_show_chart, ShortcutTint.BLUE,
            ShortcutAction.OpenTab("markets", MARKETS_YIELDS)),
        Shortcut("inflation", R.string.shortcut_inflation, R.drawable.ic_shortcut_percent, ShortcutTint.SAND,
            ShortcutAction.OpenTab("economy", ECONOMY_INFLATION)),
        Shortcut("jobs", R.string.shortcut_jobs, R.drawable.ic_shortcut_work, ShortcutTint.TEAL,
            ShortcutAction.OpenTab("economy", ECONOMY_EMPLOYMENT)),
        Shortcut("news", R.string.shortcut_news, R.drawable.ic_news, ShortcutTint.SLATE,
            ShortcutAction.OpenTab("news")),
        Shortcut("refresh", R.string.shortcut_refresh, R.drawable.ic_shortcut_refresh, ShortcutTint.BLUE,
            ShortcutAction.RefreshData),
        Shortcut("spreads", R.string.shortcut_spreads, R.drawable.ic_shortcut_compare_arrows, ShortcutTint.TEAL,
            ShortcutAction.OpenTab("markets", MARKETS_SPREADS)),
        Shortcut("bonds", R.string.shortcut_bonds, R.drawable.ic_shortcut_account_balance, ShortcutTint.SAND,
            ShortcutAction.OpenTab("markets", MARKETS_BONDS)),
        Shortcut("stocks", R.string.shortcut_stocks, R.drawable.ic_shortcut_candlestick_chart, ShortcutTint.BLUE,
            ShortcutAction.OpenTab("markets", MARKETS_STOCKS)),
        Shortcut("gdp", R.string.shortcut_gdp, R.drawable.ic_shortcut_factory, ShortcutTint.SLATE,
            ShortcutAction.OpenTab("economy", ECONOMY_GDP)),
        Shortcut("housing", R.string.shortcut_housing, R.drawable.ic_shortcut_home, ShortcutTint.TEAL,
            ShortcutAction.OpenTab("economy", ECONOMY_HOUSING)),
        Shortcut("edit_cards", R.string.shortcut_edit_cards, R.drawable.ic_edit, ShortcutTint.SAND,
            ShortcutAction.EditCards),
        Shortcut("chat_history", R.string.shortcut_chat_history, R.drawable.ic_history, ShortcutTint.BLUE,
            ShortcutAction.ChatHistory),
    )

    /** The plan's default six, in order. Unavailable ones are skipped by [defaults]. */
    @JvmField
    val DEFAULT_IDS: List<String> = listOf("search", "yield_curve", "inflation", "jobs", "news", "refresh")

    private val byId: Map<String, Shortcut> = ALL.associateBy { it.id }

    @JvmStatic
    fun find(id: String): Shortcut? = byId[id]?.takeIf { it.available }

    /** Every shortcut the user can pick right now, in catalog order. */
    @JvmStatic
    fun available(): List<Shortcut> = ALL.filter { it.available }

    /**
     * The default list: [DEFAULT_IDS] minus anything unavailable, topped up
     * from the catalog order so it still holds [MAX] tiles.
     */
    @JvmStatic
    fun defaults(): List<String> {
        val out = DEFAULT_IDS.filter { find(it) != null }.toMutableList()
        for (s in available()) {
            if (out.size >= MAX) break
            if (s.id !in out) out.add(s.id)
        }
        return out
    }

    /**
     * Reads a stored order back: null (never set) gives [defaults]; otherwise
     * unknown or unavailable ids drop, duplicates collapse to their first
     * place, and the list caps at [MAX]. An empty stored list stays empty.
     */
    @JvmStatic
    fun sanitize(stored: List<String>?): List<String> {
        if (stored == null) return defaults()
        val out = LinkedHashSet<String>()
        for (id in stored) {
            if (out.size >= MAX) break
            if (find(id) != null) out.add(id)
        }
        return out.toList()
    }
}
