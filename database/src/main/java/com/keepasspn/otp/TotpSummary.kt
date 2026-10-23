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
package com.keepasspn.otp

import com.keepasspn.database.element.Group
import com.keepasspn.database.element.GroupId
import com.keepasspn.database.element.node.NodeIdUUID
import com.keepasspn.model.GroupInfo
import com.keepasspn.model.SortedGroupInfo
import com.keepasspn.model.SortedNodeInfo
import java.util.UUID

/**
 * Virtual folder listing every entry that has a one-time password, shown at the top of the root group.
 * It is built at display time and never written to the database, so it is not exported.
 */
object TotpSummary {

    val NODE_ID: GroupId = NodeIdUUID(UUID.fromString("3f1a6c2e-8b4d-4e7a-9c15-0d2b7e6f4a10"))

    /**
     * Custom data key that marks the group holding the codes added by camera scan.
     */
    const val IMPORT_GROUP_MARKER_KEY = "keepasspn.totp"

    fun isSummary(node: SortedNodeInfo?): Boolean {
        return node?.nodeId == NODE_ID
    }

    /**
     * The scanned codes live in the import group only, never in the summary.
     */
    fun isImportGroup(group: Group?): Boolean {
        return group?.customData?.get(IMPORT_GROUP_MARKER_KEY)?.value == "true"
    }

    fun buildNode(title: String, numberOfEntries: Int): SortedGroupInfo {
        val info = GroupInfo().apply {
            nodeId = NODE_ID
            this.title = title
        }
        return SortedGroupInfo(groupToCopy = info, numberChildrenEntries = numberOfEntries)
    }
}
