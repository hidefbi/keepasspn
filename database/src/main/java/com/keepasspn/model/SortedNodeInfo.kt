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
package com.keepasspn.model

import com.keepasspn.database.element.Tags
import com.keepasspn.database.element.icon.IconImage
import com.keepasspn.database.element.node.NodeId
import com.keepasspn.database.element.node.NodeNaturalOrderInterface
import com.keepasspn.database.element.node.NodeTimeInterface
import com.keepasspn.database.element.node.Nodes

/**
 * Interface for node information that includes sorting and hierarchy data.
 * Combines time tracking and natural ordering capabilities.
 */
interface SortedNodeInfo : NodeTimeInterface, NodeNaturalOrderInterface {
    /**
     * Unique identifier for the node.
     */
    val nodeId: NodeId<*>

    /**
     * The title of the node.
     */
    val title: String

    /**
     * The icon associated with the node.
     */
    val icon: IconImage

    /**
     * Tags associated with the node.
     */
    val tags: Tags

    /**
     * The path of the node in the database.
     */
    val path: String?

    /**
     * Whether the node is currently references in another node or defined as recyclerBin or templates group
     */
    val isReferenced: Boolean
}

/**
 * Extension function to convert a list of [SortedNodeInfo] to [Nodes].
 */
fun List<SortedNodeInfo>.toNodes(): Nodes {
    return Nodes(
        listGroupsIds =
            this.mapNotNull {
                when (it) {
                    is SortedGroupInfo -> it.nodeId
                    else -> null
                }
            },
        listEntriesIds =
            this.mapNotNull {
                when (it) {
                    is SortedEntryInfo -> it.nodeId
                    else -> null
                }
            }
    )
}