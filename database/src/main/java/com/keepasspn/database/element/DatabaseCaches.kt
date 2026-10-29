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
package com.keepasspn.database.element

import com.keepasspn.database.element.database.DatabaseKDB
import com.keepasspn.database.element.database.DatabaseKDBX
import com.keepasspn.database.NoOpLog
import java.io.File

/**
 * Removes the indexes, icons, attachments and binaries cached for the loaded database,
 * and the files of its temporary directory.
 */
internal class DatabaseCaches(
    private val kdb: () -> DatabaseKDB?,
    private val kdbx: () -> DatabaseKDBX?,
) {

    fun clearIndexesAndBinaries(filesDirectory: File?) {
        kdb()?.clearIndexes()
        kdbx()?.clearIndexes()

        kdb()?.clearIconsCache()
        kdbx()?.clearIconsCache()

        kdb()?.clearAttachmentsCache()
        kdbx()?.clearAttachmentsCache()

        kdb()?.clearBinaries()
        kdbx()?.clearBinaries()

        // delete all the files in the temp dir if allowed
        try {
            filesDirectory?.let { directory ->
                cleanDirectory(directory)
            }
        } catch (e: Exception) {
            NoOpLog.e(TAG, "Unable to clear the directory cache.", e)
        }
    }

    private fun cleanDirectory(directory: File) {
        directory.listFiles()?.let { files ->
            for (file in files) {
                if (file.isDirectory) {
                    cleanDirectory(file)
                }
                file.delete()
            }
        }
    }

    companion object {
        private val TAG = DatabaseCaches::class.java.name
    }
}
