package com.davidedicillo.portalroutine.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class PortalModeTest {
    @Test
    fun blankStoredModeDefaultsToStandalonePortal() {
        assertEquals(PortalMode.StandalonePortal, PortalMode.fromStoredValue(null))
        assertEquals(PortalMode.StandalonePortal, PortalMode.fromStoredValue(""))
        assertEquals(PortalMode.StandalonePortal, PortalMode.fromStoredValue("unexpected"))
    }

    @Test
    fun hubClientRoundTripsThroughStableStoredValue() {
        assertEquals("hub-client", PortalMode.HubClient.storedValue)
        assertEquals(PortalMode.HubClient, PortalMode.fromStoredValue("hub-client"))
    }
}
