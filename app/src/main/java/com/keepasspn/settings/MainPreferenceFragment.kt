/*
 * Copyright 2019 Jeremy Jamet / Kunzisoft.
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
package com.keepasspn.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import com.keepasspn.R
import com.keepasspn.database.ContextualDatabase
import com.keepasspn.viewmodels.DatabaseViewModel
import kotlinx.coroutines.launch

class MainPreferenceFragment : PreferenceFragmentCompat() {

    private var mCallback: Callback? = null

    private val mDatabaseViewModel: DatabaseViewModel by activityViewModels()
    private val mDatabase: ContextualDatabase?
        get() = mDatabaseViewModel.database

    override fun onAttach(context: Context) {
        super.onAttach(context)

        if (context is Callback) {
            mCallback = context
        } else {
            throw IllegalStateException("Owner must implement " + Callback::class.java.name)
        }
    }

    override fun onDetach() {
        mCallback = null
        super.onDetach()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                mDatabaseViewModel.databaseState.collect { database ->
                    checkDatabaseLoaded(database?.loaded == true)
                }
            }
        }
    }

    private fun checkDatabaseLoaded(isDatabaseLoaded: Boolean) {
        findPreference<Preference>(getString(R.string.settings_database_key))
            ?.isEnabled = isDatabaseLoaded

        findPreference<PreferenceCategory>(getString(R.string.settings_database_category_key))
            ?.isVisible = isDatabaseLoaded
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        findPreference<Preference>(getString(R.string.settings_check_update_key))?.apply {
            onPreferenceClickListener = Preference.OnPreferenceClickListener {
                showUpdateCheckDialog()
                true
            }
        }

        // add listeners for non-default actions
        findPreference<Preference>(getString(R.string.settings_app_key))?.apply {
            onPreferenceClickListener = Preference.OnPreferenceClickListener {
                mCallback?.onNestedPreferenceSelected(NestedSettingsFragment.Screen.APPLICATION)
                false
            }
        }

        findPreference<Preference>(getString(R.string.settings_form_filling_key))?.apply {
            onPreferenceClickListener = Preference.OnPreferenceClickListener {
                mCallback?.onNestedPreferenceSelected(NestedSettingsFragment.Screen.FORM_FILLING)
                false
            }
        }

        findPreference<Preference>(getString(R.string.settings_device_unlock_key))?.apply {
            onPreferenceClickListener = Preference.OnPreferenceClickListener {
                mCallback?.onNestedPreferenceSelected(NestedSettingsFragment.Screen.DEVICE_UNLOCK)
                false
            }
        }

        findPreference<Preference>(getString(R.string.settings_appearance_key))?.apply {
            onPreferenceClickListener = Preference.OnPreferenceClickListener {
                mCallback?.onNestedPreferenceSelected(NestedSettingsFragment.Screen.APPEARANCE)
                false
            }
        }

        findPreference<Preference>(getString(R.string.settings_database_key))?.apply {
            onPreferenceClickListener = Preference.OnPreferenceClickListener {
                mCallback?.onNestedPreferenceSelected(NestedSettingsFragment.Screen.DATABASE)
                false
            }
        }

        findPreference<Preference>(getString(R.string.settings_database_security_key))?.apply {
            onPreferenceClickListener = Preference.OnPreferenceClickListener {
                mCallback?.onNestedPreferenceSelected(NestedSettingsFragment.Screen.DATABASE_SECURITY)
                false
            }
        }

        findPreference<Preference>(getString(R.string.settings_database_credentials_key))?.apply {
            onPreferenceClickListener = Preference.OnPreferenceClickListener {
                mCallback?.onNestedPreferenceSelected(NestedSettingsFragment.Screen.DATABASE_MASTER_KEY)
                false
            }
        }

        checkDatabaseLoaded(mDatabase?.loaded == true)
    }

    private fun showUpdateCheckDialog() {
        val context = requireContext()
        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (e: PackageManager.NameNotFoundException) {
            ""
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.check_for_updates)
            .setMessage(getString(R.string.check_for_updates_dialog_message, version))
            .setPositiveButton(R.string.open_releases_page) { _, _ -> openReleasesPage() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openReleasesPage() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.releases_url)))
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(requireContext(), R.string.releases_page_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    interface Callback {
        fun onNestedPreferenceSelected(key: NestedSettingsFragment.Screen, reload: Boolean = false)
    }
}