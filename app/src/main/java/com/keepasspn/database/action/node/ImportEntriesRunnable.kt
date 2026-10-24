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
import com.keepasspn.database.file.importer.ImportParser
import com.keepasspn.database.file.importer.ImportedEntry
import com.keepasspn.hardware.HardwareKey
import com.keepasspn.model.EntryInfo
import com.keepasspn.model.GroupInfo
import com.keepasspn.utils.ImportTempFile
import com.keepasspn.utils.NoOpLog
import java.io.File

/**
 * Parses a private copy of an import file and writes its entries into a new group at the root
 * of the database.
 *
 * The file is parsed here, on the task thread, so that no password is kept in an Intent or in
 * the main thread. Entries that repeat within the same import (same title, username, URL and
 * password) are written once. Passwords are wiped and the copy is deleted when the action
 * finishes, whether it succeeded or not.
 */
class ImportEntriesRunnable(
    context: Context,
    database: ContextualDatabase,
    private val importFile: File,
    private val groupTitle: String,
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

    private var mImported: List<ImportedEntry> = emptyList()
    private var mRoot: Group? = null
    private var mNewGroup: Group? = null
    private var mGroupAdded = false
    private val mNewEntries = mutableListOf<Entry>()

    override fun nodeAction() {
        val root = database.rootGroup
            ?: throw IllegalStateException("Unable to retrieve the root group to import entries")
        mRoot = root
        mImported = importFile.inputStream().use { ImportParser.parse(it).entries }

        val newGroup = database.createGroup(GroupInfo().apply { this.title = groupTitle })
            ?: throw IllegalStateException("Unable to create the group for the imported entries")
        mNewGroup = newGroup
        newGroup.touch(modified = true, touchParents = true)
        database.addGroupTo(newGroup, root)
        mGroupAdded = true

        val seenKeys = HashSet<String>()
        mImported.forEach { imported ->
            if (seenKeys.add(duplicateKey(imported))) {
                val entryInfo = EntryInfo().apply {
                    title = imported.title
                    username = imported.username
                    password = imported.password.copyOf()
                    url = imported.url
                    notes = imported.notes
                }
                database.createEntry(entryInfo)?.let { entry ->
                    entry.touch(modified = true, touchParents = true)
                    database.addEntryTo(entry, newGroup)
                    mNewEntries.add(entry)
                }
            }
        }
    }

    override fun nodeFinish(): ActionNodesValues {
        try {
            mImported.forEach { it.wipe() }
            mImported = emptyList()
            if (!result.isSuccess) {
                rollback()
            }
        } catch (e: Exception) {
            NoOpLog.e(TAG, "Unable to clean the import: ${e.javaClass.simpleName}")
        } finally {
            ImportTempFile.delete(importFile)
        }
        return ActionNodesValues(
            newEntriesIds = mNewEntries.map { it.nodeId },
            newGroupsIds = if (mGroupAdded) mNewGroup?.nodeId?.let { listOf(it) } else null
        )
    }

    private fun rollback() {
        mNewGroup?.let { group ->
            mNewEntries.forEach { entry ->
                database.removeEntryFrom(entry, group)
            }
        }
        if (mGroupAdded) {
            val group = mNewGroup
            val root = mRoot
            if (group != null && root != null) {
                database.removeGroupFrom(group, root)
            }
        }
        mNewEntries.clear()
        mGroupAdded = false
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
