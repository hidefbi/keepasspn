/*
 * Copyright 2026 hidefbi
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
package com.keepasspn.tests.pgp

import com.keepasspn.encrypt.pgp.PgpCore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class PgpCoreTest {

    private val passphrase = "pass phrase"

    private fun newKey(): PgpCore.GeneratedKey {
        return PgpCore.generate("Tester <tester@example.com>", passphrase.toCharArray(), PgpCore.KeyType.CURVE25519)
    }

    private fun encryptText(publicArmored: String, text: String): ByteArray {
        val sink = ByteArrayOutputStream()
        val bytes = text.toByteArray(Charsets.UTF_8)
        PgpCore.encrypt(
            listOf(publicArmored), ByteArrayInputStream(bytes), "message.txt",
            true, bytes.size.toLong(), sink
        )
        return sink.toByteArray()
    }

    private fun reasonOf(block: () -> Unit): PgpCore.Reason? {
        return try {
            block()
            null
        } catch (e: PgpCore.PgpError) {
            e.reason
        }
    }

    @Test
    fun encryptThenDecryptGivesTheText() {
        val key = newKey()
        val encrypted = encryptText(key.publicArmored, "hello there")
        val out = ByteArrayOutputStream()
        PgpCore.decrypt(key.secretArmored, passphrase.toCharArray(), ByteArrayInputStream(encrypted), out)
        assertEquals("hello there", out.toString(Charsets.UTF_8.name()))
    }

    @Test
    fun wrongPassphraseIsRefused() {
        val key = newKey()
        val encrypted = encryptText(key.publicArmored, "secret")
        val reason = reasonOf {
            PgpCore.decrypt(
                key.secretArmored, "wrong".toCharArray(),
                ByteArrayInputStream(encrypted), ByteArrayOutputStream()
            )
        }
        assertEquals(PgpCore.Reason.WRONG_PASSPHRASE, reason)
    }

    @Test
    fun tamperedMessageIsRefused() {
        val key = newKey()
        val armored = String(encryptText(key.publicArmored, "a longer text to change in the middle"), Charsets.UTF_8)
        // Change one letter in the middle of the body, the armor checksum or the MDC must fail
        val lines = armored.lines().toMutableList()
        val bodyIndex = lines.indexOfFirst { it.isNotEmpty() && it.length > 40 && it[0] != '-' && !it.contains(':') }
        assertTrue(bodyIndex > 0)
        val line = lines[bodyIndex]
        val pos = line.length / 2
        val replacement = if (line[pos] == 'A') 'B' else 'A'
        lines[bodyIndex] = line.substring(0, pos) + replacement + line.substring(pos + 1)
        val tampered = lines.joinToString("\n").toByteArray(Charsets.UTF_8)
        val reason = reasonOf {
            PgpCore.decrypt(key.secretArmored, passphrase.toCharArray(), ByteArrayInputStream(tampered), ByteArrayOutputStream())
        }
        assertNotNull(reason)
    }

    @Test
    fun signatureVerifiesOnlyForTheSameText() {
        val key = newKey()
        val signature = PgpCore.sign(
            key.secretArmored, passphrase.toCharArray(), ByteArrayInputStream("data".toByteArray())
        )
        val good = PgpCore.verify(
            listOf(key.publicArmored), ByteArrayInputStream("data".toByteArray()), signature
        )
        assertTrue(good.signerFound)
        assertTrue(good.valid)
        assertTrue(good.keyUsable)

        val changed = PgpCore.verify(
            listOf(key.publicArmored), ByteArrayInputStream("date".toByteArray()), signature
        )
        assertFalse(changed.valid)
    }

    @Test
    fun generatedKeyCanEncrypt() {
        val key = newKey()
        val info = PgpCore.inspect(key.publicArmored)
        assertEquals(key.fingerprint, info.fingerprint)
        assertTrue(info.canEncrypt)
    }

    @Test
    fun emptyPassphraseIsRefusedOnGenerate() {
        val reason = reasonOf {
            PgpCore.generate("Tester", CharArray(0), PgpCore.KeyType.CURVE25519)
        }
        assertEquals(PgpCore.Reason.EMPTY_PASSPHRASE, reason)
    }

    @Test
    fun renewKeepsTheFingerprintAndKeepsWorking() {
        val key = newKey()
        val renewed = PgpCore.renew(key.secretArmored, passphrase.toCharArray())
        assertEquals(key.fingerprint, renewed.fingerprint)
        assertTrue(PgpCore.inspect(renewed.publicArmored).canEncrypt)
        val out = ByteArrayOutputStream()
        PgpCore.decrypt(
            renewed.secretArmored,
            passphrase.toCharArray(),
            ByteArrayInputStream(encryptText(renewed.publicArmored, "after renew")),
            out
        )
        assertEquals("after renew", out.toString(Charsets.UTF_8.name()))
    }

    @Test
    fun renewRefusesTheWrongPassphrase() {
        val key = newKey()
        val reason = reasonOf { PgpCore.renew(key.secretArmored, "wrong".toCharArray()) }
        assertEquals(PgpCore.Reason.WRONG_PASSPHRASE, reason)
    }

    @Test
    fun currentKeysAreNotReprotected() {
        val key = newKey()
        assertNull(PgpCore.reprotect(key.secretArmored, passphrase.toCharArray()))
    }
}
