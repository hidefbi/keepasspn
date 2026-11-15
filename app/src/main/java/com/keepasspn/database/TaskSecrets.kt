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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with KeePassPN.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.keepasspn.database

import android.content.Intent
import android.os.Parcelable

import com.keepasspn.services.DatabaseTaskNotificationService
import java.util.UUID

/**
 * Keeps the secrets of a database task (master credential, entry being saved) in memory.
 *
 * An Intent is copied through the system process and its Parcel is never wiped,
 * so these values never go into an Intent. The Intent only carries a random token.
 * A token can be taken once. Everything not taken yet is dropped when the database is locked.
 */
object TaskSecrets {

    private const val TOKEN_SUFFIX = "_TOKEN"

    // Keys of a task bundle that must not travel in an Intent
    val SECRET_KEYS = listOf(
        DatabaseTaskNotificationService.MAIN_CREDENTIAL_KEY,
        DatabaseTaskNotificationService.ENTRY_KEY
    )

    private val lock = Any()
    private val secrets = HashMap<String, Parcelable>()

    /**
     * Key of the Intent extra that holds the token of [key]
     */
    fun tokenKey(key: String): String {
        return key + TOKEN_SUFFIX
    }

    /**
     * Keep [secret] in memory, returns the token to put in the Intent
     */
    fun put(secret: Parcelable): String {
        val token = UUID.randomUUID().toString()
        synchronized(lock) {
            secrets[token] = secret
        }
        return token
    }

    /**
     * Remove the secret behind [token] and return it, null if there is none
     */
    fun take(token: String?): Parcelable? {
        if (token == null) return null
        return synchronized(lock) {
            secrets.remove(token)
        }
    }

    /**
     * Forget a secret that was never used, its content is wiped
     */
    fun discard(token: String) {
        take(token)?.let { wipe(it) }
    }

    /**
     * Called when the database is locked, nothing is kept after that
     */
    fun clear() {
        synchronized(lock) {
            secrets.values.forEach { wipe(it) }
            secrets.clear()
        }
    }

    private fun wipe(secret: Parcelable) {
        if (secret is MainCredential) {
            secret.clear()
        }
    }
}

/**
 * True if the Intent carries a token for [key]
 */
fun Intent.hasSecretExtra(key: String): Boolean {
    return hasExtra(TaskSecrets.tokenKey(key))
}

/**
 * Take the secret of [key] from the token of this Intent, null if there is none
 */
inline fun <reified T : Parcelable> Intent.takeSecretExtra(key: String): T? {
    return (TaskSecrets.take(getStringExtra(TaskSecrets.tokenKey(key))) as? T)
}
