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
import com.keepasspn.database.element.node.NodeHandler

/**
 * Copy, move, delete and recycle of the entries and groups of the loaded database.
 *
 * The placement itself is done by [nodes]. The recycle bin is given by [recycleBin] and created
 * by [ensureRecycleBinExists], so that these operations follow the bin as the database has it.
 */
internal class DatabaseNodeActions(
    private val nodes: DatabaseNodes,
    private val kdb: () -> DatabaseKDB?,
    private val kdbx: () -> DatabaseKDBX?,
    private val recycleBin: () -> Group?,
    private val ensureRecycleBinExists: (String) -> Unit,
) {

    /**
     * @return A duplicate entry with the same values, a new random UUID and a new parent
     * @param entryToCopy
     * @param newParent
     */
    fun copyEntryTo(entryToCopy: Entry, newParent: Group): Entry {
        val entryCopied = Entry(entryToCopy, copyHistory = false)
        entryCopied.nodeId = kdb()?.newEntryId() ?: kdbx()?.newEntryId() ?: NodeIdUUID()
        entryCopied.parent = newParent
        entryCopied.title += " (~)"
        nodes.addEntryTo(entryCopied, newParent)
        return entryCopied
    }

    /**
     * @return A duplicate group with the same values, a new random UUID and a new parent
     * @param groupToCopy
     * @param newParent
     */
    fun copyGroupTo(groupToCopy: Group, newParent: Group): Group {
        val groupCopied = Group(groupToCopy)
        val newId = kdb()?.newGroupId() ?: kdbx()?.newGroupId() ?: NodeIdUUID()
        when (newId) {
            is NodeIdInt -> groupCopied.setNodeId(newId)
            is NodeIdUUID -> groupCopied.setNodeId(newId)
        }
        groupCopied.parent = newParent
        groupCopied.title += " (~)"
        nodes.addGroupTo(groupCopied, newParent)

        // Copy entries
        groupToCopy.getChildEntries().forEach {
            copyEntryTo(it, groupCopied)
        }

        // Copy sub-groups
        groupToCopy.getChildGroups().forEach {
            copyGroupTo(it, groupCopied)
        }

        return groupCopied
    }

    fun moveEntryTo(entryToMove: Entry, newParent: Group) {
        entryToMove.parent?.let {
            nodes.removeEntryFrom(entryToMove, it)
        }
        nodes.addEntryTo(entryToMove, newParent)
    }

    fun moveGroupTo(groupToMove: Group, newParent: Group) {
        groupToMove.parent?.let {
            nodes.removeGroupFrom(groupToMove, it)
        }
        nodes.addGroupTo(groupToMove, newParent)
    }

    fun deleteEntry(entry: Entry) {
        entry.entryKDBX?.id?.let { entryId ->
            kdbx()?.addDeletedObject(entryId)
        }
        entry.parent?.let {
            nodes.removeEntryFrom(entry, it)
        }
    }

    fun deleteGroup(group: Group) {
        group.doForEachChildAndForIt(
                object : NodeHandler<Entry>() {
                    override fun operate(node: Entry): Boolean {
                        deleteEntry(node)
                        return true
                    }
                },
                object : NodeHandler<Group>() {
                    override fun operate(node: Group): Boolean {
                        node.groupKDBX?.id?.let { groupId ->
                            kdbx()?.addDeletedObject(groupId)
                        }
                        node.parent?.let {
                            nodes.removeGroupFrom(node, it)
                        }
                        return true
                    }
                })
    }

    fun canRecycle(entry: Entry): Boolean {
        var canRecycle: Boolean? = null
        entry.entryKDB?.let {
            canRecycle = kdb()?.canRecycle(it)
        }
        entry.entryKDBX?.let {
            canRecycle = kdbx()?.canRecycle(it)
        }
        return canRecycle ?: false
    }

    fun canRecycle(group: Group): Boolean {
        var canRecycle: Boolean? = null
        group.groupKDB?.let {
            canRecycle = kdb()?.canRecycle(it)
        }
        group.groupKDBX?.let {
            canRecycle = kdbx()?.canRecycle(it)
        }
        return canRecycle ?: false
    }

    fun recycle(entry: Entry, recyclerBinTitle: String) {
        ensureRecycleBinExists(recyclerBinTitle)
        entry.parent?.let { parent ->
            nodes.removeEntryFrom(entry, parent)
        }
        recycleBin()?.let {
            nodes.addEntryTo(entry, it)
        }
        entry.afterAssignNewParent()
    }

    fun recycle(group: Group, recyclerBinTitle: String) {
        ensureRecycleBinExists(recyclerBinTitle)
        group.parent?.let { parent ->
            nodes.removeGroupFrom(group, parent)
        }
        recycleBin()?.let {
            nodes.addGroupTo(group, it)
        }
        group.afterAssignNewParent()
    }

    fun undoRecycle(entry: Entry, parent: Group) {
        recycleBin()?.let {
            nodes.removeEntryFrom(entry, it)
        }
        nodes.addEntryTo(entry, parent)
        entry.afterAssignNewParent()
    }

    fun undoRecycle(group: Group, parent: Group) {
        recycleBin()?.let {
            nodes.removeGroupFrom(group, it)
        }
        nodes.addGroupTo(group, parent)
        group.afterAssignNewParent()
    }
}
