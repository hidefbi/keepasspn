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
 * Text of JSON values, so that a value the importer does not map is kept in the notes.
 */
internal object ImportValues {

    /**
     * Text of a scalar, the JSON text of an object or an array, and an empty string for null.
     */
    fun text(value: Any?): String {
        return when (value) {
            null, SafeJsonParser.JsonNull -> ""
            is String -> value
            is Boolean -> value.toString()
            else -> json(value)
        }
    }

    /**
     * Compact JSON text of a value read by [SafeJsonParser].
     */
    fun json(value: Any?): String {
        val out = StringBuilder()
        write(value, out)
        return out.toString()
    }

    private fun write(value: Any?, out: StringBuilder) {
        when (value) {
            null, SafeJsonParser.JsonNull -> out.append("null")
            is String -> writeString(value, out)
            is Boolean -> out.append(value.toString())
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((key, item) in value) {
                    if (!first) out.append(',')
                    first = false
                    writeString(key.toString(), out)
                    out.append(':')
                    write(item, out)
                }
                out.append('}')
            }
            is List<*> -> {
                out.append('[')
                value.forEachIndexed { index, item ->
                    if (index > 0) out.append(',')
                    write(item, out)
                }
                out.append(']')
            }
            else -> out.append(value.toString())
        }
    }

    private fun writeString(text: String, out: StringBuilder) {
        out.append('"')
        for (c in text) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c < ' ' -> out.append("\\u").append(String.format("%04x", c.code))
                else -> out.append(c)
            }
        }
        out.append('"')
    }
}
