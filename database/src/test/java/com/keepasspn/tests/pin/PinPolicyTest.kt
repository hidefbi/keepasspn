package com.keepasspn.tests.pin

import com.keepasspn.database.pin.PinPolicy
import com.keepasspn.database.pin.PinPolicy.MasterFailureOutcome
import com.keepasspn.database.pin.PinPolicy.PinFailureOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class PinPolicyTest {

    private val day = 24L * 60L * 60L * 1000L
    private val start = 1_800_000_000_000L

    @Test
    fun supportedValidityOptions() {
        listOf(0, 7, 14, 30, 60).forEach { assertTrue(PinPolicy.isSupportedValidity(it)) }
        listOf(1, 3, 6, 90, -7).forEach { assertFalse(PinPolicy.isSupportedValidity(it)) }
    }

    @Test
    fun neverExpires() {
        assertFalse(PinPolicy.isExpired(start, start + 3650 * day, PinPolicy.VALIDITY_NEVER_DAYS))
    }

    @Test
    fun noRecordedOpenIsNotExpired() {
        assertFalse(PinPolicy.isExpired(0L, start + 365 * day, 7))
    }

    @Test
    fun expiresExactlyAtPeriodBoundary() {
        assertFalse(PinPolicy.isExpired(start, start + 7 * day - 1, 7))
        assertTrue(PinPolicy.isExpired(start, start + 7 * day, 7))
        assertTrue(PinPolicy.isExpired(start, start + 60 * day, 60))
        assertFalse(PinPolicy.isExpired(start, start + 60 * day - 1, 60))
    }

    @Test
    fun backwardsClockFailsClosed() {
        assertTrue(PinPolicy.isExpired(start, start - 1, 30))
    }

    @Test
    fun unsupportedValidityIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            PinPolicy.isExpired(start, start, 9)
        }
    }

    @Test
    fun firstTwoWrongPinsOnlyRetry() {
        assertEquals(PinFailureOutcome.Retry(2), PinPolicy.onPinFailure(1))
        assertEquals(PinFailureOutcome.Retry(1), PinPolicy.onPinFailure(2))
    }

    @Test
    fun thirdWrongPinWipesPin() {
        assertEquals(PinFailureOutcome.WipePin, PinPolicy.onPinFailure(3))
        assertEquals(PinFailureOutcome.WipePin, PinPolicy.onPinFailure(4))
    }

    @Test
    fun firstWrongMasterAfterPinWipeRetries() {
        assertEquals(MasterFailureOutcome.Retry(1), PinPolicy.onMasterFailureAfterPinWipe(1))
    }

    @Test
    fun secondWrongMasterAfterPinWipeDeletesDatabase() {
        assertEquals(MasterFailureOutcome.DeleteDatabase, PinPolicy.onMasterFailureAfterPinWipe(2))
    }

    @Test
    fun totalFailuresBeforeDatabaseDeletionIsFive() {
        // 3 wrong PINs, then 2 wrong master passwords
        assertEquals(3, PinPolicy.MAX_PIN_FAILURES)
        assertEquals(2, PinPolicy.MAX_MASTER_FAILURES_AFTER_PIN_WIPE)
        assertEquals(5, PinPolicy.MAX_PIN_FAILURES + PinPolicy.MAX_MASTER_FAILURES_AFTER_PIN_WIPE)
    }
}
