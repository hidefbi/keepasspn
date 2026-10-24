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
import org.junit.Test

class ImportSanitizerTest {

    @Test
    fun allowedUrlSchemesAreKept() {
        assertEquals("https://example.com/login", ImportSanitizer.normalizeUrl(" https://example.com/login "))
        assertEquals("http://example.com", ImportSanitizer.normalizeUrl("http://example.com"))
        assertEquals("androidapp://com.example.app", ImportSanitizer.normalizeUrl("androidapp://com.example.app"))
    }

    @Test
    fun emptyUrlIsAllowed() {
        assertEquals("", ImportSanitizer.normalizeUrl("   "))
    }

    @Test
    fun dangerousOrMalformedUrlsAreRefused() {
        assertNull(ImportSanitizer.normalizeUrl("javascript:alert(1)"))
        assertNull(ImportSanitizer.normalizeUrl("file:///etc/passwd"))
        assertNull(ImportSanitizer.normalizeUrl("data:text/html,<script>"))
        assertNull(ImportSanitizer.normalizeUrl("https://exa mple.com"))
        assertNull(ImportSanitizer.normalizeUrl("https://"))
    }

    @Test
    fun titleFallsBackToHost() {
        val result = ImportSanitizer.validateRow("", "user", "secret", "https://www.example.com/a", "", "")
        assertTrue(result is RowResult.Accepted)
        assertEquals("www.example.com", (result as RowResult.Accepted).entry.title)
    }

    @Test
    fun rowWithoutTitleAndUrlIsRejected() {
        val result = ImportSanitizer.validateRow("", "user", "secret", "", "", "")
        assertEquals(ImportIssueReason.MISSING_TITLE_AND_URL, (result as RowResult.Rejected).reason)
    }

    @Test
    fun controlCharactersAreRejected() {
        val result = ImportSanitizer.validateRow("Mail\u0000", "user", "secret", "", "", "")
        assertEquals(ImportIssueReason.INVALID_CHARACTER, (result as RowResult.Rejected).reason)
    }

    @Test
    fun overlongPasswordIsRejected() {
        val password = "a".repeat(ImportLimits.MAX_PASSWORD_LENGTH + 1)
        val result = ImportSanitizer.validateRow("Mail", "user", password, "", "", "")
        assertEquals(ImportIssueReason.FIELD_TOO_LONG, (result as RowResult.Rejected).reason)
    }

    @Test
    fun invalidUrlRejectsTheRow() {
        val result = ImportSanitizer.validateRow("Mail", "user", "secret", "javascript:alert(1)", "", "")
        assertEquals(ImportIssueReason.INVALID_URL, (result as RowResult.Rejected).reason)
    }
}
