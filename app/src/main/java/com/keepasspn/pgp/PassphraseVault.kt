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
 */
object PassphraseVault {

    private const val KEY_ALIAS = "keepasspn_pgp_passphrase"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128
    private const val PREFIX = "v1:"

    /**
     * Returns the wrapped passphrase as text, or null when it can't be wrapped on this device.
     */
    fun wrap(passphrase: CharArray): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        return try {
            val plain = encode(passphrase)
            try {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
                val sealed = cipher.doFinal(plain)
                PREFIX + Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
                    Base64.encodeToString(sealed, Base64.NO_WRAP)
            } finally {
                plain.fill(0)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Opens a stored passphrase. A value without the prefix is an older plain copy and comes back
     * as it is. Returns null when a wrapped value can't be opened here, the caller asks for it.
     */
    fun unwrap(stored: CharArray): CharArray? {
        if (!isWrapped(stored)) return stored.copyOf()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        return try {
            val parts = String(stored, PREFIX.length, stored.size - PREFIX.length).split(":")
            if (parts.size != 2) return null
            val key = existingKey() ?: return null
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

    private fun isWrapped(value: CharArray): Boolean {
        return value.size > PREFIX.length && PREFIX.indices.all { value[it] == PREFIX[it] }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun wrappingKey(): SecretKey {
        return existingKey() ?: createKey(strongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun existingKey(): SecretKey? {
        val store = KeyStore.getInstance(KEYSTORE)
        store.load(null)
        return store.getKey(KEY_ALIAS, null) as? SecretKey
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun createKey(strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
        if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            spec.setIsStrongBoxBacked(true)
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        return try {
            generator.init(spec.build())
            generator.generateKey()
        } catch (e: ProviderException) {
            // StrongBox is missing on many phones, the normal Keystore is the fallback
            if (strongBox) createKey(strongBox = false) else throw e
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
