package com.economic.dashboard.news;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
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
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

public class NewsFragment extends Fragment {

    private FragmentNewsBinding binding;

    private NewsViewModel        viewModel;
    private NewsAdapter          adapter;
    private SwipeRefreshLayout   swipeRefresh;
    private ProgressBar          progressBar;
    private TextView             tvError;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentNewsBinding.inflate(inflater, container, false);
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

        // ─── Progress / Error views ──────────────────────────────────────────────
        progressBar = binding.progressNews;
        tvError     = binding.textNewsError;
        binding.btnNewsRetry.setOnClickListener(v -> {
            binding.errorContainer.setVisibility(View.GONE);
            viewModel.loadNews(true);
        });

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
        });

        viewModel.getIsLoading().observe(getViewLifecycleOwner(), loading -> {
            boolean hasData = adapter.getItemCount() > 0;
            // Show ProgressBar only on initial load (no data yet)
            progressBar.setVisibility(loading && !hasData ? View.VISIBLE : View.GONE);
            // Stop swipe refresh indicator when done
            if (!loading) swipeRefresh.setRefreshing(false);
        });

        viewModel.getErrorMessage().observe(getViewLifecycleOwner(), error -> {
            boolean hasError = error != null && !error.isEmpty();
            // Only surface the error card when there's nothing to read —
            // stale-but-present content beats an error banner.
            binding.errorContainer.setVisibility(
                    hasError && adapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
            if (hasError) tvError.setText(error);
        });

        // ─── Initial load ────────────────────────────────────────────────────────
        viewModel.loadNews(false);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
