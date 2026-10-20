/*
 * Copyright 2017 Brian Pellin, Jeremy Jamet / Kunzisoft.
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
package com.keepasspn.encrypt.aes

import android.annotation.SuppressLint
import com.keepasspn.encrypt.NoOpLog
import com.keepasspn.encrypt.HashManager
import com.keepasspn.encrypt.NativeLib
import java.io.IOException
import java.security.InvalidKeyException
import javax.crypto.Cipher
import javax.crypto.ShortBufferException
import javax.crypto.spec.SecretKeySpec

object AESTransformer {

    @Throws(IOException::class)
    fun transformKey(
        seed: ByteArray?,
        key: ByteArray?,
        rounds: ULong?
    ): ByteArray? {
        // Prefer the native final key implementation
        if (rounds == null) {
            throw IOException("Invalid rounds")
        }
        if (seed == null || key == null) {
            throw IOException("Invalid seed or key")
        }
        if (!NativeLib.init() || !nativeKdfVerified) {
            return transformKeyInJVM(seed, key, rounds)
        }
        return try {
            NativeAESKeyTransformer.nTransformKey(seed, key, rounds.toLong())
        } catch (exception: Throwable) {
            NoOpLog.e(AESTransformer::class.java.simpleName, "Unable to perform native AES key transformation", exception)
            // Fall back on the android crypto implementation
            transformKeyInJVM(seed, key, rounds)
        }
    }

    /**
     * The native key transformation is used only if it reproduces the JCA result on a fixed
     * vector. Checked once per process; any mismatch or load failure falls back to JCA.
     */
    private val nativeKdfVerified: Boolean by lazy {
        try {
            if (!NativeLib.init()) return@lazy false
            val seed = ByteArray(32) { it.toByte() }
            val key = ByteArray(32) { (0xA5 xor it).toByte() }
            val rounds: ULong = 1000u
            val nativeResult = NativeAESKeyTransformer.nTransformKey(seed, key, rounds.toLong())
            val jvmResult = transformKeyInJVM(seed, key, rounds)
            val verified = nativeResult != null && nativeResult.contentEquals(jvmResult)
            if (!verified) {
                NoOpLog.e(AESTransformer::class.java.simpleName, "Native AES KDF self-test mismatch, using JCA")
            }
            verified
        } catch (exception: Throwable) {
            NoOpLog.e(AESTransformer::class.java.simpleName, "Native AES KDF self-test failed", exception)
            false
        }
    }

    @SuppressLint("GetInstance")
    @Throws(IOException::class)
    fun transformKeyInJVM(
        seed: ByteArray?,
        key: ByteArray?,
        rounds: ULong?
    ): ByteArray {
        val cipher: Cipher = try {
            Cipher.getInstance("AES/ECB/NoPadding")
        } catch (e: Exception) {
            throw IOException("Unable to get the cipher", e)
        }
        try {
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(seed, "AES"))
        } catch (e: InvalidKeyException) {
            throw IOException("Unable to init the cipher", e)
        }
        if (key == null) {
            throw IOException("Invalid key")
        }
        if (rounds == null) {
            throw IOException("Invalid rounds")
        }

        // Encrypt key rounds times
        val keyLength = key.size
        val newKey = ByteArray(keyLength)
        System.arraycopy(key, 0, newKey, 0, keyLength)
        val destKey = ByteArray(keyLength)
        for (i in 0uL until rounds) {
            try {
                cipher.update(newKey, 0, newKey.size, destKey, 0)
                System.arraycopy(destKey, 0, newKey, 0, newKey.size)
            } catch (e: ShortBufferException) {
                throw IOException("Short buffer", e)
            }
        }

        // Hash the key
        return HashManager.sha256(newKey)
    }
}