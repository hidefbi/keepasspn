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
 * Reads JSON files: either an array of entries, or an object holding that array under one of
 * the keys in [ENTRY_LIST_KEYS].
 *
 * Keys read per entry, the first present one wins:
 * title or name, username, password, url or uri, notes or note, group or folder.
 * A nested "login" object (Bitwarden layout) provides the username, password and first URI
 * when the entry does not have them itself.
 */
internal object JsonImportParser {

    private val TITLE_KEYS = listOf("title", "name")
    private val USERNAME_KEYS = listOf("username")
    private val PASSWORD_KEYS = listOf("password")
    private val URL_KEYS = listOf("url", "uri")
    private val NOTES_KEYS = listOf("notes", "note")
    private val GROUP_KEYS = listOf("group", "folder")
    private val ENTRY_LIST_KEYS = listOf("items", "entries", "logins", "passwords")

    fun parse(bytes: ByteArray): ImportResult {
        val text = ImportText.decodeUtf8(bytes)
        val root = SafeJsonParser(text, ImportLimits.MAX_JSON_DEPTH).parse()
        val items: List<*> = when (root) {
            is List<*> -> root
            is Map<*, *> -> {
                if (root["encrypted"] == true) {
                    throw ImportFileException(ImportFileError.ENCRYPTED_NOT_SUPPORTED)
                }
                ENTRY_LIST_KEYS.firstNotNullOfOrNull { root[it] as? List<*> }
                    ?: throw ImportFileException(ImportFileError.MALFORMED_STRUCTURE)
            }
            else -> throw ImportFileException(ImportFileError.MALFORMED_STRUCTURE)
        }
        if (items.isEmpty()) {
            throw ImportFileException(ImportFileError.EMPTY_FILE)
        }
        if (items.size > ImportLimits.MAX_ENTRIES) {
            throw ImportFileException(ImportFileError.TOO_MANY_ENTRIES)
        }

        val entries = ArrayList<ImportedEntry>()
        val skipped = ArrayList<ImportRowIssue>()
        items.forEachIndexed { index, item ->
            val position = index + 1
            val row = item as? Map<*, *>
            val fields = row?.let { withLoginFields(it) }
            if (fields == null) {
                skipped.add(ImportRowIssue(position, ImportIssueReason.INVALID_VALUE))
                return@forEachIndexed
            }
            val values = listOf(
                fieldText(fields, TITLE_KEYS),
                fieldText(fields, USERNAME_KEYS),
                fieldText(fields, PASSWORD_KEYS),
                fieldText(fields, URL_KEYS),
                fieldText(fields, NOTES_KEYS),
                fieldText(fields, GROUP_KEYS)
            )
            if (values.any { it == null }) {
                skipped.add(ImportRowIssue(position, ImportIssueReason.INVALID_VALUE))
                return@forEachIndexed
            }
            val texts = values.map { it.orEmpty() }
            when (val result = ImportSanitizer.validateRow(
                title = texts[0],
                username = texts[1],
                password = texts[2],
                url = texts[3],
                notes = texts[4],
                groupName = texts[5]
            )) {
                is RowResult.Accepted -> entries.add(result.entry)
                is RowResult.Rejected -> skipped.add(ImportRowIssue(position, result.reason))
            }
        }
        return ImportResult(entries, skipped)
    }

    /**
     * The entry's fields, with the username, password and first URI of a nested "login" object
     * added where the entry has none of its own. Returns null when "login" is not an object.
     */
    private fun withLoginFields(row: Map<*, *>): Map<*, *>? {
        val loginValue = row["login"] ?: return row
        val login = loginValue as? Map<*, *> ?: return null
        val merged = LinkedHashMap<Any?, Any?>(row)
        for (key in listOf("username", "password")) {
            val loginField = login[key]
            if (merged[key] == null && loginField != null) {
                merged[key] = loginField
            }
        }
        if (merged["url"] == null && merged["uri"] == null) {
            val uris = login["uris"] as? List<*>
            val firstUri = uris?.firstNotNullOfOrNull { (it as? Map<*, *>)?.get("uri") as? String }
            if (firstUri != null) {
                merged["url"] = firstUri
            }
        }
        return merged
    }

    /**
     * Text of the first key present, an empty string when none is, or null when the value
     * is an object or an array, which is not a field value.
     */
    private fun fieldText(row: Map<*, *>, keys: List<String>): String? {
        for (key in keys) {
            val value = row[key] ?: continue
            return value as? String
        }
        return ""
    }
}
