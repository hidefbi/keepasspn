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

import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * Helpers shared by the parsers: text of JSON values, one-time passwords, dates and secret names.
 */
internal object ImportValues {

    private val BASE32_SECRET = Regex("^[A-Z2-7]{16,}=*$")
    private val SECRET_WORDS = setOf("pin", "passcode", "passwd", "secret", "token", "cvv", "cvc", "key")

    /**
     * True for a name that holds a secret, so its value is stored protected. A word of the name
     * must be one of [SECRET_WORDS], or the name must contain "password".
     */
    fun isSecretName(name: String): Boolean {
        val lower = name.lowercase()
        if (lower.contains("password")) return true
        return lower.split(Regex("[^a-z]+")).any { it in SECRET_WORDS }
    }

    /**
     * An otpauth:// address for a one-time password. A bare Base32 secret is turned into one,
     * labelled with [label]. Returns null when [value] is neither.
     */
    fun otpUriOf(value: String, label: String): String? {
        val trimmed = value.trim()
        if (trimmed.startsWith("otpauth://", ignoreCase = true)) return trimmed
        val secret = trimmed.replace(" ", "").uppercase()
        if (!BASE32_SECRET.matches(secret)) return null
        val encodedLabel = URLEncoder.encode(label.ifEmpty { "entry" }, "UTF-8").replace("+", "%20")
        return "otpauth://totp/$encodedLabel?secret=$secret"
    }

    /**
     * Date of a value written as an ISO 8601 date or date-time, a "yyyy-MM-dd HH:mm:ss" date-time
     * (in the device time zone), a "yyyy-MM-dd" date, or a Unix time in seconds or milliseconds.
     * Returns null when the value is none of these.
     */
    fun instantOf(value: String): Instant? {
        val text = value.trim()
        if (text.isEmpty()) return null
        if (text.all { it.isDigit() }) {
            val number = text.toLongOrNull() ?: return null
            return if (text.length >= 13) Instant.ofEpochMilli(number) else Instant.ofEpochSecond(number)
        }
        return try {
            Instant.parse(text)
        } catch (e: DateTimeParseException) {
            try {
                OffsetDateTime.parse(text).toInstant()
            } catch (e: DateTimeParseException) {
                try {
                    LocalDateTime.parse(text.replace(' ', 'T')).atZone(ZoneId.systemDefault()).toInstant()
                } catch (e: DateTimeParseException) {
                    try {
                        LocalDate.parse(text).atStartOfDay(ZoneId.systemDefault()).toInstant()
                    } catch (e: DateTimeParseException) {
                        null
                    }
                }
            }
        }
    }

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
