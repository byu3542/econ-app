package com.economic.dashboard.news;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.economic.dashboard.databinding.FragmentNewsBinding;
import com.economic.dashboard.R;
import com.economic.dashboard.utils.MotionUtil;
import com.facebook.shimmer.ShimmerFrameLayout;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

import java.util.List;

public class NewsFragment extends Fragment {

    /** Cross-fade duration for the skeleton → content swap. */
    private static final long REVEAL_MS = 300L;

    private FragmentNewsBinding binding;

    private NewsViewModel        viewModel;
    private NewsAdapter          adapter;
    private SwipeRefreshLayout   swipeRefresh;
    private TextView             tvError;

    /** Cold-start skeleton (view_news_skeleton), null once the view is torn down. */
    private ShimmerFrameLayout skeleton;
    /** Latched the first time content lands, so refresh never re-shows the skeleton. */
    private boolean skeletonHidden = true;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentNewsBinding.inflate(inflater, container, false);
        // IOS-04 (2026-07-27 nav fix): News was skipped by the original IOS-04
        // pass (its root is a CoordinatorLayout, a different shape from the
        // NestedScrollView-rooted leaf tabs), so its subtitle and filter chip
        // row rendered underneath the translucent header. Split the insets:
        // the header block clears the top bar, the list clears the bottom nav
        // (and keeps clipToPadding=false so rows still scroll under it).
        com.economic.dashboard.utils.EdgeToEdgeInsets.applyTopInset(binding.newsHeaderBlock);
        com.economic.dashboard.utils.EdgeToEdgeInsets.applyBottomInset(binding.recyclerNews);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        viewModel = new ViewModelProvider(requireActivity()).get(NewsViewModel.class);

        // ─── RecyclerView ────────────────────────────────────────────────────────
        RecyclerView recycler = binding.recyclerNews;
        adapter = new NewsAdapter();
        recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        recycler.setAdapter(adapter);

        // ─── Cold-start skeleton ─────────────────────────────────────────────────
        // Decided before the observers are attached: LiveData replays its last
        // value the moment we observe, so checking afterwards would flash a
        // skeleton on every return to the tab. Warm = this process already has
        // stories (activity-scoped ViewModel survives rotation and tab switches)
        // or the repository still holds a fresh feed.
        skeleton = binding.newsSkeleton.getRoot();
        List<NewsItem> known = viewModel.getNewsItems().getValue();
        boolean coldStart = (known == null || known.isEmpty())
                && NewsRepository.getInstance().getCachedItems().isEmpty();
        if (coldStart) showSkeleton();

        // ─── Error views ─────────────────────────────────────────────────────────
        tvError     = binding.textNewsError;
        binding.btnNewsRetry.setOnClickListener(v -> {
            binding.errorContainer.setVisibility(View.GONE);
            // Retrying from the error card is still a cold start — there is
            // nothing on screen to keep the reader occupied.
            if (adapter.getItemCount() == 0) showSkeleton();
            viewModel.loadNews(true);
        });
        binding.btnNewsClearFilter.setOnClickListener(v -> binding.chipAll.setChecked(true));

        // ─── Swipe-to-refresh ────────────────────────────────────────────────────
        swipeRefresh = binding.swipeRefreshNews;
        swipeRefresh.setColorSchemeColors(0xFFc9a84c);
        swipeRefresh.setOnRefreshListener(() -> viewModel.loadNews(true));

        // ─── Filter chips ────────────────────────────────────────────────────────
        ChipGroup chipGroup = binding.chipGroupFilter;
        chipGroup.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) {
                viewModel.setFilter(null);
                return;
            }
            int id = checkedIds.get(0);
            if      (id == R.id.chip_all)       viewModel.setFilter(null);
            else if (id == R.id.chip_fed)       viewModel.setFilter("FED");
            else if (id == R.id.chip_inflation) viewModel.setFilter("INFLATION");
            else if (id == R.id.chip_jobs)      viewModel.setFilter("JOBS");
            else if (id == R.id.chip_yields)    viewModel.setFilter("YIELDS");
            else if (id == R.id.chip_economy)   viewModel.setFilter("ECONOMY");
            else if (id == R.id.chip_research)  viewModel.setFilter("RESEARCH");
            else if (id == R.id.chip_housing)   viewModel.setFilter("HOUSING");
        });

        // ─── Observers ──────────────────────────────────────────────────────────
        viewModel.getNewsItems().observe(getViewLifecycleOwner(), items -> {
            adapter.submitList(items);
            boolean empty = items == null || items.isEmpty();
            recycler.setVisibility(empty ? View.GONE : View.VISIBLE);
            binding.emptyContainer.setVisibility(
                    empty && skeletonHidden ? View.VISIBLE : View.GONE);
            if (!empty) {
                binding.textNewsMeta.setText(getString(R.string.news_meta, items.size(), "just now"));
                hideSkeleton();
            }
        });

        viewModel.getIsLoading().observe(getViewLifecycleOwner(), loading -> {
            boolean hasData = adapter.getItemCount() > 0;
            if (loading && !hasData) binding.emptyContainer.setVisibility(View.GONE);
            // The fetch is over: drop the skeleton even if it came back empty,
            // so the empty state isn't stuck behind a shimmer forever.
            if (!loading) {
                hideSkeleton();
                // Stop swipe refresh indicator when done
                swipeRefresh.setRefreshing(false);
            }
        });

        viewModel.getErrorMessage().observe(getViewLifecycleOwner(), error -> {
            boolean hasError = error != null && !error.isEmpty();
            // Only surface the error card when there's nothing to read —
            // stale-but-present content beats an error banner.
            binding.errorContainer.setVisibility(
                    hasError && adapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
            if (hasError && adapter.getItemCount() == 0) {
                hideSkeleton();
                binding.emptyContainer.setVisibility(View.GONE);
            }
            if (hasError) tvError.setText(error);
        });

        // ─── Initial load ────────────────────────────────────────────────────────
        viewModel.loadNews(false);
    }

    /**
     * Puts the shape of the feed on screen — one hero card plus rows — while the
     * first fetch is in flight. Only ever called with an empty list: a cold start
     * with no cache, or a retry from the error card.
     */
    private void showSkeleton() {
        if (binding == null || skeleton == null) return;
        skeletonHidden = false;
        binding.emptyContainer.setVisibility(View.GONE);
        binding.recyclerNews.setAlpha(0f);
        skeleton.animate().cancel(); // drop any in-flight fade-out from a prior reveal
        skeleton.setAlpha(1f);
        skeleton.setVisibility(View.VISIBLE);
        // TICKET-28: reduced motion gets the same skeleton, rendered static.
        if (MotionUtil.animationsEnabled(skeleton.getContext())) skeleton.startShimmer();
    }

    /**
     * Cross-fades the cold-start skeleton out and the list in. Idempotent: once
     * the first feed (or error) lands, {@code skeletonHidden} stays latched, so
     * pull-to-refresh and chip filtering can never bring the skeleton back —
     * refresh keeps the SwipeRefreshLayout spinner it always had. Only an
     * explicit {@link #showSkeleton()} (retry with an empty list) unlatches it.
     */
    private void hideSkeleton() {
        if (skeletonHidden || binding == null || skeleton == null) return;
        skeletonHidden = true;

        final ShimmerFrameLayout shimmer = skeleton;
        final RecyclerView recycler = binding.recyclerNews;
        shimmer.stopShimmer();

        // TICKET-28: reduced motion swaps instantly instead of cross-fading.
        if (!MotionUtil.animationsEnabled(shimmer.getContext())) {
            recycler.setAlpha(1f);
            shimmer.setVisibility(View.GONE);
        } else {
            recycler.animate().alpha(1f).setDuration(REVEAL_MS).start();
            shimmer.animate().alpha(0f).setDuration(REVEAL_MS)
                    .withEndAction(() -> shimmer.setVisibility(View.GONE))
                    .start();
        }

        // Nothing came back: hand the screen over to the empty state, unless the
        // error card already owns it.
        if (adapter.getItemCount() == 0
                && binding.errorContainer.getVisibility() != View.VISIBLE) {
            binding.emptyContainer.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (skeleton != null) {
            skeleton.stopShimmer();
            skeleton.animate().cancel();
            skeleton = null;
        }
        skeletonHidden = true;
        binding = null;
    }
}
