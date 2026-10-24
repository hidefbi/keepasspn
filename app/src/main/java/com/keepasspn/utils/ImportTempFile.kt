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
package com.keepasspn.utils

import android.content.Context
import com.keepasspn.database.file.importer.ImportFileError
import com.keepasspn.database.file.importer.ImportFileException
import com.keepasspn.database.file.importer.ImportLimits
import java.io.File
import java.io.InputStream
import java.util.UUID

/**
 * Private temporary copy of an import file.
 *
 * The copy lives in the app-private, non-backed-up directory under a random name. It is
 * deleted by the caller in a finally block, and any leftover copy is removed at start.
 * The original file chosen by the user is never modified or deleted here.
 */
object ImportTempFile {

    private const val PREFIX = "import-"
    private const val SUFFIX = ".tmp"

    /**
     * Copies [input] into a new private file. Throws if the input is larger than the import limit.
     */
    fun copyFrom(context: Context, input: InputStream): File {
        val file = File(context.noBackupFilesDir, PREFIX + UUID.randomUUID().toString() + SUFFIX)
        try {
            file.outputStream().use { output ->
                copyLimited(input, output)
            }
            return file
        } catch (e: Throwable) {
            delete(file)
            throw e
        }
    }

    /**
     * True only for a copy this object created in the private import directory.
     * Used to refuse any other path received from an Intent.
     */
    fun isImportCopy(context: Context, file: File): Boolean {
        return try {
            file.parentFile?.canonicalPath == context.noBackupFilesDir.canonicalPath
                && file.name.startsWith(PREFIX)
                && file.name.endsWith(SUFFIX)
        } catch (e: java.io.IOException) {
            false
        }
    }

    /**
     * Deletes [file] if it exists. Returns true when nothing is left on disk.
     */
    fun delete(file: File?): Boolean {
        if (file == null || !file.exists()) return true
        return file.delete()
    }

    /**
     * Removes copies left behind by an interrupted import, for example after a crash.
     */
    fun deleteLeftovers(context: Context) {
        context.noBackupFilesDir
            .listFiles { file -> file.name.startsWith(PREFIX) && file.name.endsWith(SUFFIX) }
            ?.forEach { delete(it) }
    }

    private fun copyLimited(input: InputStream, output: java.io.OutputStream) {
        val buffer = ByteArray(8192)
        try {
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                total += read
                if (total > ImportLimits.MAX_FILE_BYTES) {
                    throw ImportFileException(ImportFileError.FILE_TOO_LARGE)
                }
                output.write(buffer, 0, read)
            }
        } finally {
            buffer.fill(0)
        }
    }
}
