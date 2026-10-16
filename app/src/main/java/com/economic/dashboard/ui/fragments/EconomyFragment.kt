package com.economic.dashboard.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup

import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter

import com.economic.dashboard.databinding.FragmentEconomyBinding
import com.economic.dashboard.utils.EdgeToEdgeInsets
import com.google.android.material.tabs.TabLayoutMediator

/**
 * Level-2 Economy destination.
 * Hosts four child fragments in a horizontal ViewPager2 controlled by a secondary TabLayout:
 *   0 → GdpFragment
 *   1 → EmploymentFragment
 *   2 → InflationFragment
 *   3 → HousingFragment
 */
class EconomyFragment : Fragment() {

    private var binding: FragmentEconomyBinding? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val b = FragmentEconomyBinding.inflate(inflater, container, false)
        binding = b
        // IOS-04 (2026-07-27 nav fix): this host owns the screen's topmost
        // pixel — its TabLayout was previously sitting at y=0 underneath the
        // translucent header, invisible and untappable, because the top inset
        // had been applied to the leaf fragments inside the ViewPager2 instead.
        // The host takes the top inset; the leaves take bottom only.
        EdgeToEdgeInsets.applyTopInset(b.root)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val b = binding ?: return
        val tabLayout = b.economyTabLayout
        val viewPager = b.economyViewPager

        // Child fragment adapter — use getChildFragmentManager() for nested fragments
        viewPager.adapter = EconomyPagerAdapter(this)
        viewPager.offscreenPageLimit = 3
        // UX request (2026-08-03): swipe-between-tabs removed in favor of
        // tab-press-only navigation. TabLayoutMediator still drives
        // viewPager.setCurrentItem() programmatically on tab tap, which is
        // unaffected by isUserInputEnabled — only touch/drag gestures are
        // disabled.
        viewPager.isUserInputEnabled = false

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = TAB_TITLES[position]
        }.attach()
        if (savedInstanceState == null) arguments?.getInt(ARG_SUB_TAB, -1)?.let { selectSubTab(it) }
    }

    /** Settings parity U6: a shortcut opens straight to one sub-tab. Read on
     *  first creation from [ARG_SUB_TAB]; [selectSubTab] covers a host that is
     *  already showing. */
    fun selectSubTab(position: Int) {
        val vp = binding?.economyViewPager ?: return
        if (position in 0 until (vp.adapter?.itemCount ?: 0)) vp.setCurrentItem(position, false)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }

    // ── Inner adapter ──────────────────────────────────────────────────────────
    // Nested (not `inner`), matching the Java `static` nested class — an inner
    // class would capture an outer instance the adapter does not need.

    private class EconomyPagerAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {

        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> GdpFragment()
                1 -> EmploymentFragment()
                2 -> InflationFragment()
                3 -> HousingFragment()
                else -> GdpFragment()
            }
        }

        override fun getItemCount(): Int = 4
    }

    companion object {
        const val ARG_SUB_TAB = "sub_tab"
        private val TAB_TITLES = arrayOf("GDP", "Employment", "Inflation", "Housing")
    }
}
