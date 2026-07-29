package com.economic.dashboard.news

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup

import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager

import com.economic.dashboard.R
import com.economic.dashboard.databinding.FragmentNewsBinding
import com.economic.dashboard.utils.EdgeToEdgeInsets

class NewsFragment : Fragment() {

    private var binding: FragmentNewsBinding? = null

    private lateinit var viewModel: NewsViewModel
    private lateinit var adapter: NewsAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val b = FragmentNewsBinding.inflate(inflater, container, false)
        binding = b
        // IOS-04 (2026-07-27 nav fix): News was skipped by the original IOS-04
        // pass (its root is a CoordinatorLayout, a different shape from the
        // NestedScrollView-rooted leaf tabs), so its subtitle and filter chip
        // row rendered underneath the translucent header. Split the insets:
        // the header block clears the top bar, the list clears the bottom nav
        // (and keeps clipToPadding=false so rows still scroll under it).
        EdgeToEdgeInsets.applyTopInset(b.newsHeaderBlock)
        EdgeToEdgeInsets.applyBottomInset(b.recyclerNews)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val b = binding ?: return
        viewModel = ViewModelProvider(requireActivity()).get(NewsViewModel::class.java)

        // ─── RecyclerView ────────────────────────────────────────────────────────
        val recycler = b.recyclerNews
        val newsAdapter = NewsAdapter()
        adapter = newsAdapter
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = newsAdapter

        // ─── Progress / Error views ──────────────────────────────────────────────
        val progressBar = b.progressNews
        val tvError = b.textNewsError
        b.btnNewsRetry.setOnClickListener {
            b.errorContainer.visibility = View.GONE
            viewModel.loadNews(true)
        }
        b.btnNewsClearFilter.setOnClickListener { b.chipAll.isChecked = true }

        // ─── Swipe-to-refresh ────────────────────────────────────────────────────
        val swipeRefresh = b.swipeRefreshNews
        // .toInt() on an 8-digit hex literal: Kotlin would infer Long otherwise
        // and setColorSchemeColors takes int (migration lesson 4).
        swipeRefresh.setColorSchemeColors(0xFFc9a84c.toInt())
        swipeRefresh.setOnRefreshListener { viewModel.loadNews(true) }

        // ─── Filter chips ────────────────────────────────────────────────────────
        val chipGroup = b.chipGroupFilter
        chipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isEmpty()) {
                viewModel.setFilter(null)
            } else {
                val id = checkedIds[0]
                if (id == R.id.chip_all) viewModel.setFilter(null)
                else if (id == R.id.chip_fed) viewModel.setFilter("FED")
                else if (id == R.id.chip_inflation) viewModel.setFilter("INFLATION")
                else if (id == R.id.chip_jobs) viewModel.setFilter("JOBS")
                else if (id == R.id.chip_yields) viewModel.setFilter("YIELDS")
                else if (id == R.id.chip_economy) viewModel.setFilter("ECONOMY")
                else if (id == R.id.chip_research) viewModel.setFilter("RESEARCH")
                else if (id == R.id.chip_housing) viewModel.setFilter("HOUSING")
            }
        }

        // ─── Observers ──────────────────────────────────────────────────────────
        viewModel.getNewsItems().observe(viewLifecycleOwner) { items ->
            newsAdapter.submitList(items)
            val empty = items == null || items.isEmpty()
            recycler.visibility = if (empty) View.GONE else View.VISIBLE
            b.emptyContainer.visibility = if (empty) View.VISIBLE else View.GONE
            if (!empty) {
                b.textNewsMeta.text = getString(R.string.news_meta, items.size, "just now")
            }
        }

        viewModel.getIsLoading().observe(viewLifecycleOwner) { loading ->
            val hasData = newsAdapter.itemCount > 0
            // Show ProgressBar only on initial load (no data yet)
            progressBar.visibility = if (loading && !hasData) View.VISIBLE else View.GONE
            b.textNewsLoading.visibility = if (loading && !hasData) View.VISIBLE else View.GONE
            if (loading && !hasData) b.emptyContainer.visibility = View.GONE
            // Stop swipe refresh indicator when done
            if (!loading) swipeRefresh.isRefreshing = false
        }

        viewModel.getErrorMessage().observe(viewLifecycleOwner) { error ->
            val hasError = error != null && !error.isEmpty()
            // Only surface the error card when there's nothing to read —
            // stale-but-present content beats an error banner.
            b.errorContainer.visibility =
                if (hasError && newsAdapter.itemCount == 0) View.VISIBLE else View.GONE
            if (hasError && newsAdapter.itemCount == 0) {
                b.emptyContainer.visibility = View.GONE
                b.textNewsLoading.visibility = View.GONE
            }
            if (hasError) tvError.text = error
        }

        // ─── Initial load ────────────────────────────────────────────────────────
        viewModel.loadNews(false)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }
}
