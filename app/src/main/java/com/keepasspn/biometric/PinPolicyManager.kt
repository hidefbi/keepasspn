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
package com.keepasspn.biometric

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import com.keepasspn.app.database.AppDatabase
import com.keepasspn.app.database.CipherDatabaseAction
import com.keepasspn.database.pin.PinPolicy
import com.keepasspn.settings.PreferencesUtil
import com.keepasspn.utils.LocalDatabaseBackupManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Applies [PinPolicy] to the app's stored data.
 *
 * "Removing a database" here means removing everything the app keeps for it:
 * its history entry, PIN, device unlock entry and private recovery copy. The
 * user's original .kdbx file is never touched, so it can be imported again.
 */
@RequiresApi(Build.VERSION_CODES.M)
object PinPolicyManager {

    /**
     * Call on every app open, before any PIN prompt.
     *
     * If the configured validity has elapsed since the last open, every PIN is removed
     * together with its database entry and recovery copy. Then the open time is recorded.
     */
    suspend fun onAppOpened(context: Context) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val validityDays = PreferencesUtil.getPinValidityDays(context)
        val lastOpen = PinPolicyStore.getLastOpenMillis(context)
        if (PinPolicy.isExpired(lastOpen, now, validityDays)) {
            PinCryptoHelper.listPinDatabaseUris(context).forEach { databaseUri ->
                purgeDatabase(context, databaseUri)
            }
        }
        PinPolicyStore.setLastOpenMillis(context, now)
    }

    /**
     * Counts a wrong master password while a PIN lockout is active.
     * Returns null when no lockout is active (the failure is not part of the PIN policy).
     */
    suspend fun onMasterPasswordFailure(
        context: Context,
        databaseUri: Uri
    ): PinPolicy.MasterFailureOutcome? = withContext(Dispatchers.IO) {
        if (!PinPolicyStore.isMasterLockoutActive(context, databaseUri)) return@withContext null
        val failures = PinPolicyStore.incrementMasterFailure(context, databaseUri)
        when (val outcome = PinPolicy.onMasterFailureAfterPinWipe(failures)) {
            is PinPolicy.MasterFailureOutcome.DeleteDatabase -> {
                purgeDatabase(context, databaseUri)
                outcome
            }
            else -> outcome
        }
    }

    /** A successful master-password unlock ends the lockout. */
    suspend fun onMasterPasswordSuccess(context: Context, databaseUri: Uri) = withContext(Dispatchers.IO) {
        PinPolicyStore.clearMasterLockout(context, databaseUri)
    }

    /** Removes the PIN, the history entry, the device unlock entry and the recovery copy. */
    suspend fun purgeDatabase(context: Context, databaseUri: Uri) = withContext(Dispatchers.IO) {
        PinCryptoHelper.removePin(context, databaseUri)
        CipherDatabaseAction.getInstance(context).deleteByDatabaseUri(databaseUri)
        LocalDatabaseBackupManager.deleteBackup(context, databaseUri)
        val historyDao = AppDatabase.getDatabase(context).fileDatabaseHistoryDao()
        historyDao.getByDatabaseUri(databaseUri.toString())?.let { historyDao.delete(it) }
        PinPolicyStore.clearMasterLockout(context, databaseUri)
    }
}
