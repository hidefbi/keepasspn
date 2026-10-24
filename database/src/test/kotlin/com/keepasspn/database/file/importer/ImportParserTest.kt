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
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream

class ImportParserTest {

    @Test
    fun jsonIsDetectedAfterByteOrderMarkAndWhitespace() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "  \n[]".toByteArray()
        assertEquals(ImportFormat.JSON, ImportParser.detectFormat(bytes))
    }

    @Test
    fun csvIsTheDefaultFormat() {
        assertEquals(ImportFormat.CSV, ImportParser.detectFormat("name,password\n".toByteArray()))
    }

    @Test
    fun parseUsesTheRequestedFormat() {
        val result = ImportParser.parse(
            ByteArrayInputStream("name,password\nShop,secret\n".toByteArray()),
            ImportFormat.CSV
        )
        assertEquals("Shop", result.entries[0].title)
    }

    @Test
    fun emptyInputIsRefused() {
        assertFileError(ImportFileError.EMPTY_FILE) {
            ImportParser.parse(ByteArrayInputStream(ByteArray(0)))
        }
    }

    @Test
    fun fileOverTheSizeLimitIsRefused() {
        val tooLarge = ByteArray(ImportLimits.MAX_FILE_BYTES + 1) { 'a'.code.toByte() }
        assertFileError(ImportFileError.FILE_TOO_LARGE) {
            ImportParser.parse(ByteArrayInputStream(tooLarge), ImportFormat.CSV)
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
