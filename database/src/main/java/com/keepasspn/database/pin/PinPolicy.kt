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

/**
 * Pure rules for PIN unlock. No Android types here, so the policy can be
 * unit-tested on the JVM.
 *
 * Rules:
 *  - A PIN may be valid for 7, 14, 30 or 60 days, or never expire.
 *    If the app is not opened within that period, the next opening requires
 *    importing the .kdbx file again and unlocking with the master password.
 *  - Three wrong PINs in a row delete the PIN and force the master password.
 *  - After that PIN deletion, two wrong master passwords delete the database
 *    from the app (it must be imported again).
 */
object PinPolicy {

    const val MAX_PIN_FAILURES = 3
    const val MAX_MASTER_FAILURES_AFTER_PIN_WIPE = 2

    /** Stored value meaning "the PIN never expires". */
    const val VALIDITY_NEVER_DAYS = 0

    val VALIDITY_OPTIONS_DAYS: List<Int> = listOf(7, 14, 30, 60)

    private const val DAY_MILLIS = 24L * 60L * 60L * 1000L

    fun isSupportedValidity(days: Int): Boolean =
        days == VALIDITY_NEVER_DAYS || days in VALIDITY_OPTIONS_DAYS

    /**
     * True when the PIN validity period has elapsed since [lastOpenMillis].
     *
     * - No recorded open (0) is not expired: the caller starts the clock now.
     * - A clock that moved backwards is treated as expired (fail closed), so
     *   setting the clock back cannot extend a PIN's validity.
     */
    fun isExpired(lastOpenMillis: Long, nowMillis: Long, validityDays: Int): Boolean {
        require(isSupportedValidity(validityDays)) { "Unsupported PIN validity: $validityDays" }
        if (validityDays == VALIDITY_NEVER_DAYS) return false
        if (lastOpenMillis <= 0L) return false
        if (nowMillis < lastOpenMillis) return true
        return nowMillis - lastOpenMillis >= validityDays * DAY_MILLIS
    }

    sealed class PinFailureOutcome {
        data class Retry(val remainingAttempts: Int) : PinFailureOutcome()
        object WipePin : PinFailureOutcome()
    }

    /** [failedPinAttempts] is the consecutive wrong-PIN count, including the latest one. */
    fun onPinFailure(failedPinAttempts: Int): PinFailureOutcome =
        if (failedPinAttempts >= MAX_PIN_FAILURES) {
            PinFailureOutcome.WipePin
        } else {
            PinFailureOutcome.Retry(MAX_PIN_FAILURES - failedPinAttempts)
        }

    sealed class MasterFailureOutcome {
        data class Retry(val remainingAttempts: Int) : MasterFailureOutcome()
        object DeleteDatabase : MasterFailureOutcome()
    }

    /**
     * Only meaningful while a PIN lockout is active.
     * [failedMasterAttempts] counts wrong master passwords since the PIN was wiped.
     */
    fun onMasterFailureAfterPinWipe(failedMasterAttempts: Int): MasterFailureOutcome =
        if (failedMasterAttempts >= MAX_MASTER_FAILURES_AFTER_PIN_WIPE) {
            MasterFailureOutcome.DeleteDatabase
        } else {
            MasterFailureOutcome.Retry(MAX_MASTER_FAILURES_AFTER_PIN_WIPE - failedMasterAttempts)
        }
}
