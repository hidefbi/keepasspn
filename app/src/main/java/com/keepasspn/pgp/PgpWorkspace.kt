/*
 * Copyright 2026 hidefbi.
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
package com.keepasspn.pgp

import android.content.Context
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID

/**
 * Temp files for the PGP screen. Everything lives in cacheDir/pgp and nothing else
 * is touched, so clearing it never affects the database or other app data.
 */
object PgpWorkspace {

    private const val DIR_NAME = "pgp"

    private fun dir(context: Context): File = File(context.cacheDir, DIR_NAME).apply { mkdirs() }

    fun newFile(context: Context, label: String): File {
        return File(dir(context), "$label-${UUID.randomUUID()}")
    }

    /**
     * Overwrites the file with zeros, then deletes it. Best effort only: flash storage and
     * copy-on-write file systems may keep old blocks, so this is not a guarantee.
     */
    fun wipe(file: File) {
        try {
            if (file.isFile && file.length() > 0L) {
                RandomAccessFile(file, "rw").use { raf ->
                    val zeros = ByteArray(BUFFER_SIZE)
                    var left = raf.length()
                    raf.seek(0L)
                    while (left > 0L) {
                        val count = minOf(left, zeros.size.toLong()).toInt()
                        raf.write(zeros, 0, count)
                        left -= count
                    }
                    raf.fd.sync()
                }
            }
        } catch (_: IOException) {
            // Nothing else can be done, the file is deleted below anyway
        } finally {
            file.delete()
        }
    }

    /**
     * Wipes every file in the PGP folder, then removes the folder.
     */
    fun clearAll(context: Context) {
        val dir = File(context.cacheDir, DIR_NAME)
        dir.walkBottomUp().forEach { if (it.isFile) wipe(it) }
        dir.deleteRecursively()
    }

    private const val BUFFER_SIZE = 65536
}
