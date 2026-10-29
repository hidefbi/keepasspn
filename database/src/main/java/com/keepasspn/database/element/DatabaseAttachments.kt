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

import com.keepasspn.database.element.binary.AttachmentPool
import com.keepasspn.database.element.binary.BinaryData
import com.keepasspn.database.element.database.DatabaseKDB
import com.keepasspn.database.element.database.DatabaseKDBX

/**
 * Binary attachments of the loaded database.
 *
 * The attachments belong to the KDB or KDBX database currently loaded, so they are read through
 * [kdb] and [kdbx] at each call, as the database may change.
 */
internal class DatabaseAttachments(
    private val kdb: () -> DatabaseKDB?,
    private val kdbx: () -> DatabaseKDBX?,
    private val compressionForNewEntry: () -> Boolean,
) {

    val attachmentPool: AttachmentPool
        get() = kdb()?.attachmentPool ?: kdbx()?.attachmentPool ?: AttachmentPool()

    val allowMultipleAttachments: Boolean
        get() {
            if (kdb() != null)
                return false
            if (kdbx() != null)
                return true
            return false
        }

    fun buildNewBinaryAttachment(): BinaryData? {
        return kdb()?.buildNewBinaryAttachment()
                ?: kdbx()?.buildNewBinaryAttachment( false,
                        compressionForNewEntry(),
                        false)
    }

    /**
     * Remove the [binaryData] from the binary pool if not used
     * @param clearCache Clear the cache if true
     */
    fun removeBinaryIfNotUsed(binaryData: BinaryData, clearCache: Boolean = false) {
        // No need in KDB database because unique attachment by entry
        // Don't clear to fix upload multiple times
        kdbx()?.removeUnlinkedAttachment(binaryData, clearCache)
    }

    /**
     * Remove the binary data of an [Attachment] from the binary pool if not used
     * @param clearCache Clear the cache if true
     */
    fun removeAttachmentIfNotUsed(attachment: Attachment, clearCache: Boolean = false) {
        removeBinaryIfNotUsed(attachment.binaryData, clearCache)
    }

    fun removeUnlinkedAttachments() {
        // No check in database KDB because unique attachment by entry
        kdbx()?.removeUnlinkedAttachments(true)
    }
}
