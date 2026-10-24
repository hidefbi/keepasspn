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

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Entry point for importing CSV, JSON, or a ZIP archive holding one JSON file.
 *
 * The input is read once with a size limit and parsed in memory. Nothing is written to disk here:
 * the caller owns the input stream and closes it.
 */
object ImportParser {

    fun parse(input: InputStream, requested: ImportFormat? = null): ImportResult {
        val raw = readLimited(input)
        var unpacked: ByteArray? = null
        try {
            if (raw.isEmpty()) {
                throw ImportFileException(ImportFileError.EMPTY_FILE)
            }
            val bytes = if (isZip(raw)) {
                ZipJson.read(raw).also { unpacked = it }
            } else {
                raw
            }
            return when (requested ?: detectFormat(bytes)) {
                ImportFormat.CSV -> CsvImportParser.parse(bytes)
                ImportFormat.JSON -> JsonImportParser.parse(bytes)
            }
        } finally {
            raw.fill(0)
            unpacked?.fill(0)
        }
    }

    /**
     * JSON files start with an object or an array, after an optional byte order mark and whitespace.
     */
    fun detectFormat(bytes: ByteArray): ImportFormat {
        var i = 0
        if (bytes.size >= 3
            && bytes[0] == 0xEF.toByte()
            && bytes[1] == 0xBB.toByte()
            && bytes[2] == 0xBF.toByte()
        ) {
            i = 3
        }
        while (i < bytes.size) {
            val b = bytes[i].toInt()
            if (b == 0x20 || b == 0x09 || b == 0x0A || b == 0x0D) i++ else break
        }
        val first = if (i < bytes.size) bytes[i].toInt() else -1
        return if (first == 0x7B || first == 0x5B) ImportFormat.JSON else ImportFormat.CSV
    }

    /**
     * ZIP archives start with the local file header signature.
     */
    private fun isZip(bytes: ByteArray): Boolean {
        return bytes.size >= 4
            && bytes[0] == 'P'.code.toByte()
            && bytes[1] == 'K'.code.toByte()
            && bytes[2] == 0x03.toByte()
            && bytes[3] == 0x04.toByte()
    }

    private fun readLimited(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        try {
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                total += read
                if (total > ImportLimits.MAX_FILE_BYTES) {
                    throw ImportFileException(ImportFileError.FILE_TOO_LARGE)
                }
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        } finally {
            buffer.fill(0)
        }
    }
}

/**
 * Strict UTF-8 decoding shared by the parsers. Invalid bytes are refused, never replaced.
 */
internal object ImportText {

    private const val BYTE_ORDER_MARK = '﻿'

    fun decodeUtf8(bytes: ByteArray): String {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        val text = try {
            decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            throw ImportFileException(ImportFileError.INVALID_ENCODING)
        }
        return if (text.isNotEmpty() && text[0] == BYTE_ORDER_MARK) text.substring(1) else text
    }
}
