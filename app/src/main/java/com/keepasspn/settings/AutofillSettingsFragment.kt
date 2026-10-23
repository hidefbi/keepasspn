/*
 * Copyright 2020 Jeremy Jamet / Kunzisoft.
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

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.TwoStatePreference
import com.keepasspn.R
import com.keepasspn.credentialprovider.autofill.TrustedBrowsers
import com.keepasspn.settings.preferencedialogfragment.AutofillBlocklistAppIdPreferenceDialogFragmentCompat
import com.keepasspn.settings.preferencedialogfragment.AutofillBlocklistWebDomainPreferenceDialogFragmentCompat

@RequiresApi(Build.VERSION_CODES.O)
class AutofillSettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        // Load the preferences from an XML resource
        setPreferencesFromResource(R.xml.preferences_autofill, rootKey)

        val autofillInlineSuggestionsPreference: TwoStatePreference? = findPreference(getString(R.string.autofill_inline_suggestions_key))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            autofillInlineSuggestionsPreference?.isVisible = false
        }

        val autofillAskSaveDataPreference: TwoStatePreference? = findPreference(getString(R.string.autofill_ask_to_save_data_key))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            autofillAskSaveDataPreference?.isVisible = false
        }

        findPreference<Preference>(getString(R.string.autofill_trusted_browsers_key))?.setOnPreferenceClickListener {
            showTrustedBrowsersDialog()
            true
        }
    }

    private fun showTrustedBrowsersDialog() {
        val context = requireContext()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Toast.makeText(context, R.string.trusted_browsers_unsupported, Toast.LENGTH_LONG).show()
            return
        }
        // Only non built-in browsers are managed here, built-in ones are always verified
        val candidates = TrustedBrowsers.candidatePackages(context)
            .sortedBy { labelOf(it).lowercase() }
        if (candidates.isEmpty()) {
            Toast.makeText(context, R.string.trusted_browsers_none, Toast.LENGTH_LONG).show()
            return
        }
        val userPinned = TrustedBrowsers.userBrowsers(context).keys
        val checked = candidates.map { it in userPinned }.toBooleanArray()
        AlertDialog.Builder(context)
            .setTitle(R.string.trusted_browsers_dialog_title)
            .setMultiChoiceItems(candidates.map { labelOf(it) as CharSequence }.toTypedArray(), checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val selected = candidates.filterIndexed { index, _ -> checked[index] }.toSet()
                candidates.filter { it in userPinned && it !in selected }
                    .forEach { TrustedBrowsers.removeUserBrowser(context, it) }
                val toAdd = selected - userPinned
                if (toAdd.isNotEmpty()) {
                    confirmTrustedBrowsers(context, toAdd.toList())
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmTrustedBrowsers(context: Context, packages: List<String>) {
        AlertDialog.Builder(context)
            .setTitle(R.string.trusted_browsers_confirm_title)
            .setMessage(getString(R.string.trusted_browsers_confirm_message, packages.joinToString("\n") { labelOf(it) }))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                packages.forEach { TrustedBrowsers.addUserBrowser(context, it) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun labelOf(packageName: String): String {
        val packageManager = requireContext().packageManager
        return try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
        } catch (e: Exception) {
            packageName
        }
    }

    override fun onDisplayPreferenceDialog(preference: Preference) {
        var dialogFragment: DialogFragment? = null

        when (preference.key) {
            getString(R.string.autofill_application_id_blocklist_key) -> {
                dialogFragment = AutofillBlocklistAppIdPreferenceDialogFragmentCompat.newInstance(preference.key)
            }
            getString(R.string.autofill_web_domain_blocklist_key) -> {
                dialogFragment = AutofillBlocklistWebDomainPreferenceDialogFragmentCompat.newInstance(preference.key)
            }
            else -> {}
        }

        if (dialogFragment != null) {
            @Suppress("DEPRECATION")
            dialogFragment.setTargetFragment(this, 0)
            dialogFragment.show(parentFragmentManager, TAG_AUTOFILL_PREF_FRAGMENT)
        }
        // Could not be handled here. Try with the super method.
        else {
            super.onDisplayPreferenceDialog(preference)
        }
    }

    companion object {

        private const val TAG_AUTOFILL_PREF_FRAGMENT = "TAG_AUTOFILL_PREF_FRAGMENT"
    }
}
