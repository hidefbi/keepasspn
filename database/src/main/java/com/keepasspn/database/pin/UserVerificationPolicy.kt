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
package com.keepasspn.database.pin

/** How a user verification prompt is answered. */
enum class VerificationMethod {
    /** The database PIN, when one is set for this database */
    PIN,
    /** The device credential (PIN, pattern, password or biometric) */
    DEVICE_CREDENTIAL,
    /** The first characters of the master password */
    DATABASE_CREDENTIAL,
    /** Nothing can verify the user */
    NONE
}

/**
 * Chooses the verification method for user verification prompts.
 * Pure logic, no Android types, so it can be unit-tested on the JVM.
 */
object UserVerificationPolicy {

    /**
     * Order: the database PIN when set, then the device credential when the device offers
     * one, then the master password check.
     */
    fun chooseMethod(
        pinSet: Boolean,
        deviceCredentialAvailable: Boolean,
        databaseAvailable: Boolean
    ): VerificationMethod = when {
        pinSet -> VerificationMethod.PIN
        deviceCredentialAvailable -> VerificationMethod.DEVICE_CREDENTIAL
        databaseAvailable -> VerificationMethod.DATABASE_CREDENTIAL
        else -> VerificationMethod.NONE
    }
}
