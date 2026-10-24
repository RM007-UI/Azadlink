package com.azadlink.vpn.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class AetherFallbackPolicyTest {
    @Test
    fun balancedScanFallsBackToThoroughFullSubnetScan() {
        assertEquals("thorough", AetherAutoConnector.fallbackScanModeFor("balanced", "balanced"))
    }

    @Test
    fun turboScanFallsBackToBalancedScan() {
        assertEquals("balanced", AetherAutoConnector.fallbackScanModeFor("turbo", "turbo"))
    }

    @Test
    fun explicitThoroughModeIsPreservedAfterItsTurboFirstAttempt() {
        assertEquals("thorough", AetherAutoConnector.fallbackScanModeFor("thorough", "turbo"))
    }
}
