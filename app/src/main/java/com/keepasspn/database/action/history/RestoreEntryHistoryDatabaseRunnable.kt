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
package com.keepasspn.database.action.history

import android.content.Context
import com.keepasspn.database.ContextualDatabase
import com.keepasspn.database.action.node.UpdateEntryRunnable
import com.keepasspn.database.element.Entry
import com.keepasspn.database.element.EntryId
import com.keepasspn.hardware.HardwareKey
import com.keepasspn.tasks.ActionRunnable

class RestoreEntryHistoryDatabaseRunnable (
    private val context: Context,
    private val database: ContextualDatabase,
    entryId: EntryId,
    private val entryHistoryPosition: Int,
    private val save: Boolean,
    private val challengeResponseRetriever: (HardwareKey, ByteArray?) -> ByteArray
) : ActionRunnable() {

    private var updateEntryRunnable: UpdateEntryRunnable? = null
    private var mMainEntry: Entry? = null

    init {
        database.getEntryById(entryId)?.let { entry ->
            mMainEntry = entry
        }
    }

    override fun onStartRun() {
        try {
            mMainEntry?.let { mainEntry ->
                val historyToRestore = database.getEntryInfoFrom(
                    entry = mainEntry.getHistory()[entryHistoryPosition],
                    raw = true
                )
                // Update the entry with the fresh formatted entry to restore
                updateEntryRunnable = UpdateEntryRunnable(
                    context = context,
                    database = database,
                    newEntry = historyToRestore,
                    save = save,
                    afterActionNodesFinish = null,
                    challengeResponseRetriever = challengeResponseRetriever
                )

                updateEntryRunnable?.onStartRun()
            } ?: setError("Entry to restore cannot be null")
        } catch (e: Exception) {
            setError(e)
        }
    }

    override fun onActionRun() {
        updateEntryRunnable?.onActionRun()
    }

    override fun onFinishRun() {
        updateEntryRunnable?.onFinishRun()
    }
}
