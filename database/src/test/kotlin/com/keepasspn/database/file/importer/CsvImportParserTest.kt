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
        assertTrue(result.skipped.isEmpty())
    }

    @Test
    fun chromeColumnsInAnyOrderAreRecognised() {
        val result = CsvImportParser.parse(csv("password,url,username,name\nsecret2,https://a.example.org,bob,Shop\n"))
        assertEquals("Shop", result.entries[0].title)
        assertEquals("bob", result.entries[0].username)
        assertEquals("secret2", String(result.entries[0].password))
    }

    @Test
    fun semicolonDelimiterIsDetected() {
        val result = CsvImportParser.parse(csv("name;password\nShop;secret3\n"))
        assertEquals("secret3", String(result.entries[0].password))
    }

    @Test
    fun quotedFieldsKeepCommasQuotesAndLineBreaks() {
        val result = CsvImportParser.parse(
            csv("name,password,notes\n\"Shop, Inc\",\"pa\"\"ss\",\"line1\nline2\"\n")
        )
        val entry = result.entries[0]
        assertEquals("Shop, Inc", entry.title)
        assertEquals("pa\"ss", String(entry.password))
        assertEquals("line1\nline2", entry.notes)
    }

    @Test
    fun byteOrderMarkAndCrLfAreHandled() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            csv("name,password\r\nShop,secret4\r\n")
        val result = CsvImportParser.parse(bytes)
        assertEquals("Shop", result.entries[0].title)
    }

    @Test
    fun invalidRowsAreReportedWithTheirLine() {
        val result = CsvImportParser.parse(
            csv(
                "name,password,url\n" +
                    "Good,secret5,https://ok.example.com\n" +
                    "\n" +
                    "Bad,secret6,javascript:alert(1)\n"
            )
        )
        assertEquals(1, result.entries.size)
        assertEquals(1, result.skipped.size)
        assertEquals(ImportRowIssue(4, ImportIssueReason.INVALID_URL), result.skipped[0])
    }

    @Test
    fun invalidUtf8IsRefused() {
        assertFileError(ImportFileError.INVALID_ENCODING) {
            CsvImportParser.parse(byteArrayOf(0x6E, 0x61, 0x6D, 0x65, 0x2C, 0xFF.toByte()))
        }
    }

    @Test
    fun missingPasswordColumnIsRefused() {
        assertFileError(ImportFileError.MISSING_REQUIRED_COLUMNS) {
            CsvImportParser.parse(csv("name,url\nShop,https://a.example.org\n"))
        }
    }

    @Test
    fun unterminatedQuoteIsRefused() {
        assertFileError(ImportFileError.MALFORMED_STRUCTURE) {
            CsvImportParser.parse(csv("name,password\n\"Shop,secret\n"))
        }
    }

    @Test
    fun emptyFileIsRefused() {
        assertFileError(ImportFileError.EMPTY_FILE) {
            CsvImportParser.parse(csv("\n\n"))
        }
    }

    private fun assertFileError(expected: ImportFileError, block: () -> Unit) {
        try {
            block()
            fail("Expected $expected")
        } catch (e: ImportFileException) {
            assertEquals(expected, e.error)
        }
    }
}
