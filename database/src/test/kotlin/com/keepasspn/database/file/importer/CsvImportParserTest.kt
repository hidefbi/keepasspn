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
package com.keepasspn.database.file.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CsvImportParserTest {

    private fun csv(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

    @Test
    fun keepassColumnsAreRecognised() {
        val result = CsvImportParser.parse(
            csv(
                "Account,Login Name,Password,Web Site,Comments\n" +
                    "Mail,alice,secret1,https://mail.example.com,my note\n"
            )
        )
        assertEquals(1, result.entries.size)
        val entry = result.entries[0]
        assertEquals("Mail", entry.title)
        assertEquals("alice", entry.username)
        assertEquals("secret1", String(entry.password))
        assertEquals("https://mail.example.com", entry.url)
        assertEquals("my note", entry.notes)
    }

    @Test
    fun unknownColumnsBecomeCustomFields() {
        val result = CsvImportParser.parse(csv("Password,Title,Custom\np1,T1,v1\n"))
        assertEquals("T1", result.entries[0].title)
        assertEquals("", result.entries[0].notes)
        assertEquals("Custom", result.entries[0].fields[0].name)
        assertEquals("v1", result.entries[0].fields[0].value)
    }

    @Test
    fun dateColumnsBecomeEntryDates() {
        val result = CsvImportParser.parse(
            csv("Title,Password,Created,Last Modified\nA,p,2024-01-02 03:04:05,2025-06-07\n")
        )
        val entry = result.entries[0]
        assertTrue(entry.createdAt != null)
        assertTrue(entry.modifiedAt != null)
        assertTrue(entry.fields.isEmpty())
    }

    @Test
    fun base32SecretInTotpColumnBecomesOtpUri() {
        val result = CsvImportParser.parse(csv("Title,Password,TOTP\nMy Site,p,JBSW Y3DP EHPK 3PXP\n"))
        assertEquals("otpauth://totp/My%20Site?secret=JBSWY3DPEHPK3PXP", result.entries[0].otpUri)
    }

    @Test
    fun partialRowIsKept() {
        val result = CsvImportParser.parse(csv("Title,Password\nOnlyTitle,\n,\n"))
        assertEquals(2, result.entries.size)
        assertEquals("", String(result.entries[0].password))
    }

    @Test
    fun duplicateRowsAreAllKept() {
        val result = CsvImportParser.parse(csv("Title,Password\nA,p\nA,p\n"))
        assertEquals(2, result.entries.size)
    }

    @Test
    fun groupColumnGivesGroupPath() {
        val result = CsvImportParser.parse(csv("Title,Password,Group\nA,p,Work\nB,p,\n"))
        assertEquals(listOf("Work"), result.entries[0].groupPath)
        assertEquals(emptyList<String>(), result.entries[1].groupPath)
    }

    @Test
    fun otpauthInTotpColumnBecomesOtpUri() {
        val result = CsvImportParser.parse(
            csv("Title,Password,TOTP\nA,p,otpauth://totp/A?secret=JBSWY3DPEHPK3PXP\n")
        )
        assertEquals("otpauth://totp/A?secret=JBSWY3DPEHPK3PXP", result.entries[0].otpUri)
    }

    @Test
    fun otherTotpTextBecomesProtectedField() {
        val result = CsvImportParser.parse(csv("Title,Password,TOTP\nA,p,123456\n"))
        assertNull(result.entries[0].otpUri)
        assertEquals("TOTP", result.entries[0].fields[0].name)
        assertEquals("123456", result.entries[0].fields[0].value)
        assertTrue(result.entries[0].fields[0].isProtected)
    }

    @Test
    fun semicolonDelimiterIsDetected() {
        val result = CsvImportParser.parse(csv("Title;Password\nA;p\n"))
        assertEquals("A", result.entries[0].title)
    }

    @Test
    fun missingPasswordColumnIsRefused() {
        assertRefused(ImportFileError.MISSING_REQUIRED_COLUMNS) {
            CsvImportParser.parse(csv("Title,Username\nA,u\n"))
        }
    }

    @Test
    fun unterminatedQuoteIsRefused() {
        assertRefused(ImportFileError.MALFORMED_STRUCTURE) {
            CsvImportParser.parse(csv("Title,Password\n\"A,p\n"))
        }
    }

    private fun assertRefused(error: ImportFileError, block: () -> Unit) {
        try {
            block()
            fail("Expected $error")
        } catch (e: ImportFileException) {
            assertEquals(error, e.error)
        }
    }
}
