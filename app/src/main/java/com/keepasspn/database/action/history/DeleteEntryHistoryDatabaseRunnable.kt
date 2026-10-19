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
import com.keepasspn.database.action.SaveDatabaseRunnable
import com.keepasspn.database.element.Entry
import com.keepasspn.database.element.EntryId
import com.keepasspn.hardware.HardwareKey

class DeleteEntryHistoryDatabaseRunnable (
    context: Context,
    database: ContextualDatabase,
    mainEntryId: EntryId,
    private val entryHistoryPosition: Int,
    save: Boolean,
    challengeResponseRetriever: (HardwareKey, ByteArray?) -> ByteArray
) : SaveDatabaseRunnable(
    context,
    database,
    save,
    mainCredential = null,
    challengeResponseRetriever
) {

    private var mMainEntry: Entry? = null

    init {
        database.getEntryById(mainEntryId)?.let { mainEntry ->
            mMainEntry = mainEntry
        }
    }

    override fun onStartRun() {
        try {
            mMainEntry?.let {
                database.removeEntryHistory(it, entryHistoryPosition)
            }
        } catch (e: Exception) {
            setError(e)
        }
        super.onStartRun()
    }
}
