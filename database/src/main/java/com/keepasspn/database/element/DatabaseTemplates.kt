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

import com.keepasspn.database.element.database.DatabaseKDBX

/**
 * Templates group of the loaded database. Only the KDBX database has one.
 */
internal class DatabaseTemplates(
    private val kdbx: () -> DatabaseKDBX?,
) {

    val allowTemplatesGroup: Boolean
        get() = kdbx() != null

    // Maybe another templates method with KDBX5
    val isTemplatesEnabled: Boolean
        get() = kdbx()?.isTemplatesGroupEnabled() ?: false

    fun enableTemplates(enable: Boolean, templatesGroupName: String) {
        kdbx()?.enableTemplatesGroup(enable, templatesGroupName)
        kdbx()?.entryTemplatesGroupChanged = DateInstant()
    }

    val templatesGroup: Group?
        get() {
            kdbx()?.getTemplatesGroup()?.let {
                return Group(it)
            }
            return null
        }

    fun setTemplatesGroup(group: Group?) {
        // Only the kdbx templates group can be changed
        if (group != null) {
            kdbx()?.entryTemplatesGroup = group.nodeIdKDBX.id
        } else {
            kdbx()?.removeTemplatesGroup()
        }
        kdbx()?.entryTemplatesGroupChanged = DateInstant()
    }
}
