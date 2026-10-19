package com.keepasspn.tests.pin

import com.keepasspn.database.pin.UserVerificationPolicy
import com.keepasspn.database.pin.VerificationMethod
import org.junit.Assert.assertEquals
import org.junit.Test

class UserVerificationPolicyTest {

    @Test
    fun pinIsPreferredWhenSet() {
        assertEquals(VerificationMethod.PIN,
            UserVerificationPolicy.chooseMethod(pinSet = true, deviceCredentialAvailable = true, databaseAvailable = true))
        assertEquals(VerificationMethod.PIN,
            UserVerificationPolicy.chooseMethod(pinSet = true, deviceCredentialAvailable = false, databaseAvailable = true))
    }

    @Test
    fun deviceCredentialWhenNoPin() {
        assertEquals(VerificationMethod.DEVICE_CREDENTIAL,
            UserVerificationPolicy.chooseMethod(pinSet = false, deviceCredentialAvailable = true, databaseAvailable = true))
    }

    @Test
    fun masterPasswordWhenNoPinAndNoDeviceCredential() {
        assertEquals(VerificationMethod.DATABASE_CREDENTIAL,
            UserVerificationPolicy.chooseMethod(pinSet = false, deviceCredentialAvailable = false, databaseAvailable = true))
    }

    @Test
    fun noneWithoutDatabaseOrCredential() {
        assertEquals(VerificationMethod.NONE,
            UserVerificationPolicy.chooseMethod(pinSet = false, deviceCredentialAvailable = false, databaseAvailable = false))
    }
}
