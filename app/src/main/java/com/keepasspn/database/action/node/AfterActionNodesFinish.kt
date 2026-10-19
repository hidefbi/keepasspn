/*
 * Copyright 2019 Jeremy Jamet / Kunzisoft.
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

import com.keepasspn.database.element.EntryId
import com.keepasspn.database.element.GroupId
import com.keepasspn.tasks.ActionRunnable

/**
 * Callback method who return the node(s) modified after an action
 * - Add : @param oldNodes empty, @param newNodes CreatedNodes
 * - Copy : @param oldNodes NodesToCopy, @param newNodes NodesCopied
 * - Delete : @param oldNodes NodesToDelete, @param newNodes empty
 * - Move : @param oldNodes empty, @param newNodes NodesToMove
 * - Update : @param oldNodes NodesToUpdate, @param newNodes NodesUpdated
 * - Touch : @param olNodes TouchedNodes, @param newNodes empty
 */
class ActionNodesValues(
    val oldGroupsIds: List<GroupId>? = null,
    val oldEntriesIds: List<EntryId>? = null,
    val newGroupsIds: List<GroupId>? = null,
    val newEntriesIds: List<EntryId>? = null,
)

abstract class AfterActionNodesFinish {
    abstract fun onActionNodesFinish(
        result: ActionRunnable.Result,
        actionNodesValues: ActionNodesValues
    )
}
