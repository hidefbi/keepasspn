/*
 * Copyright 2026 hidefbi.
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
package com.keepasspn.pgp

import com.keepasspn.database.element.Field
import com.keepasspn.database.element.security.ProtectedString
import com.keepasspn.model.EntryInfo

/**
 * Where the PGP keys live: in ordinary entries of the database, so they follow its
 * encryption, backups and sync. The public key is plain, the private key and its
 * passphrase are protected fields.
 */
object PgpKeyEntries {

    const val FIELD_PUBLIC_KEY = "PGP Public Key"
    const val FIELD_SECRET_KEY = "PGP Secret Key"
    const val FIELD_PASSPHRASE = "PGP Passphrase"

    /**
     * Builds the entry for a key. Pass a secret key and passphrase only for a key pair
     * made by the app, an imported public key has neither.
     */
    fun newEntry(
        userId: String,
        fingerprint: String,
        publicArmored: String,
        secretArmored: String?,
        passphrase: CharArray?
    ): EntryInfo {
        val entryInfo = EntryInfo()
        entryInfo.title = userId
        entryInfo.username = fingerprint
        entryInfo.customFields.add(Field(FIELD_PUBLIC_KEY, ProtectedString(false, publicArmored.toCharArray())))
        secretArmored?.let {
            entryInfo.customFields.add(Field(FIELD_SECRET_KEY, ProtectedString(true, it.toCharArray())))
        }
        // Kept only when the Keystore can wrap it, otherwise the app asks for it each time
        passphrase?.let { plain ->
            PassphraseVault.wrap(plain)?.let { wrapped ->
                entryInfo.customFields.add(Field(FIELD_PASSPHRASE, ProtectedString(true, wrapped.toCharArray())))
            }
        }
        return entryInfo
    }

    fun publicKeyOf(entryInfo: EntryInfo): String? {
        return valueOf(entryInfo, FIELD_PUBLIC_KEY)?.let { String(it) }
    }

    fun secretKeyOf(entryInfo: EntryInfo): String? {
        return valueOf(entryInfo, FIELD_SECRET_KEY)?.let { String(it) }
    }

    /**
     * The passphrase if one is kept and can be opened here, a new copy the caller wipes when done.
     * Null means the app has to ask for it.
     */
    fun passphraseOf(entryInfo: EntryInfo): CharArray? {
        val stored = valueOf(entryInfo, FIELD_PASSPHRASE) ?: return null
        return PassphraseVault.unwrap(stored)
    }

    private fun valueOf(entryInfo: EntryInfo, name: String): CharArray? {
        val value = entryInfo.customFields.firstOrNull { it.name == name }?.protectedValue?.charArrayValue
        return if (value == null || value.isEmpty()) null else value
    }
}
