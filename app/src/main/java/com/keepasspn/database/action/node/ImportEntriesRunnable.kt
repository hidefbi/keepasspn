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
import com.keepasspn.otp.OtpEntryFields
import com.keepasspn.utils.ImportTempFile
import com.keepasspn.utils.NoOpLog
import java.io.File

/**
 * Parses a private copy of an import file and writes its entries into the database.
 *
 * All entries are written, as the file gives them. A new group named after the import is added
 * at the root of the database; the groups of the file are nested inside it, and an entry without
 * group sits directly in it. One-time passwords are stored as OTP fields when they can be read,
 * otherwise their text is kept in the notes.
 *
 * The file is parsed here, on the task thread, so that no password is kept in an Intent or in
 * the main thread. Passwords are wiped and the copy is deleted when the action finishes, whether
 * it succeeded or not. When the action fails, everything it created is removed.
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
    private val mCreatedGroups = mutableListOf<Pair<Group, Group>>()
    private val mNewEntries = mutableListOf<Pair<Entry, Group>>()

    override fun nodeAction() {
        val root = database.rootGroup
            ?: throw IllegalStateException("Unable to retrieve the root group to import entries")
        mImported = importFile.inputStream().use { ImportParser.parse(it).entries }

        val importGroup = createGroup(groupTitle, root)
        val groupsByPath = HashMap<List<String>, Group>()
        mImported.forEach { imported ->
            val parent = groupFor(imported.groupPath, importGroup, groupsByPath)
            createEntry(imported, parent)
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
            newEntriesIds = mNewEntries.map { it.first.nodeId },
            newGroupsIds = if (mCreatedGroups.isEmpty()) {
                null
            } else {
                mCreatedGroups.map { it.first.nodeId }
            }
        )
    }

    private fun createGroup(title: String, parent: Group): Group {
        val group = database.createGroup(GroupInfo().apply { this.title = title })
            ?: throw IllegalStateException("Unable to create a group for the imported entries")
        group.touch(modified = true, touchParents = true)
        database.addGroupTo(group, parent)
        mCreatedGroups.add(group to parent)
        return group
    }

    /**
     * Group at the end of [path], created with its parents when they do not exist yet.
     * An empty path gives [base].
     */
    private fun groupFor(path: List<String>, base: Group, groupsByPath: MutableMap<List<String>, Group>): Group {
        var parent = base
        for (depth in 1..path.size) {
            val key = path.subList(0, depth).toList()
            val existing = groupsByPath[key]
            if (existing != null) {
                parent = existing
            } else {
                val group = createGroup(path[depth - 1], parent)
                groupsByPath[key] = group
                parent = group
            }
        }
        return parent
    }

    private fun createEntry(imported: ImportedEntry, parent: Group) {
        val entryInfo = EntryInfo().apply {
            title = imported.title
            username = imported.username
            password = imported.password.copyOf()
            url = imported.url
            notes = imported.notes
        }
        val otpUri = imported.otpUri
        if (otpUri != null) {
            val otpElement = OtpEntryFields.parseOTPUri(otpUri)
            if (otpElement != null) {
                entryInfo.customFields = mutableListOf(
                    OtpEntryFields.buildOtpField(otpElement, imported.title, imported.username)
                )
            } else {
                entryInfo.notes = listOf(imported.notes, "otp: $otpUri")
                    .filter { it.isNotEmpty() }
                    .joinToString("\n")
            }
        }
        database.createEntry(entryInfo)?.let { entry ->
            entry.touch(modified = true, touchParents = true)
            database.addEntryTo(entry, parent)
            mNewEntries.add(entry to parent)
        } ?: throw IllegalStateException("Unable to create an imported entry")
    }

    /**
     * Removes what this action created, entries first, then groups from the deepest one.
     */
    private fun rollback() {
        mNewEntries.asReversed().forEach { (entry, parent) ->
            database.removeEntryFrom(entry, parent)
        }
        mCreatedGroups.asReversed().forEach { (group, parent) ->
            database.removeGroupFrom(group, parent)
        }
        mNewEntries.clear()
        mCreatedGroups.clear()
    }

    companion object {
        private val TAG = ImportEntriesRunnable::class.simpleName
    }
}
