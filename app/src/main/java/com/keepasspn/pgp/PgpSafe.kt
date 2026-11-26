package com.keepasspn.pgp

import com.keepasspn.database.element.CustomDataItem
import com.keepasspn.database.element.Group
import com.keepasspn.model.GroupInfo

/**
 * The folder that holds the PGP keys. It is made the first time a key is generated or imported,
 * and the name stays the same in every language.
 */
object PgpSafe {

    const val GROUP_NAME = "PGP Safe"

    /** Custom data key that marks the folder, so a rename or a move does not lose it */
    const val MARKER_KEY = "keepasspn.pgp"

    fun isPgpSafe(group: Group?): Boolean {
        return group?.customData?.get(MARKER_KEY)?.value == "true"
    }

    fun findGroup(root: Group?): Group? {
        return root?.getChildGroups()?.firstOrNull { isPgpSafe(it) }
    }

    fun buildGroupInfo(): GroupInfo {
        return GroupInfo().apply {
            title = GROUP_NAME
            customData.put(CustomDataItem(MARKER_KEY, "true"))
        }
    }
}
