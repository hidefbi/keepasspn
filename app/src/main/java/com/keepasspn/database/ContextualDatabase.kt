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
package com.keepasspn.database

import android.content.Context
import android.net.Uri
import com.keepasspn.utils.NoOpLog
import com.keepasspn.database.element.EntryId
import com.keepasspn.database.element.GroupId
import com.keepasspn.database.element.icon.IconImage
import com.keepasspn.database.element.icon.IconImageCustom
import com.keepasspn.database.element.icon.IconImageStandard
import com.keepasspn.icons.IconDrawableFactory
import com.keepasspn.model.DatabaseInfo
import com.keepasspn.model.EntryInfo
import com.keepasspn.model.RegisterInfo
import com.keepasspn.model.SnapFileDatabaseInfo
import com.keepasspn.utils.SingletonHolder
import com.keepasspn.viewmodels.FileDatabaseInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class ContextualDatabase: DatabaseInfo() {

    // Actual URI currently used to read/write the KDBX. This may be the
    // private recovery copy when the original document is unavailable.
    var fileUri: Uri? = null

    // Stable identity of the database. Unlike [fileUri], this never changes
    // when the app falls back to its private recovery copy.
    var logicalFileUri: Uri? = null

    // True when the current database was loaded from the private recovery copy.
    var loadedFromPrivateBackup: Boolean = false

    // Factory to draw icons
    val iconDrawableFactory = IconDrawableFactory(
        retrieveBinaryCache = { binaryCache },
        retrieveCustomIconBinary = { iconId -> getBinaryForCustomIcon(iconId) }
    )

    // To reload the main activity
    var wasReloaded = false

    // To defined if unsaved data still remaining
    private val mDataModifiedSinceLastLoading = MutableStateFlow(false)
    val dataModifiedSinceLastLoadingFlow = mDataModifiedSinceLastLoading.asStateFlow()
    val dataModifiedSinceLastLoading: Boolean
        get() = mDataModifiedSinceLastLoading.value

    // File description
    var snapFileDatabaseInfo: SnapFileDatabaseInfo? = null
        private set

    /**
     * Save the database file info
     */
    fun saveDatabaseInfo(context: Context) {
        try {
            fileUri?.let {
                snapFileDatabaseInfo = SnapFileDatabaseInfo.fromFileDatabaseInfo(
                    FileDatabaseInfo(context, it))
                NoOpLog.i(TAG, "Database file saved $snapFileDatabaseInfo")
            }
        } catch (e: Exception) {
            NoOpLog.e(TAG, "Unable to save database info", e)
        }
    }

    /**
     * Indicate that the database has unsaved data still remaining,
     * Data are not necessarily changed from the app
     */
    fun indicateNotSavedData() {
        mDataModifiedSinceLastLoading.value = true
    }

    /**
     * Indicate that data in the database is up to date
     */
    fun indicateUpToDateData() {
        mDataModifiedSinceLastLoading.value = false
    }

    /**
     * Build entry info to create.
     * @param parentId The parent ID to use.
     * @param registerInfo The registration info to use.
     * @return The created entry info if successful, null otherwise.
     */
    suspend fun buildNewEntryInfo(
        parentId: GroupId,
        registerInfo: RegisterInfo? = null
    ): EntryInfo? {
        getGroupById(parentId)?.let { parentGroup ->
            val entry = createEntry()?.apply {
                // Add the default icon from parent if not a folder
                val parentIcon = parentGroup.icon
                // Set default icon
                if (parentIcon.custom.isUnknown
                    && parentIcon.standard.id != IconImageStandard.FOLDER_ID
                ) {
                    icon = IconImage(parentIcon.standard)
                }
                if (!parentIcon.custom.isUnknown) {
                    icon = IconImage(parentIcon.custom)
                }
                // Set default username
                username = defaultUsername
            }
            var entryInfo: EntryInfo? = null
            // Decode the entry / load entry info
            entry?.let {
                getEntryInfoFrom(entry = entry, raw = true).let { tempEntryInfo ->
                    // Retrieve data from registration
                    registerInfo?.let { regInfo ->
                        saveRegisterInfoIn(tempEntryInfo, regInfo)
                    }
                    entryInfo = tempEntryInfo
                }
            }
            return entryInfo
        }
        return null
    }

    /**
     * Build entry info to update.
     * @param entryId The entry ID to update.
     * @param registerInfo The registration info to use.
     * @param actionDataOverwrite The action to perform when data is overwritten.
     */
    suspend fun buildEntryInfoFrom(
        entryId: EntryId,
        registerInfo: RegisterInfo? = null,
        actionDataOverwrite: suspend () -> Unit = {}
    ): EntryInfo? {
        getEntryInfoById(entryId = entryId, raw = true)?.let { tempEntryInfo ->
            // Retrieve data from registration
            registerInfo?.let { regInfo ->
                if (saveRegisterInfoIn(tempEntryInfo, regInfo))
                    actionDataOverwrite()
            }
            return tempEntryInfo
        }
        return null
    }

    override fun removeCustomIcon(customIcon: IconImageCustom) {
        iconDrawableFactory.clearFromCache(customIcon)
        super.removeCustomIcon(customIcon)
    }

    override fun clearIndexesAndBinaries(filesDirectory: File?) {
        iconDrawableFactory.clearCache()
        super.clearIndexesAndBinaries(filesDirectory)
    }

    override fun clearAndClose(filesDirectory: File?) {
        super.clearAndClose(filesDirectory)
        this.fileUri = null
        this.logicalFileUri = null
        this.loadedFromPrivateBackup = false
    }

    companion object : SingletonHolder<ContextualDatabase>(::ContextualDatabase) {
        private val TAG = ContextualDatabase::class.java.name
    }
}