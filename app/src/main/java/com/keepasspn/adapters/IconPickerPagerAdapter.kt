package com.keepasspn.adapters

import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.keepasspn.activities.fragments.IconCustomFragment
import com.keepasspn.activities.fragments.IconStandardFragment

class IconPickerPagerAdapter(fragment: Fragment, val size: Int)
    : FragmentStateAdapter(fragment) {

    private val iconStandardFragment = IconStandardFragment()
    private val iconCustomFragment = IconCustomFragment()

    override fun getItemCount(): Int {
        return size
    }

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            1 -> iconCustomFragment
            else -> iconStandardFragment
        }
    }
}