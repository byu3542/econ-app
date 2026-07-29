package com.economic.dashboard.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup

import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter

import com.economic.dashboard.databinding.FragmentMarketsHostBinding
import com.google.android.material.tabs.TabLayoutMediator

/**
 * Level-2 Markets destination.
 * Hosts two child fragments in a horizontal ViewPager2 controlled by a secondary TabLayout:
 *   0 → YieldsFragment
 *   1 → SpreadsFragment
 */
class TreasuryFragment : Fragment() {

    private var binding: FragmentMarketsHostBinding? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val b = FragmentMarketsHostBinding.inflate(inflater, container, false)
        binding = b
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val b = binding ?: return
        val tabLayout = b.marketsTabLayout
        val viewPager = b.marketsViewPager

        // Child fragment adapter — use getChildFragmentManager() for nested fragments
        viewPager.adapter = MarketsTabAdapter(this)
        viewPager.offscreenPageLimit = 1

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = TAB_TITLES[position]
        }.attach()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }

    // ── Inner adapter ──────────────────────────────────────────────────────────
    // Nested (not `inner`), matching the Java `static` nested class.

    private class MarketsTabAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {

        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> YieldsFragment()
                1 -> SpreadsFragment()
                else -> YieldsFragment()
            }
        }

        override fun getItemCount(): Int = 2
    }

    companion object {
        private val TAB_TITLES = arrayOf("Yields", "Spreads")
    }
}
