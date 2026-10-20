/*
 * Copyright 2021 Jeremy Jamet / Kunzisoft.
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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with KeePassPN. If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.keepasspn.encrypt

import com.keepasspn.encrypt.aes.AESTransformer
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.util.Random
import javax.crypto.Cipher

class AESTest {

    private val mRand = Random()

    private fun hexToBytes(hex: String): ByteArray {
        return ByteArray(hex.length / 2) { index ->
            hex.substring(2 * index, 2 * index + 2).toInt(16).toByte()
        }
    }

    @Test
    fun testAESKnownAnswer() {
        // NIST SP 800-38A, F.2.1 (CBC-AES128.Encrypt), first block
        val key = hexToBytes("2b7e151628aed2a6abf7158809cf4f3c")
        val iv = hexToBytes("000102030405060708090a0b0c0d0e0f")
        val plain = hexToBytes("6bc1bee22e409f96e93d7e117393172a")
        val expected = hexToBytes("7649abac8119b246cee98e9b12e9197d")

        val output = CipherFactory.getAES(Cipher.ENCRYPT_MODE, key, iv).doFinal(plain)

        assertArrayEquals("Check AES known answer", expected, output.copyOfRange(0, 16))
    }

    @Test
    fun testAESRoundTrip() {
        val input = ByteArray(mRand.nextInt(494) + 18)
        mRand.nextBytes(input)
        val key = ByteArray(32)
        mRand.nextBytes(key)
        val iv = ByteArray(16)
        mRand.nextBytes(iv)

        val encrypted = CipherFactory.getAES(Cipher.ENCRYPT_MODE, key, iv).doFinal(input)
        val decrypted = CipherFactory.getAES(Cipher.DECRYPT_MODE, key, iv).doFinal(encrypted)

        assertArrayEquals("Check AES round trip", input, decrypted)
    }

    @Test
    fun testAESKDF() {
        val seed = ByteArray(32)
        mRand.nextBytes(seed)
        val key = ByteArray(32)
        mRand.nextBytes(key)
        val rounds: ULong = 60000u

        val androidKey = AESTransformer.transformKeyInJVM(seed, key, rounds)
        val nativeKey = AESTransformer.transformKey(seed, key, rounds)

        assertArrayEquals("Does not match", androidKey, nativeKey)
    }
}
