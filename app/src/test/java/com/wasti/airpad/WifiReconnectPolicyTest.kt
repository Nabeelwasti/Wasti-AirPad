package com.wasti.airpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WifiReconnectPolicyTest {
    @Test
    fun retryDelayUsesBoundedExponentialBackoff() {
        assertEquals(1L, WifiReconnectPolicy.delaySeconds(1))
        assertEquals(2L, WifiReconnectPolicy.delaySeconds(2))
        assertEquals(4L, WifiReconnectPolicy.delaySeconds(3))
        assertEquals(8L, WifiReconnectPolicy.delaySeconds(4))
        assertEquals(16L, WifiReconnectPolicy.delaySeconds(5))
    }

    @Test
    fun retryPolicyRejectsAttemptsOutsideTheLimit() {
        assertNull(WifiReconnectPolicy.delaySeconds(0))
        assertNull(WifiReconnectPolicy.delaySeconds(6))
        assertEquals(5, WifiReconnectPolicy.MAX_ATTEMPTS)
        assertEquals(60L, WifiReconnectPolicy.STABLE_CONNECTION_RESET_SECONDS)
    }
}
