/*
 * Copyright 2025 Jeremy Jamet / Kunzisoft.
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
package com.keepasspn.settings.preferencedialogfragment

import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.annotation.RequiresApi
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.keepasspn.R
import com.keepasspn.credentialprovider.passkey.data.AndroidPrivilegedApp
import com.keepasspn.settings.preferencedialogfragment.adapter.ListSelectionItemAdapter
import com.keepasspn.settings.preferencedialogfragment.viewmodel.PasskeysPrivilegedAppsViewModel
import kotlinx.coroutines.launch

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class PasskeysPrivilegedAppsPreferenceDialogFragmentCompat
    : InputPreferenceDialogFragmentCompat() {

    private var mAdapter = ListSelectionItemAdapter<AndroidPrivilegedApp>()
    private val passkeysPrivilegedAppsViewModel : PasskeysPrivilegedAppsViewModel by activityViewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                passkeysPrivilegedAppsViewModel.retrievePrivilegedAppsToSelect()

                passkeysPrivilegedAppsViewModel.uiState.collect { uiState ->
                    when(uiState) {
                        is PasskeysPrivilegedAppsViewModel.UiState.Loading -> {}
                        is PasskeysPrivilegedAppsViewModel.UiState.OnPrivilegedAppsToSelectRetrieved -> {
                            mAdapter.apply {
                                setItems(uiState.privilegedApps)
                                selectedItems = uiState.selected.toMutableList()
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onBindDialogView(view: View) {
        super.onBindDialogView(view)
        setExplanationText(R.string.passkeys_privileged_apps_explanation)
        view.findViewById<RecyclerView>(R.id.pref_dialog_list).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = mAdapter
        }
    }

    override fun onDialogClosed(positiveResult: Boolean) {
        if (positiveResult) {
            passkeysPrivilegedAppsViewModel.saveSelectedPrivilegedApp(mAdapter.selectedItems)
        }
    }

    companion object {

        fun newInstance(key: String): PasskeysPrivilegedAppsPreferenceDialogFragmentCompat {
            val fragment = PasskeysPrivilegedAppsPreferenceDialogFragmentCompat()
            val bundle = Bundle(1)
            bundle.putString(ARG_KEY, key)
            fragment.arguments = bundle

            return fragment
        }
    }
}
