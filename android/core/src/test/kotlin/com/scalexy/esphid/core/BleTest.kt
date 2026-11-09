package com.scalexy.esphid.core

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

    @Test
    fun transientLinkDropsAreRetried() {
        for (status in listOf(8, 34, 35, 42, 62, 133, 147))
            assertTrue("status $status", isTransientGattFailure("GATT status $status"))
        assertTrue(isTransientGattFailure("BLE disconnected"))
    }

    @Test
    fun permanentFailuresAreNotRetried() {
        for (status in listOf(0, 5, 15, 19, 22, 137, 257))
            assertFalse("status $status", isTransientGattFailure("GATT status $status"))
        for (m in listOf(null, "GATT operation status 34", "Bluetooth Connect permission is required", "GATT status 34x"))
            assertFalse("message $m", isTransientGattFailure(m))
    }
}
