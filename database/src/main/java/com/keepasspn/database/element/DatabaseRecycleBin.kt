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
import com.keepasspn.database.element.DateInstant

/**
 * Recycle bin of the loaded database.
 *
 * [ensureRecycleBinExists] and [getGroupById] are the database methods that create the bin
 * and find a group, so that the group logic stays in one place.
 */
internal class DatabaseRecycleBin(
    private val kdb: () -> DatabaseKDB?,
    private val kdbx: () -> DatabaseKDBX?,
    private val ensureRecycleBinExists: (String) -> Unit,
    private val getGroupById: (GroupId) -> Group?,
) {

    val isRecycleBinEnabled: Boolean
        // Backup is always enabled in KDB database
        get() = kdb() != null || kdbx()?.isRecycleBinEnabled ?: false

    fun enableRecycleBin(enable: Boolean, recyclerBinTitle: String) {
        kdbx()?.isRecycleBinEnabled = enable
        if (enable) {
            ensureRecycleBinExists(recyclerBinTitle)
        } else {
            kdbx()?.removeRecycleBin()
        }
        kdbx()?.recycleBinChanged = DateInstant()
    }

    val recycleBin: Group?
        get() {
            kdb()?.backupGroup?.let {
                return getGroupById(it.nodeId) ?: Group(it)
            }
            kdbx()?.recycleBin?.let {
                return getGroupById(it.nodeId) ?: Group(it)
            }
            return null
        }

    fun setRecycleBin(group: Group?) {
        // Only the kdbx recycle bin can be changed
        if (group != null) {
            kdbx()?.recycleBinUUID = group.nodeIdKDBX.id
        } else {
            kdbx()?.removeRecycleBin()
        }
        kdbx()?.recycleBinChanged = DateInstant()
    }
}
