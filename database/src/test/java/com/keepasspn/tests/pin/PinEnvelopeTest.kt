package com.keepasspn.tests.pin

import com.keepasspn.database.pin.PinEnvelope
import com.keepasspn.database.pin.PinEnvelopeException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer

class PinEnvelopeTest {

    private fun sample(): PinEnvelope = PinEnvelope(
        memoryKiB = 64 * 1024,
        iterations = 3,
        parallelism = 1,
        salt = ByteArray(PinEnvelope.SALT_LENGTH) { it.toByte() },
        outerIv = ByteArray(PinEnvelope.IV_LENGTH) { (it + 100).toByte() },
        ciphertext = ByteArray(60) { (it * 3).toByte() }
    )

    @Test
    fun roundTrip_preservesEveryField() {
        val original = sample()
        val parsed = PinEnvelope.fromBinary(original.toBinary())
        assertEquals(original.memoryKiB, parsed.memoryKiB)
        assertEquals(original.iterations, parsed.iterations)
        assertEquals(original.parallelism, parsed.parallelism)
        assertArrayEquals(original.salt, parsed.salt)
        assertArrayEquals(original.outerIv, parsed.outerIv)
        assertArrayEquals(original.ciphertext, parsed.ciphertext)
        assertEquals(PinEnvelope.HEADER_LENGTH, original.headerBytes().size)
    }

    @Test
    fun storedPrefix_isDetected() {
        assertTrue(PinEnvelope.isV2("v2.AAAA"))
        // Legacy v1 values are plain Base64 and never start with "v2."
        assertFalse(PinEnvelope.isV2("AAAAv2."))
    }

    @Test
    fun unsupportedVersion_isRejected() {
        val bytes = sample().toBinary()
        bytes[0] = 1
        expectRejected(bytes)
    }

    @Test
    fun unsupportedKdf_isRejected() {
        val bytes = sample().toBinary()
        bytes[1] = 2
        expectRejected(bytes)
    }

    @Test
    fun truncated_isRejected() {
        expectRejected(sample().toBinary().copyOf(PinEnvelope.HEADER_LENGTH))
    }

    @Test
    fun memoryOutOfBounds_isRejected() {
        val bytes = sample().toBinary()
        ByteBuffer.wrap(bytes).putInt(2, 1024) // 1 MiB, below the minimum
        expectRejected(bytes)
        ByteBuffer.wrap(bytes).putInt(2, Int.MAX_VALUE) // far above the maximum
        expectRejected(bytes)
    }

    @Test
    fun iterationsAndParallelism_outOfBounds_areRejected() {
        val zeroIterations = sample().toBinary().also { it[6] = 0 }
        expectRejected(zeroIterations)
        val tooManyLanes = sample().toBinary().also { it[7] = 100 }
        expectRejected(tooManyLanes)
    }

    @Test
    fun outerAad_bindsDatabaseUri() {
        val header = sample().headerBytes()
        assertFalse(
            PinEnvelope.outerAad("content://a", header).contentEquals(PinEnvelope.outerAad("content://b", header))
        )
    }

    @Test
    fun outerAad_bindsHeader() {
        val header = sample().headerBytes()
        val tampered = header.copyOf().also { it[6] = (it[6] + 1).toByte() }
        assertFalse(
            PinEnvelope.outerAad("content://a", header).contentEquals(PinEnvelope.outerAad("content://a", tampered))
        )
    }

    @Test
    fun innerAad_bindsDatabaseUri() {
        assertFalse(PinEnvelope.innerAad("content://a").contentEquals(PinEnvelope.innerAad("content://b")))
    }

    @Test
    fun innerAad_andOuterAad_areDomainSeparated() {
        val header = sample().headerBytes()
        assertFalse(PinEnvelope.innerAad("x").contentEquals(PinEnvelope.outerAad("x", header)))
    }

    @Test
    fun outerPlaintext_splitsAndJoins() {
        val iv = ByteArray(PinEnvelope.IV_LENGTH) { 7 }
        val ct = ByteArray(40) { 9 }
        val (splitIv, splitCt) = PinEnvelope.splitOuterPlaintext(PinEnvelope.joinOuterPlaintext(iv, ct))
        assertArrayEquals(iv, splitIv)
        assertArrayEquals(ct, splitCt)
    }

    @Test
    fun outerPlaintext_tooShort_isRejected() {
        try {
            PinEnvelope.splitOuterPlaintext(ByteArray(PinEnvelope.IV_LENGTH))
            fail("Expected PinEnvelopeException")
        } catch (expected: PinEnvelopeException) {
            // expected
        }
    }

    private fun expectRejected(bytes: ByteArray) {
        try {
            PinEnvelope.fromBinary(bytes)
            fail("Expected PinEnvelopeException")
        } catch (expected: PinEnvelopeException) {
            // expected
        }
    }
}
