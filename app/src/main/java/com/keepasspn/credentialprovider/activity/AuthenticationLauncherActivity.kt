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
 */
package com.keepasspn.credentialprovider.activity

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.keepasspn.activities.legacy.DatabaseLockActivity
import com.keepasspn.credentialprovider.EntrySelectionHelper.retrieveSearchInfo
import com.keepasspn.credentialprovider.SpecialMode
import com.keepasspn.credentialprovider.UserVerificationActionType
import com.keepasspn.credentialprovider.UserVerificationData
import com.keepasspn.credentialprovider.UserVerificationHelper.Companion.checkUserVerification
import com.keepasspn.credentialprovider.UserVerificationHelper.Companion.getUserVerifiedWithAuth
import com.keepasspn.credentialprovider.UserVerificationHelper.Companion.retrieveUserVerificationRequirement
import com.keepasspn.credentialprovider.passkey.data.UserVerificationRequirement
import com.keepasspn.database.ContextualDatabase
import com.keepasspn.settings.PreferencesUtil.isUserVerificationForcedWhenPreferred
import com.keepasspn.view.toastError
import com.keepasspn.viewmodels.UserVerificationViewModel
import kotlinx.coroutines.launch

/**
 * Abstract class to easily manage credential provider for authentication launcher,
 * Allow to retrieve User Verification in the ceremony
 */
abstract class AuthenticationLauncherActivity: DatabaseLockActivity() {

    protected val userVerificationViewModel: UserVerificationViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // To prevent auto finish the activity
        mTimeoutEnable = false
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    userVerificationViewModel.onUserVerificationSucceeded.collect { data ->
                        when (data.actionType) {
                            UserVerificationActionType.LAUNCH_AUTHENTICATION_CEREMONY -> {
                                setUserVerified()
                                launchActionIfNeeded(
                                    intent = intent,
                                    specialMode = mSpecialMode,
                                    database = data.database
                                )
                            }
                            else -> {}
                        }
                    }
                }
                launch {
                    userVerificationViewModel.onUserVerificationCanceled.collect { result ->
                        toastError(result.error)
                        cancelResult()
                    }
                }
            }
        }
    }

    override fun onUnknownDatabaseRetrieved(database: ContextualDatabase?) {
        super.onUnknownDatabaseRetrieved(database)

        val userVerificationForcedWhenPreferred = isUserVerificationForcedWhenPreferred(this)
        val userVerificationRequirement = intent.retrieveUserVerificationRequirement()
        val userVerificationNeeded = (userVerificationRequirement == UserVerificationRequirement.REQUIRED
                || (userVerificationForcedWhenPreferred
                && userVerificationRequirement == UserVerificationRequirement.PREFERRED)
                ) && intent.getUserVerifiedWithAuth().not()
        if (mTypeMode.useUserVerification && userVerificationNeeded) {
            // If user verification is needed, it means that the database is open
            // otherwise, it would be verified with auth
            if (database != null) {
                val dataToVerify = UserVerificationData(
                    actionType = UserVerificationActionType.LAUNCH_AUTHENTICATION_CEREMONY,
                    database = database,
                    originName = intent.retrieveSearchInfo()?.toString()
                )
                if (database.allowUserVerification) {
                    checkUserVerification(
                        userVerificationViewModel = userVerificationViewModel,
                        dataToVerify = dataToVerify
                    )
                } else {
                    userVerificationViewModel.onUserVerificationFailed(
                        dataToVerify = dataToVerify,
                        error = SecurityException(
                            "User Verification is not allowed for this opened database"
                        )
                    )
                }
            }
        } else {
            launchActionIfNeeded(
                intent = intent,
                specialMode = mSpecialMode,
                database = database
            )
        }
    }

    open fun setUserVerified() {}

    /**
     * Must be redefined to call the associated viewModel
     */
    abstract fun launchActionIfNeeded(
        intent: Intent,
        specialMode: SpecialMode,
        database: ContextualDatabase?
    )

    abstract fun cancelResult()

    override fun viewToInvalidateTimeout(): View? = null
}