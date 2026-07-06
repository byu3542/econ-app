package com.economic.dashboard.ui.fragments;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.economic.dashboard.databinding.FragmentMarketsHostBinding;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

/**
 * Level-2 Markets destination.
 * Hosts two child fragments in a horizontal ViewPager2 controlled by a secondary TabLayout:
 *   0 → YieldsFragment
 *   1 → SpreadsFragment
 */
public class TreasuryFragment extends Fragment {

    private static final String[] TAB_TITLES = {"Yields", "Spreads"};

    private FragmentMarketsHostBinding binding;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentMarketsHostBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        TabLayout tabLayout  = binding.marketsTabLayout;
        ViewPager2 viewPager = binding.marketsViewPager;

        // Child fragment adapter — use getChildFragmentManager() for nested fragments
        viewPager.setAdapter(new MarketsTabAdapter(this));
        viewPager.setOffscreenPageLimit(1);

        new TabLayoutMediator(tabLayout, viewPager, (tab, position) ->
                tab.setText(TAB_TITLES[position])
        ).attach();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    // ── Inner adapter ──────────────────────────────────────────────────────────

    private static class MarketsTabAdapter extends FragmentStateAdapter {

        MarketsTabAdapter(@NonNull Fragment fragment) {
            super(fragment);
        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            switch (position) {
                case 0:  return new YieldsFragment();
                case 1:  return new SpreadsFragment();
                default: return new YieldsFragment();
            }
        }