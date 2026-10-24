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
 * Collects the entries of one file, exactly as the file gives them.
 *
 * The only change made to a value is the removal of characters that a database cannot store:
 * control characters that XML 1.0 forbids, non-characters and unpaired surrogates. Their number
 * is reported in the result. Nothing is skipped, shortened or rejected for its content.
 */
internal class ImportCollector {

    private val entries = ArrayList<ImportedEntry>()
    private var removedCharacters = 0

    fun add(
        title: String,
        username: String,
        password: String,
        url: String,
        notes: String,
        groupPath: List<String>,
        otpUri: String?,
        fields: List<ImportedField> = emptyList(),
        createdAt: Instant? = null,
        modifiedAt: Instant? = null
    ) {
        if (entries.size >= ImportLimits.MAX_ENTRIES) {
            throw ImportFileException(ImportFileError.TOO_MANY_ENTRIES)
        }
        entries.add(
            ImportedEntry(
                title = clean(title),
                username = clean(username),
                password = clean(password).toCharArray(),
                url = clean(url),
                notes = clean(notes),
                groupPath = groupPath.map { clean(it) }.filter { it.isNotEmpty() },
                otpUri = otpUri?.let { clean(it) },
                fields = fields.map { field ->
                    ImportedField(
                        name = clean(field.name).ifEmpty { "field" },
                        value = clean(field.value),
                        isProtected = field.isProtected
                    )
                },
                createdAt = createdAt,
                modifiedAt = modifiedAt
            )
        )
    }

    fun result(): ImportResult = ImportResult(entries, removedCharacters)

    private fun clean(value: String): String {
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (Character.isHighSurrogate(c) && i + 1 < value.length && Character.isLowSurrogate(value[i + 1])) {
                out.append(c).append(value[i + 1])
                i += 2
                continue
            }
            if (isStorable(c)) {
                out.append(c)
            } else {
                removedCharacters++
            }
            i++
        }
        return out.toString()
    }

    private fun isStorable(c: Char): Boolean {
        if (c == '\t' || c == '\n' || c == '\r') return true
        if (c < ' ') return false
        if (c == '￾' || c == '￿') return false
        return !Character.isSurrogate(c)
    }
}
