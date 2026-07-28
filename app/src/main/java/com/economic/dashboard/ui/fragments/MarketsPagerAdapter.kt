package com.economic.dashboard.ui.fragments

import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter

class MarketsPagerAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {

    override fun createFragment(position: Int): Fragment = when (position) {
        0 -> StocksFragment()
        1 -> BondsFragment()
        2 -> YieldsFragment()
        3 -> SpreadsFragment()
        else -> StocksFragment()
    }

    override fun getItemCount(): Int = 4
}
