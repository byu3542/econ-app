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

import com.economic.dashboard.R;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

public class NewsFragment extends Fragment {

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
        return inflater.inflate(R.layout.fragment_news, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        viewModel = new ViewModelProvider(requireActivity()).get(NewsViewModel.class);

        // ─── RecyclerView ────────────────────────────────────────────────────────
        RecyclerView recycler = view.findViewById(R.id.recycler_news);
        adapter = new NewsAdapter();
        recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        recycler.setAdapter(adapter);

        // ─── Progress / Error views ──────────────────────────────────────────────
        progressBar = view.findViewById(R.id.progress_news);
        tvError     = view.findViewById(R.id.text_news_error);

        // ─── Swipe-to-refresh ────────────────────────────────────────────────────
        swipeRefresh = view.findViewById(R.id.swipe_refresh_news);
        swipeRefresh.setColorSchemeColors(0xFFc9a84c);
        swipeRefresh.setOnRefreshListener(() -> viewModel.loadNews(true));

        // ─── Filter chips ────────────────────────────────────────────────────────
        ChipGroup chipGroup = view.findViewById(R.id.chip_group_filter);
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
            tvError.setVisibility(hasError ? View.VISIBLE : View.GONE);
            if (hasError) tvError.setText(error);
        });

        // ─── Initial load ────────────────────────────────────────────────────────
        viewModel.loadNews(false);
    }
}
