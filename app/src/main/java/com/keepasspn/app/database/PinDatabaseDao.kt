package com.keepasspn.app.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Transaction
import androidx.room.Query
import androidx.room.Update

@Dao
interface PinDatabaseDao {

    @Query("SELECT * FROM pin_database WHERE database_uri = :databaseUriString")
    fun getByDatabaseUri(databaseUriString: String): PinDatabaseEntity?

    @Query("SELECT * FROM pin_database")
    fun getAll(): List<PinDatabaseEntity>

    @Insert
    fun add(vararg pinDatabase: PinDatabaseEntity)

    @Update
    fun update(vararg pinDatabase: PinDatabaseEntity)

    /** Atomically clears the consecutive PIN failure counter after a successful unlock. */
    @Query("UPDATE pin_database SET failed_attempts = 0 WHERE database_uri = :databaseUriString")
    fun resetFailureState(databaseUriString: String)

    /**
     * Atomically counts one wrong PIN. When the count reaches [maxAttempts] the
     * PIN entry is deleted in the same transaction. Returns null when no PIN exists.
     */
    @Transaction
    fun recordPinFailure(databaseUriString: String, maxAttempts: Int): PinFailureState? {
        val entity = getByDatabaseUri(databaseUriString) ?: return null

        entity.failedAttempts += 1
        if (entity.failedAttempts >= maxAttempts) {
            deleteByDatabaseUri(databaseUriString)
            return PinFailureState(entity.failedAttempts, wiped = true)
        }

        update(entity)
        return PinFailureState(entity.failedAttempts, wiped = false)
    }

    @Query("DELETE FROM pin_database WHERE database_uri = :databaseUriString")
    fun deleteByDatabaseUri(databaseUriString: String)

    @Query("DELETE FROM pin_database")
    fun deleteAll()
}


data class PinFailureState(
    val failedAttempts: Int,
    val wiped: Boolean
)
