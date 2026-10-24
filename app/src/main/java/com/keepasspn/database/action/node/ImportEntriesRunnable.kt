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
import com.keepasspn.database.element.Group
import com.keepasspn.database.file.importer.ImportedEntry
import com.keepasspn.hardware.HardwareKey
import com.keepasspn.model.EntryInfo
import com.keepasspn.model.GroupInfo
import com.keepasspn.utils.NoOpLog

/**
 * Writes imported entries into a new group at the root of the database.
 *
 * Entries that repeat within the same import (same title, username, password and URL)
 * are written once. Passwords of the imported entries are wiped when the action finishes,
 * whether it succeeded or not.
 */
class ImportEntriesRunnable(
    context: Context,
    database: ContextualDatabase,
    private val importedEntries: List<ImportedEntry>,
    groupTitle: String,
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
    private var mNewGroup: Group? = null
    private val mNewEntries = mutableListOf<Entry>()

    init {
        mRoot = database.rootGroup
        if (mRoot == null) {
            NoOpLog.w(TAG, "Unable to retrieve the root group to import entries")
        } else {
            val groupInfo = GroupInfo().apply {
                this.title = groupTitle
            }
            mNewGroup = database.createGroup(groupInfo)
            val seenKeys = HashSet<String>()
            importedEntries.forEach { imported ->
                if (seenKeys.add(duplicateKey(imported))) {
                    val entryInfo = EntryInfo()
                    entryInfo.title = imported.title
                    entryInfo.username = imported.username
                    entryInfo.password = imported.password.copyOf()
                    entryInfo.url = imported.url
                    entryInfo.notes = imported.notes
                    database.createEntry(entryInfo)?.let { mNewEntries.add(it) }
                }
            }
        }
    }

    override fun nodeAction() {
        mNewGroup?.let { newGroup ->
            newGroup.touch(modified = true, touchParents = true)
            mRoot?.let { root -> database.addGroupTo(newGroup, root) }
        }
        mNewGroup?.let { group ->
            mNewEntries.forEach { entry ->
                entry.touch(modified = true, touchParents = true)
                database.addEntryTo(entry, group)
            }
        }
    }

    override fun nodeFinish(): ActionNodesValues {
        importedEntries.forEach { it.wipe() }
        if (!result.isSuccess) {
            mNewGroup?.let { group ->
                mNewEntries.forEach { entry ->
                    database.removeEntryFrom(entry, group)
                }
            }
            mNewGroup?.let { group ->
                mRoot?.let { root -> database.removeGroupFrom(group, root) }
            }
        }
        return ActionNodesValues(
            newEntriesIds = mNewEntries.map { it.nodeId },
            newGroupsIds = mNewGroup?.nodeId?.let { listOf(it) }
        )
    }

    companion object {
        private val TAG = ImportEntriesRunnable::class.simpleName

        /**
         * Identity of an entry inside one import. The password is part of it, so two accounts
         * with the same title and user name but different passwords are both kept.
         */
        private fun duplicateKey(imported: ImportedEntry): String {
            return listOf(imported.title, imported.username, imported.url, String(imported.password))
                .joinToString("\u0000")
        }
    }
}
