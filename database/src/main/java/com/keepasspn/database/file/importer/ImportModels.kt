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

import java.time.Instant

/**
 * Formats that can be imported. They are data files, never opened as a database.
 * A ZIP archive is accepted too, when it holds one JSON file.
 */
enum class ImportFormat {
    CSV,
    JSON
}

/**
 * Limits applied to every import, to bound memory use and to refuse abusive input.
 * They refuse oversized files; they never change the content of an accepted file.
 */
object ImportLimits {
    const val MAX_FILE_BYTES = 10 * 1024 * 1024
    const val MAX_ENTRIES = 10_000
    const val MAX_JSON_DEPTH = 16
    const val MAX_ZIP_ENTRIES = 1_000
    const val MAX_ZIP_INFLATED_BYTES = 64 * 1024 * 1024
}

/**
 * Custom field read from an import file: a name and a value, as the file gives them.
 * [isProtected] is true when the value is a secret, such as a PIN or a password history.
 */
class ImportedField(
    val name: String,
    val value: String,
    val isProtected: Boolean
)

/**
 * Entry read from an import file, with its values as they are in the file.
 * The password is a char array so it can be wiped after use.
 *
 * [groupPath] is the chain of group names the entry sits in, outermost first; empty for no group.
 * [otpUri] is an otpauth:// address when the file holds a one-time password.
 * [fields] are the other values of the entry, stored as custom fields.
 * [createdAt] and [modifiedAt] are the dates of the entry in the file, when it has them.
 */
class ImportedEntry(
    val title: String,
    val username: String,
    val password: CharArray,
    val url: String,
    val notes: String,
    val groupPath: List<String>,
    val otpUri: String?,
    val fields: List<ImportedField>,
    val createdAt: Instant?,
    val modifiedAt: Instant?
) {
    fun wipe() {
        password.fill('\u0000')
    }
}

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
    EMPTY_FILE,
    ZIP_NO_JSON_FILE,
    ZIP_SEVERAL_JSON_FILES,
    ZIP_UNREADABLE
}

/**
 * Thrown when the whole file is refused. Carries no field values.
 */
class ImportFileException(val error: ImportFileError) : Exception(error.name)

/**
 * Parsed entries, and how many characters were removed because a database cannot store them.
 */
class ImportResult(
    val entries: List<ImportedEntry>,
    val removedCharacters: Int
)
