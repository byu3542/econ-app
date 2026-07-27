package com.economic.dashboard.ui.fragments;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.viewpager2.widget.ViewPager2;

import com.economic.dashboard.databinding.FragmentMarketsBinding;
import com.economic.dashboard.ui.EconomicViewModel;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

public class MarketsFragment extends Fragment {

    private EconomicViewModel viewModel;
    private FragmentMarketsBinding binding;
    private MarketsPagerAdapter pagerAdapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentMarketsBinding.inflate(inflater, container, false);
        // IOS-04 (2026-07-27 nav fix): this host owns the screen's topmost
        // pixel — its TabLayout was previously sitting at y=0 underneath the
        // translucent header, invisible and untappable, because the top inset
        // had been applied to the leaf fragments inside the ViewPager2 instead.
        // The host takes the top inset; the leaves take bottom only.
        com.economic.dashboard.utils.EdgeToEdgeInsets.applyTopInset(binding.getRoot());
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        viewModel = new ViewModelProvider(requireActivity()).get(EconomicViewModel.class);

        TabLayout tabLayout = binding.tabLayout;
        ViewPager2 viewPager = binding.viewPager;

        pagerAdapter = new MarketsPagerAdapter(this);
        viewPager.setAdapter(pagerAdapter);

        new TabLayoutMediator(tabLayout, viewPager, (tab, position) -> {
            switch (position) {
                case 0: tab.setText("STOCKS"); break;
                case 1: tab.setText("BONDS"); break;
                case 2: tab.setText("YIELDS"); break;
                case 3: tab.setText("SPREADS"); break;
                default: tab.setText(""); break;
            }
        }).attach();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
