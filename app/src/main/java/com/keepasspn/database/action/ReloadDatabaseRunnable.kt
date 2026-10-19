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
import com.keepasspn.database.ContextualDatabase
import com.keepasspn.database.exception.DatabaseException
import com.keepasspn.database.exception.UnknownDatabaseLocationException
import com.keepasspn.tasks.ActionRunnable
import com.keepasspn.tasks.ProgressTaskUpdater
import com.keepasspn.utils.AppUtil.getLimits
import com.keepasspn.utils.getBinaryDir
import com.keepasspn.utils.getUriInputStream

class ReloadDatabaseRunnable(
    private val context: Context,
    private val mDatabase: ContextualDatabase,
    private val progressTaskUpdater: ProgressTaskUpdater?
) : ActionRunnable() {

    var afterReloadDatabase : ((Result) -> Unit)? = null

    private val binaryDir = context.getBinaryDir()

    override fun onStartRun() {
        // Clear before we load
        mDatabase.clearIndexesAndBinaries(binaryDir)
        mDatabase.wasReloaded = true
    }

    override fun onActionRun() {
        try {
            mDatabase.reloadData(
                databaseStream = context.contentResolver.getUriInputStream(mDatabase.fileUri)
                    ?: throw UnknownDatabaseLocationException(),
                limits = context.getLimits(),
                progressTaskUpdater = progressTaskUpdater
            )
        } catch (e: DatabaseException) {
            setError(e)
        } finally {
            mDatabase.indicateUpToDateData()
        }

        if (!result.isSuccess) {
            mDatabase.clearAndClose(binaryDir)
        }
    }

    override fun onFinishRun() {
        afterReloadDatabase?.invoke(result)
    }
}
