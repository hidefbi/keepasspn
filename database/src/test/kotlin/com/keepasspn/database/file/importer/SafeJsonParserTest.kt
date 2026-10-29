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

class SafeJsonParserTest {

    private fun parse(text: String, maxDepth: Int = 16): Any? {
        return SafeJsonParser(text, maxDepth).parse()
    }

    private fun errorOf(text: String, maxDepth: Int = 16): ImportFileError {
        try {
            parse(text, maxDepth)
        } catch (e: ImportFileException) {
            return e.error
        }
        fail("No error for: $text")
        throw IllegalStateException()
    }

    @Test
    fun objectKeepsKeysAndValues() {
        val result = parse("{\"name\":\"alpha\",\"count\":12}") as Map<*, *>
        assertEquals("alpha", result["name"])
        assertEquals("12", result["count"])
    }

    @Test
    fun arrayKeepsItemsInOrder() {
        val result = parse("[\"a\",\"b\",true]") as List<*>
        assertEquals(listOf("a", "b", true), result)
    }

    @Test
    fun literalsAreReadAsTheirValues() {
        assertEquals(true, parse("true"))
        assertEquals(false, parse("false"))
        assertEquals(SafeJsonParser.JsonNull, parse("null"))
    }

    @Test
    fun escapesAreDecoded() {
        assertEquals("a\"b\\c/d\n", parse("\"a\\\"b\\\\c\\/d\\n\""))
        assertEquals("A", parse("\"\\u0041\""))
    }

    @Test
    fun nestingAtTheLimitIsAccepted() {
        assertTrue(parse("[[1]]", maxDepth = 2) is List<*>)
    }

    @Test
    fun nestingOverTheLimitIsRefused() {
        assertEquals(ImportFileError.TOO_DEEP, errorOf("[[[1]]]", maxDepth = 2))
        assertEquals(ImportFileError.TOO_DEEP, errorOf("{\"a\":{\"b\":{\"c\":1}}}", maxDepth = 2))
    }

    @Test
    fun missingColonIsRefused() {
        assertEquals(ImportFileError.MALFORMED_STRUCTURE, errorOf("{\"a\" 1}"))
    }

    @Test
    fun trailingCommaIsRefused() {
        assertEquals(ImportFileError.MALFORMED_STRUCTURE, errorOf("[1,]"))
    }

    @Test
    fun contentAfterTheValueIsRefused() {
        assertEquals(ImportFileError.MALFORMED_STRUCTURE, errorOf("1 2"))
    }

    @Test
    fun emptyInputIsRefused() {
        assertEquals(ImportFileError.MALFORMED_STRUCTURE, errorOf(""))
    }

    @Test
    fun rawControlCharacterInStringIsRefused() {
        assertEquals(ImportFileError.MALFORMED_STRUCTURE, errorOf("\"a\nb\""))
    }

    @Test
    fun signInUnicodeEscapeIsRefused() {
        assertEquals(ImportFileError.MALFORMED_STRUCTURE, errorOf("\"\\u+041\""))
    }

    @Test
    fun leadingZeroNumberIsRefused() {
        assertEquals(ImportFileError.MALFORMED_STRUCTURE, errorOf("01"))
    }

    @Test
    fun unknownEscapeIsRefused() {
        assertEquals(ImportFileError.MALFORMED_STRUCTURE, errorOf("\"\\x\""))
    }

    @Test
    fun unterminatedStringIsRefused() {
        assertEquals(ImportFileError.MALFORMED_STRUCTURE, errorOf("\"abc"))
    }

    @Test
    fun emptyContainersAreAccepted() {
        assertTrue((parse("{}") as Map<*, *>).isEmpty())
        assertTrue((parse("[]") as List<*>).isEmpty())
    }
}
