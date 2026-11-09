package dev.scalexy.esphid.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleTest {
    @Test
    fun authenticationAndEncryptionStatusesAllowOneBondRetry() {
        for (status in listOf(5, 15, 137)) {
            assertTrue("status $status", shouldRetryGattAfterBond(status, alreadyRetried = false))
            assertFalse("status $status", shouldRetryGattAfterBond(status, alreadyRetried = true))
        }
    }

    @Test
    fun successAndUnrelatedFailuresNeverTriggerBondRetry() {
        for (status in listOf(0, 1, 3, 8, 13, 19, 22, 133, 143, 257)) {
            assertFalse("status $status", shouldRetryGattAfterBond(status, alreadyRetried = false))
            assertFalse("status $status", shouldRetryGattAfterBond(status, alreadyRetried = true))
        }
    }
}
