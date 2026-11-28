package com.keepasspn.tests.crypto

import com.keepasspn.database.crypto.kdf.AesKdf
import com.keepasspn.database.crypto.kdf.Argon2Kdf
import com.keepasspn.database.crypto.kdf.Limits
import com.keepasspn.database.exception.KDFIterationDatabaseException
import com.keepasspn.database.exception.KDFMemoryDatabaseException
import com.keepasspn.database.exception.KDFParallelismDatabaseException
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Crafted key derivation headers, the same values a hostile file would carry.
 * checkLimits runs before the database is read, so none of these run the KDF.
 */
class KdfWorkLimitTest {

    private val limits = Limits(
        isMemorySufficient = { _, _ -> true },
        parallelism = 4L
    )

    private fun argon2With(memory: ULong, iterations: ULong, parallelism: UInt = 4u): Argon2Kdf {
        return Argon2Kdf(Argon2Kdf.Type.ARGON2_ID).apply {
            parameters.setUInt64("M", memory)
            parameters.setUInt64("I", iterations)
            parameters.setUInt32("P", parallelism)
        }
    }

    @Test
    fun argon2DefaultsAreAccepted() {
        Argon2Kdf(Argon2Kdf.Type.ARGON2_ID).checkLimits(limits)
    }

    @Test
    fun argon2WithinBudgetIsAccepted() {
        // 1 GiB for 8 iterations is 8192 MiB of work, right on the budget
        argon2With(1024uL * 1024u * 1024u, 8uL).checkLimits(limits)
    }

    @Test(expected = KDFIterationDatabaseException::class)
    fun argon2IterationsAboveBudgetAreRefused() {
        // 1 GiB for 9 iterations is over the budget
        argon2With(1024uL * 1024u * 1024u, 9uL).checkLimits(limits)
    }

    @Test(expected = KDFIterationDatabaseException::class)
    fun argon2HugeIterationsAreRefused() {
        argon2With(16uL * 1024u * 1024u, UInt.MAX_VALUE.toULong()).checkLimits(limits)
    }

    @Test(expected = KDFIterationDatabaseException::class)
    fun argon2SmallMemoryStillCapsIterations() {
        // Minimum memory is 8 MiB, which allows 1024 iterations, so 1025 is refused
        argon2With(8uL * 1024u * 1024u, 1025uL).checkLimits(limits)
    }

    @Test(expected = KDFParallelismDatabaseException::class)
    fun argon2ParallelismAboveMaximumIsRefused() {
        argon2With(16uL * 1024u * 1024u, 3uL, parallelism = 257u).checkLimits(limits)
    }

    @Test(expected = KDFMemoryDatabaseException::class)
    fun argon2MemoryAboveMaximumIsRefused() {
        argon2With(5_000_000_000uL, 1uL).checkLimits(limits)
    }

    @Test
    fun argon2SettersStayInsideBudget() {
        val kdf = Argon2Kdf(Argon2Kdf.Type.ARGON2_ID)
        // Iterations are capped by the memory in use, 16 MiB allows 512
        kdf.setMemoryUsage(16uL * 1024u * 1024u)
        kdf.setKeyRounds(100_000uL)
        assertEquals(512uL, kdf.getKeyRounds())
        // Memory is capped by the iterations in use, 512 iterations allow 16 MiB
        kdf.setMemoryUsage(1024uL * 1024u * 1024u)
        assertEquals(16uL * 1024u * 1024u, kdf.getMemoryUsage())
    }

    @Test(expected = SecurityException::class)
    fun aesRoundsAboveCapAreRefused() {
        AesKdf().apply {
            parameters.setUInt64("R", 50_000_001uL)
        }.checkLimits(limits)
    }

    @Test
    fun aesRoundsAtCapAreAccepted() {
        AesKdf().apply {
            parameters.setUInt64("R", 50_000_000uL)
        }.checkLimits(limits)
    }
}
