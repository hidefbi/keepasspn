/*
 * Copyright 2026 Jeremy Jamet / Kunzisoft.
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
package com.keepasspn.credentialprovider

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.keepasspn.activities.dialogs.CheckDatabaseCredentialDialogFragment
import com.keepasspn.activities.dialogs.CheckDeviceCredentialDialogFragment
import com.keepasspn.biometric.PinCryptoHelper
import com.keepasspn.biometric.PinVerificationDialog
import com.keepasspn.biometric.PinWipedException
import com.keepasspn.credentialprovider.passkey.data.UserVerificationRequirement
import com.keepasspn.database.pin.UserVerificationPolicy
import com.keepasspn.database.pin.VerificationMethod
import com.keepasspn.utils.getEnumExtra
import com.keepasspn.utils.putEnumExtra
import com.keepasspn.viewmodels.UserVerificationViewModel
import kotlinx.coroutines.launch

/**
 * Helper for the User Verification.
 */
class UserVerificationHelper {

    companion object {

        private const val EXTRA_USER_VERIFICATION = "com.keepasspn.extra.userVerification"
        private const val EXTRA_USER_VERIFIED_WITH_AUTH = "com.keepasspn.extra.userVerifiedWithAuth"

        /**
         * Allowed authenticators for the User Verification
         */
        const val ALLOWED_AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

        /**
         * Check if the device supports the biometric prompt for User Verification
         */
        fun Context.isAuthenticatorsAllowed(): Boolean {
            return BiometricManager.from(this)
                .canAuthenticate(ALLOWED_AUTHENTICATORS) == BIOMETRIC_SUCCESS
        }

        /**
         * Add the User Verification to the intent
         * @param userVerification The requirement
         * @param userVerifiedWithAuth True if verified with auth
         */
        fun Intent.addUserVerification(
            userVerification: UserVerificationRequirement,
            userVerifiedWithAuth: Boolean
        ) {
            putEnumExtra(EXTRA_USER_VERIFICATION, userVerification)
            putExtra(EXTRA_USER_VERIFIED_WITH_AUTH, userVerifiedWithAuth)
        }

        /**
         * Define if the User is verified with authentication from the intent
         */
        fun Intent.getUserVerifiedWithAuth(): Boolean {
            return getBooleanExtra(EXTRA_USER_VERIFIED_WITH_AUTH, true)
        }

        /**
         * Get the User Verification from the intent
         */
        fun Intent.retrieveUserVerificationRequirement(): UserVerificationRequirement {
            return getEnumExtra<UserVerificationRequirement>(EXTRA_USER_VERIFICATION)
                ?: UserVerificationRequirement.PREFERRED
        }

        /**
         * Check the user verification
         * @param userVerificationViewModel The ViewModel
         * @param dataToVerify The data to verify
         */
        fun Fragment.checkUserVerification(
            userVerificationViewModel: UserVerificationViewModel,
            dataToVerify: UserVerificationData
        ) {
            activity?.checkUserVerification(userVerificationViewModel, dataToVerify)
        }

        /**
         * Displays a dialog to verify the user
         * @param userVerificationViewModel The ViewModel
         * @param dataToVerify The data to verify
         */
        fun FragmentActivity.checkUserVerification(
            userVerificationViewModel: UserVerificationViewModel,
            dataToVerify: UserVerificationData
        ) {
            val deviceCredentialAvailable = isAuthenticatorsAllowed()
            val databaseAvailable = dataToVerify.database != null
            val databaseUri = dataToVerify.database?.logicalFileUri
            lifecycleScope.launch {
                val pinSet = databaseUri?.let { isPinSetSafely(this@checkUserVerification, it) } ?: false
                when (UserVerificationPolicy.chooseMethod(pinSet, deviceCredentialAvailable, databaseAvailable)) {
                    VerificationMethod.PIN -> databaseUri?.let {
                        this@checkUserVerification.showUserVerificationPin(userVerificationViewModel, dataToVerify, it)
                    }
                    VerificationMethod.DEVICE_CREDENTIAL -> this@checkUserVerification.showUserVerificationDeviceCredential(
                        userVerificationViewModel, dataToVerify
                    )
                    VerificationMethod.DATABASE_CREDENTIAL -> this@checkUserVerification.showUserVerificationDatabaseCredential(
                        userVerificationViewModel, dataToVerify
                    )
                    VerificationMethod.NONE -> { /* Nothing can verify the user */ }
                }
            }
        }

        private suspend fun isPinSetSafely(context: Context, databaseUri: Uri): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
            return try {
                PinCryptoHelper.isPinSet(context, databaseUri)
            } catch (e: Exception) {
                false
            }
        }

        /**
         * Displays a dialog for entering the database PIN to verify the user.
         * If the PIN has been deleted after too many wrong attempts, the master password check is shown.
         */
        fun FragmentActivity.showUserVerificationPin(
            userVerificationViewModel: UserVerificationViewModel,
            dataToVerify: UserVerificationData,
            databaseUri: Uri
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
            userVerificationViewModel.dataToVerify = dataToVerify
            PinVerificationDialog.show(
                activity = this,
                databaseUri = databaseUri,
                onVerified = {
                    userVerificationViewModel.onUserVerificationSucceeded(dataToVerify)
                },
                onFailed = { error ->
                    if (error is PinWipedException && dataToVerify.database != null) {
                        showUserVerificationDatabaseCredential(userVerificationViewModel, dataToVerify)
                    } else {
                        userVerificationViewModel.onUserVerificationFailed(dataToVerify, error)
                    }
                }
            )
        }

        /**
         * Displays a dialog for entering the device credential to be checked
         * @param userVerificationViewModel The ViewModel
         * @param dataToVerify The data to verify
         */
        fun FragmentActivity.showUserVerificationDeviceCredential(
            userVerificationViewModel: UserVerificationViewModel,
            dataToVerify: UserVerificationData
        ) {
            userVerificationViewModel.dataToVerify = dataToVerify
            val fragmentTag = "checkDeviceCredentialDialog"
            val fragment = supportFragmentManager.findFragmentByTag(fragmentTag)
                as? CheckDeviceCredentialDialogFragment
                ?: CheckDeviceCredentialDialogFragment.getInstance()

            if (!fragment.isAdded) {
                fragment.show(supportFragmentManager, fragmentTag)
            }
        }

        /**
         * Displays a dialog for entering the database credential to be checked
         * @param userVerificationViewModel The ViewModel
         * @param dataToVerify The data to verify
         */
        fun FragmentActivity.showUserVerificationDatabaseCredential(
            userVerificationViewModel: UserVerificationViewModel,
            dataToVerify: UserVerificationData
        ) {
            userVerificationViewModel.dataToVerify = dataToVerify
            val fragmentTag = "checkDatabaseCredentialDialog"
            val fragment = supportFragmentManager.findFragmentByTag(fragmentTag)
                as? CheckDatabaseCredentialDialogFragment
                ?: CheckDatabaseCredentialDialogFragment.getInstance()

            if (!fragment.isAdded) {
                fragment.show(supportFragmentManager, fragmentTag)
            }
        }
    }
}
