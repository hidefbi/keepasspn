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

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.keepasspn.R
import com.keepasspn.database.element.node.Nodes
import com.keepasspn.viewmodels.NodesViewModel
import kotlinx.coroutines.launch

class DeleteNodesDialogFragment : DatabaseDialogFragment() {

    private var mNodesToDelete: Nodes = Nodes()
    private val mNodesViewModel: NodesViewModel by activityViewModels()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        lifecycleScope.launch {
            mNodesViewModel.nodesToDelete.collect { nodes ->
                this@DeleteNodesDialogFragment.mNodesToDelete = nodes
            }
        }
        var recycleBin = false
        var toRecycleBin = false
        arguments?.apply {
            if (containsKey(RECYCLE_BIN_TAG)) {
                recycleBin = this.getBoolean(RECYCLE_BIN_TAG)
            }
            if (containsKey(TO_RECYCLE_BIN_TAG)) {
                toRecycleBin = this.getBoolean(TO_RECYCLE_BIN_TAG)
            }
        }
        activity?.let { activity ->
            // Use the Builder class for convenient dialog construction
            val builder = AlertDialog.Builder(activity)

            builder.setMessage(when {
                toRecycleBin -> getString(R.string.warning_move_nodes_to_recycle_bin)
                recycleBin -> getString(R.string.warning_empty_recycle_bin)
                else -> getString(R.string.warning_permanently_delete_nodes)
            })
            builder.setPositiveButton(android.R.string.ok) { _, _ ->
                mNodesViewModel.permanentlyDeleteNodes(mNodesToDelete)
            }
            builder.setNegativeButton(android.R.string.cancel) { _, _ -> dismiss() }
            // Create the AlertDialog object and return it
            return builder.create()
        }
        return super.onCreateDialog(savedInstanceState)
    }

    companion object {
        private const val RECYCLE_BIN_TAG = "RECYCLE_BIN_TAG"
        private const val TO_RECYCLE_BIN_TAG = "TO_RECYCLE_BIN_TAG"

        fun getInstance(recycleBin: Boolean, toRecycleBin: Boolean): DeleteNodesDialogFragment {
            return DeleteNodesDialogFragment().apply {
                arguments = Bundle().apply {
                    putBoolean(RECYCLE_BIN_TAG, recycleBin)
                    putBoolean(TO_RECYCLE_BIN_TAG, toRecycleBin)
                }
            }
        }
    }
}
