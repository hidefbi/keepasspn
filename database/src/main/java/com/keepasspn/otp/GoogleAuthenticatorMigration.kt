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
package com.keepasspn.otp

import com.keepasspn.model.OtpModel
import java.io.ByteArrayOutputStream
import java.net.URLDecoder

/**
 * Reads the QR codes produced by Google Authenticator when exporting accounts
 * ("Transfer accounts"): otpauth-migration://offline?data=...
 *
 * The data is a base64 encoded protobuf MigrationPayload. One export can be split in
 * several QR codes (batches), each one carrying a part of the accounts.
 */
object GoogleAuthenticatorMigration {

    private const val MIGRATION_SCHEME = "otpauth-migration://"
    private const val DATA_PARAM = "data"

    // MigrationPayload fields
    private const val PAYLOAD_OTP_PARAMETERS = 1
    private const val PAYLOAD_BATCH_SIZE = 3
    private const val PAYLOAD_BATCH_INDEX = 4
    private const val PAYLOAD_BATCH_ID = 5

    // OtpParameters fields
    private const val OTP_SECRET = 1
    private const val OTP_NAME = 2
    private const val OTP_ISSUER = 3
    private const val OTP_ALGORITHM = 4
    private const val OTP_DIGITS = 5
    private const val OTP_TYPE = 6
    private const val OTP_COUNTER = 7

    // Wire types
    private const val WIRE_VARINT = 0
    private const val WIRE_FIXED64 = 1
    private const val WIRE_LENGTH_DELIMITED = 2
    private const val WIRE_FIXED32 = 5

    /**
     * Accounts read from one QR code.
     * @param accounts The OTP models that could be read.
     * @param skipped The number of accounts that use an unsupported setting (e.g. MD5).
     * @param batchIndex The position of this QR code in the export, starting at 0.
     * @param batchSize The number of QR codes in the export.
     * @param batchId Identifier shared by all the QR codes of one export.
     */
    class MigrationBatch(
        val accounts: List<OtpModel>,
        val skipped: Int,
        val batchIndex: Int,
        val batchSize: Int,
        val batchId: Int
    )

    /**
     * Tell if [text] looks like a Google Authenticator migration link.
     */
    fun isMigrationUri(text: String): Boolean {
        return text.trim().startsWith(MIGRATION_SCHEME, ignoreCase = true)
    }

    /**
     * Parse one migration link.
     * @throws IllegalArgumentException if the link or its payload is malformed.
     */
    @Throws(IllegalArgumentException::class)
    fun parse(uri: String): MigrationBatch {
        val query = uri.trim().substringAfter('?', missingDelimiterValue = "")
        val dataValue = query.split('&')
            .firstOrNull { it.startsWith("$DATA_PARAM=") }
            ?.substringAfter('=')
            ?: throw IllegalArgumentException("No data in migration link")
        // URL decoding turns '+' into spaces, restore them before the base64 decoding
        val base64 = URLDecoder.decode(dataValue, "UTF-8").replace(' ', '+')
        val payload = decodeBase64(base64)
        return parsePayload(payload)
    }

    private fun parsePayload(payload: ByteArray): MigrationBatch {
        val reader = ProtoReader(payload)
        val accounts = mutableListOf<OtpModel>()
        var skipped = 0
        var batchSize = 1
        var batchIndex = 0
        var batchId = 0
        while (reader.hasMore()) {
            val key = reader.readVarint()
            val field = (key ushr 3).toInt()
            val wireType = (key and 7L).toInt()
            when {
                field == PAYLOAD_OTP_PARAMETERS && wireType == WIRE_LENGTH_DELIMITED -> {
                    val otpBytes = reader.readLengthDelimited()
                    val otpModel = parseOtpParameters(otpBytes)
                    if (otpModel != null) accounts.add(otpModel) else skipped++
                }
                field == PAYLOAD_BATCH_SIZE && wireType == WIRE_VARINT ->
                    batchSize = reader.readVarint().toInt()
                field == PAYLOAD_BATCH_INDEX && wireType == WIRE_VARINT ->
                    batchIndex = reader.readVarint().toInt()
                field == PAYLOAD_BATCH_ID && wireType == WIRE_VARINT ->
                    batchId = reader.readVarint().toInt()
                else -> reader.skipField(wireType)
            }
        }
        return MigrationBatch(accounts, skipped, batchIndex, batchSize, batchId)
    }

    /**
     * @return The OTP model, or null if the account uses an unsupported setting.
     */
    private fun parseOtpParameters(bytes: ByteArray): OtpModel? {
        val reader = ProtoReader(bytes)
        var secret: ByteArray? = null
        var name = ""
        var issuer = ""
        var algorithm = 0
        var digits = 0
        var type = 0
        var counter = 0L
        while (reader.hasMore()) {
            val key = reader.readVarint()
            val field = (key ushr 3).toInt()
            val wireType = (key and 7L).toInt()
            when {
                field == OTP_SECRET && wireType == WIRE_LENGTH_DELIMITED -> secret = reader.readLengthDelimited()
                field == OTP_NAME && wireType == WIRE_LENGTH_DELIMITED ->
                    name = String(reader.readLengthDelimited(), Charsets.UTF_8)
                field == OTP_ISSUER && wireType == WIRE_LENGTH_DELIMITED ->
                    issuer = String(reader.readLengthDelimited(), Charsets.UTF_8)
                field == OTP_ALGORITHM && wireType == WIRE_VARINT -> algorithm = reader.readVarint().toInt()
                field == OTP_DIGITS && wireType == WIRE_VARINT -> digits = reader.readVarint().toInt()
                field == OTP_TYPE && wireType == WIRE_VARINT -> type = reader.readVarint().toInt()
                field == OTP_COUNTER && wireType == WIRE_VARINT -> counter = reader.readVarint()
                else -> reader.skipField(wireType)
            }
        }

        val secretBytes = secret
        if (secretBytes == null || secretBytes.isEmpty()) return null

        val otpModel = OtpModel()
        otpModel.secret = secretBytes
        otpModel.name = name
        otpModel.issuer = issuer
        otpModel.algorithm = when (algorithm) {
            0, 1 -> TokenCalculator.HashAlgorithm.SHA1   // 0 = unspecified, treated as SHA1
            2 -> TokenCalculator.HashAlgorithm.SHA256
            3 -> TokenCalculator.HashAlgorithm.SHA512
            else -> return null                          // MD5 and unknown values are not supported
        }
        otpModel.digits = when (digits) {
            0, 1 -> TokenCalculator.OTP_DEFAULT_DIGITS   // 0 = unspecified, treated as 6
            2 -> 8
            else -> return null
        }
        when (type) {
            1 -> {
                otpModel.type = OtpType.HOTP
                otpModel.tokenType = OtpTokenType.RFC4226
                otpModel.counter = counter
            }
            else -> {                                    // 2 = TOTP, 0 = unspecified treated as TOTP
                otpModel.type = OtpType.TOTP
                otpModel.tokenType = OtpTokenType.RFC6238
                otpModel.period = TokenCalculator.TOTP_DEFAULT_PERIOD
            }
        }
        return otpModel
    }

    /**
     * Minimal base64 decoder (standard and URL-safe alphabets, padding and whitespace ignored).
     * Written here because android.util.Base64 is not available in plain JVM unit tests and
     * java.util.Base64 requires API 26.
     */
    @Throws(IllegalArgumentException::class)
    internal fun decodeBase64(input: String): ByteArray {
        val output = ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (c in input) {
            val value = when (c) {
                in 'A'..'Z' -> c - 'A'
                in 'a'..'z' -> c - 'a' + 26
                in '0'..'9' -> c - '0' + 52
                '+', '-' -> 62
                '/', '_' -> 63
                '=', ' ', '\n', '\r', '\t' -> continue
                else -> throw IllegalArgumentException("Invalid base64 character")
            }
            buffer = ((buffer shl 6) or value) and 0xFFFFFF
            bits += 6
            if (bits >= 8) {
                bits -= 8
                output.write((buffer shr bits) and 0xFF)
            }
        }
        return output.toByteArray()
    }

    /**
     * Minimal protobuf reader, enough for the MigrationPayload message.
     */
    private class ProtoReader(private val bytes: ByteArray) {
        private var position = 0

        fun hasMore(): Boolean = position < bytes.size

        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                if (position >= bytes.size || shift > 63) {
                    throw IllegalArgumentException("Malformed varint in migration payload")
                }
                val byte = bytes[position++].toInt() and 0xFF
                result = result or ((byte and 0x7F).toLong() shl shift)
                if (byte and 0x80 == 0) return result
                shift += 7
            }
        }

        fun readLengthDelimited(): ByteArray {
            val length = readVarint()
            if (length < 0 || length > bytes.size - position) {
                throw IllegalArgumentException("Truncated field in migration payload")
            }
            val value = bytes.copyOfRange(position, position + length.toInt())
            position += length.toInt()
            return value
        }

        fun skipField(wireType: Int) {
            when (wireType) {
                WIRE_VARINT -> readVarint()
                WIRE_FIXED64 -> skipBytes(8)
                WIRE_LENGTH_DELIMITED -> readLengthDelimited()
                WIRE_FIXED32 -> skipBytes(4)
                else -> throw IllegalArgumentException("Unsupported wire type $wireType")
            }
        }

        private fun skipBytes(count: Int) {
            if (count > bytes.size - position) {
                throw IllegalArgumentException("Truncated field in migration payload")
            }
            position += count
        }
    }
}
