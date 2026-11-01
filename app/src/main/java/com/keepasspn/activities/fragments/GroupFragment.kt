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
package com.keepasspn.activities.fragments

import android.os.Bundle
import com.keepasspn.otp.TotpSummary
import com.keepasspn.utils.NoOpLog
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.SimpleItemAnimator
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.abs
import android.graphics.Color
import android.graphics.Paint
import com.keepasspn.R
import com.keepasspn.adapters.NodesAdapter
import com.keepasspn.credentialprovider.EntrySelectionHelper.retrieveSpecialMode
import com.keepasspn.credentialprovider.SpecialMode
import com.keepasspn.database.ContextualDatabase
import com.keepasspn.database.element.SortNodeEnum
import com.keepasspn.model.GroupInfo
import com.keepasspn.model.SearchGroupInfo
import com.keepasspn.activities.dialogs.NodeActionsPopup
import com.keepasspn.model.SortedNodeInfo
import com.keepasspn.viewmodels.GroupViewModel
import kotlinx.coroutines.launch

class GroupFragment : DatabaseFragment() {

    private var mNodesRecyclerView: RecyclerView? = null
    private var mLayoutManager: LinearLayoutManager? = null

    private var mAdapter: NodesAdapter? = null
    private var mNodeTouchHelper: ItemTouchHelper? = null
    private val mNodeTouchCallback = NodeTouchCallback()
    private var mNodeActionsPopup: NodeActionsPopup? = null
    private var mLongPressNode: SortedNodeInfo? = null
    private var mLongPressActive = false
    private var mDragging = false
    private var mTouchDownX = 0f
    private var mTouchDownY = 0f
    private val mTouchSlop: Int by lazy { ViewConfiguration.get(requireContext()).scaledTouchSlop }

    private val mGroupViewModel: GroupViewModel by activityViewModels()

    private var specialMode: SpecialMode = SpecialMode.DEFAULT

    private var mRecycleViewScrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
            super.onScrollStateChanged(recyclerView, newState)
            if (newState == SCROLL_STATE_IDLE) {
                val firstPosition = mLayoutManager?.findFirstVisibleItemPosition() ?: 0
                val firstView = mLayoutManager?.findViewByPosition(firstPosition)
                val offset = firstView?.top ?: 0
                mGroupViewModel.assignPosition(firstPosition, offset)
            }
        }
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            super.onScrolled(recyclerView, dx, dy)
            mGroupViewModel.scrollTo(dy)
        }
    }

    /**
     * Show the actions of [node] next to its row, after a long press.
     */
    fun showNodeActions(
        node: SortedNodeInfo,
        title: String,
        actions: NodeActionsPopup.Actions
    ) {
        val adapter = mAdapter ?: return
        val recyclerView = mNodesRecyclerView ?: return
        val position = adapter.positionOf(node)
        val holder = recyclerView.findViewHolderForAdapterPosition(position)
            ?: return
        adapter.setPressedNode(node)
        mLongPressNode = node
        mLongPressActive = true
        holder.itemView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val popup = mNodeActionsPopup ?: NodeActionsPopup(holder.itemView.context).also {
            mNodeActionsPopup = it
        }
        popup.show(
            anchor = holder.itemView,
            title = title,
            actions = actions,
            onAction = { action -> mGroupViewModel.onNodeAction(action) },
            onDismiss = { if (!mDragging) adapter.setPressedNode(null) }
        )
    }

    /**
     * Drags a node among the nodes of its kind. The rows make room while the dragged row moves
     * and the new order is saved when the row is released.
     */
    private inner class NodeTouchCallback : ItemTouchHelper.SimpleCallback(0, 0) {

        private var mDragStartPosition = RecyclerView.NO_POSITION

        // Long press opens the actions popup, the drag starts when the finger moves
        override fun isLongPressDragEnabled(): Boolean = false

        override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
            val adapter = mAdapter ?: return 0
            val position = viewHolder.adapterPosition
            if (position == RecyclerView.NO_POSITION) return 0
            return if (canReorder(adapter.nodeAt(position))) {
                makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
            } else {
                0
            }
        }

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean {
            val adapter = mAdapter ?: return false
            val from = viewHolder.adapterPosition
            val to = target.adapterPosition
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
            val other = adapter.nodeAt(to)
            // Only neighbours of the same kind are swapped, pinned nodes are never passed
            if (!adapter.sameKind(adapter.nodeAt(from), other) || !mGroupViewModel.isMovable(other)) {
                return false
            }
            adapter.moveDisplayed(from, to)
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            // Swipe is disabled, nodes are removed only through the confirmation dialog
        }

        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(viewHolder, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                mDragStartPosition = viewHolder.adapterPosition
                mAdapter?.beginDisplayOrder()
            }
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            mDragging = false
            val adapter = mAdapter ?: return
            val startPosition = mDragStartPosition
            mDragStartPosition = RecyclerView.NO_POSITION
            val finalPosition = viewHolder.adapterPosition
            if (startPosition == RecyclerView.NO_POSITION
                || finalPosition == RecyclerView.NO_POSITION
                || finalPosition == startPosition) {
                adapter.cancelDisplayOrder()
            } else {
                val node = adapter.nodeAt(finalPosition)
                val listedIndex = adapter.listedIndexAt(finalPosition)
                adapter.commitDisplayOrder()
                mGroupViewModel.onReorderNode(node, listedIndex)
            }
            adapter.setPressedNode(null)
        }
    }

    /**
     * Whether the node can be dragged to another position right now.
     */
    private fun canReorder(node: SortedNodeInfo): Boolean {
        val adapter = mAdapter ?: return false
        val allowed = if (node is GroupInfo && !TotpSummary.isSummary(node)) {
            adapter.isGroupReorderAllowed()
        } else {
            adapter.isNodeReorderAllowed()
        }
        return allowed && mGroupViewModel.isMovable(node)
    }

    /**
     * Watches the touch stream after a long press. Once the popup is open, a move beyond the
     * touch slop closes it and starts dragging the row under the finger.
     */
    private val mLongPressTouchListener = object : RecyclerView.OnItemTouchListener {
        override fun onInterceptTouchEvent(recyclerView: RecyclerView, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    mLongPressActive = false
                    mTouchDownX = event.x
                    mTouchDownY = event.y
                }
                MotionEvent.ACTION_MOVE -> {
                    val moved = abs(event.x - mTouchDownX) > mTouchSlop
                            || abs(event.y - mTouchDownY) > mTouchSlop
                    if (mLongPressActive && moved) {
                        mLongPressActive = false
                        startDragAfterLongPress(recyclerView)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    mLongPressActive = false
                }
            }
            return false
        }

        override fun onTouchEvent(recyclerView: RecyclerView, event: MotionEvent) {}

        override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
    }

    private fun startDragAfterLongPress(recyclerView: RecyclerView) {
        val node = mLongPressNode ?: return
        val adapter = mAdapter ?: return
        mLongPressNode = null
        val canDrag = canReorder(node)
        // The highlight stays on the row while it is dragged
        mDragging = canDrag
        mNodeActionsPopup?.dismiss()
        if (!canDrag) return
        val holder = recyclerView.findViewHolderForAdapterPosition(adapter.positionOf(node))
        if (holder == null) {
            mDragging = false
            adapter.setPressedNode(null)
            return
        }
        mNodeTouchHelper?.startDrag(holder)
    }

    override fun onDatabaseRetrieved(database: ContextualDatabase) {
        context?.let { context ->
            mAdapter = NodesAdapter(
                context,
                database.iconDrawableFactory,
                SortNodeEnum.SortDatabaseParameters(
                    recycleBinEnabled = database.isRecycleBinEnabled,
                    recycleBinId = database.recycleBin?.nodeId
                )
            ).apply {
                setOnNodeClickListener(object : NodesAdapter.NodeClickCallback {
                    override fun onNodeClick(node: SortedNodeInfo) {
                        mGroupViewModel.performNodeClick(node)
                    }
                    override fun onNodeLongClick(node: SortedNodeInfo): Boolean {
                        if (specialMode == SpecialMode.DEFAULT) {
                            mGroupViewModel.onNodeLongClick(node, mAdapter?.isNodeReorderAllowed() == true)
                        } else {
                            // Autofill, passkey or other special flows keep the selection behaviour
                            mGroupViewModel.performLongNodeClick(node)
                        }
                        return true
                    }
                    override fun onGroupReorder(node: SortedNodeInfo, targetIndex: Int) {
                        mGroupViewModel.onReorderGroup(node, targetIndex)
                    }
                })
                setActionNodes(mGroupViewModel.actionsNodes.value)
            }
            mNodesRecyclerView?.adapter = mAdapter
            mNodesRecyclerView?.let { recyclerView ->
                if (mNodeTouchHelper == null) {
                    mNodeTouchHelper = ItemTouchHelper(mNodeTouchCallback).also {
                        it.attachToRecyclerView(recyclerView)
                    }
                }
            }
            mAdapter?.onDragHandleStart = { holder -> mNodeTouchHelper?.startDrag(holder) }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        super.onCreateView(inflater, container, savedInstanceState)
        // To apply theme
        return inflater.inflate(R.layout.fragment_nodes, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        mNodesRecyclerView = view.findViewById(R.id.nodes_list)
        mNodesRecyclerView?.addOnItemTouchListener(mLongPressTouchListener)
        (mNodesRecyclerView?.itemAnimator as? SimpleItemAnimator)?.moveDuration = MOVE_ANIMATION_DURATION_MS

        mLayoutManager = LinearLayoutManager(context)
        mNodesRecyclerView?.apply {
            scrollBarStyle = View.SCROLLBARS_INSIDE_INSET
            layoutManager = mLayoutManager
            adapter = mAdapter
        }
        resetAppTimeoutWhenViewFocusedOrChanged(view)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    mGroupViewModel.groupUIState.collect { groupUIState ->
                        try {
                            groupUIState.group?.let { currentGroup ->
                                val isSearchInfo = currentGroup is SearchGroupInfo
                                val children = groupUIState.children
                                mAdapter?.showAllOtpCodes = currentGroup.nodeId == TotpSummary.NODE_ID
                                if (children != null) {
                                    mAdapter?.rebuildList(
                                        nodes = children,
                                        isSearch = isSearchInfo
                                    )
                                }
                                // Direct action node selection after rebuild
                                mAdapter?.setActionNodes(mGroupViewModel.actionsNodes.value)
                            }
                        } catch (e:Exception) {
                            NoOpLog.e(TAG, "Unable to rebuild the list", e)
                        }
                    }
                }
                launch {
                    mGroupViewModel.viewEvent.collect { event ->
                        when (event) {
                            is GroupViewModel.GroupEvent.ShowPosition -> {
                                mNodesRecyclerView?.post {
                                    mLayoutManager?.scrollToPositionWithOffset(event.position, event.offset)
                                }
                            }
                            is GroupViewModel.GroupEvent.RemoveNodeAction -> {
                                mAdapter?.unselectActionNodes()
                            }
                            is GroupViewModel.GroupEvent.SortSelected -> {
                                // Tell the main group adapter to refresh its list
                                try {
                                    mAdapter?.notifyChangeSort(
                                        sortNodeEnum = event.sortNode.sortNodeEnum,
                                        sortNodeParameters = event.sortNode.sortNodeParameters,
                                        sortDatabaseParameters = event.sortNode.sortDatabaseParameters
                                    )
                                    mGroupViewModel.loadGroup()
                                } catch (e: Exception) {
                                    NoOpLog.e(TAG, "Unable to sort the list", e)
                                }
                            }
                            else -> {}
                        }
                    }
                }
                launch {
                    mGroupViewModel.actionsNodes.collect { actionsNodes ->
                        mAdapter?.setActionNodes(actionsNodes)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()

        mNodesRecyclerView?.addOnScrollListener(mRecycleViewScrollListener)
        activity?.intent?.let {
            specialMode = it.retrieveSpecialMode()
        }
    }

    override fun onPause() {
        mNodeActionsPopup?.dismiss()

        mNodesRecyclerView?.removeOnScrollListener(mRecycleViewScrollListener)
        super.onPause()
    }

    companion object {
        // Short moves keep the rows responsive while a dragged row makes room
        private const val MOVE_ANIMATION_DURATION_MS = 150L

        private val TAG = GroupFragment::class.java.name
    }
}
