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
     * Removes every file in the PGP folder. Plain delete, the flash storage does not
     * allow a reliable overwrite.
     */
    fun clearAll(context: Context) {
        File(context.cacheDir, DIR_NAME).deleteRecursively()
    }
}
