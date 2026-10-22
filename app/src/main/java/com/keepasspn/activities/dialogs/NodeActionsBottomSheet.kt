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
package com.keepasspn.activities.dialogs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.keepasspn.R
import com.keepasspn.viewmodels.GroupViewModel

/**
 * Actions for a group or an entry, shown when the user long presses it.
 */
class NodeActionsBottomSheet : BottomSheetDialogFragment() {

    interface Listener {
        fun onNodeActionSelected(action: GroupViewModel.NodeAction)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.bottom_sheet_node_actions, container, false)
        val args = requireArguments()
        view.findViewById<TextView>(R.id.node_actions_title).text = args.getString(ARG_TITLE).orEmpty()
        bindAction(view, R.id.node_actions_edit, GroupViewModel.NodeAction.EDIT, true)
        bindAction(view, R.id.node_actions_duplicate, GroupViewModel.NodeAction.DUPLICATE, true)
        bindAction(view, R.id.node_actions_move, GroupViewModel.NodeAction.MOVE,
            args.getBoolean(ARG_CAN_MOVE))
        bindAction(view, R.id.node_actions_move_up, GroupViewModel.NodeAction.MOVE_UP,
            args.getBoolean(ARG_CAN_MOVE_UP))
        bindAction(view, R.id.node_actions_move_down, GroupViewModel.NodeAction.MOVE_DOWN,
            args.getBoolean(ARG_CAN_MOVE_DOWN))
        bindAction(view, R.id.node_actions_restore, GroupViewModel.NodeAction.RESTORE,
            args.getBoolean(ARG_CAN_RESTORE))
        bindAction(view, R.id.node_actions_delete, GroupViewModel.NodeAction.DELETE, true)
        return view
    }

    private fun bindAction(view: View, viewId: Int, action: GroupViewModel.NodeAction, visible: Boolean) {
        view.findViewById<TextView>(viewId).apply {
            this.visibility = if (visible) View.VISIBLE else View.GONE
            setOnClickListener {
                val listener = (parentFragment as? Listener) ?: (activity as? Listener)
                listener?.onNodeActionSelected(action)
                dismiss()
            }
        }
    }

    companion object {
        const val TAG = "nodeActionsBottomSheet"
        private const val ARG_TITLE = "ARG_TITLE"
        private const val ARG_CAN_MOVE_UP = "ARG_CAN_MOVE_UP"
        private const val ARG_CAN_MOVE_DOWN = "ARG_CAN_MOVE_DOWN"
        private const val ARG_CAN_RESTORE = "ARG_CAN_RESTORE"
        private const val ARG_CAN_MOVE = "ARG_CAN_MOVE"

        fun getInstance(
            title: String,
            canMoveUp: Boolean,
            canMoveDown: Boolean,
            canRestore: Boolean,
            canMove: Boolean = false
        ): NodeActionsBottomSheet {
            return NodeActionsBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_TITLE, title)
                    putBoolean(ARG_CAN_MOVE_UP, canMoveUp)
                    putBoolean(ARG_CAN_MOVE_DOWN, canMoveDown)
                    putBoolean(ARG_CAN_RESTORE, canRestore)
                    putBoolean(ARG_CAN_MOVE, canMove)
                }
            }
        }
    }
}
