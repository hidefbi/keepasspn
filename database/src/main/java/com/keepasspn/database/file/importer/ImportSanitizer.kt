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

import java.net.URI
import java.net.URISyntaxException

internal sealed class RowResult {
    class Accepted(val entry: ImportedEntry) : RowResult()
    class Rejected(val reason: ImportIssueReason) : RowResult()
}

/**
 * Validates the fields of one imported row. A row is rejected as a whole instead of being
 * silently changed, except for the host name used as a title when no title is given.
 */
internal object ImportSanitizer {

    /** Only web URLs and app-bound URLs, so an imported value can never run script or read files. */
    private val ALLOWED_URL_SCHEMES = setOf("http", "https", "androidapp")

    fun validateRow(
        title: String,
        username: String,
        password: String,
        url: String,
        notes: String,
        groupName: String
    ): RowResult {
        if (hasControlCharacter(title) || hasControlCharacter(username) || hasControlCharacter(password)
            || hasControlCharacter(url) || hasControlCharacter(notes) || hasControlCharacter(groupName)
        ) {
            return RowResult.Rejected(ImportIssueReason.INVALID_CHARACTER)
        }
        if (title.length > ImportLimits.MAX_TITLE_LENGTH
            || username.length > ImportLimits.MAX_USERNAME_LENGTH
            || password.length > ImportLimits.MAX_PASSWORD_LENGTH
            || url.length > ImportLimits.MAX_URL_LENGTH
            || notes.length > ImportLimits.MAX_NOTES_LENGTH
            || groupName.length > ImportLimits.MAX_TITLE_LENGTH
        ) {
            return RowResult.Rejected(ImportIssueReason.FIELD_TOO_LONG)
        }
        val cleanUrl = normalizeUrl(url) ?: return RowResult.Rejected(ImportIssueReason.INVALID_URL)
        val cleanTitle = title.trim().ifEmpty { hostOf(cleanUrl) }
        if (cleanTitle.isEmpty()) {
            return RowResult.Rejected(ImportIssueReason.MISSING_TITLE_AND_URL)
        }
        return RowResult.Accepted(
            ImportedEntry(
                title = cleanTitle,
                username = username.trim(),
                password = password.toCharArray(),
                url = cleanUrl,
                notes = notes,
                groupName = groupName.trim()
            )
        )
    }

    /**
     * Returns the trimmed URL, an empty string when there is no URL, or null when the URL is refused.
     */
    fun normalizeUrl(raw: String): String? {
        val url = raw.trim()
        if (url.isEmpty()) return ""
        if (url.any { it.isWhitespace() }) return null
        val uri = try {
            URI(url)
        } catch (e: URISyntaxException) {
            return null
        }
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme !in ALLOWED_URL_SCHEMES) return null
        if (uri.host.isNullOrEmpty()) return null
        return url
    }

    /**
     * Host name of an already normalized URL, or an empty string when there is none.
     */
    fun hostOf(url: String): String {
        if (url.isEmpty()) return ""
        return try {
            URI(url).host.orEmpty()
        } catch (e: URISyntaxException) {
            ""
        }
    }

    /**
     * True for control characters, except tab, line feed and carriage return.
     */
    fun hasControlCharacter(value: String): Boolean {
        for (c in value) {
            val code = c.code
            if ((code < 0x20 && c != '\t' && c != '\n' && c != '\r') || code == 0x7F) {
                return true
            }
        }
        return false
    }
}
