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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ImportParserTest {

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun parse(bytes: ByteArray) = ImportParser.parse(ByteArrayInputStream(bytes))

    @Test
    fun zipWithOneJsonFileIsRead() {
        val result = parse(
            zip(
                "readme.txt" to "not imported",
                "export.json" to """[{"name":"Mail","password":"p"}]"""
            )
        )
        assertEquals(1, result.entries.size)
        assertEquals("Mail", result.entries[0].title)
    }

    @Test
    fun zipWithTwoJsonFilesIsRefused() {
        assertRefused(ImportFileError.ZIP_SEVERAL_JSON_FILES) {
            parse(zip("a.json" to "[]", "b.json" to "[]"))
        }
    }

    @Test
    fun zipWithoutJsonFileIsRefused() {
        assertRefused(ImportFileError.ZIP_NO_JSON_FILE) {
            parse(zip("data.csv" to "Title,Password\nA,p\n"))
        }
    }

    @Test
    fun zipWithEncryptedEntryIsRefused() {
        assertRefused(ImportFileError.ENCRYPTED_NOT_SUPPORTED) {
            parse(zip("export.json" to "[]", "backup.json.pgp" to "data"))
        }
    }

    @Test
    fun truncatedZipIsRefused() {
        val archive = zip("export.json" to """[{"name":"Mail","password":"p"}]""")
        // The local file header is 30 bytes plus the 11-byte name, so this cuts inside the compressed data
        val truncated = archive.copyOf(30 + "export.json".length + 3)
        try {
            parse(truncated)
            fail("Expected a refusal")
        } catch (e: ImportFileException) {
            assertTrue(e.error == ImportFileError.ZIP_UNREADABLE || e.error == ImportFileError.ZIP_NO_JSON_FILE)
        }
    }

    @Test
    fun jsonIsDetectedFromItsFirstCharacter() {
        assertEquals(ImportFormat.JSON, ImportParser.detectFormat("  [1]".toByteArray()))
        assertEquals(ImportFormat.JSON, ImportParser.detectFormat("{}".toByteArray()))
    }

    @Test
    fun byteOrderMarkIsSkippedForDetection() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "{}".toByteArray()
        assertEquals(ImportFormat.JSON, ImportParser.detectFormat(bytes))
    }

    @Test
    fun otherTextIsDetectedAsCsv() {
        assertEquals(ImportFormat.CSV, ImportParser.detectFormat("Title,Password".toByteArray()))
    }

    @Test
    fun emptyInputIsRefused() {
        assertRefused(ImportFileError.EMPTY_FILE) { parse(ByteArray(0)) }
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
