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

/**
 * Formats that can be imported. They are data files, never opened as a database.
 */
enum class ImportFormat {
    CSV,
    JSON
}

/**
 * Limits applied to every import, to bound memory use and to refuse abusive input.
 */
object ImportLimits {
    const val MAX_FILE_BYTES = 10 * 1024 * 1024
    const val MAX_ENTRIES = 10_000
    const val MAX_JSON_DEPTH = 16
    const val MAX_TITLE_LENGTH = 4 * 1024
    const val MAX_USERNAME_LENGTH = 4 * 1024
    const val MAX_NOTES_LENGTH = 4 * 1024
    const val MAX_PASSWORD_LENGTH = 1024
    const val MAX_URL_LENGTH = 2 * 1024
}

/**
 * Entry read from an import file. The password is a char array so it can be wiped after use.
 */
class ImportedEntry(
    val title: String,
    val username: String,
    val password: CharArray,
    val url: String,
    val notes: String,
    val groupName: String
) {
    fun wipe() {
        password.fill('\u0000')
    }
}

/**
 * Why a single row or entry was skipped.
 */
enum class ImportIssueReason {
    MISSING_TITLE_AND_URL,
    FIELD_TOO_LONG,
    INVALID_CHARACTER,
    INVALID_URL,
    INVALID_VALUE
}

/**
 * A skipped row. [position] is the line number for CSV, and the entry number for JSON.
 */
data class ImportRowIssue(val position: Int, val reason: ImportIssueReason)

/**
 * Problems that stop the whole import, so nothing is written.
 */
enum class ImportFileError {
    FILE_TOO_LARGE,
    INVALID_ENCODING,
    ENCRYPTED_NOT_SUPPORTED,
    MALFORMED_STRUCTURE,
    TOO_DEEP,
    TOO_MANY_ENTRIES,
    MISSING_REQUIRED_COLUMNS,
    EMPTY_FILE
}

/**
 * Thrown when the whole file is refused. Carries no field values.
 */
class ImportFileException(val error: ImportFileError) : Exception(error.name)

/**
 * Parsed entries, and the rows that were skipped with their reasons.
 */
class ImportResult(
    val entries: List<ImportedEntry>,
    val skipped: List<ImportRowIssue>
)
