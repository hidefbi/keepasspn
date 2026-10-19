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
package com.keepasspn.database.action

import android.content.Context
import android.net.Uri
import com.keepasspn.database.ContextualDatabase
import com.keepasspn.database.MainCredential
import com.keepasspn.database.element.MasterCredential
import com.keepasspn.database.exception.DatabaseInputException
import com.keepasspn.database.exception.UnknownDatabaseLocationException
import com.keepasspn.hardware.HardwareKey
import com.keepasspn.tasks.ActionRunnable
import com.keepasspn.tasks.ProgressTaskUpdater
import com.keepasspn.utils.AppUtil.getLimits
import com.keepasspn.utils.getBinaryDir
import com.keepasspn.utils.getUriInputStream

class LoadDatabaseRunnable(
    private val context: Context,
    private val mDatabase: ContextualDatabase,
    private val mDatabaseUri: Uri,
    private val mLogicalDatabaseUri: Uri = mDatabaseUri,
    private val mMainCredential: MainCredential,
    private val mChallengeResponseRetriever: (hardwareKey: HardwareKey, seed: ByteArray?) -> ByteArray,
    private val mReadonly: Boolean,
    private val mAllowUserVerification: Boolean,
    private val mFixDuplicateUUID: Boolean,
    private val progressTaskUpdater: ProgressTaskUpdater?
) : ActionRunnable() {

    private var mMasterCredential: MasterCredential? = null
    var afterLoadDatabase : ((Result) -> Unit)? = null

    private val binaryDir = context.getBinaryDir()

    override fun onStartRun() {
        // Clear before we load
        mDatabase.clearAndClose(binaryDir)
    }

    override fun onActionRun() {
        try {
            val contentResolver = context.contentResolver
            // Keep the logical URI stable even when mDatabaseUri is the
            // private recovery copy selected by resolveLoadUri().
            mDatabase.logicalFileUri = mLogicalDatabaseUri
            mDatabase.fileUri = mDatabaseUri
            mDatabase.loadedFromPrivateBackup =
                mDatabaseUri != mLogicalDatabaseUri
            mMasterCredential = mMainCredential.toMasterCredential(contentResolver)
            mDatabase.loadData(
                databaseStream = contentResolver.getUriInputStream(mDatabaseUri)
                    ?: throw UnknownDatabaseLocationException(),
                masterCredential = mMasterCredential!!,
                challengeResponseRetriever = mChallengeResponseRetriever,
                readOnly = mReadonly,
                allowUserVerification = mAllowUserVerification,
                cacheDirectory = binaryDir,
                limits = context.getLimits(),
                fixDuplicateUUID = mFixDuplicateUUID,
                progressTaskUpdater = progressTaskUpdater
            )
        } catch (e: DatabaseInputException) {
            setError(e)
        } finally {
            mDatabase.indicateUpToDateData()
        }

        if (!result.isSuccess) {
            mDatabase.clearAndClose(binaryDir)
        }
    }

    override fun onFinishRun() {
        mMasterCredential?.clear()
        afterLoadDatabase?.invoke(result)
    }
}
