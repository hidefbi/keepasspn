/*
 * Copyright 2021 Jeremy Jamet / Kunzisoft.
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
package com.keepasspn.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.keepasspn.database.ContextualDatabase
import com.keepasspn.database.element.GroupId
import com.keepasspn.database.element.SortNodeEnum
import com.keepasspn.database.element.node.DefaultNodeFilter
import com.keepasspn.database.element.node.EmptyNodeFilter
import com.keepasspn.database.element.node.NodeFilter
import com.keepasspn.database.element.node.Nodes
import com.keepasspn.model.EntryInfo
import com.keepasspn.model.GroupInfo
import com.keepasspn.model.NodeInfo
import com.keepasspn.model.SortedEntryInfo
import com.keepasspn.model.SortedGroupInfo
import com.keepasspn.model.SortedNodeInfo
import com.keepasspn.R
import com.keepasspn.model.toNodes
import com.keepasspn.otp.TotpSummary
import com.keepasspn.settings.PreferencesUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


/**
 * ViewModel for managing groups.
 */
class GroupViewModel(application: Application): AndroidViewModel(application) {

    private var mDatabase: ContextualDatabase? = null

    // Manage Recycle Bin

    private var mRecyclingBinIsCurrentGroup = false

    private var mAddGroupOrEntryAllowed = false
    private val mAddNodeButtonAllowed: Boolean
        get() = mAddGroupOrEntryAllowed && !actionNodeInProgress()

    // Main group currently in memory
    var mainGroup: GroupInfo? = null
        private set

    // Group state to retrieve position, not a search
    private var mMainGroupState: GroupState? = null
    // Previous groups states to retrieve history and position
    private var mPreviousGroupsStates = mutableListOf<GroupState>()

    // Group currently visible (search or main group)
    val isCurrentGroupIsRoot: Boolean
        get() = groupUIState.value.group?.isRoot(mDatabase) == true

    /*
     * Manage action in group
     */
    private val _nodeActionState = MutableStateFlow(NodeActionState())
    val nodeActionState: StateFlow<NodeActionState> = _nodeActionState.asStateFlow()

    // Derived StateFlow from nodeActionState.selectedNodes to keep sync
    val actionsNodes: StateFlow<List<SortedNodeInfo>> = nodeActionState
        .map { it.selectedNodes }
        .stateIn(viewModelScope, SharingStarted.Lazily, listOf())

    // Node long pressed, used by the node actions sheet
    private var mPendingNodeAction: SortedNodeInfo? = null

    private val _groupUIState = MutableStateFlow<GroupUISTate>(GroupUISTate())
    val groupUIState: StateFlow<GroupUISTate> = _groupUIState.asStateFlow()

    private val _viewEvent = MutableSharedFlow<GroupEvent>(replay = 0)
    val viewEvent: SharedFlow<GroupEvent> = _viewEvent.asSharedFlow()

    private var mAutoFocusSearch: Boolean = false
    private var mRecursiveNumberEntries: Boolean = false
    private var mShowExpiredEntries: Boolean = false
    private var mShowTemplates: Boolean = false
    private var mNodeFilter: NodeFilter = EmptyNodeFilter()

    fun assignPreferences() {
        mAutoFocusSearch = PreferencesUtil.automaticallyFocusSearch(getApplication())
        mRecursiveNumberEntries = PreferencesUtil.recursiveNumberEntries(getApplication())
        mShowExpiredEntries = PreferencesUtil.showExpiredEntries(getApplication())
        mShowTemplates = PreferencesUtil.showTemplates(getApplication())
    }

    fun onDatabaseLoaded(database: ContextualDatabase) {
        this.mDatabase = database
        this.mNodeFilter = DefaultNodeFilter(
            database = mDatabase,
            showExpired = mShowExpiredEntries,
            showTemplates = mShowTemplates
        )
    }

    fun loadGroup() {
        loadMainGroup()
    }

    private fun loadMainGroup(
        groupState: GroupState? = mMainGroupState
    ) {
        viewModelScope.launch {
            mDatabase?.let { database ->
                _groupUIState.update { groupState ->
                    groupState.copy(loaded = false)
                }
                withContext(Dispatchers.IO) {
                    val groupId = groupState?.groupId
                    val showFromPosition = groupState?.firstVisibleItem
                    val showFromOffset = groupState?.firstVisibleItemOffset ?: 0
                    // The TOTP Summary folder is virtual: its children are the entries with a TOTP
                    val summaryNode = if (groupId == TotpSummary.NODE_ID) {
                        TotpSummary.buildNode(
                            title = PreferencesUtil.getTotpSummaryTitle(getApplication()),
                            numberOfEntries = database.getEntryInfosWithOtp().size
                        )
                    } else {
                        null
                    }
                    val group: GroupInfo? = summaryNode
                        ?: if (groupId != null) database.getGroupInfoById(groupId) else database.getRootGroupInfo()
                    if (group != null) {
                        mRecyclingBinIsCurrentGroup = summaryNode == null && group.isRecycleBin(database)
                        mainGroup = group
                        // Save group state
                        mMainGroupState = GroupState(group.nodeId, showFromPosition, showFromOffset)
                        // Breadcrumbs
                        val breadcrumbs = if (summaryNode != null) {
                            listOf(summaryNode)
                        } else {
                            mDatabase?.getBreadcrumbsFrom(
                                groupId = group.nodeId,
                                recursiveNumberOfEntries = mRecursiveNumberEntries,
                                nodeFilter = mNodeFilter
                            ) ?: listOf()
                        }
                        // Children
                        val isRootGroup = summaryNode == null && group.nodeId == database.rootGroup?.nodeId
                        val children: List<SortedNodeInfo> = if (summaryNode != null) {
                            database.getEntryInfosWithOtpAndGroup().map { (entryInfo, groupTitle) ->
                                SortedEntryInfo(
                                    entryToCopy = entryInfo,
                                    path = getApplication<Application>().getString(
                                        R.string.totp_summary_belongs_to, groupTitle
                                    ),
                                    isReferenced = false
                                )
                            }
                        } else {
                            val sortedChildren: List<SortedNodeInfo> = mDatabase?.getSortedChildrenOf(
                                parentId = group.nodeId,
                                recursiveNumberOfEntries = mRecursiveNumberEntries,
                                nodeFilter = mNodeFilter
                            ) ?: listOf()
                            if (isRootGroup && !mRecyclingBinIsCurrentGroup) {
                                // The virtual TOTP Summary folder is always on top of the root group
                                listOf(
                                    TotpSummary.buildNode(
                                        title = PreferencesUtil.getTotpSummaryTitle(getApplication()),
                                        numberOfEntries = database.getEntryInfosWithOtp().size
                                    )
                                ) + sortedChildren
                            } else {
                                sortedChildren
                            }
                        }
                        // To enable add buttons, never inside the virtual folder
                        val addGroupEnabled = summaryNode == null && database.allowAddGroupIn(group = group)
                        val addEntryEnabled = summaryNode == null && database.allowAddEntryIn(group = group)
                        mAddGroupOrEntryAllowed = addGroupEnabled || addEntryEnabled
                        withContext(Dispatchers.Main) {
                            _groupUIState.update { groupState ->
                                groupState.copy(
                                    loaded = true,
                                    breadcrumbs = breadcrumbs,
                                    group = group,
                                    children = children,
                                    showAddGroupButton = addGroupEnabled,
                                    showAddEntryButton = addEntryEnabled,
                                    showAddNodeButton = mAddNodeButtonAllowed
                                )
                            }
                            showFromPosition?.let {
                                _viewEvent.emit(GroupEvent.ShowPosition(showFromPosition, showFromOffset))
                            }
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            loadPreviousGroup()
                        }
                    }
                }
            }
        }
    }

    fun loadGroup(
        groupId: GroupId?
    ) {
        // Save the last not virtual group and it's position
        mMainGroupState?.let {
            mPreviousGroupsStates.add(it)
        }
        loadMainGroup(GroupState(groupId, firstVisibleItem = 0))
    }

    fun onSortSelected(
        sortNode: SortNodeEnum? = null,
        sortNodeParameters: SortNodeEnum.SortNodeParameters? = null
    ) {
        PreferencesUtil.saveNodeSort(
            context = getApplication(),
            sortNodeEnum = sortNode,
            sortNodeParameters = sortNodeParameters
        )
        viewModelScope.launch {
            val sortDatabaseParameters = mDatabase?.let { database ->
                SortNodeEnum.SortDatabaseParameters(
                    recycleBinEnabled = database.isRecycleBinEnabled,
                    recycleBinId = database.recycleBin?.nodeId
                )
            }
            _viewEvent.emit(GroupEvent.SortSelected(SortNode(
                sortNodeEnum = sortNode,
                sortNodeParameters = sortNodeParameters,
                sortDatabaseParameters = sortDatabaseParameters
            )))
        }
    }

    fun assignPosition(
        position: Int = 0,
        offset: Int = 0
    ) {
        mMainGroupState?.let {
            it.firstVisibleItem = position
            it.firstVisibleItemOffset = offset
        }
    }

    /*
     * Breadcrumbs
     */

    fun onBreadcrumbClicked(group: SortedGroupInfo) {
        viewModelScope.launch {
            // If last item & not a virtual root group
            val currentGroup = mainGroup
            if (currentGroup != null && group.nodeId == currentGroup.nodeId
                && currentGroup.nodeId != TotpSummary.NODE_ID
                && (currentGroup.nodeId != mDatabase?.rootGroup?.nodeId
                        || mDatabase?.rootGroupIsVirtual != true)
            ) {
                finishNodeAction()
                _viewEvent.emit(GroupEvent.ShowGroup(currentGroup))
            } else {
                when (nodeActionState.value.nodeActionMode) {
                    NodeActionMode.SELECTION -> finishNodeAction()
                    else -> {}
                }
                _viewEvent.emit(GroupEvent.OpenGroup(group.nodeId))
            }
        }
    }

    fun onBreadcrumbLongClicked(group: SortedGroupInfo) {
        viewModelScope.launch {
            val currentGroup = mainGroup
            if (mDatabase?.isReadOnly == false
                && currentGroup != null
                && currentGroup.nodeId != TotpSummary.NODE_ID
                && group.nodeId == currentGroup.nodeId
                && (mDatabase?.rootGroupIsVirtual != true
                        || currentGroup.nodeId != mDatabase?.rootGroup?.nodeId)
            ) {
                finishNodeAction()
                _viewEvent.emit(GroupEvent.EditGroup(currentGroup))
            } else {
                onBreadcrumbClicked(group)
            }
        }
    }

    /*
     * Actions
     */

    fun performNodeClick(node: SortedNodeInfo) {
        viewModelScope.launch {
            val state = nodeActionState.value
            if (TotpSummary.isSummary(node) && state.nodeActionMode != null) {
                return@launch
            }
            when (state.nodeActionMode) {
                NodeActionMode.SELECTION -> {
                    val selectedNodes = state.selectedNodes.toMutableList()
                    val existingNode = selectedNodes.find { it.nodeId == node.nodeId }
                    if (existingNode != null) {
                        // Remove selected item if already selected
                        selectedNodes.remove(existingNode)
                    } else {
                        // Add selected item if not already selected
                        selectedNodes.add(node)
                    }
                    selectNodes(selectedNodes)
                }
                NodeActionMode.PASTE_FROM_COPY,
                NodeActionMode.PASTE_FROM_MOVE -> {
                    when (node) {
                        is SortedGroupInfo -> _viewEvent.emit(GroupEvent.OpenGroup(node.nodeId))
                        else -> {}
                    }
                }
                null -> {
                    when (node) {
                        is SortedGroupInfo -> _viewEvent.emit(GroupEvent.OpenGroup(node.nodeId))
                        is SortedEntryInfo -> _viewEvent.emit(GroupEvent.OpenEntry(node))
                    }
                }
            }
        }
    }

    fun performLongNodeClick(node: SortedNodeInfo): Boolean {
        if (TotpSummary.isSummary(node)) return true
        viewModelScope.launch {
            val state = nodeActionState.value
            when (state.nodeActionMode) {
                NodeActionMode.SELECTION,
                NodeActionMode.PASTE_FROM_COPY,
                NodeActionMode.PASTE_FROM_MOVE -> {
                    performNodeClick(node)
                }
                null -> {
                    // Select the first item after a long click
                    val selectedNodes = state.selectedNodes.toMutableList()
                    if (selectedNodes.none { it.nodeId == node.nodeId })
                        selectedNodes.add(node)
                    selectNodes(selectedNodes)
                    _viewEvent.emit(GroupEvent.HideKeyboard)
                }
            }
        }
        return true
    }

    fun selectNodes(nodes: List<SortedNodeInfo>): Boolean {
        if (nodes.isNotEmpty()) {
            _nodeActionState.update {
                it.copy(
                    isActive = true,
                    nodeActionMode = NodeActionMode.SELECTION,
                    selectedNodes = nodes
                )
            }
        } else {
            finishNodeAction()
        }
        return true
    }

    fun onOpenNodeClicked() {
        val nodes = _nodeActionState.value.selectedNodes
        if (nodes.isNotEmpty()) {
            finishNodeAction()
            viewModelScope.launch {
                when (val node = nodes[0]) {
                    is SortedGroupInfo -> _viewEvent.emit(GroupEvent.OpenGroup(node.nodeId))
                    is SortedEntryInfo -> _viewEvent.emit(GroupEvent.OpenEntry(node))
                }
            }
        }
    }

    fun onEditNodeClicked() {
        val nodes = _nodeActionState.value.selectedNodes
        if (nodes.isNotEmpty()) {
            finishNodeAction()
            viewModelScope.launch {
                when (val node = nodes[0]) {
                    is GroupInfo -> _viewEvent.emit(GroupEvent.EditGroup(node))
                    is EntryInfo -> _viewEvent.emit(GroupEvent.EditEntry(node))
                }
            }
        }
    }

    fun onCopyNodesClicked() {
        val nodes = _nodeActionState.value.selectedNodes
        if (nodes.isNotEmpty()) {
            _nodeActionState.update {
                it.copy(
                    nodeActionMode = NodeActionMode.PASTE_FROM_COPY
                )
            }
            viewModelScope.launch {
                _viewEvent.emit(GroupEvent.CopyNodes(nodes.toNodes()))
            }
        }
    }

    fun onMoveNodesClicked() {
        val nodes = _nodeActionState.value.selectedNodes
        if (nodes.isNotEmpty()) {
            _nodeActionState.update {
                it.copy(
                    nodeActionMode = NodeActionMode.PASTE_FROM_MOVE
                )
            }
            viewModelScope.launch {
                _viewEvent.emit(GroupEvent.MoveNodes(nodes.toNodes()))
            }
        }
    }

    /**
     * Requests a new position for a group among its sibling groups.
     */
    fun onReorderGroup(node: SortedNodeInfo, targetIndex: Int) {
        if (TotpSummary.isSummary(node)) return
        val groupId = (node as? GroupInfo)?.nodeId ?: return
        viewModelScope.launch {
            _viewEvent.emit(GroupEvent.ReorderGroup(groupId, targetIndex))
        }
    }

    /**
     * Long press on a node shows its actions in a bottom sheet.
     * @param reorderAllowed true when the database is sorted by its own (manual) order
     */
    fun onNodeLongClick(node: SortedNodeInfo, reorderAllowed: Boolean) {
        if (TotpSummary.isSummary(node)) {
            viewModelScope.launch {
                _viewEvent.emit(GroupEvent.RenameTotpSummary((node as? GroupInfo)?.title.orEmpty()))
            }
            return
        }
        mPendingNodeAction = node
        val siblings = siblingsOf(node)
        val index = siblings.indexOfFirst { it.nodeId == node.nodeId }
        val inSummary = mainGroup?.nodeId == TotpSummary.NODE_ID
        val canMoveUp = reorderAllowed && !inSummary && index > 0
        val canMoveDown = reorderAllowed && !inSummary && index >= 0 && index < siblings.size - 1
        viewModelScope.launch {
            _viewEvent.emit(GroupEvent.ShowNodeActions(node, canMoveUp, canMoveDown))
        }
    }

    fun onNodeAction(action: NodeAction) {
        val node = mPendingNodeAction ?: return
        mPendingNodeAction = null
        when (action) {
            NodeAction.EDIT -> viewModelScope.launch {
                when (node) {
                    is GroupInfo -> _viewEvent.emit(GroupEvent.EditGroup(node))
                    is EntryInfo -> _viewEvent.emit(GroupEvent.EditEntry(node))
                }
            }
            NodeAction.DUPLICATE -> viewModelScope.launch {
                _viewEvent.emit(GroupEvent.DuplicateNodes(listOf(node).toNodes()))
            }
            NodeAction.MOVE_UP, NodeAction.MOVE_DOWN -> {
                val siblings = siblingsOf(node)
                val index = siblings.indexOfFirst { it.nodeId == node.nodeId }
                val targetIndex = if (action == NodeAction.MOVE_UP) index - 1 else index + 1
                if (index >= 0 && targetIndex in siblings.indices) {
                    viewModelScope.launch {
                        _viewEvent.emit(GroupEvent.ReorderNode(node, targetIndex))
                    }
                }
            }
            NodeAction.MOVE -> viewModelScope.launch {
                _viewEvent.emit(GroupEvent.ChooseMoveDestination(node))
            }
            NodeAction.DELETE -> {
                selectNodes(listOf(node))
                onDeleteNodesClicked()
            }
            NodeAction.RESTORE -> viewModelScope.launch {
                _viewEvent.emit(GroupEvent.RestoreNodes(listOf(node).toNodes()))
            }
        }
    }

    /**
     * Enters selection mode without selecting anything, for the multi-selection menu item.
     */
    fun startNodeSelection() {
        _nodeActionState.update {
            it.copy(
                isActive = true,
                nodeActionMode = NodeActionMode.SELECTION,
                selectedNodes = emptyList()
            )
        }
    }

    /**
     * Nodes of the same kind (group or entry) as the given node, in display order.
     */
    private fun siblingsOf(node: SortedNodeInfo): List<SortedNodeInfo> {
        val isGroup = node is GroupInfo
        return groupUIState.value.children.orEmpty()
            .filter { !TotpSummary.isSummary(it) && (it is GroupInfo) == isGroup }
    }

    fun onDeleteNodesClicked() {
        val nodes = _nodeActionState.value.selectedNodes
        if (nodes.isNotEmpty()) {
            viewModelScope.launch {
                _viewEvent.emit(GroupEvent.DeleteNodes(DeleteActionState(
                    nodes = nodes.toNodes(),
                    isRecycleBin = false
                )))
            }
            finishNodeAction()
        }
    }

    fun onPasteNodesClicked() {
        val state = _nodeActionState.value
        mainGroup?.nodeId?.let { parentId ->
            viewModelScope.launch {
                state.nodeActionMode?.let { pasteMode ->
                    _viewEvent.emit(
                        GroupEvent.PasteNodes(
                            PasteActionState(
                                pasteMode,
                                parentId,
                                state.selectedNodes.toNodes()
                            )
                        )
                    )
                }
                _nodeActionState.update {
                    it.copy(
                        nodeActionMode = null
                    )
                }
            }
            finishNodeAction()
        }
    }

    fun onActionCreated() {
        _groupUIState.update { groupState ->
            groupState.copy(showAddNodeButton = false)
        }
    }

    fun onActionDestroyed() {
        viewModelScope.launch {
            _viewEvent.emit(GroupEvent.RemoveNodeAction)
        }
        clearNodeAction()
        _groupUIState.update { groupState ->
            groupState.copy(showAddNodeButton = mAddNodeButtonAllowed)
        }
    }

    fun actionNodeInProgress(): Boolean {
        return _nodeActionState.value.isActive
    }

    fun finishNodeAction() {
        _nodeActionState.update { it.copy(isActive = false) }
    }

    fun clearNodeAction() {
        _nodeActionState.value = NodeActionState()
    }

    fun databaseActionsAllowed(): Boolean = groupUIState.value.loaded
    fun saveDatabaseActionAllowed(): Boolean = mDatabase?.isReadOnly == false
    fun mergeDatabaseActionAllowed(): Boolean = saveDatabaseActionAllowed() && mDatabase?.isMergeDataAllowed() == true
    fun reloadDatabaseActionAllowed(): Boolean = true
    fun mergeFromDatabaseActionAllowed(): Boolean = mergeDatabaseActionAllowed()
    fun copyToDatabaseActionAllowed(): Boolean = true

    fun recycleBinActionsAllowed(): Boolean = saveDatabaseActionAllowed()
            && mDatabase?.isRecycleBinEnabled == true && mRecyclingBinIsCurrentGroup

    fun emptyRecycleBin() {
        viewModelScope.launch {
            if (recycleBinActionsAllowed()) {
                val nodesToDelete = withContext(Dispatchers.IO) {
                    mDatabase?.getNodesInRecycleBin()
                }
                // Automatically delete all elements
                if (nodesToDelete != null && !nodesToDelete.isEmpty()) {
                    _viewEvent.emit(
                        GroupEvent.DeleteNodes(
                            DeleteActionState(
                                nodes = nodesToDelete,
                                isRecycleBin = true,
                            )
                        )
                    )
                }
                finishNodeAction()
            }
        }
    }

    fun containsRecycleBin(
        database: ContextualDatabase?,
        nodes: List<NodeInfo>
    ): Boolean {
        return nodes.any { it is GroupInfo && it.isRecycleBin(database) }
    }

    fun previousGroupExists(): Boolean = mPreviousGroupsStates.isNotEmpty()

    fun loadPreviousGroup() {
        if (previousGroupExists()) {
            return loadMainGroup(
                groupState = mPreviousGroupsStates.removeAt(mPreviousGroupsStates.lastIndex)
            )
        }
    }

    fun scrollTo(dy: Int) {
        viewModelScope.launch {
            if (!actionNodeInProgress())
                _viewEvent.emit(GroupEvent.ScrollTo(dy))
        }
    }

    fun hideKeyboard() {
        viewModelScope.launch {
            _viewEvent.emit(GroupEvent.HideKeyboard)
        }
    }

    data class NodeActionState(
        val isActive: Boolean = false,
        val nodeActionMode: NodeActionMode? = null,
        val selectedNodes: List<SortedNodeInfo> = emptyList()
    )

    sealed class GroupEvent {
        object RemoveNodeAction : GroupEvent()
        data class OpenGroup(val groupId: GroupId) : GroupEvent()
        data class OpenEntry(val entry: EntryInfo) : GroupEvent()
        data class EditGroup(val group: GroupInfo) : GroupEvent()
        data class EditEntry(val entry: EntryInfo) : GroupEvent()
        data class CopyNodes(val nodes: Nodes) : GroupEvent()
        data class MoveNodes(val nodes: Nodes) : GroupEvent()
        data class DeleteNodes(val deleteActionState: DeleteActionState) : GroupEvent()
        data class ReorderGroup(val groupId: GroupId, val targetIndex: Int) : GroupEvent()
        data class PasteNodes(val pasteActionState: PasteActionState) : GroupEvent()
        data class ShowGroup(val group: GroupInfo) : GroupEvent()
        object HideKeyboard : GroupEvent()
        data class ShowPosition(val position: Int, val offset: Int) : GroupEvent()
        data class ScrollTo(val dy: Int) : GroupEvent()
        data class SortSelected(val sortNode: SortNode) : GroupEvent()
        data class ShowNodeActions(
            val node: SortedNodeInfo,
            val canMoveUp: Boolean,
            val canMoveDown: Boolean
        ) : GroupEvent()
        data class DuplicateNodes(val nodes: Nodes) : GroupEvent()
        data class ReorderNode(val node: SortedNodeInfo, val targetIndex: Int) : GroupEvent()
        data class RenameTotpSummary(val currentTitle: String) : GroupEvent()
        data class RestoreNodes(val nodes: Nodes) : GroupEvent()
        data class ChooseMoveDestination(val node: SortedNodeInfo) : GroupEvent()
        object ClearSearch : GroupEvent()
    }

    data class DeleteActionState(
        val nodes: Nodes,
        val isRecycleBin: Boolean
    )

    data class GroupUISTate(
        val loaded: Boolean = false,
        val breadcrumbs: List<SortedGroupInfo>? = null,
        val group: GroupInfo? = null,
        val children: List<SortedNodeInfo>? = null,
        val showAddNodeButton: Boolean = false,
        val showAddGroupButton: Boolean = false,
        val showAddEntryButton: Boolean = false
    )

    data class GroupState(
        var groupId: GroupId?,
        var firstVisibleItem: Int?,
        var firstVisibleItemOffset: Int = 0
    )

    data class PasteActionState(
        val pasteMode: NodeActionMode,
        val parentId: GroupId,
        val nodes: Nodes
    )

    data class SortNode(
        val sortNodeEnum: SortNodeEnum? = null,
        val sortNodeParameters: SortNodeEnum.SortNodeParameters? = null,
        val sortDatabaseParameters: SortNodeEnum.SortDatabaseParameters? = null
    )

    enum class NodeAction {
        EDIT, DUPLICATE, MOVE, MOVE_UP, MOVE_DOWN, DELETE, RESTORE
    }

    enum class NodeActionMode {
        SELECTION, PASTE_FROM_COPY, PASTE_FROM_MOVE
    }

    companion object {
        private val TAG = GroupViewModel::class.java.name
    }
}