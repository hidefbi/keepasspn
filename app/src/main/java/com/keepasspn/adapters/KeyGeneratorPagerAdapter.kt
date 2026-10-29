package com.keepasspn.adapters

import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.keepasspn.activities.fragments.KeyGeneratorFragment
import com.keepasspn.activities.fragments.PassphraseGeneratorFragment
import com.keepasspn.activities.fragments.PasswordGeneratorFragment
import com.keepasspn.activities.fragments.UsernameGeneratorFragment

class KeyGeneratorPagerAdapter(
    fragment: Fragment,
    private val tabs: List<KeyGeneratorFragment.KeyGeneratorTab>
) : FragmentStateAdapter(fragment) {

    private val passwordGeneratorFragment = PasswordGeneratorFragment()
    private val passphraseGeneratorFragment = PassphraseGeneratorFragment()
    private val usernameGeneratorFragment = UsernameGeneratorFragment()

    override fun getItemCount(): Int {
        return tabs.size
    }

    override fun createFragment(position: Int): Fragment {
        return when (tabs[position]) {
            KeyGeneratorFragment.KeyGeneratorTab.PASSWORD -> passwordGeneratorFragment
            KeyGeneratorFragment.KeyGeneratorTab.PASSPHRASE -> passphraseGeneratorFragment
            KeyGeneratorFragment.KeyGeneratorTab.USERNAME -> usernameGeneratorFragment
        }
    }
}
