/*
 * Copyright 2026 Jeremy Jamet / Kunzisoft.
 *
 * This file is part of KeePassPN.
 *
 * KeePassPN is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * KeePassPN is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with KeePassPN. If not, see <http://www.gnu.org/licenses/>.
 */
package com.keepasspn.database.pin

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

class PinEnvelopeException(message: String) : Exception(message)

/**
 * Binary layout of a PIN-wrapped credential, format v2.
 *
 * Stored as: [PREFIX] + Base64URL(header || outerCiphertext)
 *
 * Header (52 bytes). The whole header is authenticated as AAD of the outer
 * layer, so the KDF parameters cannot be downgraded or swapped without
 * breaking the GCM tag:
 *   [0]      format version (0x02)
 *   [1]      KDF id (0x01 = Argon2id)
 *   [2..5]   Argon2 memory in KiB (big endian)
 *   [6]      Argon2 iterations
 *   [7]      Argon2 parallelism
 *   [8..39]  Argon2 salt (32 random bytes)
 *   [40..51] outer AES-GCM IV (12 random bytes)
 *
 * Outer plaintext = innerIv (12 bytes) || innerCiphertext
 * The inner layer is AES-GCM with the device-bound Keystore key.
 *
 * AAD binds each layer to the database URI, so an envelope copied to another
 * database entry fails authentication.
 */
class PinEnvelope(
    val memoryKiB: Int,
    val iterations: Int,
    val parallelism: Int,
    val salt: ByteArray,
    val outerIv: ByteArray,
    val ciphertext: ByteArray
) {

    fun headerBytes(): ByteArray =
        buildHeader(memoryKiB, iterations, parallelism, salt, outerIv)

    fun toBinary(): ByteArray = headerBytes() + ciphertext

    companion object {
        const val PREFIX = "v2."
        const val VERSION: Byte = 2
        const val KDF_ARGON2ID: Byte = 1

        const val HEADER_LENGTH = 52
        const val SALT_LENGTH = 32
        const val IV_LENGTH = 12
        const val TAG_LENGTH = 16

        // Bounds checked on read, so a tampered row cannot force huge allocations
        const val MIN_MEMORY_KIB = 8 * 1024
        const val MAX_MEMORY_KIB = 256 * 1024
        const val MIN_ITERATIONS = 1
        const val MAX_ITERATIONS = 10
        const val MIN_PARALLELISM = 1
        const val MAX_PARALLELISM = 8

        private val SEPARATOR = byteArrayOf(0)
        private val LABEL_OUTER = "kdbx-pin-v2/outer".toByteArray(StandardCharsets.UTF_8)
        private val LABEL_INNER = "kdbx-pin-v2/inner".toByteArray(StandardCharsets.UTF_8)

        fun isV2(stored: String): Boolean = stored.startsWith(PREFIX)

        fun buildHeader(
            memoryKiB: Int,
            iterations: Int,
            parallelism: Int,
            salt: ByteArray,
            outerIv: ByteArray
        ): ByteArray {
            require(salt.size == SALT_LENGTH) { "Salt must be $SALT_LENGTH bytes" }
            require(outerIv.size == IV_LENGTH) { "IV must be $IV_LENGTH bytes" }
            return ByteBuffer.allocate(HEADER_LENGTH)
                .put(VERSION)
                .put(KDF_ARGON2ID)
                .putInt(memoryKiB)
                .put(iterations.toByte())
                .put(parallelism.toByte())
                .put(salt)
                .put(outerIv)
                .array()
        }

        /** Parses and validates a binary envelope. Throws [PinEnvelopeException] on any malformed input. */
        fun fromBinary(data: ByteArray): PinEnvelope {
            // Minimum: header + inner IV + inner tag + at least one byte + outer tag
            if (data.size < HEADER_LENGTH + IV_LENGTH + TAG_LENGTH + 1 + TAG_LENGTH) {
                throw PinEnvelopeException("Envelope too short")
            }
            val buffer = ByteBuffer.wrap(data)
            if (buffer.get() != VERSION) throw PinEnvelopeException("Unsupported envelope version")
            if (buffer.get() != KDF_ARGON2ID) throw PinEnvelopeException("Unsupported KDF")
            val memoryKiB = buffer.getInt()
            val iterations = buffer.get().toInt() and 0xFF
            val parallelism = buffer.get().toInt() and 0xFF
            if (memoryKiB !in MIN_MEMORY_KIB..MAX_MEMORY_KIB) throw PinEnvelopeException("KDF memory out of bounds")
            if (iterations !in MIN_ITERATIONS..MAX_ITERATIONS) throw PinEnvelopeException("KDF iterations out of bounds")
            if (parallelism !in MIN_PARALLELISM..MAX_PARALLELISM) throw PinEnvelopeException("KDF parallelism out of bounds")
            val salt = ByteArray(SALT_LENGTH).also { buffer.get(it) }
            val outerIv = ByteArray(IV_LENGTH).also { buffer.get(it) }
            val ciphertext = data.copyOfRange(HEADER_LENGTH, data.size)
            return PinEnvelope(memoryKiB, iterations, parallelism, salt, outerIv, ciphertext)
        }

        /** AAD for the outer (PIN) layer: label, database URI and the full header. */
        fun outerAad(databaseUri: String, header: ByteArray): ByteArray =
            LABEL_OUTER + SEPARATOR + databaseUri.toByteArray(StandardCharsets.UTF_8) + SEPARATOR + header

        /** AAD for the inner (device key) layer: label and database URI. */
        fun innerAad(databaseUri: String): ByteArray =
            LABEL_INNER + SEPARATOR + databaseUri.toByteArray(StandardCharsets.UTF_8)

        /** Splits decrypted outer plaintext into (innerIv, innerCiphertext). */
        fun splitOuterPlaintext(plaintext: ByteArray): Pair<ByteArray, ByteArray> {
            if (plaintext.size <= IV_LENGTH + TAG_LENGTH) throw PinEnvelopeException("Inner payload too short")
            return Pair(
                plaintext.copyOfRange(0, IV_LENGTH),
                plaintext.copyOfRange(IV_LENGTH, plaintext.size)
            )
        }

        fun joinOuterPlaintext(innerIv: ByteArray, innerCiphertext: ByteArray): ByteArray =
            innerIv + innerCiphertext
    }
}
