package com.economic.dashboard.ui.fragments;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.economic.dashboard.R;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

/**
 * Level-2 Economy destination.
 * Hosts four child fragments in a horizontal ViewPager2 controlled by a secondary TabLayout:
 *   0 → GdpFragment
 *   1 → EmploymentFragment
 *   2 → InflationFragment
 *   3 → HousingFragment
 */
public class EconomyFragment extends Fragment {

    private static final String[] TAB_TITLES = {"GDP", "Employment", "Inflation", "Housing"};

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_economy, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        TabLayout tabLayout   = view.findViewById(R.id.economyTabLayout);
        ViewPager2 viewPager  = view.findViewById(R.id.economyViewPager);

        // Child fragment adapter — use getChildFragmentManager() for nested fragments
        viewPager.setAdapter(new EconomyPagerAdapter(this));
        viewPager.setOffscreenPageLimit(3);

        new TabLayoutMediator(tabLayout, viewPager, (tab, position) ->
                tab.setText(TAB_TITLES[position])
        ).attach();
    }

    // ── Inner adapter ──────────────────────────────────────────────────────────

    private static class EconomyPagerAdapter extends FragmentStateAdapter {

        EconomyPagerAdapter(@NonNull Fragment fragment) {
            super(fragment);
        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            switch (position) {
                case 0:  return new GdpFragment();
                case 1:  return new EmploymentFragment();
                case 2:  return new InflationFragment();
                case 3:  return new HousingFragment();
                default: return new GdpFragment();
            }
        }

        @Override
        public int getItemCount() { return 4; }
    }
}
