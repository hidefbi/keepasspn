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

import com.keepasspn.database.NoOpLog
import com.keepasspn.database.crypto.kdf.KdfEngine
import com.keepasspn.database.crypto.kdf.Limits
import com.keepasspn.database.element.database.DatabaseKDB
import com.keepasspn.database.element.database.DatabaseKDBX
import com.keepasspn.database.exception.DatabaseException
import com.keepasspn.database.exception.DatabaseInputException
import com.keepasspn.database.file.input.DatabaseInputKDB
import com.keepasspn.database.file.input.DatabaseInputKDBX
import com.keepasspn.hardware.HardwareKey
import com.keepasspn.tasks.ProgressTaskUpdater
import java.io.File
import java.io.InputStream

/**
 * Reads a database stream into a [Database] that is not loaded yet, or reloads
 * the stream over the database already in memory, keeping its master key.
 */
internal class DatabaseLoader(
    private val currentKdb: () -> DatabaseKDB?,
    private val currentKdbx: () -> DatabaseKDBX?,
    private val currentKdfEngine: () -> KdfEngine?,
    private val setKdb: (DatabaseKDB) -> Unit,
    private val setKdbx: (DatabaseKDBX) -> Unit
) {

    @Throws(DatabaseInputException::class)
    fun load(
        databaseStream: InputStream,
        masterCredential: MasterCredential,
        challengeResponseRetriever: (HardwareKey, ByteArray?) -> ByteArray,
        cacheDirectory: File,
        fixDuplicateUUID: Boolean,
        limits: Limits,
        progressTaskUpdater: ProgressTaskUpdater?
    ) {
        try {
            currentKdfEngine()?.checkLimits(limits)
            readDatabaseStream(databaseStream,
                { databaseInputStream ->
                    val databaseKDB = DatabaseKDB().apply {
                        binaryCache.cacheDirectory = cacheDirectory
                        changeDuplicateId = fixDuplicateUUID
                    }
                    DatabaseInputKDB(databaseKDB)
                        .openDatabase(
                            databaseInputStream,
                            progressTaskUpdater
                        ) {
                            databaseKDB.deriveMasterKey(
                                masterCredential
                            )
                        }
                    setKdb(databaseKDB)
                },
                { databaseInputStream ->
                    val databaseKDBX = DatabaseKDBX().apply {
                        binaryCache.cacheDirectory = cacheDirectory
                        changeDuplicateId = fixDuplicateUUID
                    }
                    DatabaseInputKDBX(databaseKDBX).apply {
                        setMethodToCheckMemoryForBinary { memoryWanted ->
                            limits.isMemorySufficientForBinary(memoryWanted)
                        }
                        openDatabase(
                            databaseInputStream,
                            progressTaskUpdater
                        ) {
                            databaseKDBX.deriveMasterKey(
                                masterCredential,
                                challengeResponseRetriever
                            )
                        }
                    }
                    setKdbx(databaseKDBX)
                }
            )
        } catch (e: Exception) {
            NoOpLog.e(TAG, "Unable to load the database")
            if (e is DatabaseInputException)
                throw e
            throw DatabaseInputException(e)
        }
    }

    @Throws(DatabaseInputException::class)
    fun reload(
        databaseStream: InputStream,
        limits: Limits,
        progressTaskUpdater: ProgressTaskUpdater?
    ) {
        try {
            currentKdfEngine()?.checkLimits(limits)
            // Retrieve the stream from the old database
            readDatabaseStream(databaseStream,
                { databaseInputStream ->
                    val databaseKDB = DatabaseKDB()
                    DatabaseInputKDB(databaseKDB)
                        .openDatabase(databaseInputStream, progressTaskUpdater) {
                            currentKdb()?.let { thisDatabaseKDB ->
                                databaseKDB.copyMasterKeyFrom(thisDatabaseKDB)
                            }
                        }
                    setKdb(databaseKDB)
                },
                { databaseInputStream ->
                    val databaseKDBX = DatabaseKDBX()
                    DatabaseInputKDBX(databaseKDBX).apply {
                        setMethodToCheckMemoryForBinary { memoryWanted ->
                            limits.isMemorySufficientForBinary(memoryWanted)
                        }
                        openDatabase(databaseInputStream, progressTaskUpdater) {
                            currentKdbx()?.let { thisDatabaseKDBX ->
                                databaseKDBX.copyMasterKeyFrom(thisDatabaseKDBX)
                            }
                        }
                    }
                    setKdbx(databaseKDBX)
                }
            )
        } catch (e: Exception) {
            NoOpLog.e(TAG, "Unable to reload the database")
            if (e is DatabaseException)
                throw e
            throw DatabaseInputException(e)
        }
    }

    companion object {
        private val TAG = DatabaseLoader::class.java.name
    }
}
