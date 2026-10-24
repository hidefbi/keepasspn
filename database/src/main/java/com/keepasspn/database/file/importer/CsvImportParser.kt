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
 * Reads CSV files. Columns are found from the header row, so their order does not matter.
 * The delimiter is detected from the header: comma, semicolon or tab.
 *
 * Known columns go to their field: title, user name, password, URL, notes, group, one-time
 * password, creation and modification dates. Every other column is kept as a custom field,
 * named after its header, so no value is lost and none is mixed into the notes.
 *
 * A Bitwarden export is recognised by its columns. Its own columns (type, favorite, reprompt)
 * are dropped, its dates are not kept, its comma-separated URIs become several URLs, and its
 * "fields" column, one "name: value" line per field, becomes one custom field per line.
 */
internal object CsvImportParser {

    private val TITLE_COLUMNS = setOf("account", "name", "title", "item name")
    private val USERNAME_COLUMNS = setOf("login name", "login_username", "username", "user name", "login")
    private val PASSWORD_COLUMNS = setOf("password", "login_password")
    private val URL_COLUMNS = setOf("web site", "website", "url", "login_uri", "uri")
    private val NOTES_COLUMNS = setOf("comments", "notes", "note", "extra")
    private val GROUP_COLUMNS = setOf("group", "grouping", "folder")
    private val OTP_COLUMNS = setOf("totp", "otp", "login_totp")
    private val BITWARDEN_METADATA_COLUMNS = setOf("type", "favorite", "reprompt")
    private val CREATED_COLUMNS = setOf(
        "created", "created at", "created_at", "creation date", "creation time", "date created", "create time"
    )
    private val MODIFIED_COLUMNS = setOf(
        "modified", "modified at", "modified_at", "last modified", "last_modified", "lastmodified",
        "last updated", "updated", "updated at", "revision date", "modify time"
    )

    private class Record(val line: Int, val cells: List<String>)

    fun parse(bytes: ByteArray): ImportResult {
        val text = ImportText.decodeUtf8(bytes)
        val records = readRecords(text, detectDelimiter(text))
        if (records.isEmpty()) {
            throw ImportFileException(ImportFileError.EMPTY_FILE)
        }

        val rawHeaders = records[0].cells.map { it.trim() }
        val headers = rawHeaders.map { it.lowercase() }
        val titleIndex = indexOf(headers, TITLE_COLUMNS)
        val usernameIndex = indexOf(headers, USERNAME_COLUMNS)
        val passwordIndex = indexOf(headers, PASSWORD_COLUMNS)
        val urlIndex = indexOf(headers, URL_COLUMNS)
        val notesIndex = indexOf(headers, NOTES_COLUMNS)
        val groupIndex = indexOf(headers, GROUP_COLUMNS)
        val otpIndex = indexOf(headers, OTP_COLUMNS)
        val createdIndex = indexOf(headers, CREATED_COLUMNS)
        val modifiedIndex = indexOf(headers, MODIFIED_COLUMNS)
        if (passwordIndex < 0 || (titleIndex < 0 && urlIndex < 0)) {
            throw ImportFileException(ImportFileError.MISSING_REQUIRED_COLUMNS)
        }

        val recognised = setOf(
            titleIndex, usernameIndex, passwordIndex, urlIndex, notesIndex, groupIndex, otpIndex,
            createdIndex, modifiedIndex
        )
        val bitwarden = headers.contains("login_uri") && headers.contains("reprompt")
        val fieldsIndex = if (bitwarden) headers.indexOf("fields") else -1
        val dropped = if (bitwarden) {
            headers.indices.filter { headers[it] in BITWARDEN_METADATA_COLUMNS }
        } else {
            emptyList()
        }
        val customIndexes = headers.indices.filter { it !in recognised && it !in dropped && it != fieldsIndex }

        val collector = ImportCollector()
        for (record in records.subList(1, records.size)) {
            val title = cell(record, titleIndex)
            val fields = ArrayList<ImportedField>()

            val otpCell = cell(record, otpIndex)
            var otpUri: String? = null
            if (otpCell.isNotEmpty()) {
                otpUri = ImportValues.otpUriOf(otpCell, title)
                if (otpUri == null) fields.add(ImportedField("TOTP", otpCell, isProtected = true))
            }

            val createdAt = if (bitwarden) null else dateOf(record, createdIndex, rawHeaders, fields)
            val modifiedAt = if (bitwarden) null else dateOf(record, modifiedIndex, rawHeaders, fields)

            val urlCell = cell(record, urlIndex)
            val urls = if (bitwarden) {
                urlCell.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            } else {
                listOf(urlCell)
            }
            urls.drop(1).forEachIndexed { index, extra ->
                fields.add(ImportedField("URI ${index + 2}", extra, false))
            }
            if (bitwarden) {
                for (named in bitwardenFields(cell(record, fieldsIndex))) {
                    fields.add(ImportedField(named.first, named.second, ImportValues.isSecretName(named.first)))
                }
            }

            for (index in customIndexes) {
                val value = cell(record, index)
                if (value.isEmpty()) continue
                val name = rawHeaders.getOrNull(index)?.ifEmpty { null } ?: "column ${index + 1}"
                fields.add(ImportedField(name, value, ImportValues.isSecretName(name)))
            }

            val group = cell(record, groupIndex)
            collector.add(
                title = title,
                username = cell(record, usernameIndex),
                password = cell(record, passwordIndex),
                url = urls.firstOrNull().orEmpty(),
                notes = cell(record, notesIndex),
                groupPath = if (group.isEmpty()) emptyList() else listOf(group),
                otpUri = otpUri,
                fields = fields,
                createdAt = createdAt,
                modifiedAt = modifiedAt
            )
        }
        return collector.result()
    }

    /**
     * The "name: value" lines of a Bitwarden "fields" cell. A line without a name continues the
     * value of the line before it, so a value written on several lines is kept whole.
     */
    private fun bitwardenFields(text: String): List<Pair<String, String>> {
        val pairs = ArrayList<Pair<String, String>>()
        for (line in text.lines()) {
            if (line.isBlank()) continue
            val separator = line.indexOf(':')
            if (separator > 0) {
                val name = line.substring(0, separator).trim().ifEmpty { "field" }
                pairs.add(name to line.substring(separator + 1).trim())
            } else if (pairs.isNotEmpty()) {
                val last = pairs.removeAt(pairs.size - 1)
                pairs.add(last.first to (last.second + "\n" + line.trim()))
            } else {
                pairs.add("field" to line.trim())
            }
        }
        return pairs.filter { it.second.isNotEmpty() }
    }

    /**
     * Date of a column. A value that is not a date is kept as a custom field under its header.
     */
    private fun dateOf(
        record: Record,
        index: Int,
        rawHeaders: List<String>,
        fields: MutableList<ImportedField>
    ): Instant? {
        val value = cell(record, index)
        if (value.isEmpty()) return null
        val date = ImportValues.instantOf(value)
        if (date == null) {
            val name = rawHeaders.getOrNull(index)?.ifEmpty { null } ?: "column ${index + 1}"
            fields.add(ImportedField(name, value, isProtected = false))
        }
        return date
    }

    private fun detectDelimiter(text: String): Char {
        val header = text.lineSequence().firstOrNull().orEmpty()
        val candidates = listOf(',', ';', '\t')
        val best = candidates.maxByOrNull { c -> header.count { it == c } } ?: ','
        return if (header.contains(best)) best else ','
    }

    private fun indexOf(headers: List<String>, names: Set<String>): Int {
        return headers.indexOfFirst { it in names }
    }

    private fun cell(record: Record, index: Int): String {
        return if (index >= 0 && index < record.cells.size) record.cells[index] else ""
    }

    /**
     * Splits the text into records. Handles quoted fields, doubled quotes, quoted line breaks,
     * and LF, CRLF or CR line endings. Blank lines are ignored.
     */
    private fun readRecords(text: String, delimiter: Char): List<Record> {
        val records = ArrayList<Record>()
        val cells = ArrayList<String>()
        val cell = StringBuilder()
        var inQuotes = false
        var line = 1
        var recordLine = 1

        fun endRecord() {
            cells.add(cell.toString())
            cell.setLength(0)
            if (cells.size > 1 || cells[0].isNotEmpty()) {
                if (records.size > ImportLimits.MAX_ENTRIES) {
                    throw ImportFileException(ImportFileError.TOO_MANY_ENTRIES)
                }
                records.add(Record(recordLine, ArrayList(cells)))
            }
            cells.clear()
        }

        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') {
                        cell.append('"')
                        i++
                    } else {
                        inQuotes = false
                    }
                } else {
                    if (c == '\n') line++
                    cell.append(c)
                }
            } else if (c == '"' && cell.isEmpty()) {
                inQuotes = true
            } else if (c == delimiter) {
                cells.add(cell.toString())
                cell.setLength(0)
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                endRecord()
                line++
                recordLine = line
            } else {
                cell.append(c)
            }
            i++
        }
        if (inQuotes) {
            throw ImportFileException(ImportFileError.MALFORMED_STRUCTURE)
        }
        if (cell.isNotEmpty() || cells.isNotEmpty()) {
            endRecord()
        }
        return records
    }
}
