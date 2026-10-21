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
package com.keepasspn.otp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.Base64

class GoogleAuthenticatorMigrationTest {

    // Test helpers building a MigrationPayload, the same way Google Authenticator does

    private fun varint(value: Long): ByteArray {
        val out = ByteArrayOutputStream()
        var remaining = value
        while (true) {
            if (remaining and 0x7FL.inv() == 0L) {
                out.write(remaining.toInt())
                return out.toByteArray()
            }
            out.write(((remaining and 0x7F) or 0x80).toInt())
            remaining = remaining ushr 7
        }
    }

    private fun key(field: Int, wireType: Int): ByteArray =
        varint(((field shl 3) or wireType).toLong())

    private fun varintField(field: Int, value: Long): ByteArray =
        key(field, 0) + varint(value)

    private fun bytesField(field: Int, value: ByteArray): ByteArray =
        key(field, 2) + varint(value.size.toLong()) + value

    private fun otpParameters(
        secret: ByteArray,
        name: String,
        issuer: String,
        algorithm: Int,
        digits: Int,
        type: Int,
        counter: Long = 0L
    ): ByteArray {
        return bytesField(1, secret) +
                bytesField(2, name.toByteArray(Charsets.UTF_8)) +
                bytesField(3, issuer.toByteArray(Charsets.UTF_8)) +
                varintField(4, algorithm.toLong()) +
                varintField(5, digits.toLong()) +
                varintField(6, type.toLong()) +
                varintField(7, counter)
    }

    private fun payload(
        accounts: List<ByteArray>,
        batchIndex: Int = 0,
        batchSize: Int = 1,
        batchId: Int = 0
    ): ByteArray {
        var bytes = ByteArray(0)
        accounts.forEach { bytes += bytesField(1, it) }
        bytes += varintField(3, batchSize.toLong())
        bytes += varintField(4, batchIndex.toLong())
        bytes += varintField(5, batchId.toLong())
        return bytes
    }

    private fun migrationUri(payload: ByteArray): String {
        val base64 = Base64.getEncoder().encodeToString(payload)
        return "otpauth-migration://offline?data=" + URLEncoder.encode(base64, "UTF-8")
    }

    private val secret = "Hello!".toByteArray(Charsets.US_ASCII)

    @Test
    fun parseTotpAccount() {
        val uri = migrationUri(
            payload(listOf(otpParameters(secret, "user@example.com", "Example", 1, 1, 2)))
        )

        val batch = GoogleAuthenticatorMigration.parse(uri)

        assertEquals(1, batch.accounts.size)
        assertEquals(0, batch.skipped)
        val account = batch.accounts[0]
        assertEquals(OtpType.TOTP, account.type)
        assertEquals(OtpTokenType.RFC6238, account.tokenType)
        assertEquals("user@example.com", account.name)
        assertEquals("Example", account.issuer)
        assertEquals(6, account.digits)
        assertEquals(30, account.period)
        assertEquals(TokenCalculator.HashAlgorithm.SHA1, account.algorithm)
        assertArrayEquals(secret, account.secret)
    }

    @Test
    fun parseHotpAccountWithCounterAndEightDigits() {
        val uri = migrationUri(
            payload(listOf(otpParameters(secret, "counter", "Bank", 2, 2, 1, counter = 5L)))
        )

        val account = GoogleAuthenticatorMigration.parse(uri).accounts[0]

        assertEquals(OtpType.HOTP, account.type)
        assertEquals(OtpTokenType.RFC4226, account.tokenType)
        assertEquals(5L, account.counter)
        assertEquals(8, account.digits)
        assertEquals(TokenCalculator.HashAlgorithm.SHA256, account.algorithm)
    }

    @Test
    fun parseSha512Account() {
        val uri = migrationUri(
            payload(listOf(otpParameters(secret, "a", "b", 3, 1, 2)))
        )

        val account = GoogleAuthenticatorMigration.parse(uri).accounts[0]

        assertEquals(TokenCalculator.HashAlgorithm.SHA512, account.algorithm)
    }

    @Test
    fun md5AccountIsSkipped() {
        val uri = migrationUri(
            payload(
                listOf(
                    otpParameters(secret, "md5", "Old", 4, 1, 2),
                    otpParameters(secret, "sha1", "New", 1, 1, 2)
                )
            )
        )

        val batch = GoogleAuthenticatorMigration.parse(uri)

        assertEquals(1, batch.accounts.size)
        assertEquals("sha1", batch.accounts[0].name)
        assertEquals(1, batch.skipped)
    }

    @Test
    fun accountWithoutSecretIsSkipped() {
        val uri = migrationUri(
            payload(listOf(otpParameters(ByteArray(0), "empty", "Empty", 1, 1, 2)))
        )

        val batch = GoogleAuthenticatorMigration.parse(uri)

        assertTrue(batch.accounts.isEmpty())
        assertEquals(1, batch.skipped)
    }

    @Test
    fun batchInformationIsRead() {
        val uri = migrationUri(
            payload(
                listOf(otpParameters(secret, "one", "Issuer", 1, 1, 2)),
                batchIndex = 1,
                batchSize = 3,
                batchId = 42
            )
        )

        val batch = GoogleAuthenticatorMigration.parse(uri)

        assertEquals(1, batch.batchIndex)
        assertEquals(3, batch.batchSize)
        assertEquals(42, batch.batchId)
    }

    @Test
    fun isMigrationUriDetectsScheme() {
        assertTrue(GoogleAuthenticatorMigration.isMigrationUri("otpauth-migration://offline?data=AAA"))
        assertFalse(GoogleAuthenticatorMigration.isMigrationUri("otpauth://totp/Example?secret=AAA"))
    }

    @Test
    fun missingDataIsRejected() {
        try {
            GoogleAuthenticatorMigration.parse("otpauth-migration://offline?other=1")
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun invalidBase64IsRejected() {
        try {
            GoogleAuthenticatorMigration.parse("otpauth-migration://offline?data=%21%21%21")
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun truncatedPayloadIsRejected() {
        // Length-delimited field announcing 10 bytes but with none
        val truncated = key(1, 2) + varint(10L)
        try {
            GoogleAuthenticatorMigration.parse(migrationUri(truncated))
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun base64DecoderHandlesPaddingAndWhitespace() {
        val decoded = GoogleAuthenticatorMigration.decodeBase64("SGVsbG8h\n")

        assertArrayEquals("Hello!".toByteArray(Charsets.US_ASCII), decoded)
    }
}
