package com.azadlink.vpn.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ReconnectBackoffTest {
    @Test
    fun growsExponentiallyAndCapsAtMaximum() {
        assertEquals(1_000L, ReconnectBackoff.delayMillis(0))
        assertEquals(2_000L, ReconnectBackoff.delayMillis(1))
        assertEquals(4_000L, ReconnectBackoff.delayMillis(2))
        assertEquals(60_000L, ReconnectBackoff.delayMillis(10))
        assertEquals(60_000L, ReconnectBackoff.delayMillis(100))
    }

    @Test
    fun rejectsInvalidArguments() {
        assertThrows(IllegalArgumentException::class.java) { ReconnectBackoff.delayMillis(-1) }
        assertThrows(IllegalArgumentException::class.java) { ReconnectBackoff.delayMillis(0, 0L) }
        assertThrows(IllegalArgumentException::class.java) { ReconnectBackoff.delayMillis(0, 2_000L, 1_000L) }
    }
}
