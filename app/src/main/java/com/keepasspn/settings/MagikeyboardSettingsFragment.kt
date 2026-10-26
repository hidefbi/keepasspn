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

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.TwoStatePreference
import com.keepasspn.R
import com.keepasspn.settings.preferencedialogfragment.DurationDialogFragmentCompat

class MagikeyboardSettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        // Load the preferences from an XML resource
        setPreferencesFromResource(R.xml.preferences_keyboard, rootKey)

        // Turning the auto action on needs a confirmation; the switch is set only when accepted
        findPreference<TwoStatePreference>(getString(R.string.keyboard_auto_go_action_key))
            ?.setOnPreferenceChangeListener { preference, newValue ->
                if (newValue == true) {
                    confirmAutoGoAction(preference as TwoStatePreference)
                    false
                } else {
                    true
                }
            }
    }

    private fun confirmAutoGoAction(preference: TwoStatePreference) {
        val context = context ?: return
        AlertDialog.Builder(context)
            .setTitle(R.string.keyboard_auto_go_confirm_title)
            .setMessage(R.string.keyboard_auto_go_confirm_message)
            .setPositiveButton(R.string.keyboard_auto_go_confirm_button) { _, _ ->
                preference.isChecked = true
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onDisplayPreferenceDialog(preference: Preference) {

        var otherDialogFragment = false

        var dialogFragment: DialogFragment? = null
        // Main Preferences
        when (preference.key) {
            getString(R.string.keyboard_entry_timeout_key) -> {
                dialogFragment = DurationDialogFragmentCompat.newInstance(preference.key)
            }
            else -> otherDialogFragment = true
        }

        if (dialogFragment != null) {
            @Suppress("DEPRECATION")
            dialogFragment.setTargetFragment(this, 0)
            dialogFragment.show(parentFragmentManager, TAG_PREF_FRAGMENT)
        }
        // Could not be handled here. Try with the super method.
        else if (otherDialogFragment) {
            super.onDisplayPreferenceDialog(preference)
        }
    }

    companion object {
        private const val TAG_PREF_FRAGMENT = "TAG_PREF_FRAGMENT"
    }
}
