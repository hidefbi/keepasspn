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
 * Secrets of database tasks, kept in memory. Intents only carry tokens.
 */
object TaskSecrets {

    private const val TOKEN_SUFFIX = "_TOKEN"

    // never put these in an Intent
    val SECRET_KEYS = listOf(
        DatabaseTaskNotificationService.MAIN_CREDENTIAL_KEY,
        DatabaseTaskNotificationService.ENTRY_KEY
    )

    private val lock = Any()
    private val secrets = HashMap<String, Parcelable>()

    fun tokenKey(key: String): String {
        return key + TOKEN_SUFFIX
    }

    fun put(secret: Parcelable): String {
        val token = UUID.randomUUID().toString()
        synchronized(lock) {
            secrets[token] = secret
        }
        return token
    }

    // a token works once
    fun take(token: String?): Parcelable? {
        if (token == null) return null
        return synchronized(lock) {
            secrets.remove(token)
        }
    }

    fun discard(token: String) {
        take(token)?.let { wipe(it) }
    }

    // on lock
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

fun Intent.hasSecretExtra(key: String): Boolean {
    return hasExtra(TaskSecrets.tokenKey(key))
}

inline fun <reified T : Parcelable> Intent.takeSecretExtra(key: String): T? {
    return (TaskSecrets.take(getStringExtra(TaskSecrets.tokenKey(key))) as? T)
}
