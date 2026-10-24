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
 * Reads CSV files. Columns are found from the header row, so their order does not matter.
 * The delimiter is detected from the header: comma, semicolon or tab.
 */
internal object CsvImportParser {

    private val TITLE_COLUMNS = setOf("account", "name", "title", "item name")
    private val USERNAME_COLUMNS = setOf("login name", "login_username", "username", "user name", "login")
    private val PASSWORD_COLUMNS = setOf("password", "login_password")
    private val URL_COLUMNS = setOf("web site", "website", "url", "login_uri", "uri")
    private val NOTES_COLUMNS = setOf("comments", "notes", "note", "extra")
    private val GROUP_COLUMNS = setOf("group", "grouping", "folder")

    private class Record(val line: Int, val cells: List<String>)

    fun parse(bytes: ByteArray): ImportResult {
        val text = ImportText.decodeUtf8(bytes)
        val records = readRecords(text, detectDelimiter(text))
        if (records.isEmpty()) {
            throw ImportFileException(ImportFileError.EMPTY_FILE)
        }

        val headers = records[0].cells.map { it.trim().lowercase() }
        val titleIndex = indexOf(headers, TITLE_COLUMNS)
        val usernameIndex = indexOf(headers, USERNAME_COLUMNS)
        val passwordIndex = indexOf(headers, PASSWORD_COLUMNS)
        val urlIndex = indexOf(headers, URL_COLUMNS)
        val notesIndex = indexOf(headers, NOTES_COLUMNS)
        val groupIndex = indexOf(headers, GROUP_COLUMNS)
        if (passwordIndex < 0 || (titleIndex < 0 && urlIndex < 0)) {
            throw ImportFileException(ImportFileError.MISSING_REQUIRED_COLUMNS)
        }

        val entries = ArrayList<ImportedEntry>()
        val skipped = ArrayList<ImportRowIssue>()
        for (record in records.subList(1, records.size)) {
            val result = ImportSanitizer.validateRow(
                title = cell(record, titleIndex),
                username = cell(record, usernameIndex),
                password = cell(record, passwordIndex),
                url = cell(record, urlIndex),
                notes = cell(record, notesIndex),
                groupName = cell(record, groupIndex)
            )
            when (result) {
                is RowResult.Accepted -> entries.add(result.entry)
                is RowResult.Rejected -> skipped.add(ImportRowIssue(record.line, result.reason))
            }
        }
        return ImportResult(entries, skipped)
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
