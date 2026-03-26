package com.economic.dashboard.ui;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

import com.economic.dashboard.ui.fragments.DashboardFragment;
import com.economic.dashboard.ui.fragments.TreasuryFragment;
import com.economic.dashboard.ui.fragments.GdpFragment;
import com.economic.dashboard.ui.fragments.EmploymentFragment;
import com.economic.dashboard.ui.fragments.InflationFragment;
import com.economic.dashboard.ui.fragments.HousingFragment;

public class EconomicPagerAdapter extends FragmentStateAdapter {

    public EconomicPagerAdapter(@NonNull FragmentActivity fragmentActivity) {
        super(fragmentActivity);
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        switch (position) {
            case 0: return new DashboardFragment();
            case 1: return new TreasuryFragment();
            case 2: return new GdpFragment();
            case 3: return new EmploymentFragment();
            case 4: return new InflationFragment();   // replaces separate CPI + Wages tabs
            case 5: return new HousingFragment();      // new Housing tab
            default: return new DashboardFragment();
        }
    }

    @Override
    public int getItemCount() { return 6; }
}
