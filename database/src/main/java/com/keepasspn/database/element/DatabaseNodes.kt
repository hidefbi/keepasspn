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
package com.keepasspn.database.element

import com.keepasspn.database.element.database.DatabaseKDB
import com.keepasspn.database.element.database.DatabaseKDBX
import com.keepasspn.database.element.node.NodeIdInt
import com.keepasspn.database.element.node.NodeIdUUID

/**
 * Creation, lookup and placement of the entries and groups of the loaded database.
 *
 * The nodes belong to the KDB or KDBX database currently loaded, so they are read through
 * [kdb] and [kdbx] at each call, as the database may change.
 */
internal class DatabaseNodes(
    private val kdb: () -> DatabaseKDB?,
    private val kdbx: () -> DatabaseKDBX?,
) {

    fun rootCanContainsEntry(): Boolean {
        return kdb()?.rootCanContainsEntry() ?: kdbx()?.rootCanContainsEntry() ?: false
    }

    fun createEntry(): Entry? {
        kdb()?.let { database ->
            return Entry(database.createEntry()).apply {
                nodeId = database.newEntryId()
            }
        }
        kdbx()?.let { database ->
            return Entry(database.createEntry()).apply {
                nodeId = database.newEntryId()
            }
        }

        return null
    }

    fun createGroup(): Group? {
        var group: Group? = null
        kdb()?.let { database ->
            group = Group(database.createGroup()).apply {
                setNodeId(database.newGroupId())
            }
        }
        kdbx()?.let { database ->
            group = Group(database.createGroup()).apply {
                setNodeId(database.newGroupId())
            }
        }

        return group
    }

    fun getEntryById(id: EntryId): Entry? {
        kdb()?.getEntryById(id)?.let {
            return Entry(it)
        }
        kdbx()?.getEntryById(id)?.let {
            return Entry(it)
        }
        return null
    }

    fun getGroupById(groupId: GroupId): Group? {
        when (groupId) {
            is NodeIdInt -> {
                kdb()?.getGroupById(groupId)?.let {
                    return Group(it)
                }
            }
            is NodeIdUUID -> {
                kdbx()?.getGroupById(groupId)?.let {
                    return Group(it)
                }
            }
        }
        return null
    }

    fun addEntryTo(entry: Entry, parent: Group) {
        entry.entryKDB?.let { entryKDB ->
            kdb()?.addEntryTo(entryKDB, parent.groupKDB)
        }
        entry.entryKDBX?.let { entryKDBX ->
            kdbx()?.addEntryTo(entryKDBX, parent.groupKDBX)
        }
        entry.afterAssignNewParent()
    }

    fun updateEntry(entry: Entry) {
        entry.entryKDB?.let { entryKDB ->
            kdb()?.updateEntry(entryKDB)
        }
        entry.entryKDBX?.let { entryKDBX ->
            kdbx()?.updateEntry(entryKDBX)
        }
    }

    fun removeEntryFrom(entry: Entry, parent: Group) {
        entry.entryKDB?.let { entryKDB ->
            kdb()?.removeEntryFrom(entryKDB, parent.groupKDB)
        }
        entry.entryKDBX?.let { entryKDBX ->
            kdbx()?.removeEntryFrom(entryKDBX, parent.groupKDBX)
        }
        entry.afterAssignNewParent()
    }

    fun addGroupTo(group: Group, parent: Group) {
        group.groupKDB?.let { groupKDB ->
            kdb()?.addGroupTo(groupKDB, parent.groupKDB)
        }
        group.groupKDBX?.let { groupKDBX ->
            kdbx()?.addGroupTo(groupKDBX, parent.groupKDBX)
        }
        group.afterAssignNewParent()
    }

    fun updateGroup(group: Group) {
        group.groupKDB?.let { entryKDB ->
            kdb()?.updateGroup(entryKDB)
        }
        group.groupKDBX?.let { entryKDBX ->
            kdbx()?.updateGroup(entryKDBX)
        }
    }

    fun removeGroupFrom(group: Group, parent: Group) {
        group.groupKDB?.let { groupKDB ->
            kdb()?.removeGroupFrom(groupKDB, parent.groupKDB)
        }
        group.groupKDBX?.let { groupKDBX ->
            kdbx()?.removeGroupFrom(groupKDBX, parent.groupKDBX)
        }
        group.afterAssignNewParent()
    }
}
