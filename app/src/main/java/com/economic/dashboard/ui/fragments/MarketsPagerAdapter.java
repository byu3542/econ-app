package com.economic.dashboard.ui.fragments;

import androidx.fragment.app.Fragment;
import androidx.viewpager2.adapter.FragmentStateAdapter;

public class MarketsPagerAdapter extends FragmentStateAdapter {

    public MarketsPagerAdapter(Fragment fragment) {
        super(fragment);
    }

    @Override
    public Fragment createFragment(int position) {
        switch (position) {
            case 0:  return new StocksFragment();
            case 1:  return new BondsFragment();
            case 2:  return new YieldsFragment();
            case 3:  return new SpreadsFragment();
            default: return new StocksFragment();
        }
    }

    @Override
    public int getItemCount() {
  