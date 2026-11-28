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

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.annotation.RequiresApi
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps a PGP passphrase wrapped with a key from the Android Keystore. That key stays on the
 * device, so a backup copied to another phone can't open the passphrase and the app asks for
 * it again. Below Android 6 nothing is kept, the passphrase is asked each time.
 *
 * From Android 9 the key can only be used while the device is unlocked (v2). Values wrapped
 * before that (v1) are still opened, with the old key.
 */
object PassphraseVault {

    private const val KEY_ALIAS_V1 = "keepasspn_pgp_passphrase"
    private const val KEY_ALIAS_V2 = "keepasspn_pgp_passphrase_v2"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128
    private const val PREFIX_V1 = "v1:"
    private const val PREFIX_V2 = "v2:"

    /**
     * Returns the wrapped passphrase as text, or null when it can't be wrapped on this device.
     */
    fun wrap(passphrase: CharArray): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        val unlockedOnly = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
        val alias = if (unlockedOnly) KEY_ALIAS_V2 else KEY_ALIAS_V1
        val prefix = if (unlockedOnly) PREFIX_V2 else PREFIX_V1
        return try {
            val plain = encode(passphrase)
            try {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, wrappingKey(alias, unlockedOnly))
                val sealed = cipher.doFinal(plain)
                prefix + Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
                    Base64.encodeToString(sealed, Base64.NO_WRAP)
            } finally {
                plain.fill(0)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Opens a stored passphrase. A value without a prefix is an older plain copy and comes back
     * as it is. Returns null when a wrapped value can't be opened here, for example while the
     * device is locked, the caller asks for it.
     */
    fun unwrap(stored: CharArray): CharArray? {
        if (!isWrapped(stored)) return stored.copyOf()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        val prefix = if (startsWith(stored, PREFIX_V2)) PREFIX_V2 else PREFIX_V1
        val alias = if (prefix == PREFIX_V2) KEY_ALIAS_V2 else KEY_ALIAS_V1
        return try {
            val parts = String(stored, prefix.length, stored.size - prefix.length).split(":")
            if (parts.size != 2) return null
            val key = existingKey(alias) ?: return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(TAG_BITS, Base64.decode(parts[0], Base64.NO_WRAP))
            )
            val plain = cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP))
            try {
                decode(plain)
            } finally {
                plain.fill(0)
            }
        } catch (e: Exception) {
            null
        }
    }

    fun isWrapped(value: CharArray): Boolean {
        return startsWith(value, PREFIX_V1) || startsWith(value, PREFIX_V2)
    }

    private fun startsWith(value: CharArray, prefix: String): Boolean {
        return value.size > prefix.length && prefix.indices.all { value[it] == prefix[it] }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun wrappingKey(alias: String, unlockedOnly: Boolean): SecretKey {
        return existingKey(alias)
            ?: createKey(alias, strongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P, unlockedOnly = unlockedOnly)
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun existingKey(alias: String): SecretKey? {
        val store = KeyStore.getInstance(KEYSTORE)
        store.load(null)
        return store.getKey(alias, null) as? SecretKey
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun createKey(alias: String, strongBox: Boolean, unlockedOnly: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
        if (unlockedOnly && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // No use while the device is locked, the app asks for the passphrase instead
            spec.setUnlockedDeviceRequired(true)
        }
        if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            spec.setIsStrongBoxBacked(true)
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        return try {
            generator.init(spec.build())
            generator.generateKey()
        } catch (e: ProviderException) {
            // StrongBox is missing on many phones, the normal Keystore is the fallback
            if (strongBox) createKey(alias, strongBox = false, unlockedOnly = unlockedOnly) else throw e
        }
    }

    private fun encode(chars: CharArray): ByteArray {
        val buffer = Charsets.UTF_8.encode(CharBuffer.wrap(chars))
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        if (buffer.hasArray()) buffer.array().fill(0)
        return bytes
    }

    private fun decode(bytes: ByteArray): CharArray {
        val buffer = Charsets.UTF_8.decode(ByteBuffer.wrap(bytes))
        val chars = CharArray(buffer.remaining())
        buffer.get(chars)
        if (buffer.hasArray()) buffer.array().fill('\u0000')
        return chars
    }
}
