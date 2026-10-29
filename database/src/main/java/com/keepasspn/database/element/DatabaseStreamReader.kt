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
package com.keepasspn.database.element

import com.keepasspn.database.exception.FileNotFoundDatabaseException
import com.keepasspn.database.exception.SignatureDatabaseException
import com.keepasspn.database.file.DatabaseHeaderKDB
import com.keepasspn.database.file.DatabaseHeaderKDBX
import com.keepasspn.utils.readBytes4ToUInt
import java.io.BufferedInputStream
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * Detects the format of a database stream from its signature, then hands the stream
 * to the matching opener. Fails with [SignatureDatabaseException] when the format is unknown.
 */
internal fun readDatabaseStream(
    databaseStream: InputStream,
    openDatabaseKDB: (InputStream) -> Unit,
    openDatabaseKDBX: (InputStream) -> Unit
) {
    try {
        // Load Data by InputStream
        BufferedInputStream(databaseStream).use { databaseInputStream ->

            // We'll end up reading 8 bytes to identify the header. Might as well use two extra.
            databaseInputStream.mark(10)

            // Read the signature of the file
            val sig1 = databaseInputStream.readBytes4ToUInt()
            val sig2 = databaseInputStream.readBytes4ToUInt()

            // Return to the start
            databaseInputStream.reset()

            when {
                // Header of database KDB
                DatabaseHeaderKDB.matchesHeader(sig1, sig2) -> openDatabaseKDB(
                    databaseInputStream
                )
                // Header of database KDBX
                DatabaseHeaderKDBX.matchesHeader(sig1, sig2) -> openDatabaseKDBX(
                    databaseInputStream
                )
                // Header not recognized
                else -> throw SignatureDatabaseException()
            }
        }
    } catch (_ : FileNotFoundException) {
        throw FileNotFoundDatabaseException()
    }
}
