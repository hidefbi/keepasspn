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
import com.keepasspn.utils.NoOpLog
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
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.roundToInt
import com.keepasspn.R
import com.keepasspn.adapters.NodesAdapter
import com.keepasspn.credentialprovider.EntrySelectionHelper.retrieveSpecialMode
import com.keepasspn.credentialprovider.SpecialMode
import com.keepasspn.database.ContextualDatabase
import com.keepasspn.database.element.SortNodeEnum
import com.keepasspn.model.SearchGroupInfo
import com.keepasspn.model.SortedNodeInfo
import com.keepasspn.viewmodels.GroupViewModel
import kotlinx.coroutines.launch

class GroupFragment : DatabaseFragment() {

    private var mNodesRecyclerView: RecyclerView? = null
    private var mLayoutManager: LinearLayoutManager? = null

    private var mAdapter: NodesAdapter? = null
    private var mNodeTouchHelper: ItemTouchHelper? = null
    private val mNodeTouchCallback = NodeTouchCallback()

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
     * Drags a group by its handle to change its position among the sibling groups.
     * Swiping a group to the left reveals a delete action, which uses the usual delete flow.
     */
    private inner class NodeTouchCallback : ItemTouchHelper.SimpleCallback(0, 0) {

        private var mDragStartPosition = RecyclerView.NO_POSITION
        private var mDragDistance = 0f
        private val mDeleteBackgroundPaint by lazy {
            Paint().apply { color = Color.rgb(0xD3, 0x2F, 0x2F) }
        }
        private val mDeleteTextPaint by lazy {
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 16f * resources.displayMetrics.scaledDensity
                textAlign = Paint.Align.RIGHT
            }
        }

        override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
            val adapter = mAdapter ?: return 0
            val position = viewHolder.adapterPosition
            if (position == RecyclerView.NO_POSITION || !adapter.isGroupAt(position)) return 0
            val dragDirections = if (adapter.isGroupReorderAllowed()) {
                ItemTouchHelper.UP or ItemTouchHelper.DOWN
            } else {
                0
            }
            return makeMovementFlags(dragDirections, ItemTouchHelper.LEFT)
        }

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean {
            // The list is not reordered while dragging, the new position is applied on drop
            return false
        }

        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(viewHolder, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                mDragStartPosition = viewHolder.adapterPosition
                mDragDistance = 0f
            }
        }

        override fun onChildDraw(
            canvas: Canvas,
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            dX: Float,
            dY: Float,
            actionState: Int,
            isCurrentlyActive: Boolean
        ) {
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                mDragDistance = dY
            } else if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && dX < 0) {
                val itemView = viewHolder.itemView
                canvas.drawRect(
                    itemView.left.toFloat(),
                    itemView.top.toFloat(),
                    itemView.right.toFloat(),
                    itemView.bottom.toFloat(),
                    mDeleteBackgroundPaint
                )
                val density = resources.displayMetrics.density
                val baseline = itemView.top + itemView.height / 2f -
                        (mDeleteTextPaint.descent() + mDeleteTextPaint.ascent()) / 2f
                canvas.drawText(
                    getString(R.string.menu_delete),
                    itemView.right - 24f * density,
                    baseline,
                    mDeleteTextPaint
                )
            }
            super.onChildDraw(canvas, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            val startPosition = mDragStartPosition
            val distance = mDragDistance
            mDragStartPosition = RecyclerView.NO_POSITION
            mDragDistance = 0f
            val adapter = mAdapter ?: return
            if (startPosition == RecyclerView.NO_POSITION || !adapter.isGroupAt(startPosition)) return
            val rowHeight = viewHolder.itemView.height
            if (rowHeight <= 0) return
            val rows = (distance / rowHeight).roundToInt()
            if (rows == 0) return
            val node = adapter.nodeAt(startPosition)
            val currentIndex = adapter.groupIndexOf(node)
            if (currentIndex < 0) return
            val targetIndex = (currentIndex + rows).coerceIn(0, adapter.groupCount() - 1)
            if (targetIndex != currentIndex) {
                mGroupViewModel.onReorderGroup(node, targetIndex)
            }
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            val adapter = mAdapter ?: return
            val position = viewHolder.adapterPosition
            if (position == RecyclerView.NO_POSITION) return
            val node = adapter.nodeAt(position)
            // Same selection and delete flow as the action bar, with its confirmation
            mGroupViewModel.performLongNodeClick(node)
            mGroupViewModel.onDeleteNodesClicked()
            adapter.notifyItemChanged(position)
        }
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
                        mGroupViewModel.performLongNodeClick(node)
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

        mNodesRecyclerView?.removeOnScrollListener(mRecycleViewScrollListener)
        super.onPause()
    }

    companion object {
        private val TAG = GroupFragment::class.java.name
    }
}
