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
package com.keepasspn.credentialprovider.activity

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import com.keepasspn.utils.NoOpLog
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.keepasspn.R
import com.keepasspn.activities.FileDatabaseSelectActivity
import com.keepasspn.activities.GroupActivity
import com.keepasspn.credentialprovider.EntrySelectionHelper.addNodeId
import com.keepasspn.credentialprovider.EntrySelectionHelper.addSearchInfo
import com.keepasspn.credentialprovider.EntrySelectionHelper.addSpecialMode
import com.keepasspn.credentialprovider.EntrySelectionHelper.addTypeMode
import com.keepasspn.credentialprovider.EntrySelectionHelper.setActivityResult
import com.keepasspn.credentialprovider.SpecialMode
import com.keepasspn.credentialprovider.TypeMode
import com.keepasspn.credentialprovider.UserVerificationHelper.Companion.addUserVerification
import com.keepasspn.credentialprovider.UserVerificationHelper.Companion.getUserVerifiedWithAuth
import com.keepasspn.credentialprovider.passkey.data.AndroidPrivilegedApp
import com.keepasspn.credentialprovider.passkey.data.UserVerificationRequirement
import com.keepasspn.credentialprovider.passkey.util.PassHelper.addAppOrigin
import com.keepasspn.credentialprovider.passkey.util.PassHelper.addAuthCode
import com.keepasspn.credentialprovider.viewmodel.CredentialLauncherViewModel
import com.keepasspn.credentialprovider.viewmodel.PasskeyLauncherViewModel
import com.keepasspn.database.ContextualDatabase
import com.keepasspn.database.element.EntryId
import com.keepasspn.model.AppOrigin
import com.keepasspn.model.SearchInfo
import com.keepasspn.services.DatabaseTaskNotificationService.Companion.ACTION_DATABASE_UPDATE_ENTRY_TASK
import com.keepasspn.tasks.ActionRunnable
import com.keepasspn.utils.AppUtil.randomRequestCode
import com.keepasspn.view.toastError
import kotlinx.coroutines.launch

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class PasskeyLauncherActivity : AuthenticationLauncherActivity() {

    private val passkeyLauncherViewModel: PasskeyLauncherViewModel by viewModels()

    private var mPasskeySelectionActivityResultLauncher: ActivityResultLauncher<Intent>? =
        this.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            passkeyLauncherViewModel.manageSelectionResult(it)
        }

    private var mPasskeyRegistrationActivityResultLauncher: ActivityResultLauncher<Intent>? =
        this.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            passkeyLauncherViewModel.manageRegistrationResult(it)
        }

    override fun applyCustomStyle(): Boolean = false

    override fun finishActivityIfReloadRequested(): Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
            // Initialize the parameters
            passkeyLauncherViewModel.initialize(userVerified = intent.getUserVerifiedWithAuth())
            // Retrieve the UI
            passkeyLauncherViewModel.uiState.collect { uiState ->
                when (uiState) {
                    is PasskeyLauncherViewModel.UIState.Loading -> {
                        // Nothing to do
                    }
                    is PasskeyLauncherViewModel.UIState.ShowAppPrivilegedDialog -> {
                        showAppPrivilegedDialog(
                            temptingApp = uiState.temptingApp
                        )
                    }
                    is PasskeyLauncherViewModel.UIState.ShowAppSignatureDialog -> {
                        showAppSignatureDialog(
                            temptingApp = uiState.temptingApp,
                            nodeId = uiState.nodeId
                        )
                    }
                    is PasskeyLauncherViewModel.UIState.UpdateEntry -> {
                        updateEntry(uiState.entry)
                    }
                }
            }
        }
        lifecycleScope.launch {
            passkeyLauncherViewModel.credentialUiState.collect { uiState ->
                when (uiState) {
                    is CredentialLauncherViewModel.CredentialState.Loading -> {}
                    is CredentialLauncherViewModel.CredentialState.SetActivityResult -> {
                        setActivityResult(
                            lockDatabase = uiState.lockDatabase,
                            resultCode = uiState.resultCode,
                            data = uiState.data
                        )
                    }
                    is CredentialLauncherViewModel.CredentialState.ShowError -> {
                        toastError(uiState.error)
                        passkeyLauncherViewModel.cancelResult()
                    }
                    is CredentialLauncherViewModel.CredentialState.LaunchGroupActivityForSelection -> {
                        GroupActivity.launchForSelection(
                            context = this@PasskeyLauncherActivity,
                            database = uiState.database,
                            typeMode = uiState.typeMode,
                            searchInfo = uiState.searchInfo,
                            activityResultLauncher = mPasskeySelectionActivityResultLauncher
                        )
                    }
                    is CredentialLauncherViewModel.CredentialState.LaunchGroupActivityForRegistration -> {
                        GroupActivity.launchForRegistration(
                            context = this@PasskeyLauncherActivity,
                            database = uiState.database,
                            typeMode = uiState.typeMode,
                            registerInfo = uiState.registerInfo,
                            activityResultLauncher = mPasskeyRegistrationActivityResultLauncher
                        )
                    }
                    is CredentialLauncherViewModel.CredentialState.LaunchFileDatabaseSelectActivityForSelection -> {
                        FileDatabaseSelectActivity.launchForSelection(
                            context = this@PasskeyLauncherActivity,
                            typeMode = uiState.typeMode,
                            searchInfo = uiState.searchInfo,
                            activityResultLauncher = mPasskeySelectionActivityResultLauncher
                        )
                    }
                    is CredentialLauncherViewModel.CredentialState.LaunchFileDatabaseSelectActivityForRegistration -> {
                        FileDatabaseSelectActivity.launchForRegistration(
                            context = this@PasskeyLauncherActivity,
                            typeMode = uiState.typeMode,
                            registerInfo = uiState.registerInfo,
                            activityResultLauncher = mPasskeyRegistrationActivityResultLauncher,
                        )
                    }
                }
            }
        }
    }

    override fun setUserVerified() {
        passkeyLauncherViewModel.setUserVerified()
    }

    override fun launchActionIfNeeded(
        intent: Intent,
        specialMode: SpecialMode,
        database: ContextualDatabase?
    ) {
        passkeyLauncherViewModel.launchActionIfNeeded(
            intent = intent,
            specialMode = specialMode,
            database = database
        )
    }

    override fun cancelResult() {
        passkeyLauncherViewModel.cancelResult()
    }

    override fun onDatabaseActionFinished(
        database: ContextualDatabase,
        actionTask: String,
        result: ActionRunnable.Result
    ) {
        super.onDatabaseActionFinished(database, actionTask, result)
        when (actionTask) {
            ACTION_DATABASE_UPDATE_ENTRY_TASK -> {
                // TODO When auto save is enabled, WARNING filter by the calling activity
                // passkeyLauncherViewModel.autoSelectPasskey(result, database)
            }
        }
    }

    /**
     * Display a dialog that asks the user to add an app to the list of privileged apps.
     */
    private fun showAppPrivilegedDialog(
        temptingApp: AndroidPrivilegedApp
    ) {
        NoOpLog.w(javaClass.simpleName, "No privileged apps file found")
        AlertDialog.Builder(this@PasskeyLauncherActivity).apply {
            setTitle(getString(R.string.passkeys_privileged_apps_ask_title))
            setMessage(StringBuilder()
                .append(
                    getString(
                        R.string.passkeys_privileged_apps_ask_message,
                        temptingApp.toString()
                    )
                )
                .append("\n\n")
                .append(getString(R.string.passkeys_privileged_apps_explanation))
                .toString()
            )
            setPositiveButton(android.R.string.ok) { _, _ ->
                passkeyLauncherViewModel.saveCustomPrivilegedApp(
                    intent = intent,
                    specialMode = mSpecialMode,
                    database = mDatabase,
                    temptingApp = temptingApp
                )
            }
            setNegativeButton(android.R.string.cancel) { _, _ ->
                passkeyLauncherViewModel.cancelResult()
            }
            setOnCancelListener {
                passkeyLauncherViewModel.cancelResult()
            }
        }.create().show()
    }

    /**
     * Display a dialog that asks the user to add an app signature in an existing passkey
     */
    private fun showAppSignatureDialog(
        temptingApp: AppOrigin,
        nodeId: EntryId
    ) {
        AlertDialog.Builder(this@PasskeyLauncherActivity).apply {
            setTitle(getString(R.string.passkeys_missing_signature_app_ask_title))
            setMessage(StringBuilder()
                .append(
                    getString(
                        R.string.passkeys_missing_signature_app_ask_message,
                        temptingApp.toString()
                    )
                )
                .append("\n\n")
                .append(getString(R.string.passkeys_missing_signature_app_ask_explanation))
                .append("\n\n")
                .append(getString(R.string.passkeys_missing_signature_app_ask_question))
                .toString()
            )
            setPositiveButton(android.R.string.ok) { _, _ ->
                passkeyLauncherViewModel.saveAppSignature(
                    database = mDatabase,
                    temptingApp = temptingApp,
                    nodeId = nodeId
                )
            }
            setNegativeButton(android.R.string.cancel) { _, _ ->
                passkeyLauncherViewModel.cancelResult()
            }
            setOnCancelListener {
                passkeyLauncherViewModel.cancelResult()
            }
        }.create().show()
    }

    companion object {
        private val TAG = PasskeyLauncherActivity::class.java.name

        /**
         * Get a pending intent to launch the passkey launcher activity
         * [nodeId] can be :
         *  - null if manual selection is requested
         *  - null if manual registration is requested
         *  - an entry node id if direct selection is requested
         *  - a group node id if direct registration is requested in a default group
         *  - an entry node id if overwriting is requested in an existing entry
         */
        fun getPendingIntent(
            context: Context,
            specialMode: SpecialMode,
            searchInfo: SearchInfo? = null,
            appOrigin: AppOrigin? = null,
            nodeId: EntryId? = null,
            userVerification: UserVerificationRequirement = UserVerificationRequirement.PREFERRED,
            userVerifiedWithAuth: Boolean = true
        ): PendingIntent? {
            return PendingIntent.getActivity(
                context,
                randomRequestCode(),
                Intent(context, PasskeyLauncherActivity::class.java).apply {
                    addSpecialMode(specialMode)
                    addTypeMode(TypeMode.PASSKEY)
                    addSearchInfo(searchInfo)
                    addAppOrigin(appOrigin)
                    addNodeId(nodeId)
                    addAuthCode(nodeId?.id)
                    addUserVerification(userVerification, userVerifiedWithAuth)
                },
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }
    }
}
