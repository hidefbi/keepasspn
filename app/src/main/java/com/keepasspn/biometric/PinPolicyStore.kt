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
package com.keepasspn.biometric

import android.content.Context
import android.net.Uri
import java.security.MessageDigest

/**
 * Small private state for PIN policy: the time of the last app open, per database whether
 * the master-password lockout is active after a PIN was deleted, and per database whether
 * the user declined the offer to set a PIN.
 *
 * Stored in a private SharedPreferences file. Writes use commit() so the counters
 * are on disk before the next attempt is accepted.
 */
object PinPolicyStore {

    private const val PREFS_NAME = "pin_policy_state"
    private const val KEY_LAST_OPEN_MILLIS = "last_open_millis"
    private const val KEY_MASTER_LOCKOUT_PREFIX = "master_lockout_"
    private const val KEY_PIN_OFFER_DECLINED_PREFIX = "pin_offer_declined_"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun uriKey(prefix: String, databaseUri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(databaseUri.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return prefix + digest
    }

    private fun lockoutKey(databaseUri: Uri): String =
        uriKey(KEY_MASTER_LOCKOUT_PREFIX, databaseUri)

    fun getLastOpenMillis(context: Context): Long =
        prefs(context).getLong(KEY_LAST_OPEN_MILLIS, 0L)

    fun setLastOpenMillis(context: Context, millis: Long) {
        prefs(context).edit().putLong(KEY_LAST_OPEN_MILLIS, millis).commit()
    }

    /** True after a PIN was deleted for [databaseUri] and until the master password succeeds. */
    fun isMasterLockoutActive(context: Context, databaseUri: Uri): Boolean =
        prefs(context).contains(lockoutKey(databaseUri))

    fun startMasterLockout(context: Context, databaseUri: Uri) {
        prefs(context).edit().putInt(lockoutKey(databaseUri), 0).commit()
    }

    /** Counts one wrong master password during the lockout and returns the new count. */
    fun incrementMasterFailure(context: Context, databaseUri: Uri): Int {
        val key = lockoutKey(databaseUri)
        val count = prefs(context).getInt(key, 0) + 1
        prefs(context).edit().putInt(key, count).commit()
        return count
    }

    fun clearMasterLockout(context: Context, databaseUri: Uri) {
        prefs(context).edit().remove(lockoutKey(databaseUri)).commit()
    }

    /** True when the user refused to set a PIN for [databaseUri] after a password unlock. */
    fun isPinOfferDeclined(context: Context, databaseUri: Uri): Boolean =
        prefs(context).getBoolean(uriKey(KEY_PIN_OFFER_DECLINED_PREFIX, databaseUri), false)

    fun setPinOfferDeclined(context: Context, databaseUri: Uri) {
        prefs(context).edit()
            .putBoolean(uriKey(KEY_PIN_OFFER_DECLINED_PREFIX, databaseUri), true)
            .apply()
    }

    /** Forgets every refused PIN offer, so the offer is shown again on the next unlock. */
    fun clearPinOfferDeclined(context: Context) {
        val editor = prefs(context).edit()
        prefs(context).all.keys
            .filter { it.startsWith(KEY_PIN_OFFER_DECLINED_PREFIX) }
            .forEach { editor.remove(it) }
        editor.apply()
    }
}
