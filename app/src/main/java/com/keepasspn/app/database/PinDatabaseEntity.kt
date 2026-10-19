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
package com.keepasspn.app.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persisted, PIN-wrapped copy of the master credential for a database.
 *
 * The stored [encryptedValue] is the result of a two-layer encryption:
 *   1) AES-GCM with a device-bound AndroidKeyStore key that does NOT require
 *      biometric/device-credential authentication (so it survives a reboot
 *      and a swiped-away task, and never leaves this device).
 *   2) AES-GCM with a key derived from the user's PIN via Argon2id (using the
 *      salt below), wrapped around the result of step 1.
 *
 * Both layers must be satisfied to recover the credential: knowledge of the
 * PIN alone is not enough (the ciphertext is useless on another device),
 * and possession of the device alone is not enough (the PIN is required).
 *
 * This table is entirely separate from [CipherDatabaseEntity] (which backs
 * biometric / device-credential "Advanced Unlock") so the two mechanisms
 * cannot interfere with each other and can be independently enabled,
 * disabled or wiped.
 */
@Entity(tableName = "pin_database")
data class PinDatabaseEntity(
    @PrimaryKey
    @ColumnInfo(name = "database_uri")
    val databaseUri: String,

    // Base64(AES-GCM(AES-GCM(password, deviceKey), pinKey))
    @ColumnInfo(name = "encrypted_value", defaultValue = "")
    var encryptedValue: String,

    // Base64 IV used for the inner (device-key) layer
    @ColumnInfo(name = "iv_inner", defaultValue = "")
    var ivInner: String,

    // Base64 IV used for the outer (PIN-key) layer
    @ColumnInfo(name = "iv_outer", defaultValue = "")
    var ivOuter: String,

    // Base64 random salt used to derive the PIN key with Argon2id
    @ColumnInfo(name = "pin_salt", defaultValue = "")
    var pinSalt: String,

    // Consecutive wrong PINs since the last success (PIN is deleted at the limit)
    @ColumnInfo(name = "failed_attempts", defaultValue = "0")
    var failedAttempts: Int = 0,

    // Unused since the backoff was replaced by the 3-attempt limit. Kept so the schema does not change.
    @ColumnInfo(name = "locked_until", defaultValue = "0")
    var lockedUntil: Long = 0L
)
