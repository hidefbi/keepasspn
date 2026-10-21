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
import com.keepasspn.database.element.Group
import com.keepasspn.database.element.GroupId
import com.keepasspn.hardware.HardwareKey

/**
 * Moves a group to a new position among the groups of its parent.
 */
class ReorderGroupRunnable(
    context: Context,
    database: ContextualDatabase,
    groupId: GroupId,
    targetIndex: Int,
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

    private var mGroup: Group? = null
    private var mParent: Group? = null
    private val mTargetIndex: Int = targetIndex

    init {
        mGroup = database.getGroupById(groupId)
        mParent = mGroup?.parent
    }

    override fun nodeAction() {
        val group = mGroup ?: return
        val parent = mParent ?: return
        parent.moveChildGroupToIndex(group, mTargetIndex)
    }

    override fun nodeFinish(): ActionNodesValues {
        return ActionNodesValues()
    }
}
