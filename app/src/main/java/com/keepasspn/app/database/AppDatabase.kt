/*
 * Copyright 2026 Jeremy Jamet / Kunzisoft.
 *
 * This file is part of KeePassPN.
 *
 * KeePassPN is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * KeePassPN is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with KeePassPN. If not, see <http://www.gnu.org/licenses/>.
 */
package com.keepasspn.app.database

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    version = 5,
    entities = [
        FileDatabaseHistoryEntity::class,
        CipherDatabaseEntity::class,
        PinDatabaseEntity::class
    ],
    autoMigrations = [
        AutoMigration (from = 1, to = 2),
        AutoMigration (from = 2, to = 3),
        AutoMigration (from = 3, to = 4),
        // v4 -> v5 only adds the new "pin_database" table (see PinDatabaseEntity),
        // Room generates it automatically because the table is brand new.
        AutoMigration (from = 4, to = 5)
    ]
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun fileDatabaseHistoryDao(): FileDatabaseHistoryDao
    abstract fun cipherDatabaseDao(): CipherDatabaseDao
    abstract fun pinDatabaseDao(): PinDatabaseDao

    companion object {
        private const val DATABASE_NAME = "com.keepasspn.database"

        @Volatile
        private var instance: AppDatabase? = null

        /**
         * Single shared instance. Building a new Room database on every call
         * opened a new connection pool each time, which is wasteful and risky.
         */
        fun getDatabase(applicationContext: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    applicationContext.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                ).build().also { instance = it }
            }
        }
    }
}
