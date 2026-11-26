/*
 * Copyright 2026 Jeremy Jamet / Kunzisoft.
 * Modified by hidefbi, 2026.
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
package com.keepasspn.database.action.node

import android.content.Context
import com.keepasspn.database.ContextualDatabase
import com.keepasspn.database.element.Entry
import com.keepasspn.database.element.Group
import com.keepasspn.hardware.HardwareKey
import com.keepasspn.model.EntryInfo
import com.keepasspn.pgp.PgpSafe
import com.keepasspn.utils.NoOpLog

/**
 * Adds a PGP key to the database, in the PGP Safe folder.
 *
 * The folder is found with its marker, or created at the root in first position, right under
 * the virtual TOTP Summary folder. After that it is an ordinary folder the user can move.
 */
class AddPgpEntryRunnable(
    context: Context,
    database: ContextualDatabase,
    newEntry: EntryInfo,
    save: Boolean,
    afterActionNodesFinish: AfterActionNodesFinish?,
    challengeResponseRetriever: (HardwareKey, ByteArray?) -> ByteArray
) : ActionNodeDatabaseRunnable(
    context,
    database,
    afterActionNodesFinish,
    save,
    challengeResponseRetriever
) {

    private var mRoot: Group? = null
    private var mGroup: Group? = null
    private var mNewGroup: Group? = null
    private var mNewEntry: Entry? = null

    init {
        mRoot = database.rootGroup
        if (mRoot == null) {
            NoOpLog.w(TAG, "Unable to retrieve the root group to save the PGP key")
        } else {
            mGroup = PgpSafe.findGroup(mRoot)
            if (mGroup == null) {
                mNewGroup = database.createGroup(PgpSafe.buildGroupInfo())
                mGroup = mNewGroup
            }
            mNewEntry = database.createEntry(newEntry)
        }
    }

    override fun nodeAction() {
        mNewGroup?.let { newGroup ->
            newGroup.touch(modified = true, touchParents = true)
            mRoot?.let { root ->
                database.addGroupTo(newGroup, root)
                root.moveChildGroupToIndex(newGroup, 0)
            }
        }
        mNewEntry?.let { entry ->
            entry.touch(modified = true, touchParents = true)
            mGroup?.let { group -> database.addEntryTo(entry, group) }
        }
    }

    override fun nodeFinish(): ActionNodesValues {
        if (!result.isSuccess) {
            mNewEntry?.let { entry ->
                mGroup?.let { group -> database.removeEntryFrom(entry, group) }
            }
            mNewGroup?.let { newGroup ->
                mRoot?.let { root -> database.removeGroupFrom(newGroup, root) }
            }
        }
        return ActionNodesValues(
            newEntriesIds = mNewEntry?.nodeId?.let { listOf(it) },
            newGroupsIds = mNewGroup?.nodeId?.let { listOf(it) }
        )
    }

    companion object {
        private val TAG = AddPgpEntryRunnable::class.simpleName
    }
}
