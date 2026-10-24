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
package com.keepasspn.database.file.importer

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Reads the single JSON file of a ZIP archive, such as a Proton Pass export.
 *
 * Only the content of the JSON entry is kept. Other entries are read and discarded, nothing is
 * written to disk, and entry names are never used as paths. The total amount inflated is bounded,
 * so a small archive cannot expand into a large amount of memory or time.
 */
internal object ZipJson {

    private const val BUFFER_SIZE = 8192

    fun read(archive: ByteArray): ByteArray {
        var json: ByteArray? = null
        var entryCount = 0
        var inflated = 0L
        val zip = ZipInputStream(ByteArrayInputStream(archive))
        try {
            while (true) {
                val entry: ZipEntry = zip.nextEntry ?: break
                entryCount++
                if (entryCount > ImportLimits.MAX_ZIP_ENTRIES) {
                    throw ImportFileException(ImportFileError.TOO_MANY_ENTRIES)
                }
                if (!entry.isDirectory) {
                    val name = entry.name.lowercase()
                    if (name.endsWith(".pgp") || name.endsWith(".gpg") || name.endsWith(".asc")) {
                        throw ImportFileException(ImportFileError.ENCRYPTED_NOT_SUPPORTED)
                    }
                    if (name.endsWith(".json")) {
                        if (json != null) {
                            throw ImportFileException(ImportFileError.ZIP_SEVERAL_JSON_FILES)
                        }
                        val content = readEntry(zip)
                        inflated += content.size
                        json = content
                    } else {
                        inflated += discardEntry(zip)
                    }
                }
                zip.closeEntry()
                if (inflated > ImportLimits.MAX_ZIP_INFLATED_BYTES) {
                    throw ImportFileException(ImportFileError.FILE_TOO_LARGE)
                }
            }
        } catch (e: IOException) {
            throw ImportFileException(ImportFileError.ZIP_UNREADABLE)
        } finally {
            zip.close()
        }
        return json ?: throw ImportFileException(ImportFileError.ZIP_NO_JSON_FILE)
    }

    private fun readEntry(zip: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_SIZE)
        try {
            var total = 0
            while (true) {
                val read = zip.read(buffer)
                if (read == -1) break
                total += read
                if (total > ImportLimits.MAX_FILE_BYTES) {
                    throw ImportFileException(ImportFileError.FILE_TOO_LARGE)
                }
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        } finally {
            buffer.fill(0)
        }
    }

    /**
     * Reads an entry without keeping it. Returns the number of bytes inflated.
     */
    private fun discardEntry(zip: InputStream): Long {
        val buffer = ByteArray(BUFFER_SIZE)
        try {
            var total = 0L
            while (true) {
                val read = zip.read(buffer)
                if (read == -1) break
                total += read
                if (total > ImportLimits.MAX_ZIP_INFLATED_BYTES) {
                    throw ImportFileException(ImportFileError.FILE_TOO_LARGE)
                }
            }
            return total
        } finally {
            buffer.fill(0)
        }
    }
}
