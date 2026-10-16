package com.economic.dashboard.ui.search

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.core.view.ViewCompat
import androidx.core.view.doOnAttach
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.economic.dashboard.R
import com.economic.dashboard.analyst.ConversationSummary
import com.economic.dashboard.analyst.EconomicSeriesRegistry
import com.economic.dashboard.database.YieldDatabase
import com.economic.dashboard.databinding.FragmentSearchBinding
import com.economic.dashboard.databinding.ItemSearchResultBinding
import com.economic.dashboard.news.NewsRepository
import com.economic.dashboard.search.GlobalSearchIndex
import com.economic.dashboard.search.SearchDoc
import com.economic.dashboard.search.SearchGroup
import com.economic.dashboard.search.SearchResult
import com.economic.dashboard.search.SearchScope
import com.economic.dashboard.search.SearchSources
import com.economic.dashboard.ui.DashboardIndicatorCatalog
import com.economic.dashboard.ui.SettingsFragment
import com.economic.dashboard.ui.views.PressFeedback
import com.economic.dashboard.ui.views.SegmentedView
import com.economic.dashboard.utils.AppExecutors

/**
 * Settings parity U9 (R13, R14, KTD9): full-screen global search.
 *
 * Pushed onto fragmentContainer like the Analyst (add + hide, back-stack
 * entry), so MainActivity's back-stack listener hides the header, nav and
 * FAB and restores them on the way out. Every source is a snapshot of what
 * is already loaded; nothing here fetches.
 */
class SearchFragment : Fragment() {

    /** MainActivity routes a tapped result to its target. */
    interface Host {
        fun openSearchResult(result: SearchResult, chat: ConversationSummary?)
    }

    private var binding: FragmentSearchBinding? = null
    private var sources = SearchSources()
    private val chats = HashMap<String, ConversationSummary>()
    private var scope: SearchScope? = null
    private val rows = ArrayList<Row>()
    private val adapter = ResultAdapter()
    private val runSearch = Runnable { search() }

    /** One result plus, for the first of its group, the counted header. */
    private class Row(val result: SearchResult, val header: String?)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View {
        val b = FragmentSearchBinding.inflate(inflater, container, false)
        binding = b
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val b = binding ?: return
        val padTop = b.root.paddingTop
        val padBottom = b.root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(v.paddingLeft, padTop + bars.top, v.paddingRight, padBottom + bars.bottom)
            insets
        }
        // Added after the activity's last inset pass, so ask for one.
        b.root.doOnAttach { ViewCompat.requestApplyInsets(it) }

        b.btnSearchBack.setOnClickListener {
            hideIme()
            parentFragmentManager.popBackStack(TAG, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        }

        val scopes = listOf<SearchScope?>(null) + GlobalSearchIndex.SCOPES
        b.segScope.emphasis = SegmentedView.Emphasis.SUBTLE
        b.segScope.setSegments(scopes.map { getString(labelFor(it)) }, scopes.map { tagFor(it) })
        b.segScope.select(tagFor(scope), false)
        b.segScope.onSelect = SegmentedView.OnSelect { tag ->
            scope = scopes.firstOrNull { tagFor(it) == tag }
            search()
        }

        b.rvSearch.layoutManager = LinearLayoutManager(requireContext())
        b.rvSearch.adapter = adapter
        b.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b4: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                // Debounced (KTD9): one search per pause in typing.
                view.removeCallbacks(runSearch)
                view.postDelayed(runSearch, DEBOUNCE_MS)
            }
        })

        loadSources(requireContext())
        search()
        b.etSearch.requestFocus()
        b.etSearch.post { showIme() }
    }

    /** Indicators, news and settings are in memory; chats need one read. */
    private fun loadSources(ctx: Context) {
        val indicators = DashboardIndicatorCatalog.ALL.map { m ->
            val series = m.seriesId?.let { EconomicSeriesRegistry.byId(it)?.chartLabel }
            SearchDoc(m.id, m.label, series, listOfNotNull(m.label, series))
        }
        val news = NewsRepository.getInstance().cachedItems.mapNotNull { n ->
            val url = n.url?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = n.title?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            SearchDoc(url, title, n.source)
        }
        sources = SearchSources(indicators = indicators, news = news,
            settings = SettingsFragment.searchDocs(ctx))

        val app = ctx.applicationContext
        val chatLabel = getString(R.string.search_subtitle_chat)
        AppExecutors.diskIO().execute {
            val archived = YieldDatabase.getInstance(app).chatMessageDao().getArchived()
            val byKey = HashMap<String, ConversationSummary>()
            val keyOfRow = HashMap<Long, String>()
            ConversationSummary.group(archived).forEachIndexed { i, s ->
                val key = "chat-$i"
                byKey[key] = s
                for (id in s.rowIds) keyOfRow[id] = key
            }
            // One doc per message; the index keeps one hit per conversation.
            val docs = archived.mapNotNull { r ->
                val key = keyOfRow[r.id] ?: return@mapNotNull null
                val text = r.text?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                SearchDoc(key, byKey.getValue(key).title, chatLabel, listOf(text))
            }
            binding?.root?.post {
                if (binding == null) return@post
                chats.clear(); chats.putAll(byKey)
                sources = sources.copy(chatMessages = docs)
                search()
            }
        }
    }

    private fun search() {
        val b = binding ?: return
        val query = b.etSearch.text?.toString().orEmpty()
        val groups = GlobalSearchIndex.search(sources, query, scope)
        rows.clear()
        for (g in groups) g.results.forEachIndexed { i, r ->
            rows.add(Row(r, if (i == 0) headerFor(g) else null))
        }
        adapter.notifyDataSetChanged()

        val blank = query.isBlank()
        b.llSearchEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        b.tvSearchEmptyTitle.text = if (blank) getString(R.string.search_everything)
            else getString(R.string.search_no_results_title, query.trim())
        b.tvSearchEmptyBody.setText(
            if (blank) R.string.search_empty_body else R.string.search_no_results_body)
    }

    private fun headerFor(g: SearchGroup) =
        getString(R.string.search_group_count, getString(labelFor(g.scope)), g.results.size)

    private fun open(r: SearchResult) {
        hideIme()
        (activity as? Host)?.openSearchResult(r, chats[r.key])
    }

    private fun showIme() {
        val et = binding?.etSearch ?: return
        (et.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideIme() {
        val et = binding?.etSearch ?: return
        (et.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(et.windowToken, 0)
    }

    override fun onDestroyView() {
        binding?.root?.removeCallbacks(runSearch)
        binding = null
        super.onDestroyView()
    }

    private inner class ResultAdapter : RecyclerView.Adapter<ResultAdapter.Holder>() {
        inner class Holder(val b: ItemSearchResultBinding) : RecyclerView.ViewHolder(b.root) {
            init { PressFeedback.attach(b.llResultRow) }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemSearchResultBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(h: Holder, position: Int) {
            val row = rows[position]
            h.b.tvGroupHeader.visibility = if (row.header != null) View.VISIBLE else View.GONE
            h.b.tvGroupHeader.text = row.header
            h.b.tvResultTitle.text = row.result.title
            h.b.tvResultSubtitle.text = row.result.subtitle
            h.b.tvResultSubtitle.visibility =
                if (row.result.subtitle.isNullOrBlank()) View.GONE else View.VISIBLE
            h.b.llResultRow.setOnClickListener { open(row.result) }
        }
    }

    companion object {
        const val TAG = "SearchFragment"
        private const val DEBOUNCE_MS = 150L
        private const val TAG_ALL = "all"

        private fun tagFor(s: SearchScope?) = s?.name ?: TAG_ALL

        private fun labelFor(s: SearchScope?) = when (s) {
            null -> R.string.search_scope_all
            SearchScope.INDICATORS -> R.string.search_scope_indicators
            SearchScope.NEWS -> R.string.search_scope_news
            SearchScope.CHATS -> R.string.search_scope_chats
            SearchScope.SETTINGS -> R.string.search_scope_settings
        }

        /** Pushes search over the current tab, like AssistantFragment.show. */
        @JvmStatic
        fun show(fm: FragmentManager) {
            if (fm.findFragmentByTag(TAG) != null || fm.isStateSaved) return
            val tx = fm.beginTransaction().setReorderingAllowed(true)
            fm.findFragmentById(R.id.fragmentContainer)?.let { tx.hide(it) }
            tx.add(R.id.fragmentContainer, SearchFragment(), TAG)
                .addToBackStack(TAG)
                .commit()
        }
    }
}
