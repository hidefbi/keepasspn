/*
 * Copyright 2020 Jeremy Jamet / Kunzisoft.
 * Modified by hidefbi, 2026.
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
package com.keepasspn.database.crypto.kdf

import com.keepasspn.database.NoOpLog
import com.keepasspn.encrypt.HashManager
import com.keepasspn.encrypt.argon2.Argon2Transformer
import com.keepasspn.encrypt.argon2.Argon2Type
import com.keepasspn.database.exception.KDFIterationDatabaseException
import com.keepasspn.database.exception.KDFMemoryDatabaseException
import com.keepasspn.database.exception.KDFParallelismDatabaseException
import com.keepasspn.utils.bytes16ToUuid
import java.io.IOException
import java.util.UUID

class Argon2Kdf(private val type: Type) : KdfEngine() {

    init {
        uuid = type.CIPHER_UUID
    }

    override val defaultParameters: KdfParameters
        get() {
            val p = KdfParameters(uuid!!)

            p.setParamUUID()
            p.setUInt32(PARAM_PARALLELISM, DEFAULT_PARALLELISM)
            p.setUInt64(PARAM_MEMORY, DEFAULT_MEMORY)
            p.setUInt64(PARAM_ITERATIONS, DEFAULT_ITERATIONS)
            p.setUInt32(PARAM_VERSION, MAX_VERSION)

            return p
        }

    override val defaultKeyRounds: ULong
        get() = DEFAULT_ITERATIONS

    @Throws(SecurityException::class)
    override fun checkLimits(limits: Limits) {
        val validated = getValidatedParameters()
        val parallelism = validated.parallelism.toLong()
        if (parallelism > limits.parallelism) {
            NoOpLog.w(TAG, "Parallelism is above the maximum number of processors.")
        }
        if (!limits.isMemorySufficient(validated.memory, Limits.LimitOperationType.KDF))
            throw KDFMemoryDatabaseException()
    }

    @Throws(IOException::class)
    override fun transform(masterKey: ByteArray): ByteArray {

        val salt = parameters.getByteArray(PARAM_SALT) ?: ByteArray(0)
        val validated = getValidatedParameters()

        val parallelism = validated.parallelism
        val memory = (validated.memory / MEMORY_BLOCK_SIZE).toUInt()
        val iterations = validated.iterations.toUInt()

        // Check the stored value as it is, clamping it first would hide an invalid version
        val version = parameters.getUInt32(PARAM_VERSION)?.toInt()
            ?: MAX_VERSION.toInt()
        if (version < MIN_VERSION.toInt()
            || version > MAX_VERSION.toInt())
            throw SecurityException("Version not in valid range")

        // Not used
        // val secretKey = parameters.getByteArray(PARAM_SECRET_KEY)
        // val assocData = parameters.getByteArray(PARAM_ASSOC_DATA)

        val argonType = if (type == Type.ARGON2_ID) Argon2Type.ARGON2_ID else Argon2Type.ARGON2_D

        return Argon2Transformer.transformKey(
                argonType,
                masterKey,
                salt,
                parallelism,
                memory,
                iterations,
                version
        )
    }

    private data class ValidatedParams(
        val parallelism: UInt,
        val memory: ULong,
        val iterations: ULong,
    )

    private fun getValidatedParameters(): ValidatedParams {
        val parallelism = parameters.getUInt32(PARAM_PARALLELISM) ?: DEFAULT_PARALLELISM
        if (parallelism !in MIN_PARALLELISM..MAX_PARALLELISM)
            throw KDFParallelismDatabaseException()

        val memory = parameters.getUInt64(PARAM_MEMORY) ?: DEFAULT_MEMORY
        if (memory !in MIN_MEMORY..MAX_MEMORY)
            throw KDFMemoryDatabaseException()

        val iterations = parameters.getUInt64(PARAM_ITERATIONS) ?: DEFAULT_ITERATIONS
        if (iterations !in MIN_ITERATIONS..MAX_ITERATIONS)
            throw KDFIterationDatabaseException()

        // A file can not ask for more work than the phone should spend before unlocking
        val memoryMiB = (memory + MIB - 1u) / MIB
        if (iterations * memoryMiB > MAX_WORK_MIB)
            throw KDFIterationDatabaseException()

        return ValidatedParams(parallelism, memory, iterations)
    }

    // Iterations allowed for a memory size, so iterations times memory stays in the work budget
    private fun maxIterationsFor(memory: ULong): ULong {
        val memoryMiB = ((memory + MIB - 1u) / MIB).coerceAtLeast(1u)
        return (MAX_WORK_MIB / memoryMiB).coerceIn(MIN_ITERATIONS, MAX_ITERATIONS)
    }

    // Memory allowed for an iteration count, the reverse of maxIterationsFor
    private fun maxMemoryFor(iterations: ULong): ULong {
        val memoryMiB = MAX_WORK_MIB / iterations.coerceAtLeast(1u)
        return (memoryMiB * MIB).coerceIn(MIN_MEMORY, MAX_MEMORY)
    }

    override fun randomize() {
        super.randomize()
        parameters.setByteArray(PARAM_SALT, HashManager.generateRandom(32))
    }

    override fun getSeed(): ByteArray? {
        return parameters.getByteArray(PARAM_SALT)
    }

    override fun getKeyRounds(): ULong {
        return (parameters.getUInt64(PARAM_ITERATIONS) ?: defaultKeyRounds)
            .coerceIn(minKeyRounds, maxKeyRounds)
    }

    override fun setKeyRounds(keyRounds: ULong) {
        parameters.setUInt64(
            PARAM_ITERATIONS,
            keyRounds.coerceIn(minKeyRounds, maxKeyRounds)
        )
        onParametersChanged?.invoke()
    }

    override val minKeyRounds: ULong = MIN_ITERATIONS

    // Depends on the memory, so the settings screen shows what the file would accept
    override val maxKeyRounds: ULong
        get() = maxIterationsFor(
            (parameters.getUInt64(PARAM_MEMORY) ?: DEFAULT_MEMORY)
                .coerceIn(MIN_MEMORY, MAX_MEMORY)
        )

    override fun getMemoryUsage(): ULong {
        return (parameters.getUInt64(PARAM_MEMORY) ?: defaultMemoryUsage)
            .coerceIn(minMemoryUsage, maxMemoryUsage)
    }

    override fun setMemoryUsage(memory: ULong) {
        parameters.setUInt64(
            PARAM_MEMORY,
            memory.coerceIn(minMemoryUsage, maxMemoryUsage)
        )
        onParametersChanged?.invoke()
    }

    override val defaultMemoryUsage: ULong = DEFAULT_MEMORY

    override val minMemoryUsage: ULong = MIN_MEMORY

    override val maxMemoryUsage: ULong
        get() = maxMemoryFor(
            (parameters.getUInt64(PARAM_ITERATIONS) ?: DEFAULT_ITERATIONS)
                .coerceIn(MIN_ITERATIONS, MAX_ITERATIONS)
        )

    override fun getParallelism(): Long {
        return (parameters.getUInt32(PARAM_PARALLELISM)?.toLong() ?: defaultParallelism)
            .coerceIn(minParallelism, maxParallelism)
    }

    override fun setParallelism(parallelism: Long) {
        parameters.setUInt32(
            PARAM_PARALLELISM,
            parallelism.coerceIn(minParallelism, maxParallelism).toUInt()
        )
        onParametersChanged?.invoke()
    }

    override fun toString(): String {
        return "$type"
    }

    override val defaultParallelism: Long
        get() = DEFAULT_PARALLELISM.toLong()

    override val minParallelism: Long
        get() = MIN_PARALLELISM.toLong()

    override val maxParallelism: Long
        get() = MAX_PARALLELISM.toLong()

    enum class Type(val CIPHER_UUID: UUID, private val typeName: String) {
        ARGON2_D(bytes16ToUuid(
                byteArrayOf(0xEF.toByte(),
                        0x63.toByte(),
                        0x6D.toByte(),
                        0xDF.toByte(),
                        0x8C.toByte(),
                        0x29.toByte(),
                        0x44.toByte(),
                        0x4B.toByte(),
                        0x91.toByte(),
                        0xF7.toByte(),
                        0xA9.toByte(),
                        0xA4.toByte(),
                        0x03.toByte(),
                        0xE3.toByte(),
                        0x0A.toByte(),
                        0x0C.toByte())), "Argon2d"),
        ARGON2_ID(bytes16ToUuid(
                byteArrayOf(0x9E.toByte(),
                        0x29.toByte(),
                        0x8B.toByte(),
                        0x19.toByte(),
                        0x56.toByte(),
                        0xDB.toByte(),
                        0x47.toByte(),
                        0x73.toByte(),
                        0xB2.toByte(),
                        0x3D.toByte(),
                        0xFC.toByte(),
                        0x3E.toByte(),
                        0xC6.toByte(),
                        0xF0.toByte(),
                        0xA1.toByte(),
                        0xE6.toByte())), "Argon2id");

        override fun toString(): String {
            return typeName
        }
    }

    companion object {
        private val TAG = Argon2Kdf::class.simpleName

        private const val PARAM_SALT = "S" // byte[]
        private const val PARAM_PARALLELISM = "P" // UInt32
        private const val PARAM_MEMORY = "M" // UInt64
        private const val PARAM_ITERATIONS = "I" // UInt64
        private const val PARAM_VERSION = "V" // UInt32
        private const val PARAM_SECRET_KEY = "K" // byte[]
        private const val PARAM_ASSOC_DATA = "A" // byte[]

        private const val MIN_VERSION: UInt = 0x10u
        private const val MAX_VERSION: UInt = 0x13u

        private val DEFAULT_ITERATIONS: ULong = 3u
        private val MIN_ITERATIONS: ULong = 1u
        private val MAX_ITERATIONS: ULong = 10_000u
        // Iterations times memory in MiB, about a minute of work on a mid range phone
        private val MAX_WORK_MIB: ULong = 8192u
        private val MIB: ULong = 1024uL * 1024uL

        private val DEFAULT_MEMORY: ULong = (MEMORY_BLOCK_SIZE * 1024u * 64u).toULong() // 64 MiB, RFC 9106 second choice
        private val MIN_MEMORY: ULong = (MEMORY_BLOCK_SIZE * 8u).toULong() // 8 MiB
        private val MAX_MEMORY: ULong = UInt.MAX_VALUE.toULong()  // 4 GiB
        private const val MEMORY_BLOCK_SIZE: UInt = 1024u // to pass arguments to JNI

        private const val DEFAULT_PARALLELISM: UInt = 4u
        private const val MIN_PARALLELISM: UInt = 1u
        // One thread is started per lane, so keep the number small
        private const val MAX_PARALLELISM: UInt = 256u
    }
}
