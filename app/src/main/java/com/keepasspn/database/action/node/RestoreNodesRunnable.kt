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
package com.keepasspn.database.action.node

import android.content.Context
import com.keepasspn.database.ContextualDatabase
import com.keepasspn.database.element.Entry
import com.keepasspn.database.element.EntryId
import com.keepasspn.database.element.Group
import com.keepasspn.database.element.GroupId
import com.keepasspn.database.element.database.DatabaseVersioned
import com.keepasspn.hardware.HardwareKey
import java.util.UUID

/**
 * Restores groups and entries from the recycle bin to the group they were in before deletion.
 * When that group does not exist anymore or is itself in the recycle bin, the root group is used.
 */
class RestoreNodesRunnable(
    context: Context,
    database: ContextualDatabase,
    groupsIdsToRestore: List<GroupId>,
    entriesIdsToRestore: List<EntryId>,
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

    private val mGroupsToRestore: List<Group> = database.getGroupsByIds(groupsIdsToRestore)
    private val mEntriesToRestore: List<Entry> = database.getEntriesByIds(entriesIdsToRestore)

    override fun nodeAction() {
        mGroupsToRestore.forEach { group ->
            val parent = findRestoreParent(group.previousParentGroup) ?: return@forEach
            database.undoRecycle(group, parent)
        }
        mEntriesToRestore.forEach { entry ->
            val parent = findRestoreParent(entry.previousParentGroup) ?: return@forEach
            database.undoRecycle(entry, parent)
        }
    }

    override fun nodeFinish(): ActionNodesValues {
        return ActionNodesValues()
    }

    private fun findRestoreParent(previousParentUuid: UUID): Group? {
        if (previousParentUuid != DatabaseVersioned.UUID_ZERO) {
            val previousParent = database.getAllGroupsWithoutRoot()
                .firstOrNull { it.nodeId.id == previousParentUuid }
            if (previousParent != null && !database.groupIsInRecycleBin(previousParent)) {
                return previousParent
            }
        }
        return database.rootGroup
    }
}
