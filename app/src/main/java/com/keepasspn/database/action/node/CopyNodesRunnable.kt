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
package com.keepasspn.database.action.node

import android.content.Context
import com.keepasspn.utils.NoOpLog
import com.keepasspn.database.ContextualDatabase
import com.keepasspn.database.element.Entry
import com.keepasspn.database.element.EntryId
import com.keepasspn.database.element.Group
import com.keepasspn.database.element.GroupId
import com.keepasspn.database.exception.CopyEntryDatabaseException
import com.keepasspn.database.exception.CopyGroupDatabaseException
import com.keepasspn.database.exception.MissingParentDatabaseException
import com.keepasspn.hardware.HardwareKey

class CopyNodesRunnable(
    context: Context,
    database: ContextualDatabase,
    newParentId: GroupId,
    val groupsIdsToCopy: List<GroupId>,
    val entriesIdsToCopy: List<EntryId>,
    val copyTitleSuffix: String? = null,
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

    private var mGroupsCopied = mutableListOf<Group>()
    private var mEntriesCopied = mutableListOf<Entry>()
    private var mNewParent: Group? = null
    private var mGroupsToCopy: List<Group> = listOf()
    private var mEntriesToCopy: List<Entry> = listOf()

    init {
        database.getGroupById(newParentId)?.let { newParent ->
            mNewParent = newParent
        }
        mGroupsToCopy = database.getGroupsByIds(groupsIdsToCopy)
        mEntriesToCopy = database.getEntriesByIds(entriesIdsToCopy)
    }

    override fun nodeAction() {
        val newParent = mNewParent ?: run {
            setError(MissingParentDatabaseException())
            return
        }

        for (currentNode in mGroupsToCopy) {
            try {
                val groupCopied = database.copyGroupTo(currentNode, newParent)
                copyTitleSuffix?.let { groupCopied.title = "${currentNode.title} $it" }
                mGroupsCopied.add(groupCopied)
            } catch (_: Exception) {
                setError(CopyGroupDatabaseException())
                return
            }
        }

        for (currentNode in mEntriesToCopy) {
            // Root can contains entry
            if (newParent != database.rootGroup || database.rootCanContainsEntry()) {
                // Update entry with new values
                newParent.touch(modified = false, touchParents = true)
                try {
                    val entryCopied = database.copyEntryTo(
                        entryToCopy = currentNode,
                        newParent = newParent
                    )
                    copyTitleSuffix?.let { entryCopied.title = "${currentNode.title} $it" }
                    entryCopied.touch(modified = true, touchParents = true)
                    mEntriesCopied.add(entryCopied)
                } catch (_: Exception) {
                    setError(CopyEntryDatabaseException())
                    return
                }
            } else {
                // Only finish thread
                setError(CopyEntryDatabaseException())
                return
            }
        }
    }

    override fun nodeFinish(): ActionNodesValues {
        if (!result.isSuccess) {
            // If we fail to save, try to delete the copy
            mEntriesCopied.forEach {
                try {
                    database.deleteEntry(it)
                } catch (e: Exception) {
                    NoOpLog.w(TAG, "Unable to delete the copied entry", e)
                }
            }
            // Restore groups in reverse order of creation
            mGroupsCopied.reversed().forEach {
                try {
                    database.deleteGroup(it)
                } catch (e: Exception) {
                    NoOpLog.w(TAG, "Unable to delete the copied group", e)
                }
            }
        }
        return ActionNodesValues(
            oldGroupsIds = groupsIdsToCopy,
            oldEntriesIds = entriesIdsToCopy,
            newGroupsIds = mGroupsCopied.map { it.nodeId },
            newEntriesIds = mEntriesCopied.map { it.nodeId }
        )
    }

    companion object {
        private val TAG = CopyNodesRunnable::class.java.name
    }
}
