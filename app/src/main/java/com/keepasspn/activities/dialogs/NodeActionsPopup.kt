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
package com.keepasspn.activities.dialogs

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.widget.PopupWindow
import android.widget.TextView
import com.keepasspn.R
import com.keepasspn.viewmodels.GroupViewModel

/**
 * Actions for a group or an entry, shown next to the row that was long pressed.
 *
 * The popup is not modal: it does not take the touch stream, so a finger that keeps
 * moving can still reach the list.
 */
class NodeActionsPopup(private val context: Context) {

    data class Actions(
        val canMove: Boolean,
        val canMoveUp: Boolean,
        val canMoveDown: Boolean,
        val canRestore: Boolean
    )

    private var popupWindow: PopupWindow? = null

    val isShowing: Boolean
        get() = popupWindow?.isShowing == true

    /**
     * Show the actions below [anchor], or above it when there is no room.
     * @param onDismiss called once when the popup goes away, for any reason
     */
    fun show(
        anchor: View,
        title: String,
        actions: Actions,
        onAction: (GroupViewModel.NodeAction) -> Unit,
        onDismiss: () -> Unit
    ) {
        dismiss()
        val content = LayoutInflater.from(context).inflate(R.layout.popup_node_actions, null, false)
        content.findViewById<TextView>(R.id.node_actions_title).text = title
        bind(content, R.id.node_actions_edit, GroupViewModel.NodeAction.EDIT, true, onAction)
        bind(content, R.id.node_actions_duplicate, GroupViewModel.NodeAction.DUPLICATE, true, onAction)
        bind(content, R.id.node_actions_move, GroupViewModel.NodeAction.MOVE, actions.canMove, onAction)
        bind(content, R.id.node_actions_move_up, GroupViewModel.NodeAction.MOVE_UP, actions.canMoveUp, onAction)
        bind(content, R.id.node_actions_move_down, GroupViewModel.NodeAction.MOVE_DOWN, actions.canMoveDown, onAction)
        bind(content, R.id.node_actions_restore, GroupViewModel.NodeAction.RESTORE, actions.canRestore, onAction)
        bind(content, R.id.node_actions_delete, GroupViewModel.NodeAction.DELETE, true, onAction)

        content.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val popupWidth = content.measuredWidth
        val popupHeight = content.measuredHeight
        val gap = context.resources.displayMetrics.density * 4

        val window = PopupWindow(content, popupWidth, popupHeight, false).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setOnDismissListener {
                popupWindow = null
                onDismiss()
            }
        }
        popupWindow = window

        val location = IntArray(2)
        anchor.getLocationOnScreen(location)
        val spaceBelow = context.resources.displayMetrics.heightPixels - (location[1] + anchor.height)
        val xOffset = anchor.width - popupWidth
        if (spaceBelow >= popupHeight + gap) {
            window.showAsDropDown(anchor, xOffset, gap.toInt())
        } else {
            window.showAsDropDown(anchor, xOffset, -(anchor.height + popupHeight + gap.toInt()))
        }
    }

    /**
     * Dismiss the popup if it is showing.
     */
    fun dismiss() {
        popupWindow?.dismiss()
        popupWindow = null
    }

    private fun bind(
        content: View,
        viewId: Int,
        action: GroupViewModel.NodeAction,
        visible: Boolean,
        onAction: (GroupViewModel.NodeAction) -> Unit
    ) {
        content.findViewById<TextView>(viewId).apply {
            this.visibility = if (visible) View.VISIBLE else View.GONE
            setOnClickListener {
                dismiss()
                onAction(action)
            }
        }
    }
}
