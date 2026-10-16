package com.economic.dashboard.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup

import androidx.fragment.app.Fragment

import com.economic.dashboard.databinding.FragmentMarketsBinding
import com.economic.dashboard.utils.EdgeToEdgeInsets
import com.google.android.material.tabs.TabLayoutMediator

class MarketsFragment : Fragment() {

    private var binding: FragmentMarketsBinding? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val b = FragmentMarketsBinding.inflate(inflater, container, false)
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

        val tabLayout = b.tabLayout
        val viewPager = b.viewPager

        viewPager.adapter = MarketsPagerAdapter(this)
        // UX request (2026-08-03): swipe-between-tabs removed in favor of
        // tab-press-only navigation. TabLayoutMediator still drives
        // viewPager.setCurrentItem() programmatically on tab tap, which is
        // unaffected by isUserInputEnabled — only touch/drag gestures are
        // disabled.
        viewPager.isUserInputEnabled = false

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            when (position) {
                0 -> tab.text = "STOCKS"
                1 -> tab.text = "BONDS"
                2 -> tab.text = "YIELDS"
                3 -> tab.text = "SPREADS"
                else -> tab.text = ""
            }
        }.attach()
        if (savedInstanceState == null) arguments?.getInt(ARG_SUB_TAB, -1)?.let { selectSubTab(it) }
    }

    /** Settings parity U6: a shortcut opens straight to one sub-tab. Read on
     *  first creation from [ARG_SUB_TAB]; [selectSubTab] covers a host that is
     *  already showing. */
    fun selectSubTab(position: Int) {
        val vp = binding?.viewPager ?: return
        if (position in 0 until (vp.adapter?.itemCount ?: 0)) vp.setCurrentItem(position, false)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }

    companion object {
        const val ARG_SUB_TAB = "sub_tab"
    }
}
