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

class JsonImportParserTest {

    private fun json(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

    @Test
    fun arrayOfEntriesIsImported() {
        val text = "[{\"title\":\"Mail\",\"username\":\"alice\",\"password\":\"s1\"," +
            "\"url\":\"https://mail.example.com\",\"notes\":\"n\",\"group\":\"Work\"}]"
        val result = JsonImportParser.parse(json(text))
        assertEquals(1, result.entries.size)
        val entry = result.entries[0]
        assertEquals("Mail", entry.title)
        assertEquals("alice", entry.username)
        assertEquals("s1", String(entry.password))
        assertEquals("https://mail.example.com", entry.url)
        assertEquals("n", entry.notes)
        assertEquals("Work", entry.groupName)
        assertTrue(result.skipped.isEmpty())
    }

    @Test
    fun itemsWrapperIsAccepted() {
        val result = JsonImportParser.parse(json("""{"items":[{"name":"Shop","password":"s2"}]}"""))
        assertEquals("Shop", result.entries[0].title)
        assertEquals("s2", String(result.entries[0].password))
    }

    @Test
    fun numericPasswordIsKeptAsText() {
        val result = JsonImportParser.parse(json("""[{"name":"Pin","password":12345}]"""))
        assertEquals("12345", String(result.entries[0].password))
    }

    @Test
    fun unicodeEscapesAreDecoded() {
        val result = JsonImportParser.parse(json("""[{"name":"été","password":"p"}]"""))
        assertEquals("été", result.entries[0].title)
    }

    @Test
    fun entryWithNestedLoginIsSkipped() {
        val result = JsonImportParser.parse(
            json("""[{"name":"Shop","login":{"username":"b","password":"p"}}]""")
        )
        assertTrue(result.entries.isEmpty())
        assertEquals(ImportRowIssue(1, ImportIssueReason.INVALID_VALUE), result.skipped[0])
    }

    @Test
    fun encryptedFileIsRefused() {
        assertFileError(ImportFileError.ENCRYPTED_NOT_SUPPORTED) {
            JsonImportParser.parse(json("""{"encrypted":true,"data":"abc"}"""))
        }
    }

    @Test
    fun trailingCommaIsRefused() {
        assertFileError(ImportFileError.MALFORMED_STRUCTURE) {
            JsonImportParser.parse(json("""[{"name":"a","password":"b"},]"""))
        }
    }

    @Test
    fun controlCharacterInsideStringIsRefused() {
        assertFileError(ImportFileError.MALFORMED_STRUCTURE) {
            JsonImportParser.parse(json("[{\"name\":\"a\u0001\",\"password\":\"b\"}]"))
        }
    }

    @Test
    fun deepNestingIsRefused() {
        assertFileError(ImportFileError.TOO_DEEP) {
            JsonImportParser.parse(json("[".repeat(20) + "]".repeat(20)))
        }
    }

    @Test
    fun emptyArrayIsRefused() {
        assertFileError(ImportFileError.EMPTY_FILE) {
            JsonImportParser.parse(json("[]"))
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
