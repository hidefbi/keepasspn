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
import com.keepasspn.encrypt.pgp.PgpCore
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

    /**
     * True when the kept passphrase is an old plain copy, not one wrapped with the Keystore.
     */
    fun hasLegacyPassphrase(entryInfo: EntryInfo): Boolean {
        val stored = valueOf(entryInfo, FIELD_PASSPHRASE) ?: return false
        return !PassphraseVault.isWrapped(stored)
    }

    fun setSecretKey(entryInfo: EntryInfo, armored: String) {
        replaceField(entryInfo, FIELD_SECRET_KEY, ProtectedString(true, armored.toCharArray()))
    }

    fun setPublicKey(entryInfo: EntryInfo, armored: String) {
        replaceField(entryInfo, FIELD_PUBLIC_KEY, ProtectedString(false, armored.toCharArray()))
    }

    /**
     * Fixes old protection once the passphrase has been used: a plain passphrase copy is wrapped
     * again (or dropped when the Keystore can't do it), and the secret key gets the current S2K
     * count. Returns true when the entry changed, the caller saves it.
     */
    fun upgradeProtection(entryInfo: EntryInfo, passphrase: CharArray): Boolean {
        var changed = false
        if (hasLegacyPassphrase(entryInfo)) {
            val wrapped = PassphraseVault.wrap(passphrase)
            replaceField(entryInfo, FIELD_PASSPHRASE, wrapped?.let { ProtectedString(true, it.toCharArray()) })
            changed = true
        }
        val secret = secretKeyOf(entryInfo) ?: return changed
        val upgraded = try {
            PgpCore.reprotect(secret, passphrase)
        } catch (e: PgpCore.PgpError) {
            null
        }
        if (upgraded != null) {
            setSecretKey(entryInfo, upgraded)
            changed = true
        }
        return changed
    }

    private fun replaceField(entryInfo: EntryInfo, name: String, value: ProtectedString?) {
        entryInfo.customFields.removeAll { it.name == name }
        value?.let { entryInfo.customFields.add(Field(name, it)) }
    }

    private fun valueOf(entryInfo: EntryInfo, name: String): CharArray? {
        val value = entryInfo.customFields.firstOrNull { it.name == name }?.protectedValue?.charArrayValue
        return if (value == null || value.isEmpty()) null else value
    }
}
