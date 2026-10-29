/*
 * Copyright 2022 Jeremy Jamet / Kunzisoft.
 *
 * This file is part of KeePassPN.
 *
 *  KeePassPN is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  KeePassPN is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with KeePassPN.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.keepasspn.activities.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.keepasspn.R
import com.keepasspn.adapters.KeyGeneratorPagerAdapter
import com.keepasspn.database.ContextualDatabase
import com.keepasspn.viewmodels.KeyGeneratorViewModel
import kotlinx.coroutines.launch

class KeyGeneratorFragment : DatabaseFragment() {

    private var keyGeneratorPagerAdapter: KeyGeneratorPagerAdapter? = null
    private lateinit var viewPager: ViewPager2
    private lateinit var tabLayout: TabLayout

    private val mKeyGeneratorViewModel: KeyGeneratorViewModel by activityViewModels()

    private var mSelectedTab = KeyGeneratorTab.PASSWORD
    private var mOnPageChangeCallback: ViewPager2.OnPageChangeCallback = object:
        ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            super.onPageSelected(position)
            mSelectedTab = tabs.getOrElse(position) { KeyGeneratorTab.PASSWORD }
        }
    }

    private val tabs: List<KeyGeneratorTab>
        get() = KeyGeneratorTab.getTabs(
            standalone = arguments?.getBoolean(STANDALONE_ARG, false) ?: false,
            usernameOnly = arguments?.getBoolean(USERNAME_ONLY_ARG, false) ?: false
        )

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_tabs_pagination, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        keyGeneratorPagerAdapter = KeyGeneratorPagerAdapter(this, tabs)
        viewPager = view.findViewById(R.id.tabs_view_pager)
        tabLayout = view.findViewById(R.id.tabs_layout)
        viewPager.adapter = keyGeneratorPagerAdapter
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = getString(tabs[position].stringId)
        }.attach()
        viewPager.registerOnPageChangeCallback(mOnPageChangeCallback)

        resetAppTimeoutWhenViewFocusedOrChanged(view)

        arguments?.apply {
            if (containsKey(PASSWORD_TAB_ARG)) {
                val requested = KeyGeneratorTab.values()[getInt(PASSWORD_TAB_ARG)]
                viewPager.currentItem = tabs.indexOf(requested).coerceAtLeast(0)
            }
            remove(PASSWORD_TAB_ARG)
        }

        lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    mKeyGeneratorViewModel.requireKeyGeneration.collect {
                        when (mSelectedTab) {
                            KeyGeneratorTab.PASSWORD -> {
                                mKeyGeneratorViewModel.requirePasswordGeneration()
                            }

                            KeyGeneratorTab.PASSPHRASE -> {
                                mKeyGeneratorViewModel.requirePassphraseGeneration()
                            }

                            KeyGeneratorTab.USERNAME -> {
                                mKeyGeneratorViewModel.requireUsernameGeneration()
                            }
                        }
                    }
                }
                launch {
                    mKeyGeneratorViewModel.keyGeneratedValidated.collect {
                        when (mSelectedTab) {
                            KeyGeneratorTab.PASSWORD -> {
                                mKeyGeneratorViewModel.validatePasswordGenerated()
                            }

                            KeyGeneratorTab.PASSPHRASE -> {
                                mKeyGeneratorViewModel.validatePassphraseGenerated()
                            }

                            KeyGeneratorTab.USERNAME -> {
                                mKeyGeneratorViewModel.validateUsernameGenerated()
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        viewPager.unregisterOnPageChangeCallback(mOnPageChangeCallback)
        super.onDestroyView()
    }

    override fun onDatabaseRetrieved(database: ContextualDatabase) {
        // Nothing here
    }

    enum class KeyGeneratorTab(@StringRes val stringId: Int) {
        PASSWORD(R.string.password),
        PASSPHRASE(R.string.passphrase),
        USERNAME(R.string.entry_user_name);

        companion object {
            /**
             * Tabs offered for the current use:
             * - a username field of an entry: USERNAME only
             * - the standalone page from the add menu: all tabs
             * - a password field of an entry: PASSWORD and PASSPHRASE
             */
            fun getTabs(standalone: Boolean, usernameOnly: Boolean): List<KeyGeneratorTab> {
                return when {
                    usernameOnly -> listOf(USERNAME)
                    standalone -> values().toList()
                    else -> listOf(PASSWORD, PASSPHRASE)
                }
            }
        }
    }

    companion object {

        private const val PASSWORD_TAB_ARG = "PASSWORD_TAB_ARG"
        private const val STANDALONE_ARG = "STANDALONE_ARG"
        private const val USERNAME_ONLY_ARG = "USERNAME_ONLY_ARG"

        fun getInstance(
            keyGeneratorTab: KeyGeneratorTab,
            standalone: Boolean = false,
            usernameOnly: Boolean = false
        ): KeyGeneratorFragment {
            val fragment = KeyGeneratorFragment()
            fragment.arguments = Bundle().apply {
                putInt(PASSWORD_TAB_ARG, keyGeneratorTab.ordinal)
                putBoolean(STANDALONE_ARG, standalone)
                putBoolean(USERNAME_ONLY_ARG, usernameOnly)
            }
            return fragment
        }
    }
}