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
package com.keepasspn.credentialprovider.activity

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import com.keepasspn.utils.NoOpLog
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.lifecycle.lifecycleScope
import com.keepasspn.R
import com.keepasspn.activities.FileDatabaseSelectActivity
import com.keepasspn.activities.GroupActivity
import com.keepasspn.activities.legacy.DatabaseModeActivity
import com.keepasspn.credentialprovider.EntrySelectionHelper.addRegisterInfo
import com.keepasspn.credentialprovider.EntrySelectionHelper.addSearchInfo
import com.keepasspn.credentialprovider.EntrySelectionHelper.addSpecialMode
import com.keepasspn.credentialprovider.EntrySelectionHelper.getRegisterInfo
import com.keepasspn.credentialprovider.EntrySelectionHelper.getSearchInfo
import com.keepasspn.credentialprovider.EntrySelectionHelper.getSpecialMode
import com.keepasspn.credentialprovider.EntrySelectionHelper.setActivityResult
import com.keepasspn.credentialprovider.SpecialMode
import com.keepasspn.credentialprovider.autofill.AutofillComponent
import com.keepasspn.credentialprovider.autofill.AutofillHelper.addAutofillComponent
import com.keepasspn.credentialprovider.autofill.AutofillHelper.retrieveAutofillComponent
import com.keepasspn.credentialprovider.viewmodel.AutofillLauncherViewModel
import com.keepasspn.credentialprovider.viewmodel.CredentialLauncherViewModel
import com.keepasspn.database.ContextualDatabase
import com.keepasspn.model.RegisterInfo
import com.keepasspn.model.SearchInfo
import com.keepasspn.utils.AppUtil.randomRequestCode
import com.keepasspn.view.toastError
import kotlinx.coroutines.launch

@RequiresApi(api = Build.VERSION_CODES.O)
class AutofillLauncherActivity : DatabaseModeActivity() {

    private val autofillLauncherViewModel: AutofillLauncherViewModel by viewModels()

    private var mAutofillSelectionActivityResultLauncher: ActivityResultLauncher<Intent>? =
        this.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            autofillLauncherViewModel.manageSelectionResult(it)
        }

    private var mAutofillRegistrationActivityResultLauncher: ActivityResultLauncher<Intent>? =
        this.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            autofillLauncherViewModel.manageRegistrationResult(it)
        }

    override fun applyCustomStyle(): Boolean = false

    override fun finishActivityIfReloadRequested(): Boolean = false

    override fun manageDatabaseInfo(): Boolean  = false

    override fun onCreate(savedInstanceState: Bundle?) {

        // before managing intent in super class
        intent.retrieveSelectionBundle()?.apply {
            intent.addSpecialMode(getSpecialMode())
            intent.addSearchInfo(getSearchInfo())
            intent.addRegisterInfo(getRegisterInfo())
            intent.addAutofillComponent(retrieveAutofillComponent())
        }
        super.onCreate(savedInstanceState)
        autofillLauncherViewModel.initialize()
        lifecycleScope.launch {
            // Initialize the parameters
            autofillLauncherViewModel.uiState.collect { uiState ->
                when (uiState) {
                    AutofillLauncherViewModel.UIState.Loading -> {}
                    is AutofillLauncherViewModel.UIState.ShowBlockRestartMessage -> {
                        showBlockRestartMessage()
                        autofillLauncherViewModel.cancelResult()
                    }
                    is AutofillLauncherViewModel.UIState.ShowAutofillSuggestionMessage -> {
                        showAutofillSuggestionMessage()
                    }
                }
            }
        }
        lifecycleScope.launch {
            // Retrieve the UI
            autofillLauncherViewModel.credentialUiState.collect { uiState ->
                when (uiState) {
                    is CredentialLauncherViewModel.CredentialState.Loading -> {}
                    is CredentialLauncherViewModel.CredentialState.LaunchGroupActivityForSelection -> {
                        GroupActivity.launchForSelection(
                            context = this@AutofillLauncherActivity,
                            database = uiState.database,
                            searchInfo = uiState.searchInfo,
                            typeMode = uiState.typeMode,
                            activityResultLauncher = mAutofillSelectionActivityResultLauncher,
                        )
                    }
                    is CredentialLauncherViewModel.CredentialState.LaunchGroupActivityForRegistration -> {
                        GroupActivity.launchForRegistration(
                            context = this@AutofillLauncherActivity,
                            database = uiState.database,
                            registerInfo = uiState.registerInfo,
                            typeMode = uiState.typeMode,
                            activityResultLauncher = mAutofillRegistrationActivityResultLauncher
                        )
                    }
                    is CredentialLauncherViewModel.CredentialState.LaunchFileDatabaseSelectActivityForSelection -> {
                        FileDatabaseSelectActivity.launchForSelection(
                            context = this@AutofillLauncherActivity,
                            searchInfo = uiState.searchInfo,
                            typeMode = uiState.typeMode,
                            activityResultLauncher = mAutofillSelectionActivityResultLauncher,
                            autofillPinUnlock = true
                        )
                    }
                    is CredentialLauncherViewModel.CredentialState.LaunchFileDatabaseSelectActivityForRegistration -> {
                        FileDatabaseSelectActivity.launchForRegistration(
                            context = this@AutofillLauncherActivity,
                            registerInfo = uiState.registerInfo,
                            typeMode = uiState.typeMode,
                            activityResultLauncher = mAutofillRegistrationActivityResultLauncher,
                        )
                    }
                    is CredentialLauncherViewModel.CredentialState.SetActivityResult -> {
                        setActivityResult(
                            lockDatabase = uiState.lockDatabase,
                            resultCode = uiState.resultCode,
                            data = uiState.data
                        )
                    }
                    is CredentialLauncherViewModel.CredentialState.ShowError -> {
                        toastError(uiState.error)
                        autofillLauncherViewModel.cancelResult()
                    }
                }
            }
        }
    }

    override fun onUnknownDatabaseRetrieved(database: ContextualDatabase?) {
        super.onUnknownDatabaseRetrieved(database)
        autofillLauncherViewModel.launchActionIfNeeded(intent, mSpecialMode, database)
    }

    private fun showBlockRestartMessage() {
        // If item not allowed, show a toast
        Toast.makeText(
            applicationContext,
            R.string.autofill_block_restart,
            Toast.LENGTH_LONG
        ).show()
    }

    private fun showAutofillSuggestionMessage() {
        Toast.makeText(
            applicationContext,
            R.string.autofill_inline_suggestions_keyboard,
            Toast.LENGTH_SHORT
        ).show()
    }

    companion object {

        private const val KEY_PENDING_INTENT_BUNDLE = "com.keepasspn.extra.BUNDLE"
        private val TAG = AutofillLauncherActivity::class.java.name

        fun Intent.retrieveSelectionBundle(): Bundle? {
            return this.getBundleExtra(KEY_PENDING_INTENT_BUNDLE)
        }

        fun getPendingIntentForSelection(
            context: Context,
            searchInfo: SearchInfo? = null,
            autofillComponent: AutofillComponent
        ): PendingIntent? {
            try {
                // Doesn't work with direct extra Parcelable in Android 11 (don't know why?)

                // Wrap into a bundle to bypass the problem
                val tempBundle = Bundle().apply {
                    addSpecialMode(SpecialMode.SELECTION)
                    addSearchInfo(searchInfo)
                    addAutofillComponent(autofillComponent)
                }
                return PendingIntent.getActivity(
                    context,
                    randomRequestCode(),
                    Intent(context, AutofillLauncherActivity::class.java).apply {
                        putExtra(KEY_PENDING_INTENT_BUNDLE, tempBundle)
                    },
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_CANCEL_CURRENT
                    } else {
                        PendingIntent.FLAG_CANCEL_CURRENT
                    }
                )
            } catch (e: RuntimeException) {
                NoOpLog.e(TAG, "Unable to create pending intent for selection", e)
                return null
            }
        }

        fun getPendingIntentForRegistration(
            context: Context,
            registerInfo: RegisterInfo
        ): PendingIntent? {
            try {
                // Bypass intent issue
                val tempBundle = Bundle().apply {
                    addSpecialMode(SpecialMode.REGISTRATION)
                    addRegisterInfo(registerInfo)
                }
                return PendingIntent.getActivity(
                    context,
                    randomRequestCode(),
                    Intent(context, AutofillLauncherActivity::class.java).apply {
                        putExtra(KEY_PENDING_INTENT_BUNDLE, tempBundle)
                    },
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_CANCEL_CURRENT
                    } else {
                        PendingIntent.FLAG_CANCEL_CURRENT
                    }
                )
            } catch (e: RuntimeException) {
                NoOpLog.e(TAG, "Unable to create pending intent for registration", e)
                return null
            }
        }
    }
}
