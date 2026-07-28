package com.economic.dashboard.ui

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.economic.dashboard.ui.fragments.DashboardFragment
import com.economic.dashboard.ui.fragments.EmploymentFragment
import com.economic.dashboard.ui.fragments.GdpFragment
import com.economic.dashboard.ui.fragments.HousingFragment
import com.economic.dashboard.ui.fragments.InflationFragment
import com.economic.dashboard.ui.fragments.TreasuryFragment

class EconomicPagerAdapter(fragmentActivity: FragmentActivity) : FragmentStateAdapter(fragmentActivity) {

    override fun createFragment(position: Int): Fragment = when (position) {
        0 -> DashboardFragment()
        1 -> TreasuryFragment()
        2 -> GdpFragment()
        3 -> EmploymentFragment()
        4 -> InflationFragment() // replaces separate CPI + Wages tabs
        5 -> HousingFragment() // new Housing tab
        else -> DashboardFragment()
    }

    override fun getItemCount(): Int = 6
}
