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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ImportValuesTest {

    @Test
    fun secretNamesAreRecognised() {
        assertTrue(ImportValues.isSecretName("Password"))
        assertTrue(ImportValues.isSecretName("Login password"))
        assertTrue(ImportValues.isSecretName("passwordHint"))
        assertTrue(ImportValues.isSecretName("PIN"))
        assertTrue(ImportValues.isSecretName("API_KEY"))
    }

    @Test
    fun ordinaryNamesAreNotSecret() {
        assertFalse(ImportValues.isSecretName("username"))
        assertFalse(ImportValues.isSecretName("keyboard"))
        assertFalse(ImportValues.isSecretName("website"))
    }

    @Test
    fun otpAddressIsKeptAsItIs() {
        val uri = "otpauth://totp/Mail?secret=JBSWY3DPEHPK3PXP"
        assertEquals(uri, ImportValues.otpUriOf(uri, "ignored"))
    }

    @Test
    fun bareSecretBecomesAnOtpAddress() {
        assertEquals(
            "otpauth://totp/Mail?secret=JBSWY3DPEHPK3PXP",
            ImportValues.otpUriOf("jbsw y3dp ehpk3pxp", "Mail")
        )
    }

    @Test
    fun otpLabelIsEncodedAndDefaulted() {
        assertEquals(
            "otpauth://totp/my%20site?secret=JBSWY3DPEHPK3PXP",
            ImportValues.otpUriOf("JBSWY3DPEHPK3PXP", "my site")
        )
        assertEquals(
            "otpauth://totp/entry?secret=JBSWY3DPEHPK3PXP",
            ImportValues.otpUriOf("JBSWY3DPEHPK3PXP", "")
        )
    }

    @Test
    fun shortOrInvalidSecretGivesNoOtpAddress() {
        assertNull(ImportValues.otpUriOf("hello", "Mail"))
        assertNull(ImportValues.otpUriOf("JBSWY3DP1EHPK3PX", "Mail"))
    }

    @Test
    fun unixTimeInSecondsAndMillisecondsGiveTheSameInstant() {
        assertEquals(1700000000000L, ImportValues.instantOf("1700000000")?.toEpochMilli())
        assertEquals(1700000000000L, ImportValues.instantOf("1700000000000")?.toEpochMilli())
    }

    @Test
    fun isoDateTimeIsRead() {
        assertEquals(1704164645L, ImportValues.instantOf("2024-01-02T03:04:05Z")?.epochSecond)
    }

    @Test
    fun plainDateIsReadInTheDeviceZone() {
        val instant = ImportValues.instantOf("2024-01-02")
        assertEquals(LocalDate.of(2024, 1, 2), instant?.atZone(ZoneId.systemDefault())?.toLocalDate())
    }

    @Test
    fun unreadableDateGivesNull() {
        assertNull(ImportValues.instantOf("not a date"))
        assertNull(ImportValues.instantOf(""))
    }

    @Test
    fun textOfScalarsAndNullValues() {
        assertEquals("", ImportValues.text(null))
        assertEquals("", ImportValues.text(SafeJsonParser.JsonNull))
        assertEquals("true", ImportValues.text(true))
        assertEquals("abc", ImportValues.text("abc"))
    }

    @Test
    fun jsonTextOfContainers() {
        assertEquals("[\"x\",\"y\"]", ImportValues.text(listOf("x", "y")))
        assertEquals("{\"k\":\"v\\n\"}", ImportValues.json(mapOf("k" to "v\n")))
    }

    @Test
    fun controlCharactersAreWrittenAsEscapes() {
        assertEquals("\"\\u0001\"", ImportValues.json("\u0001"))
    }

    @Test
    fun compactJsonIsWrittenBackUnchanged() {
        val text = "{\"a\":[\"x\",true,null]}"
        assertEquals(text, ImportValues.json(SafeJsonParser(text, 16).parse()))
    }
}
