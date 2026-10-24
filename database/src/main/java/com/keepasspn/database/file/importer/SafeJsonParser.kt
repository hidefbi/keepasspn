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
 * Strict JSON reader with a bounded nesting depth.
 *
 * Objects become [LinkedHashMap], arrays become [ArrayList], strings become [String],
 * numbers stay as their literal text (also a [String]), booleans become [Boolean],
 * and null becomes [JsonNull]. Any deviation from the JSON grammar is refused.
 */
internal class SafeJsonParser(private val text: String, private val maxDepth: Int) {

    object JsonNull

    private var pos = 0

    fun parse(): Any? {
        skipWhitespace()
        val value = parseValue(0)
        skipWhitespace()
        if (pos != text.length) fail()
        return value
    }

    private fun fail(): Nothing {
        throw ImportFileException(ImportFileError.MALFORMED_STRUCTURE)
    }

    private fun peek(): Char = if (pos < text.length) text[pos] else '\u0000'

    private fun skipWhitespace() {
        while (pos < text.length) {
            val c = text[pos]
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++ else break
        }
    }

    /**
     * [depth] is the number of enclosing containers.
     */
    private fun parseValue(depth: Int): Any? {
        if (pos >= text.length) fail()
        return when (text[pos]) {
            '{' -> parseObject(depth)
            '[' -> parseArray(depth)
            '"' -> parseString()
            't' -> parseLiteral("true", true)
            'f' -> parseLiteral("false", false)
            'n' -> parseLiteral("null", JsonNull)
            else -> parseNumber()
        }
    }

    private fun parseObject(depth: Int): Map<String, Any?> {
        val inner = depth + 1
        if (inner > maxDepth) throw ImportFileException(ImportFileError.TOO_DEEP)
        pos++ // opening brace
        val result = LinkedHashMap<String, Any?>()
        skipWhitespace()
        if (peek() == '}') {
            pos++
            return result
        }
        while (true) {
            skipWhitespace()
            if (peek() != '"') fail()
            val key = parseString()
            skipWhitespace()
            if (peek() != ':') fail()
            pos++
            skipWhitespace()
            result[key] = parseValue(inner)
            skipWhitespace()
            when (peek()) {
                ',' -> pos++
                '}' -> {
                    pos++
                    return result
                }
                else -> fail()
            }
        }
    }

    private fun parseArray(depth: Int): List<Any?> {
        val inner = depth + 1
        if (inner > maxDepth) throw ImportFileException(ImportFileError.TOO_DEEP)
        pos++ // opening bracket
        val result = ArrayList<Any?>()
        skipWhitespace()
        if (peek() == ']') {
            pos++
            return result
        }
        while (true) {
            skipWhitespace()
            result.add(parseValue(inner))
            skipWhitespace()
            when (peek()) {
                ',' -> pos++
                ']' -> {
                    pos++
                    return result
                }
                else -> fail()
            }
        }
    }

    private fun parseString(): String {
        pos++ // opening quote
        val sb = StringBuilder()
        while (true) {
            if (pos >= text.length) fail()
            val c = text[pos++]
            when {
                c == '"' -> return sb.toString()
                c == '\\' -> {
                    if (pos >= text.length) fail()
                    when (text[pos++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (pos + 4 > text.length) fail()
                            val hex = text.substring(pos, pos + 4)
                            if (hex.any { it == '+' || it == '-' }) fail()
                            val code = hex.toIntOrNull(16) ?: fail()
                            sb.append(code.toChar())
                            pos += 4
                        }
                        else -> fail()
                    }
                }
                c.code < 0x20 -> fail()
                else -> sb.append(c)
            }
        }
    }

    private fun parseLiteral(word: String, value: Any): Any {
        if (!text.startsWith(word, pos)) fail()
        pos += word.length
        return value
    }

    private fun parseNumber(): String {
        val start = pos
        while (pos < text.length && (text[pos].isDigit() || text[pos] in "+-.eE")) pos++
        val literal = text.substring(start, pos)
        if (!NUMBER.matches(literal)) fail()
        return literal
    }

    companion object {
        private val NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
    }
}
