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
package com.keepasspn.database.element.node

import com.keepasspn.database.element.Database
import com.keepasspn.database.element.Entry
import com.keepasspn.database.element.Group

/**
 * Node filter by default.
 * @param database The database to filter.
 * @param showExpired Whether to show expired entries.
 * @param showTemplates Whether to show templates.
 */
class DefaultNodeFilter(
    var database: Database? = null,
    var showExpired: Boolean = true,
    var showTemplates: Boolean = true
): NodeFilter {
    override val filter: (Node) -> Boolean = { node ->
        when (node) {
            is Entry -> {
                node.entryKDB?.isMetaStream() != true
            }
            is Group -> {
                showTemplates || database?.templatesGroup != node
            }
            else -> true
        } && (showExpired || !node.isCurrentlyExpires)
    }
}