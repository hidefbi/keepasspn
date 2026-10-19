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
import com.keepasspn.database.exception.DatabaseException
import com.keepasspn.hardware.HardwareKey
import com.keepasspn.tasks.ActionRunnable
import com.keepasspn.tasks.ProgressTaskUpdater
import com.keepasspn.utils.AppUtil.getLimits
import com.keepasspn.utils.LocalDatabaseBackupManager
import com.keepasspn.utils.clear
import com.keepasspn.utils.getUriOutputStream
import java.io.File

open class SaveDatabaseRunnable(
    protected var context: Context,
    protected var database: ContextualDatabase,
    private var save: Boolean,
    private var mainCredential: MainCredential?, // If null, uses composite Key
    protected var challengeResponseRetriever: (HardwareKey, ByteArray?) -> ByteArray,
    private var databaseCopyUri: Uri? = null,
    private var dataModified: Boolean = !save,
    protected var progressTaskUpdater: ProgressTaskUpdater? = null
) : ActionRunnable() {

    private var mMasterCredential: MasterCredential? = null
    var afterSaveDatabase: ((Result) -> Unit)? = null

    private var cachedHardwareResponse: ByteArray? = null
    protected val cachingRetriever: (HardwareKey, ByteArray?) -> ByteArray = { hardwareKey, seed ->
        cachedHardwareResponse ?: challengeResponseRetriever(hardwareKey, seed).also {
            cachedHardwareResponse = it
        }
    }

    override fun onStartRun() {}

    override fun onActionRun() {
        database.checkVersion()
        // Save database in all cases if it's a copy
        if ((databaseCopyUri != null || save) && result.isSuccess) {
            try {
                val contentResolver = context.contentResolver
                mMasterCredential = mainCredential?.toMasterCredential(contentResolver)
                // Build temp database file to avoid file corruption if error
                database.saveData(
                    cacheFile = File(context.cacheDir, databaseCopyUri.hashCode().toString()),
                    databaseOutputStream = {
                        contentResolver.getUriOutputStream(databaseCopyUri ?: database.fileUri)
                    },
                    masterCredential = mMasterCredential,
                    challengeResponseRetriever = cachingRetriever,
                    limits = context.getLimits()
                )
                // Indicate data was saved only if it's not a new location
                if (databaseCopyUri == null) {
                    database.indicateUpToDateData()
                    val logicalUri = database.logicalFileUri ?: database.fileUri
                    val actualUri = database.fileUri
                    if (logicalUri != null && actualUri != null) {
                        // Always update the recovery copy under the stable
                        // logical identity. When the database was loaded from
                        // the private copy, fileUri is the backup URI and must
                        // never become the logical identity itself.
                        LocalDatabaseBackupManager.syncFromUri(
                            context,
                            logicalUri,
                            actualUri
                        )
                    }
                }
            } catch (e: DatabaseException) {
                setError(e)
            }
        } else if (dataModified) {
            database.indicateNotSavedData()
        }
    }

    override fun onFinishRun() {
        // Need to call super.onFinishRun() in child class
        mMasterCredential?.clear()
        cachedHardwareResponse?.clear()
        afterSaveDatabase?.invoke(result)
    }
}
