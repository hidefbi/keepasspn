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
import com.keepasspn.R
import com.keepasspn.database.ContextualDatabase
import com.keepasspn.database.element.CustomDataItem
import com.keepasspn.database.element.Entry
import com.keepasspn.database.element.Group
import com.keepasspn.hardware.HardwareKey
import com.keepasspn.model.EntryInfo
import com.keepasspn.model.GroupInfo
import com.keepasspn.model.OtpModel
import com.keepasspn.otp.TotpSummary
import com.keepasspn.utils.NoOpLog

/**
 * Adds one-time password accounts to the database, in a dedicated import group.
 *
 * The group is found with its custom data marker, or created at the root of the database,
 * in second position. Its name is the default import name, which the user can change.
 * Accounts already present (same issuer, name and secret) are skipped.
 */
class AddOtpEntriesRunnable(
    context: Context,
    database: ContextualDatabase,
    otpModels: List<OtpModel>,
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

    private val mImportGroupTitle: String = context.getString(R.string.totp_import_group_title)
    private var mRoot: Group? = null
    private var mGroup: Group? = null
    private var mNewGroup: Group? = null
    private val mNewEntries = mutableListOf<Entry>()

    init {
        mRoot = database.rootGroup
        if (mRoot == null) {
            NoOpLog.w(TAG, "Unable to retrieve the root group to import codes")
        } else {
            mGroup = findTotpGroup(database)
            if (mGroup == null) {
                mNewGroup = database.createGroup(buildTotpGroupInfo(mImportGroupTitle))
                mGroup = mNewGroup
            }
            val knownAccounts = buildKnownAccounts(database)
            otpModels.forEach { otpModel ->
                if (knownAccounts.add(accountKey(otpModel))) {
                    val entryInfo = EntryInfo()
                    entryInfo.title = otpModel.issuer.ifEmpty { otpModel.name }
                    entryInfo.username = if (otpModel.issuer.isNotEmpty()) otpModel.name else ""
                    entryInfo.otpModel = otpModel
                    database.createEntry(entryInfo)?.let { mNewEntries.add(it) }
                }
            }
        }
    }

    override fun nodeAction() {
        mNewGroup?.let { newGroup ->
            newGroup.touch(modified = true, touchParents = true)
            mRoot?.let { root -> database.addGroupTo(newGroup, root) }
            // First group of the root; the virtual TOTP Summary folder is displayed above it
            mRoot?.let { root -> root.moveChildGroupToIndex(newGroup, 0) }
        }
        mGroup?.let { group ->
            mNewEntries.forEach { entry ->
                entry.touch(modified = true, touchParents = true)
                database.addEntryTo(entry, group)
            }
        }
    }

    override fun nodeFinish(): ActionNodesValues {
        if (!result.isSuccess) {
            mGroup?.let { group ->
                mNewEntries.forEach { entry ->
                    database.removeEntryFrom(entry, group)
                }
            }
            mNewGroup?.let { newGroup ->
                mRoot?.let { root -> database.removeGroupFrom(newGroup, root) }
            }
        }
        return ActionNodesValues(
            newEntriesIds = mNewEntries.map { it.nodeId },
            newGroupsIds = mNewGroup?.nodeId?.let { listOf(it) }
        )
    }

    companion object {
        private val TAG = AddOtpEntriesRunnable::class.simpleName

        /**
         * Custom data key that marks the group holding the imported codes
         */
        const val TOTP_GROUP_MARKER_KEY = TotpSummary.IMPORT_GROUP_MARKER_KEY
        private fun buildTotpGroupInfo(title: String): GroupInfo {
            return GroupInfo().apply {
                this.title = title
                customData.put(CustomDataItem(TOTP_GROUP_MARKER_KEY, "true"))
            }
        }

        private fun findTotpGroup(database: ContextualDatabase): Group? {
            return database.rootGroup?.getChildGroups()?.firstOrNull { group ->
                TotpSummary.isImportGroup(group)
            }
        }

        private fun buildKnownAccounts(database: ContextualDatabase): MutableSet<String> {
            val keys = mutableSetOf<String>()
            database.getEntryInfosWithOtp().forEach { entryInfo ->
                entryInfo.otpModel?.let { otpModel ->
                    keys.add(accountKey(otpModel))
                }
            }
            return keys
        }

        private fun accountKey(otpModel: OtpModel): String {
            val secretHex = otpModel.secret?.joinToString("") { "%02x".format(it) } ?: ""
            return listOf(otpModel.issuer, otpModel.name, secretHex).joinToString("\u0000")
        }
    }
}
